# Sprint C1 — Première exécution supervisée d'un agent IA : rapport d'implémentation

- **Branche** : `feat/ai-agent-execution-c1`, créée depuis le commit C0 `5c48ac7` (historique C0 intact).
- **Date** : 14 novembre 2026
- **État Git** : modifications **non commitées et non poussées**.
- **Périmètre** : une exécution **déclenchée par un humain**, **supervisée**, en **génération de texte uniquement** avec Anthropic Claude. Aucun outil, shell, fichier, navigateur ni appel MCP ; pas de bascule automatique entre fournisseurs ; rien ne démarre sans action explicite d'un utilisateur autorisé.

## 1. Légende (à lire avant les résultats)

| Terme | Sens |
| --- | --- |
| **Test automatisé (mocks)** | Test exécuté avec un faux Core API et un **faux Anthropic** (respx / stub). Prouve la logique, pas le vrai fournisseur. |
| **Intégration locale** | Pile réelle (Keycloak, PostgreSQL, Core API, orchestrateur, checkpoints LangGraph) mais avec un **faux serveur Anthropic local**. |
| **Test live Anthropic** | Appel réel à l'API d'Anthropic. **Non réalisé.** |

## 2. Ce qui est livré

### 2.1 Domaine d'exécution (Core API)
- Migration additive **V5** : table `agent_executions` (clés composites `(organization_id, agent_id)` et `(organization_id, connection_id)`, contraintes CHECK de machine à états : date de fin ssi état terminal, résultat seulement en succès, code d'erreur ssi échec), permissions `agent.execute` et `agent.execution.read` (OWNER/ADMIN uniquement ; membres et rôles personnalisés non élargis). V1–V4 intactes.
- Machine à états `PENDING → RUNNING → SUCCEEDED | FAILED` (et `PENDING → FAILED`), appliquée dans l'entité sous verrou de ligne : transition illégale ou doublon = 409. `CANCELLED` **non implémenté** (un appel fournisseur en cours ne peut pas être arrêté de façon cohérente).
- API : `POST /organizations/{org}/agents/{id}/executions` (202), `GET …/agents/{id}/executions`, `GET …/executions`, `GET …/executions/{id}` ; pagination, plus récent d'abord, filtres, RFC 9457, OpenAPI synchronisé.
- Règles au lancement : appartenance + `agent.execute`, agent trouvé **via l'organisation de l'appelant** (404 sinon), agent `ACTIVE`, liaison PRIMARY avec connexion `VALID` et fournisseur Anthropic, limite d'exécutions ouvertes par organisation (3 par défaut, sous verrou d'organisation). Audit du lancement **sans l'instruction**.
- Dispatch après commit, via un pool borné **explicite** (jamais sur le thread de la requête) ; échec sûr si la remise échoue (voir §2.6) ; **reaper** qui échoue (`TIMEOUT`) les exécutions restées ouvertes au-delà de `app.executions.timeout`.

### 2.2 Communication service à service (les deux sens)
- Deux clients Keycloak confidentiels, client credentials uniquement : `ai-orchestrator` (→ Core API, audience `ai-workforce-api`) et **`ai-workforce-core-api`** (→ orchestrateur, audience `ai-orchestrator`). Provisionnés par un script idempotent (exécuté sur le Keycloak existant).
- Core API → orchestrateur : jeton de service du Core API (cache mémoire, jamais journalisé) ; **jamais** un jeton utilisateur. L'orchestrateur n'accepte que les clients de service autorisés (`azp`).
- Endpoints internes du Core API (chaîne `/api/v1/internal/**`, réservée au compte de service de l'orchestrateur) : `start`, `credential`, `complete`, `fail`. Les jetons utilisateur y reçoivent 403 ; le jeton de service est refusé sur les endpoints utilisateur (401).

### 2.3 Résolution sécurisée du crédentiel (cœur du sprint)
- Le SecretStore B3 reste l'unique source. `ProviderCredentialBroker` (package `provider`, seul autorisé à appeler `SecretStore.reveal`) déchiffre uniquement pour l'endpoint `credential`.
- **Conditions de délivrance** : compte de service de l'orchestrateur ; exécution `RUNNING` ; **une seule fois** par exécution ; agent encore `ACTIVE` ; liaison PRIMARY inchangée (même connexion) ; connexion `VALID` ; fournisseur activé. Sinon : 409/403/404 (fail closed). Un refus ne consomme pas l'usage unique.
- Réponse `Cache-Control: no-store`, `toString` expurgé, jamais stockée ni auditée ni renvoyée ailleurs.
- Côté orchestrateur : un `CredentialResolver` **limité à l'exécution**, appelé **à l'intérieur du nœud d'invocation du modèle** ; le secret n'existe que dans les variables locales d'un appel fournisseur. **Rien dans l'état LangGraph, les checkpoints, les journaux, les erreurs ni l'enregistrement d'exécution.**

### 2.4 Graphe LangGraph d'exécution (orchestrateur)
`validate → start → load_context → build_prompt → invoke_model → report → finalize`, plus une branche d'échec unique. Toute exception devient un **code d'erreur sûr** avec message fixe (aucun corps de fournisseur ni du Core API n'est copié). L'état checkpointé ne contient que identifiants, noms d'étapes, usage et code d'erreur ; **l'instruction, le prompt et le résultat ne sont pas checkpointés**. Les droits d'outils sont **ignorés** (aucun outil n'est lancé ni décrit au modèle). Exécutions en arrière-plan, bornées (concurrence, délai), annulées à l'arrêt du service.
- Adaptateur Anthropic réel (API Messages) : modèle issu de la liaison de l'agent (jamais codé en dur), `max_tokens` configurable (défaut 1024), délais et reprises bornés (429/5xx/timeout), usage enregistré.

### 2.4 bis Interface Angular
Dans le détail d'un agent (mode `api`) : panneau « Run this agent » (instruction ≤ 8000 caractères, lancement explicite), suivi par polling (nettoyé à la destruction, abandon après 10 min), résultat, usage, message d'échec sûr, historique des exécutions de l'agent (ouvrable). Bouton désactivé (avec explication) si l'agent n'est pas éligible ou si l'utilisateur n'a pas `agent.execute` ; aucun champ de clé ; état supprimé au changement d'organisation ou d'agent. Liste d'autorisation Electron : lancement/lecture/liste uniquement ; **jamais** les endpoints `/internal/**`. Aucune réponse « réussie » factice en mode réel.

### 2.5 Durcissement annexe
`python -m app` fonctionne maintenant sur Windows natif (sélection de la boucle `selector` requise par psycopg ; `uvicorn` imposait la boucle `proactor`) — découvert pendant la vérification locale.

### 2.6 Corrections pré-commit (audit F1–F4)

| # | Correction | Tests |
| --- | --- | --- |
| **F1** | `ExecutionCoordinator.failIfPending` (nouvelle transaction, verrou de ligne) n'échoue une exécution **que si elle est `PENDING`**. Une erreur de remise (par exemple un 202 perdu après que l'orchestrateur a démarré le run) ne peut plus écraser une exécution `RUNNING`, `SUCCEEDED` ou `FAILED`. Le reaper garde `failIfOpen`. | `aLostDispatchAnswerNeverFailsAnExecutionThatTheOrchestratorAlreadyStarted` (RUNNING conservé puis complétion acceptée ; **vérifié rouge** avec l'ancien comportement), `aDispatchErrorNeverOverwritesAFinalExecution` (SUCCEEDED et FAILED intacts), `aGenuinelyPendingExecutionIsStillFailedWhenTheHandOverFails` |
| **F2** | Le dispatcher soumet explicitement la remise au pool (plus de `@Async`) ; pool borné avec `AbortPolicy` (jamais `CallerRunsPolicy`) ; un `TaskRejectedException` fait échouer l'exécution **immédiatement** avec `DISPATCH_CAPACITY_EXCEEDED` (écriture en nouvelle transaction, depuis le callback après commit). Le lancement répond **202 avec l'identifiant et l'état déjà réel (`FAILED`)** : le client ne doit jamais deviner. Pool configurable (`app.executions.dispatch-max-threads`, `dispatch-queue-capacity`). | `ExecutionDispatchSaturationIntegrationTest` : rejet, ligne persistée une seule fois, auditée, lisible par id, réponse 202 + FAILED, remise rejetée jamais arrivée à l'orchestrateur, **tous les appels de remise sur des threads `exec-dispatch-*`**, remise en cours non affectée, capacité retrouvée |
| **F3** | `OrchestratorDispatchValidator` : hors profils `dev` et `test` (donc aussi sans profil), l'URL de l'orchestrateur et celle du jeton doivent être **HTTPS** (pas d'exception loopback) ; URL mal formée, avec identifiants ou schéma non http(s) refusées dans tous les profils ; configuration partielle fatale en production, remise désactivée en développement ; URL du jeton dérivée par défaut de `OIDC_ISSUER`. La config Compose locale (HTTP sur réseau Docker, profil `dev`) est inchangée. **Régression détectée et corrigée** pendant la vérification : une première version refusait de démarrer le Core API local (le `.env` contenait l'URL et le secret mais pas l'URL du jeton). | `OrchestratorDispatchValidatorTest` (18 cas : HTTPS prod accepté, HTTP prod refusé y compris loopback, HTTP dev/test accepté y compris hôte Docker, URL invalides, partiel, absent, URL du jeton dérivée seule) ; démarrage réel du Core API en profil `dev` vérifié |
| **F4** | Vérifié par test : les exécutions `PENDING` **et** `RUNNING` périmées passent en `FAILED/TIMEOUT`, la capacité de l'organisation est **libérée** (nouveau lancement accepté), une exécution terminée n'est jamais touchée. Le délai de récupération et l'absence de redispatch sont documentés (voir §6). Aucun redispatch automatique : il pourrait exécuter et facturer deux fois la même instruction. | `closingOrphanedExecutionsReleasesTheOrganizationCapacityAndLeavesFinishedRunsAlone` + `stalePendingAndRunningExecutionsAreFailedWithATimeout` |

Aucune migration ni fichier sans rapport n'a été modifié par ces corrections (seule V5, nouvelle depuis C0, existe).

## 3. Résultats exécutés

| Vérification | Résultat | Nature |
| --- | --- | --- |
| Core API `./mvnw clean verify` | **230 tests, 0 échec** (194 + 36, dont 18 tests unitaires du validateur de configuration) ; ArchUnit OK ; OpenAPI synchronisé | Intégration (PostgreSQL Testcontainers, orchestrateur simulé) + unitaires |
| Python `pytest` | **164 réussis, 4 ignorés** (3 tests live catalogue + 1 test live génération) ; `ruff` OK | Mocks + PostgreSQL réel |
| Angular (Vitest) | **109 tests, 0 échec** (94 + 15) | Mocks |
| Electron | **44 tests, 0 échec** ; build OK | — |
| Builds Angular production et `mock` | OK | — |
| `docker compose config`, build de l'image orchestrateur | OK | — |
| `git diff --check` | aucune erreur d'espaces | — |

Nouveaux tests notables — **Core API** : lancement, éligibilité (brouillon, pause, connexion non valide, fournisseur non activé), RBAC (rôle sans permission, séparation lancer/lire), **IDOR inter-organisations** (utilisateur et compte de service), pagination, limite de concurrence, machine à états et non-duplication de la complétion, échecs sûrs, **crédentiel à usage unique** (refus avant démarrage, après pause, après remplacement du crédentiel), aucun secret dans les vues, les lignes ni l'audit, séparation jetons utilisateur/service, orchestrateur injoignable, reaper, migration B3→V5 avec rétro-affectation des permissions. **Python** : exécution de bout en bout avec Anthropic simulé (requête, en-têtes, `max_tokens`, **aucun outil envoyé**), absence de secret/instruction/résultat dans les checkpoints (mémoire **et PostgreSQL réel**), journaux sans secret, erreurs fournisseur normalisées, refus de crédentiel, exécution d'une autre organisation, reprises, délai, concurrence, jetons de service invalides. **Angular** : validation du formulaire, lancement, progression, résultat, échec, refus serveur, historique, changement d'organisation, aucun champ de clé.

### Vérification d'intégration locale (pile réelle, **faux Anthropic**)
Exécutée avec de vrais jetons Keycloak (utilisateur de test temporaire + les deux comptes de service), le vrai Core API, PostgreSQL, l'orchestrateur lancé depuis les sources et ses checkpoints PostgreSQL :
- connexion testée `VALID`, agent activé, exécution lancée → `SUCCEEDED`, 57/9 tokens, résultat persisté ;
- le faux fournisseur a reçu la clé stockée (authentifiée), le modèle de la liaison, `max_tokens` 1024, **aucun outil**, le prompt système de l'agent ;
- **la clé est absente** de la ligne d'exécution, de l'audit, des tables de checkpoints (9 lignes pour l'exécution), des journaux de l'orchestrateur et du Core API ; l'instruction et le résultat sont absents des checkpoints ;
- fournisseur arrêté → `FAILED` / `PROVIDER_UNAVAILABLE` avec message sûr ;
- endpoint `credential` avec jeton utilisateur → 403 ; point d'entrée de l'orchestrateur avec jeton utilisateur → 401.
Le client Keycloak et l'utilisateur de test temporaires ont été supprimés. Une organisation « C1 E2E (safe to delete) » et ses données de test restent dans la base locale de développement.

## 4. Connectivité réelle avec Anthropic : **NON VÉRIFIÉE**

Aucune clé Anthropic réelle n'était configurée. Toutes les exécutions ci-dessus ont utilisé des fournisseurs **simulés** : elles prouvent le câblage, la sécurité et la gestion d'erreurs, **pas** que l'API Anthropic accepte ces requêtes. Pour la vérifier : `AIO_LIVE_PROVIDER_TESTS=1 AIO_LIVE_ANTHROPIC_GENERATION=1 AIO_LIVE_ANTHROPIC_KEY=… AIO_LIVE_ANTHROPIC_MODEL=<id de modèle> uv run pytest tests/test_live_providers.py` (**facturable**, quelques tokens), puis un lancement depuis l'interface avec une vraie connexion. Aucune clé n'a été saisie, committée ni écrite dans un rapport.

## 5. Considérations de sécurité

Humain à l'origine de chaque exécution ; organisation toujours revalidée par le Core API (jamais prise d'un client) ; deux identités de service distinctes ; jetons utilisateur et de service cloisonnés ; crédentiel à usage unique, lié à l'exécution, jamais persisté ; état et checkpoints sans secret ni contenu métier ; messages d'erreur fixes ; limites (taille de l'instruction 8000, sortie, concurrence, délai) ; audit sans instruction ; aucun outil exécuté.

## 6. Dette technique et limites connues

- Exécutions en tâches asyncio **dans le processus** : elles ne survivent pas à un redémarrage de l'orchestrateur. **Récupération** : le reaper (toutes les 30 s) échoue en `TIMEOUT` toute exécution `PENDING` ou `RUNNING` plus ancienne que `app.executions.timeout` (5 min par défaut) ; la capacité de l'organisation (3 exécutions ouvertes) est alors libérée, soit au plus environ 5,5 min après un crash. **Aucun redispatch automatique** n'est garanti : si le Core API s'arrête entre le commit et la remise, ou si l'orchestrateur redémarre en cours de run, l'exécution se termine en `TIMEOUT` et l'utilisateur relance. Pas de file durable ni d'outbox (aucun besoin démontré). Garder `AIO_RUN_TIMEOUT_SECONDS` inférieur à `app.executions.timeout`.
- Polling côté desktop (pas de push) ; pas de streaming ; pas d'annulation. Un lancement refusé par saturation du pool répond tout de même 202 (avec l'état `FAILED` et le code `DISPATCH_CAPACITY_EXCEEDED`).
- Le crédentiel transite en clair entre le Core API et l'orchestrateur sur la liaison interne : HTTP dans le réseau Docker local, **HTTPS exigé en production** (validé par la configuration de l'orchestrateur ; à imposer aussi côté Core API par la configuration de déploiement).
- Le rapport de coût (usage) est enregistré mais aucun quota n'existe.
- L'historique est limité aux 10 dernières exécutions par agent dans l'interface.
- Le script d'intégration locale utilisé n'est pas versionné (il crée des comptes Keycloak) ; la procédure est décrite ci-dessus.
- La saisie initiale de la clé fournisseur (B3) reste visible brièvement dans la mémoire du renderer (inchangé depuis C0).

## 7. Reporté (C2 / C3)

Exécution d'outils et autorisations MCP réelles, approbations humaines, serveurs MCP distants, collaboration multi-agents, OpenAI/Gemini en exécution, bascule de fournisseur, annulation, file durable, streaming, quotas et coûts.

## 8. Fichiers

Créés : V5, `AgentExecution`, `ExecutionStatus`, `ExecutionRepository`, `ExecutionService`, `ExecutionCoordinator`, `ExecutionController`, `InternalExecutionRunController`, `ExecutionView`, `ExecutionProperties/Config/Dispatcher/Reaper`, `OrchestratorGateway`, `HttpOrchestratorGateway`, `ProviderCredentialBroker`, tests Core API (`ExecutionIntegrationTest`, `ExecutionReaperIntegrationTest`, `RecordingOrchestrator`) ; Python `graphs/execution.py`, `executor.py`, `routes/executions.py`, `tests/test_execution.py` ; Angular `agent-executions.ts` + spec ; ADR-0017. Modifiés : permissions, propriétés d'orchestrateur, `application.yml`, client Core API Python, configuration et registre de fournisseurs, `__main__`, allowlist Electron, détail d'agent, Compose, `.env.example`, script Keycloak, contrat OpenAPI, documentation (architecture IA, fournisseurs, READMEs, Keycloak).
