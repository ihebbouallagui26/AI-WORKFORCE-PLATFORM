# Sprint C2 — Exécution sécurisée d'outils IA, MCP et approbation humaine : rapport final

Branche : `feat/ai-tool-execution-c2` (base `4224d6f`, ancêtre vérifié). **Rien n'est committé ni poussé** : 46 fichiers modifiés,
~50 non suivis, rien d'indexé. Les migrations V1–V5 sont intactes ; V6 est nouvelle et additive.

## Niveaux de preuve (à ne pas confondre)

| Niveau | Ce que cela prouve | Ce que cela ne prouve pas |
|---|---|---|
| Tests unitaires | logique isolée (politique, hash, chiffrement, chronologie, masquage) | l'intégration |
| Tests d'intégration simulés | boucle outils/approbation côté Python avec Core API, Anthropic et MCP **simulés** ; composants Angular avec un **faux pont de bureau** | le comportement des vrais services |
| MCP local réel | gestionnaire de production + SDK `mcp` 2.3.0 + vrai processus enfant stdio (timeout, crash, sortie volumineuse, nettoyage, isolation de l'environnement) | un serveur MCP tiers |
| PostgreSQL réel (Testcontainers) | persistance, migrations, verrous, reprise, checkpoints scellés | — |
| Recréation réelle de conteneur | l'orchestrateur Docker recréé reprend un état PostgreSQL scellé avec la même clé | — |
| **Pile complète réelle (C2.5)** | Keycloak (PKCE), classes du processus principal Electron (`AuthSession`, `ApiProxy`), Core API, PostgreSQL, conteneur orchestrateur, gestionnaire MCP réel — 28 contrôles | l'interface Angular dans une fenêtre authentifiée |
| Application Electron réelle | démarrage, surface `window.desktop`, liste blanche via le vrai canal IPC | une session authentifiée dans la fenêtre |
| **Anthropic réel** | — | **NON VÉRIFIÉ** : aucune requête réelle n'a été faite (faux fournisseur déterministe partout) |

## 1. Objectifs
« Un agent peut demander une action ; la plateforme décide si elle est autorisée, soumise à approbation humaine, ou refusée. »
Livré : registre d'outils unique avec politique, usage d'outils Anthropic borné, MCP réel (stdio, liste blanche), approbation
humaine liée à l'appel exact, pause/reprise durables, chiffrement de l'état suspendu, interface de supervision.

## 2. Architecture
Utilisateur → Core API (valide, enregistre) → orchestrateur (contexte autorisé, boucle modèle/outils) → Core API (décide chaque
appel : automatique / approbation / refus) → gestionnaire MCP (exécute) → Core API (résultat borné) → utilisateur (chronologie).
Le Core API possède toutes les données métier ; l'orchestrateur n'écrit que dans sa base de checkpoints. Détails :
`AI_ORCHESTRATION_ARCHITECTURE.md`, `MCP_AND_TOOL_SECURITY.md`.

## 3. Registre d'outils et autorisation
Migration V6 : colonnes de politique sur `tool_definitions` (tout est `DENIED` par défaut), outils de référence du serveur
local `diagnostic` (`echo`, `add` en lecture seule ; `record_note` en écriture avec approbation), statut `WAITING_APPROVAL`,
tables `tool_invocations` et `tool_approvals`, permissions `approval.read` / `approval.decide` (OWNER, ADMIN).
Revalidation au démarrage de l'appel ; une approbation ne remplace jamais un grant manquant.

## 4. Usage d'outils Anthropic
Adaptateur `generate_turn` (blocs `tool_use` / `tool_result`), alias d'outils sûrs, validation JSON Schema des arguments,
limites de tours et d'appels. **Testé uniquement avec des réponses simulées.**

## 5. MCP
SDK 2.3.0, stdio uniquement, une connexion persistante par serveur, délais, nettoyage, erreurs normalisées. 27 tests réels
(Windows, Linux). Liste blanche fermée (`diagnostic`) ; environnement du processus enfant sans aucun secret (testé).

## 6. Approbations : états
`PENDING → APPROVED | REJECTED | EXPIRED | CANCELLED` ; appel : `AWAITING_APPROVAL → APPROVED → RUNNING → SUCCEEDED | FAILED` ;
exécution : `RUNNING → WAITING_APPROVAL → RUNNING | FAILED`. Un seul gagnant sous concurrence (verrous exécution → appel → approbation).

## 7. Reprise PostgreSQL
Outbox dans `tool_approvals`, maintenance planifiée (expiration, clôture des approbations d'exécutions terminées, redistribution des
notifications, abandon `RESUME_FAILED`), réclamation de retry sous verrou. Livraison **au moins une fois**, traitement idempotent ;
au plus un démarrage d'outil. 10 tests de reprise sur PostgreSQL réel. Détails : `APPROVAL_RECOVERY_ARCHITECTURE.md`.

## 8. Chiffrement des checkpoints
AES-256-GCM, AAD organisation:exécution, `AIO_STATE_ENCRYPTION_KEY` obligatoire en production ; seule la conversation suspendue est
chiffrée (identifiants et noms d'étapes restent en clair). Pas de rotation (différée). Détails : `STATE_ENCRYPTION.md`.

## 9. Docker
`ORCHESTRATOR_STATE_ENCRYPTION_KEY` (obligatoire, sans défaut, hors build), générée une fois par `ensure-env.sh` (existant préservé,
clé malformée = arrêt sans rien changer). Image : ni la variable ni la valeur dans l'environnement ni dans l'historique des couches ;
`prod` sans clé : code 1 sans fuite. `docker compose config` valide (profils `orchestrator` et `app`).

## 10. Interface Angular / Electron
Chronologie reconstruite à partir de l'exécution et des appels d'outils (**il n'existe pas d'endpoint d'événements**) ; cartes
d'activité ; carte d'approbation avec confirmation délibérée, hash des arguments, sans mise à jour optimiste ; historique paginé ;
reprise après rechargement ; polling borné à l'état terminal (limite 20 min). Le backend **n'expose pas d'horodatage de reprise** (non
affiché) ; un dépassement de délai est un `FAILED` avec `errorCode = TIMEOUT` ; l'auteur d'une décision est un **identifiant court de
membre**. Détails : `EXECUTION_EXPERIENCE_UI.md`.

## 11. Contrôles de sécurité
Voir `MCP_AND_TOOL_SECURITY.md` et la revue ci-dessous.

## 12. Tests automatisés (résultats exacts)

| Suite | Plateforme | Résultat |
|---|---|---|
| Core API `./mvnw clean verify` | Windows | **255 tests**, 0 échec, 0 ignoré, code 0 (dont ArchUnit 21) |
| Core API | Linux | non exécutée (pas de runner Linux disponible pour Maven/Testcontainers) |
| Python complet + `ruff` | Windows | **256 réussis**, 4 ignorés (live Anthropic/OpenAI/Gemini, opt-in) ; ruff OK |
| Python complet + `ruff` | Linux (`python:3.13-slim`, socket Docker) | **255 réussis**, 5 ignorés (4 live + 1 test Compose sans CLI docker) ; ruff OK |
| Angular `npm --prefix apps/desktop/ui test -- --watch=false` | Windows | **12 fichiers, 177 tests**, aucune erreur non gérée, aucune erreur de répéteur |
| Angular builds production et mock | Windows | OK |
| Electron tests / build | Windows | **44 / 44**, build OK |
| Angular, Electron | Linux | non exécutés |

## 13. Tests d'intégration réels (C2.5)
Script pilote : vrai Keycloak (connexion PKCE via `AuthSession`), `ApiProxy` avec liste blanche, Core API, PostgreSQL, conteneur
orchestrateur, MCP réel, faux fournisseur. **28 contrôles sur 28** :
lecture seule automatique ; pause et approbation unique (hash erroné refusé 409, double approbation 409, **un seul démarrage d'outil**,
un seul reçu) ; doublons de notification de reprise (202 puis 409, aucun second effet) ; rejet ; **recréation du conteneur orchestrateur**
pendant une pause puis décision par une **nouvelle session** (rechargement) → reprise réussie, même empreinte de clé ; expiration
(409, `APPROVAL_EXPIRED`, rien exécuté) ; exécution terminée avec approbation en attente → `CANCELLED` ; outil non accordé refusé ;
isolation entre organisations (404) ; membre simple 403 ; liste blanche (route interne, suppression, approbation en masse, historique
global : bloquées avant le réseau). Journaux orchestrateur + Core API et trois tables de checkpoints : aucune clé, aucun secret, aucune
instruction ni argument en clair.
Application Electron **réelle** démarrée avec profil temporaire, inspectée via CDP : écran de connexion chargé, `window.desktop` =
`api`, `auth`, `getAppInfo`, `openExternal` (inchangé), ni `require` ni `process`, routes internes et suppression refusées par le canal
IPC réel, routes d'approbation autorisées jusqu'à l'authentification.
Test de contrat : 17 tests comparent les **vraies réponses capturées** du Core API aux modèles Angular (champs exacts, énumérations,
structure de page) et vérifient l'affichage de la chronologie et de la carte à partir de ces charges réelles.
**Limite de frontière** : l'interface Angular n'a pas été pilotée dans une fenêtre Electron authentifiée (la connexion exige un
navigateur interactif). L'affichage est donc démontré par tests de composants alimentés par des réponses réelles, pas par un test de
bout en bout dans la fenêtre.

## 14. Revue de sécurité (diff C2 complet)
Aucun constat **CRITICAL** ni **HIGH**.

| # | Sévérité | Constat | Fichier | État |
|---|---|---|---|---|
| 1 | MEDIUM | Un appel d'outil `RUNNING` restait `RUNNING` à jamais si l'exécution se terminait (orchestrateur tombé en plein appel). Scénario : approbation → `begin` → `fail`. Impact : historique trompeur, appel jamais clos. | `ApprovalLifecycle`/`ApprovalMaintenance` | **Corrigé** : clôture en `FAILED / OUTCOME_UNKNOWN`, test de non-régression |
| 2 | MEDIUM | Les erreurs de validation de la configuration affichaient la fin des valeurs d'entrée (dont la clé de chiffrement). | `app/core/config.py` | **Corrigé** (C2.3) : `hide_input_in_errors` + test |
| 3 | LOW | Un doublon de reprise tardif était pris pour un état perdu ; `error_code` périmé relu d'un checkpoint. | `graphs/execution.py`, `executor.py` | **Corrigé** (C2.3.1) + 2 tests |
| 4 | LOW | Commentaire inexact : la capacité « ouverte » comptait aussi `WAITING_APPROVAL`. | `ExecutionProperties.java` | **Corrigé** |
| 5 | MEDIUM | Le résultat d'un outil est stocké en clair (borné à 8000 caractères) dans `tool_invocations.result` et visible des membres `agent.execution.read`. | `ToolCallCoordinator`, V6 | Ouvert (documenté) : n'utiliser que des outils dont la sortie n'est pas sensible, ou chiffrer ce champ (C3) |
| 6 | LOW | Rédaction des arguments par nom de clé et motif : un secret dans un champ libre non reconnu n'est pas masqué. | `ToolArguments.java` | Ouvert (documenté) |
| 7 | LOW | Pas de règle des quatre yeux : le demandeur peut approuver s'il a `approval.decide`. | `ApprovalService` | Ouvert (décision de conception) |
| 8 | LOW | Le relecteur ne voit que des arguments masqués ; l'approbation porte sur le hash des arguments complets. | `ApprovalCard`, `ToolArguments` | Ouvert (documenté) |
| 9 | LOW | L'AAD du blob scellé lie organisation et exécution, pas l'étape : un accès en écriture à la base de checkpoints permettrait de rejouer un état plus ancien (le Core API contrôle toujours chaque appel). | `app/core/crypto.py` | Ouvert (documenté) |
| 10 | LOW | La sortie d'un serveur MCP est tronquée après réception complète (allocation possible de plusieurs Mo). | `app/mcp/executor.py` | Ouvert (documenté) |
| 11 | LOW | Le contrat OpenAPI n'est pas reproductible octet pour octet (ordre des clés du schéma `JsonNode`) et ce schéma expose des propriétés internes (`pojo`, `float`…). Sémantiquement identique au contrat publié. | `ToolViews`/springdoc | Ouvert |
| 12 | LOW | L'auteur d'une décision n'est affiché que par un identifiant court de membre. | `approvals-tab.ts` | Ouvert (limite d'API) |

Contrôles vérifiés sans écart : isolation par organisation (tests Core, pile réelle), IDOR approbations/appels, hash et expiration,
décisions concurrentes (un gagnant), grants révoqués, authentification de service (jeton utilisateur refusé sur `/internal`),
liste blanche MCP et refus de commande/argument/URL fournis par un agent, nettoyage des processus, isolation de l'environnement enfant,
nonce aléatoire et intégrité GCM (altération, mauvaise clé, mauvaise exécution rejetées), clé absente des journaux/image/checkpoints,
rendu texte (pas de HTML), masquage côté client, absence de nouvelle capacité IPC.

## 15. Limites connues
Anthropic réel non vérifié ; pas de streaming ; pas d'endpoint d'événements (chronologie reconstruite) ; pas d'horodatage de reprise ;
`TIMEOUT` = `FAILED` + code ; auteur de décision = identifiant court ; livraison au moins une fois (pas « exactement une fois ») ;
chiffrement limité à la conversation suspendue ; pas de rotation de clé ; pas de shell, fichiers ni navigateur ; pas de multi-agents.
Core API, Angular et Electron non exécutés sous Linux dans cette session.

## 16. Reporté à C3 et évaluation finale
C3 : collaboration multi-agents, outils d'écriture réels, transports MCP réseau, rotation de clé, chiffrement du résultat d'outil,
règle des quatre yeux, endpoint d'événements avec horodatage de reprise.
**Évaluation : PRÊT AVEC LIMITES DOCUMENTÉES.** Aucun constat bloquant ; les limites ci-dessus (Anthropic réel, interface dans une
fenêtre authentifiée, Linux hors Python) sont explicites.
