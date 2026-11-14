# Sprint C0 — Fondation du moteur d'orchestration IA : rapport d'implémentation

- **Branche** : `feat/ai-orchestration-c` (créée depuis `main` à jour, `e20a471`, fast-forward)
- **Date** : 14 novembre 2026
- **État Git** : modifications **non commitées et non poussées**.
- **Périmètre** : fondation sécurisée du moteur Python. **Aucun agent n'est exécuté, aucun modèle n'est appelé pour une tâche, aucun outil ni serveur MCP réel n'est lancé** (C1, C2 et C3 restent à faire).

## 1. Légende

| Terme | Sens |
| --- | --- |
| **Implémenté** | Le code existe. |
| **Testé** | Couvert par un test automatisé exécuté dans cette étape. |
| **Vérifié en réel** | Contrôlé manuellement contre la pile locale réelle (Keycloak, PostgreSQL, Docker, Core API). |
| **Non vérifié** | Rien ne le prouve à ce stade. |
| **Reporté** | Volontairement hors périmètre C0. |

## 2. Service Python (`services/ai-orchestrator`)

| Élément | Implémenté | Testé | Vérifié en réel |
| --- | :-: | :-: | :-: |
| FastAPI : lifespan, arrêt propre, `/health/live`, `/health/ready`, `/api/v1/runtime/info` | ✓ | ✓ | ✓ (conteneur sain, `ready` = `UP`) |
| Configuration `AIO_*` validée (pydantic-settings), secrets en `SecretStr`, production exigeant HTTPS + secret + base | ✓ | ✓ | — |
| Journalisation structurée JSON + masquage des secrets, identifiants de corrélation, erreurs RFC 9457, limite de taille (413), délai (504) | ✓ | ✓ | — |
| Authentification des appelants : JWT Keycloak (signature par JWKS, issuer, audience, expiration, `azp` allow-listé) | ✓ | ✓ | ✓ (vrai JWKS Keycloak ; voir §6) |
| Client Core API typé et asynchrone (client credentials, cache de jeton, 1 rafraîchissement sur 401, reprises avec backoff, erreurs sûres) | ✓ | ✓ | ✓ (appel réel authentifié → 404 attendu) |
| Contrats de contexte d'exécution ; contrats de rapport d'état, de résultat et de demande d'approbation (modèles seulement) | ✓ | ✓ (contexte) | — |
| Fournisseurs Anthropic, OpenAI, Gemini : `generate`, `stream`, `list_models`, usage, erreurs normalisées, limites de débit, reprises, délais | ✓ | ✓ (mocks) | ✗ |
| Moteur LangGraph : état typé, contexte d'exécution, identifiants de thread générés côté serveur, délai, annulation, reprise, pause humaine | ✓ | ✓ | ✓ (checkpoints PostgreSQL réels dans le conteneur) |
| Graphe de diagnostic déterministe (sans LLM, shell ni fichier) | ✓ | ✓ | — |
| Checkpoints PostgreSQL dans une base et un schéma dédiés, rôle restreint | ✓ | ✓ | ✓ (base `ai_orchestrator`, rôle sans privilèges) |
| MCP : configuration à liste fermée, registre d'outils, validation des métadonnées, autorisation par permissions B3, cycle de vie, délais | ✓ | ✓ (vrai protocole stdio) | — |

## 3. Intégration avec le Core API (Spring Boot)

Implémenté **et testé** (6 nouveaux tests d'intégration, 194 au total) :

- Chaîne de sécurité dédiée `/api/v1/internal/**` (ordre 1) : n'accepte que les jetons dont `azp` est le client `ai-orchestrator` (propriété `app.orchestrator.client-id`, variable `ORCHESTRATOR_CLIENT_ID`) et leur donne l'unique autorité `ROLE_ORCHESTRATOR`.
- La chaîne utilisateur **refuse** ces jetons (401) : un jeton de service ne peut jamais être provisionné comme utilisateur ni servir sur les endpoints utilisateur.
- Endpoint en lecture seule `GET /api/v1/internal/organizations/{org}/agents/{agent}/execution-context` : revalide le couple organisation/agent (404 sinon), n'accepte que les agents `ACTIVE` (409 sinon), ne renvoie que les liaisons dont la connexion est `VALID`, les droits d'outils approuvés (avec coordonnées MCP), les compétences actives et la mémoire. **Aucun identifiant ni secret** n'y figure (vérifié par test).
- Contrat OpenAPI synchronisé.

**Vérifié en réel** avec un vrai jeton Keycloak : `internal` + jeton de service → 404 (autorisé, ressource absente) ; `/api/v1/me` + jeton de service → 401 ; sans jeton → 401.

**Non implémenté (volontairement)** : rapports d'état, soumission de résultats, demandes d'approbation et résolution de crédentiels côté Core API. Seuls leurs contrats Python existent : aucun endpoint interne non restreint n'a été inventé.

## 4. Crédentiels des fournisseurs

- Aucun second stockage de clés : le SecretStore B3 reste la seule source. L'orchestrateur définit un `CredentialResolver` qui **échoue par défaut** (fail-closed) tant que C1 n'a pas ajouté le mécanisme Core API dédié (conception : `PROVIDER_INTEGRATION_ARCHITECTURE.md`).
- Aucune clé dans l'état LangGraph (`assert_state_safe` refuse les clés et valeurs ressemblant à des secrets ; un test lit les tables de checkpoints), les journaux, les réponses, ni les métadonnées d'outils MCP.
- **Exposition transitoire dans le moteur de rendu (durcie, non supprimée)** : la clé est toujours saisie une fois dans le renderer Angular. Mesures ajoutées : champ `password` sans autocomplétion, correction, majuscules ni gestionnaires de mots de passe (`autocomplete=new-password`, `data-1p-ignore`, `data-lpignore`, …) ; la clé est **sortie du formulaire avant l'envoi** (seule une variable locale la détient pendant la requête) ; elle est **effacée** à la perte de focus de la fenêtre, quand la page est masquée, à la fermeture, à l'annulation et à la destruction du composant (le nœud DOM est vidé aussi) ; aucun stockage navigateur. Le processus principal Electron ne journalise aucun corps de requête (vérifié). **La frappe existe malgré tout en mémoire du renderer** puis transite par l'IPC : on ne peut pas affirmer le contraire. Une saisie native côté processus principal reste la piste de suppression complète (5 tests Angular ajoutés).

## 5. Docker et infrastructure

- `Dockerfile` multi-étapes (`uv sync --frozen`), utilisateur non root (uid 10001), système de fichiers en lecture seule, `cap_drop: ALL`, `no-new-privileges`, healthcheck. Profil Compose `orchestrator` (non démarré par défaut), publié sur `127.0.0.1:8091` uniquement.
- **Port** : 8090 est occupé par un logiciel tiers sur cette machine (vérifié) ; le défaut est donc **8091**.
- `db-init` (idempotent) crée la base `ai_orchestrator` et le rôle `aiw_orchestrator` (ni superuser, ni createdb, ni createrole, accès révoqué à PUBLIC) **sur le volume existant**, sans rien supprimer.
- `ensure-env.sh` ajoute `ORCHESTRATOR_DB_*`, `ORCHESTRATOR_CLIENT_SECRET`, `ORCHESTRATOR_PORT` sans modifier les valeurs existantes (vérifié par diff). **Correctif** : il tolère désormais les fins de ligne CRLF (un CR final finissait dans les valeurs générées).
- Script idempotent `infra/keycloak/scripts/provision-orchestrator-client.sh` (le realm n'est importé qu'une fois et ne doit contenir aucun secret) : client confidentiel, compte de service, ni flux navigateur ni mot de passe ni implicite, mapper d'audience `ai-workforce-api` ; secret passé par variable d'environnement, jamais affiché. Exécuté deux fois : OK.
- Job CI `orchestrator` ajouté (ruff + pytest, sans clé réelle).

## 6. Résultats exécutés

| Vérification | Résultat |
| --- | --- |
| Python `pytest` (Windows) | **135 réussis, 3 ignorés** (tests live) |
| Python `pytest -m "not docker"` (conteneur Linux `python:3.13-slim`) | 127 réussis, 3 ignorés, 8 écartés (ceux qui exigent Docker) |
| Python `ruff check` | OK |
| Core API `./mvnw clean verify` | **194 tests, 0 échec** (188 + 6), ArchUnit 21 règles |
| Angular (Vitest) | **94 tests, 0 échec** (89 + 5) |
| Electron | **44 tests, 0 échec** ; build OK |
| Builds Angular production et `mock` | OK |
| OpenAPI exporté vs contrat | identiques |
| `docker compose config` (profils `app` et `orchestrator`) | valide |
| `git diff --check` | aucune erreur |
| Conteneur de l'orchestrateur | sain ; non root ; système de fichiers en lecture seule (écriture refusée) ; `ready` = `UP` ; `/runtime/info` sans jeton = 401 |
| Jeton de service Keycloak réel | `iss` = issuer du Core API, `aud` ⊇ `ai-workforce-api`, `azp` = `ai-orchestrator`, pas d'e-mail ; rejeté par l'orchestrateur (401) car ce n'est pas un appelant autorisé — comportement voulu |

**Bug trouvé grâce au test réel** : le JWKS de Keycloak contient aussi des clés de chiffrement (`use: enc`, RSA-OAEP) que PyJWT refuse ; la vérification échouait en 503. Seules les clés de signature sont désormais chargées (test de non-régression ajouté).

## 7. Non vérifié

- **Aucune connectivité réelle avec Anthropic, OpenAI ou Gemini** : aucune clé réelle n'était disponible ; les 3 tests live sont ignorés. Les formes des requêtes de génération et de streaming ne sont vérifiées que par des mocks ; seul l'appel du catalogue de modèles est conçu comme test live (non facturable).
- Aucun appel d'un appelant autorisé (`ai-workforce-core-api`) vers l'orchestrateur : ce client de service n'existe pas encore (C1). Le chemin « jeton valide accepté » est testé avec une clé RSA locale, pas avec Keycloak.
- Le rapport « Linux » n'inclut pas les tests PostgreSQL (Testcontainers exige un Docker imbriqué) ; ils ont été exécutés sous Windows et dans le conteneur réel.
- Parcours graphique d'Electron et test Keycloak réel d'Electron non relancés.

## 8. Reporté (C1 à C3)

Exécution d'un agent, appels LLM pour des tâches, exécution d'outils et d'autorisations MCP réelles, serveurs MCP distants, mécanisme de résolution de crédentiels côté Core API, enregistrements d'exécution, approbations humaines, rapports d'état et résultats, coût/usage, orchestration des fallbacks, rotation de clés, saisie native de la clé fournisseur, client de service `ai-workforce-core-api`.

## 9. Fichiers principaux

Python : `services/ai-orchestrator/` (code, 135+3 tests, `Dockerfile`, `uv.lock`). Core API : `SecurityConfig`, `OrchestratorProperties`, `ExecutionContext{View,Reader}`, `InternalExecutionController`, test d'intégration. Infra : `compose.yaml`, `init-roles.sh`, `ensure-env.sh`, `.env.example`, script Keycloak. Desktop : `providers-tab.ts` + spec. Docs : architecture IA, fournisseurs, MCP, ADR-0016, README (racine, orchestrateur, Core API, Keycloak).
