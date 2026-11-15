# C3.0 — Workflows LangGraph et collaboration multi-agents : audit et conception

Statut : **conception C3.0 approuvée ; C3.1 (pipeline déterministe) implémenté, voir §10.** Les sections 1 à 9 restent la conception d'origine.
Base vérifiée : `origin/main` = `ba80866` (fusion de la PR #5), qui contient `ccac7f7` (C2) et `94e717e` (correctif OpenAPI).

## 1. Audit de l'architecture actuelle (vérifié dans le code)

| Sujet | Réalité vérifiée |
|---|---|
| Unité d'exécution | `agent_executions` : **un agent**, une instruction (`task` ≤ 8000), un résultat (≤ 60000), `requested_by` (utilisateur, NOT NULL), `connection_id` figé au lancement. Statuts `PENDING, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED` |
| Lancement | `ExecutionService.start` : droit `agent.execute`, agent `ACTIVE`, fournisseur primaire valide, **Anthropic seul**, plafond par organisation sous verrou (`maxConcurrentPerOrganization` = 3, `WAITING_APPROVAL` compte) |
| Identifiant fournisseur | `ProviderCredentialBroker` : remis à l'orchestrateur **une seule fois par exécution**, `RUNNING` uniquement. Donc chaque « tour » d'agent exige une ligne d'exécution |
| Moteur Python | `execution.py` : graphe LangGraph par exécution (`validate → start → load_context → build_prompt → invoke_model/outils → report`), thread `organisation:exécution`, checkpoints PostgreSQL séparés, état = identifiants et compteurs, conversation suspendue scellée AES-256-GCM |
| Boucle outils | `tool_loop.py` : tours bornés, outils validés, décisions du Core API appel par appel, pause sur approbation, reprise idempotente |
| Approbations | `tool_approvals` liées à **un appel d'outil** (`invocation_id` NOT NULL, unique) ; outbox PostgreSQL, expiration, clôture des approbations d'exécutions terminées |
| Reprise | maintenance planifiée (`ApprovalMaintenance`), réaper des exécutions `PENDING/RUNNING` (`timeout` 5 min), conteneur orchestrateur recréable sans perte |
| Capacité Python | exécutions en tâches asynchrones **dans le processus** (`max_concurrent_executions` = 4), délai d'un segment `AIO_RUN_TIMEOUT_SECONDS` = 60 s |
| Équipes | `teams` (chef = **employé humain**), `team_agent_members` : appartenance d'agents sans aucune sémantique d'exécution |
| Interface | panneau d'exécution par agent, chronologie reconstruite (pas d'endpoint d'événements), approbations, historique |
| Fournisseurs | outils (tool use) : Anthropic uniquement |

### Composants réutilisables
exécution enfant (`agent_executions` + réaper + crédential à usage unique + jetons comptés) ; boucle outils et registre ; **approbations et outbox** ; clôture des approbations orphelines ; chiffrement d'état ; audit ; verrou par organisation ; `orgResource` et composants Angular (chronologie, cartes, approbation) ; fixtures de contrat capturées du Core API ; liste blanche Electron.

### Lacunes (à traiter, pas à contourner)
1. **Pas de lien exécution ↔ workflow** : aucune notion de parent, de rôle ni d'ordre.
2. **Pas d'annulation** : ni statut ni API (C1/C2 l'ont explicitement exclue).
3. **Plafond de concurrence par organisation (3)** : un workflow multi-étapes peut affamer ou être affamé ; il faut un plafond propre aux workflows.
4. **Exécutions en tâches du processus** : un workflow long ne peut pas vivre dans un processus ; il doit être reconstitué à chaque étape depuis l'état durable.
5. **Outils** : `ToolPolicyEvaluator` exige un backend MCP ; aucune action « de plateforme » (déléguer, terminer) n'existe.
6. **Approbations liées aux seuls appels d'outils** : un « point de contrôle humain » de workflow n'a pas de support direct.
7. **Aucune information sur les pairs** dans `ExecutionContextView` ; `task` borné à 8000 caractères.
8. **Aucun endpoint d'événements ni de messages** : la supervision d'un workflow ne peut pas se reconstruire proprement côté client.
9. **Outbox et maintenance spécifiques aux approbations** : à généraliser sans les dupliquer.
10. **Un seul fournisseur** (Anthropic) : un workflow hétérogène est impossible.

## 2. Architecture proposée

### 2.1 Décision structurante (à valider)
Trois options ont été comparées :

| Option | Principe | Avantages | Inconvénients |
|---|---|---|---|
| A | Machine à états du workflow **dans le Core API** ; LangGraph seulement *dans* chaque étape | une seule source de vérité, reprise déjà résolue (PostgreSQL), sécurité inchangée | LangGraph n'orchestre pas le workflow |
| B | **Graphe LangGraph « superviseur »** Python qui détient l'état du workflow et attend les enfants (`interrupt`) | usage natif de LangGraph | **deux sources de vérité** (checkpoints ↔ Core), état de workflow dans la base de checkpoints, long processus, cohérence à la reprise difficile |
| **C (recommandée)** | **Core API = système de référence et garde-fou** (états, limites, politique, audit) ; **LangGraph = chaque « tour » d'agent**, y compris les tours de décision du coordinateur, relancés à chaque événement ; état de workflow reconstruit par le Core API à chaque tour | durable par construction, aucune nouvelle source de vérité, réutilise tout C1/C2 | le flux global n'est pas un graphe LangGraph unique (le flux est une suite de graphes) |

Pourquoi pas B : l'état d'un workflow est de la donnée métier (le Core API la possède) ; la dupliquer dans des checkpoints crée exactement la divergence que C2.2 a dû éliminer. LangGraph garde son rôle fort : exécution durable, pause/reprise et checkpoints **par tour**.

### 2.2 Modèle conceptuel
* **Workflow (définition)** : coordinateur (optionnel) + **participants explicites** (agents choisis un par un) + limites.
* **Exécution de workflow (`run`)** : un objectif humain, un état, un budget.
* **Étape (`step`)** : un tour d'agent = **une `agent_executions` enfant** (aucun second modèle d'exécution), avec son rang, son parent, son rôle (`COORDINATOR` / `WORKER`).
* **Messages** : journal **en ajout seul** (tâche, résultat, question), lié à des étapes.

Séparation des états :

| Niveau | Contenu | Où |
|---|---|---|
| État du workflow | statut, tour courant, nombre d'étapes, budget consommé, résultat | Core API (`workflow_runs`) |
| État d'un agent | l'exécution enfant (statut, outils, approbations) | Core API (`agent_executions`, C1/C2) |
| État d'un tour LangGraph | conversation du tour, scellée si pause | base de checkpoints (inchangé) |
| Contexte partagé | objectif + résumés **bornés** de résultats d'étapes, reconstruits par le Core API pour chaque tour | jamais dans les checkpoints ; jamais intégral pour un worker |
| Messages et résultats | journal borné, immuable | Core API (`workflow_messages`), résultat complet dans l'exécution enfant |

### 2.3 Boucle d'exécution (séquentielle)
1. L'utilisateur lance un run (`workflow.execute`) ; le Core API valide, vérifie les plafonds et crée le run.
2. **Tour de décision** : une exécution enfant du **coordinateur** reçoit objectif + contexte partagé borné + budget restant.
3. Le coordinateur n'a que deux **outils de plateforme réservés** : `workflow.delegate(agent, task, inputs?)` et `workflow.finish(result)`. Ce sont des appels d'outils ordinaires (table `tool_invocations`) : donc **audités, idempotents par `(exécution, tool_call_id)`, validés par le Core API, soumis à approbation si la politique l'exige**. Seule une exécution marquée coordinateur peut les appeler.
4. Sur `delegate`, le Core API vérifie (participant explicite, agent `ACTIVE`, profondeur, nombre d'étapes, budget, anti-cycle) puis crée l'**étape worker** = exécution enfant de l'agent **avec ses propres outils et grants uniquement** ; elle est dispatchée par le chemin C1 existant.
5. À la fin du worker (ou son échec), le Core API enregistre le message résultat et lance le **tour de décision suivant**. Retour au 3, jusqu'à `finish` ou une limite.
6. Résultat consolidé = argument de `finish`, enregistré sur le run.

Sans coordinateur (mode **pipeline**, C3.1) : suite statique d'étapes définie par l'humain ; la sortie d'une étape alimente la suivante.

Parallélisme : **reporté**. Conditions préalables : étapes indépendantes déclarées, plafond d'organisation et de fournisseur, barrière de jointure, impact sur le volume d'approbations, limite d'éventail ≤ 3.

### 2.4 Délégation et gouvernance
* Un agent « disponible » n'est **pas** autorisé : il doit être **participant explicite** du workflow (ligne `workflow_participants`), créé par un utilisateur `workflow.manage` qui doit aussi avoir `agent.read` sur l'agent.
* Un worker s'exécute avec **ses** grants, jamais ceux du coordinateur ; le coordinateur ne peut accorder aucun outil.
* Revalidation à chaque démarrage d'étape (agent actif, participant toujours présent, politique du workflow inchangée).
* Les workers **ne délèguent pas** (profondeur 1) ; pas de messages worker → worker (topologie en étoile).
* Limites : `maxSteps` (par défaut 8), `maxDepth` = 1, `maxRetriesPerStep`, durée totale, plafond de workflows ouverts par organisation, jetons cumulés (somme des exécutions). Anti-boucle : même `(agent, hash(tâche))` refusé deux fois ; auto-délégation interdite.

### 2.5 Échecs, nouvelles tentatives, annulation, délais
* Politique par étape : `FAIL_FAST` (défaut), `CONTINUE` (le coordinateur est informé), `RETRY(n)` **uniquement** pour les erreurs transitoires du fournisseur et **jamais** si un appel d'outil a démarré (`OUTCOME_UNKNOWN` inclus).
* **Annulation** (nouvelle) : le run passe `CANCELLED` ; les enfants non démarrés ne sont pas lancés ; les enfants actifs sont terminés en `FAILED` avec le code `CANCELLED` (même convention que `TIMEOUT`) et l'orchestrateur le constate à son prochain appel au Core API (409) ; les approbations en attente sont clôturées par le mécanisme C2.2 existant.
* Délais : réaper existant par exécution enfant ; délai global du run vérifié par la maintenance (échec + annulation des enfants).
* Une approbation expirée fait échouer l'étape, puis applique la politique d'échec.

### 2.6 Approbation humaine
Les actions sensibles restent approuvées **au niveau de l'appel d'outil de l'étape** (mécanisme C2 inchangé). Un run est `WAITING_APPROVAL` si une de ses étapes l'est. Une délégation vers un agent sensible peut elle-même être `APPROVAL_REQUIRED` (c'est un appel d'outil réservé). Les « points de contrôle » libres (avant la synthèse, par exemple) exigent de généraliser les approbations : **reporté** (C3.4), pour ne pas déformer le modèle C2.

### 2.7 Persistance et reprise
* Migration additive **V7** (V1–V6 intactes) : `workflow_definitions`, `workflow_participants`, `workflow_runs`, `workflow_steps` (FK vers `agent_executions`, unique `(run, rang)`), `workflow_messages` (ajout seul). Toutes avec clés étrangères composites sur l'organisation. **Aucune modification de `agent_executions`** (le lien est porté par `workflow_steps`).
* Nouveaux droits : `workflow.read`, `workflow.manage`, `workflow.execute` (OWNER et ADMIN par défaut).
* Reprise : le tour suivant est déclenché par le Core API (outbox PostgreSQL sur le modèle d'`ApprovalMaintenance`, généralisé) ; un tour interrompu est rejoué par numéro de tour (`workflow_runs.turn`), idempotent ; les étapes déjà créées ne sont jamais recréées. Aucun Redis ni broker.
* Aucune donnée de workflow dans les checkpoints sauf identifiants ; conversation de tour scellée comme en C2.

## 3. Contrats Core API (à figer avant tout code)

| Usage | Endpoint (proposé) | Droit |
|---|---|---|
| Définitions | `POST/GET /organizations/{org}/workflows`, `GET/PATCH …/{id}`, `PUT …/{id}/participants`, `POST …/{id}/archive` | `workflow.manage` / `workflow.read` |
| Lancer | `POST …/workflows/{id}/runs` `{objective}` → 202 | `workflow.execute` |
| Superviser | `GET …/workflow-runs`, `GET …/workflow-runs/{id}`, `GET …/workflow-runs/{id}/steps`, `GET …/workflow-runs/{id}/messages` | `workflow.read` |
| Annuler | `POST …/workflow-runs/{id}/cancel` | `workflow.execute` |
| Approbations | endpoints C2 **inchangés** ; la vue d'approbation gagne `workflowRunId` et `stepId` optionnels (additif) | `approval.*` |
| Interne (orchestrateur) | `GET /api/v1/internal/…/executions/{id}/workflow-context` (rôle, tâche, entrées, budget restant) ; les outils réservés passent par `/tool-calls` existant | compte de service seul |

Compatibilité : vues d'exécution C1/C2 **inchangées** (champs additifs nullables seulement).

## 4. Intégration Angular / Electron (plan, après contrats)
Nouvelle fonctionnalité `workflows` : liste et éditeur (coordinateur, participants), lancement, **vue de run** (étapes en séquence, état par étape, messages, résultat consolidé) en réutilisant `ExecutionTimeline`, `ToolActivityCard` et `ApprovalCard` ; stores `orgResource` ; polling borné à l'état terminal ; liste blanche Electron étendue (jamais `/internal`) ; **tests de contrat sur réponses réelles capturées** (méthode C2.5). Le Core API doit exposer un endpoint de messages **avant** l'interface (la chronologie par reconstruction ne suffit pas pour plusieurs agents).

## 5. Analyse des menaces

| # | Menace | Parade |
|---|---|---|
| T1 | Délégation infinie / récursion | profondeur 1, `maxSteps`, anti-cycle, auto-délégation interdite, délai et budget globaux, plafond de workflows |
| T2 | Élévation de privilèges par délégation (confused deputy) | le worker n'a que ses grants ; participants explicites ; revalidation à chaque étape ; outils réservés limités au coordinateur |
| T3 | Injection de prompt via le résultat d'un worker | résultats enveloppés comme non fiables ; le coordinateur ne peut qu'appeler `delegate`/`finish`, validés par le Core API ; il ne peut ni ajouter de participant ni changer de limites |
| T4 | Fuite entre organisations | clés composites partout, chemins internes validés, participants de la même organisation |
| T5 | Fuite de contexte entre agents | le worker ne reçoit que sa tâche et les entrées choisies ; contexte intégral jamais transmis ; contexte hors checkpoints |
| T6 | Rejeu / doublons | `(run, rang)` unique, `(exécution, tool_call_id)` unique, numéro de tour, résultats rejetés si l'étape n'attend plus |
| T7 | Épuisement de ressources / coût | plafonds d'étapes, de concurrence, de jetons cumulés, de workflows ouverts |
| T8 | Contournement de l'approbation par délégation | l'approbation reste au niveau de l'appel d'outil de l'étape ; déléguer ne la remplace pas |
| T9 | Exposition d'identifiants | aucun identifiant dans l'état, les messages, les journaux ; identifiant fournisseur à usage unique par exécution inchangé |
| T10 | Répudiation | journal d'audit `workflow.*` corrélé aux exécutions, messages en ajout seul |
| T11 | Usurpation du coordinateur | seul un rôle `COORDINATOR` de l'étape courante peut appeler les outils réservés (contrôle côté Core API) |
| T12 | Divergence à la reprise | le Core API reste l'unique source de vérité ; un tour est rejouable |

## 6. Stratégie de tests
Fournisseur **simulé déterministe** partout au début (jamais une connectivité réelle). Couvrir : collaboration séquentielle ; bonne délégation ; isolation du contexte partagé ; organisation croisée ; assignation d'un agent non autorisé ; échec d'agent ; limites de reprise ; prévention de délégation infinie ; reprise après recréation du conteneur ; approbation humaine dans une étape ; doublons de messages et de requêtes ; workflows concurrents ; annulation. Niveaux : unitaires, intégration PostgreSQL réelle (Core), tests de contrat sur charges réelles, puis validation pile complète sur le modèle C2.5.

## 7. Découpage en incréments

| Incrément | Contenu | Hors périmètre |
|---|---|---|
| **C3.1** | V7 ; définitions + participants ; run en mode **pipeline** (2 à N étapes statiques) ; étapes = exécutions enfants ; contexte et messages bornés ; limites ; annulation ; audit ; lecture (steps/messages) ; fournisseur simulé | coordinateur LLM, parallèle, interface |
| C3.2 | Coordinateur : outils réservés `delegate`/`finish`, tours de décision, anti-boucle | parallèle |
| C3.3 | Politique d'échec / nouvelles tentatives, reprise renforcée (outbox généralisée), délais globaux | |
| C3.4 | Points de contrôle humains (généralisation des approbations), évaluation du parallèle | |
| C3.5 | Interface Angular/Electron des workflows | |
| C3.6 | Revue de sécurité, validation pile complète, documentation | |

## 8. Risques, dépendances et critères d'acceptation
**Risques** : coût et latence (chaque tour = une exécution) ; qualité du coordinateur sur un modèle réel (non vérifiable sans connectivité Anthropic réelle, **toujours non vérifiée**) ; concurrence avec le plafond par organisation ; complexité de l'annulation coopérative ; dépendance à un seul fournisseur.
**Dépendances** : C2 fusionné (vérifié) ; décision sur l'option C ; définition des droits `workflow.*`.
**Critères d'acceptation C3.1** : (1) un run pipeline à ≥ 2 agents réussit de bout en bout avec fournisseur simulé ; (2) un agent non participant est refusé ; (3) aucune donnée d'une organisation visible d'une autre ; (4) limites d'étapes et de durée appliquées ; (5) annulation effective, approbations orphelines clôturées ; (6) reprise après recréation de l'orchestrateur sans double exécution d'étape ; (7) V1–V6 inchangées, C0–C2 sans régression ; (8) aucun identifiant dans l'état, les messages, les journaux ni les réponses ; (9) contrat OpenAPI déterministe et synchronisé.

## 9. Recommandation pour C3.1
**Commencer par le mode pipeline déterministe** (étapes statiques, sans coordinateur LLM) sur l'option C. Il valide d'abord toute la plomberie risquée — lien exécution ↔ étape, contexte borné, limites, annulation, reprise, isolation, audit — avec un comportement entièrement prévisible, avant d'introduire l'imprévisibilité d'un coordinateur (C3.2). Il livre déjà une collaboration séquentielle réelle (agent A puis agent B) et un contrat d'API stable pour l'interface.

### Décisions que j'attends de vous
1. Valider l'**option C** (Core API = référence, LangGraph = tours) ou imposer l'option B.
2. Valider l'ordre : **pipeline d'abord**, coordinateur ensuite.
3. Confirmer que les participants sont des **agents explicitement choisis** (et non « les membres d'une équipe » automatiquement).
4. Confirmer le plafond initial (8 étapes, profondeur 1, séquentiel) et les noms de droits `workflow.read|manage|execute`.

## 10. Réalité de C3.1 (implémenté, vérifié)

Décisions approuvées appliquées : option C (Core API = référence, un LangGraph par tour d'agent), pipeline statique séquentiel, 8 étapes
maximum, profondeur 1, participants choisis un par un, droits `workflow.read|manage|execute`, plafond de 3 runs ouverts par organisation
(indépendant du plafond de 3 exécutions ouvertes), annulation coopérative `CANCEL_REQUESTED`, budgets cumulés de jetons et de temps.

| Sujet | Implémentation |
|---|---|
| Schéma | migration additive **V7** : `workflow_definitions`, `workflow_participants`, `workflow_runs` (instantané immuable `snapshot`), `workflow_steps` (une étape = une ligne `agent_executions`, `agent_executions` inchangée), `workflow_messages` (journal en ajout seul : trigger) |
| Emplacement du code | module `agent` (`Workflow*`) : les dépôts ne sont pas publics (ArchUnit) |
| Moteur | `WorkflowScheduler.advance` : idempotent, run verrouillé, une étape à la fois ; `WorkflowMaintenance` (3 s) = moteur **et** reprise (tout l'état est en PostgreSQL) |
| Lancement d'une étape | `ExecutionService.launch` (extraction du chemin C1, comportement inchangé) avec l'identité du demandeur ; `CapacityExceededException` = attendre, pas échouer |
| Autorisation | `workflow.execute` **et** `agent.execute` au démarrage ; le demandeur est réautorisé avant chaque étape (`REQUESTER_NOT_AUTHORIZED`) |
| Contexte | `WorkflowText.composeTask` : objectif, consigne, résumés masqués des étapes choisies (`inputsFrom`) encadrés comme données non fiables, budget restant ; ≤ 8000 caractères |
| Échec | `FAIL_FAST` ; nouvelle tentative automatique seulement pour `PROVIDER_TIMEOUT/UNAVAILABLE/RATE_LIMIT` **et** si aucun appel d'outil n'a démarré ; une tentative = une **nouvelle** exécution, l'ancienne est conservée |
| Annulation | `CANCEL_REQUESTED` ; enfant non démarré ou en attente d'approbation : terminé aussitôt ; appel d'outil en vol : délai de grâce (`cancel-grace`, 30 s) puis `OUTCOME_UNKNOWN` ; jamais présenté comme annulé après coup |
| Idempotence | `requestId` optionnel à l'appel de lancement ; index unique `(run, rang)` pour toute étape vivante ou réussie ; `(run, rang, tentative)` ; une exécution appartient à au plus une étape |

### Écarts par rapport à la conception, assumés
* **Pas d'endpoint interne `workflow-context`** : le contexte borné est composé par le Core API dans l'instruction (`task`) de l'exécution
  enfant. L'orchestrateur Python n'a donc **pas été modifié** et ne connaît pas les workflows.
* Modification d'une définition : autorisée tant qu'elle est `ACTIVE` (chaque changement = nouvelle version) ; les runs sont protégés par leur instantané.
* **Pas d'API de nouvelle tentative manuelle** : seule la reprise automatique transitoire existe ; la structure (tentatives conservées) la permettra.
* Le budget de jetons est vérifié **entre** les étapes (une étape en cours ne peut pas être interrompue au jeton près) ; le budget restant est transmis à l'agent à titre indicatif.
* La reprise après redémarrage est testée avec un « nouveau worker sans mémoire » sur PostgreSQL réel, et la chaîne réelle (conteneur orchestrateur) a été validée
  de bout en bout une fois ; la recréation du conteneur *pendant* un run n'a pas été rejouée pour C3.1 (elle l'a été pour C2).

### Hors périmètre, inchangé
Coordinateur LLM, `workflow.delegate` / `workflow.finish`, parallélisme, délégation dynamique, communication entre workers, mémoire partagée, interface, nouveaux transports MCP, courtier de messages.

## 11. Réalité de C3.2 (implémenté, vérifié) — coordinateur IA et délégation dynamique

Principe : **le Core API décide ce qui est autorisé ; le coordinateur décide ce qui doit se passer ensuite ; les workers n'exécutent que les
tâches pour lesquelles ils sont explicitement autorisés.** Le mode `PIPELINE` (C3.1) est inchangé ; le mode `COORDINATED` est additif.

| Sujet | Implémentation |
|---|---|
| Schéma | migration additive **V8** : `mode` / `coordinator_agent_id` sur les définitions, `mode` sur les runs, rôle d'étape `COORDINATOR`, table `workflow_actions` (idempotence `(organisation, exécution coordinateur, tool_call_id)`, une action acceptée par tour, anti-répétition `(run, worker, hash de tâche)`). V1–V7 intacts |
| Définition | `mode` = `PIPELINE` ou `COORDINATED` ; un workflow coordonné nomme un coordinateur qui est aussi un **participant explicite** ; aucune étape statique (`409`) ; le démarrage exige le coordinateur **et** au moins un worker |
| Instantané | mode, coordinateur et participants figés dans le snapshot du run ; références **opaques** `coordinator`, `worker_1…` (jamais d'UUID, de connexion, de credential ni d'URL de service) |
| Coordinateur | un vrai agent B3 (`ACTIVE`, fournisseur valide, **son propre** credential/binding) ; chaque tour de décision = une **exécution enfant C1** (étape de rôle `COORDINATOR`) ; sans état, reconstruit par le Core API à chaque tour (`WorkflowText.composeCoordinatorTask`) ; aucun superviseur LangGraph durable |
| Actions | deux actions **natives de la plateforme** (pas des outils MCP, pas de faux serveur MCP) : `workflow.delegate {participantRef, task, inputRanks?}` et `workflow.finish {result}`. Servies par le Core API dans la réponse `start` d'un tour coordinateur (`workflowRole`, `platformActions`) ; un tour coordinateur n'a **aucun** outil MCP (`COORDINATOR_TOOLS_NOT_ALLOWED` côté `tool-calls`) |
| Endpoint interne | `POST /api/v1/internal/organizations/{org}/executions/{executionId}/workflow-actions` (compte de service uniquement ; un jeton utilisateur ou une exécution non coordinatrice → `403`) ; résultat normalisé `{action, status, code, message}` |
| Validation `delegate` | tour courant du coordinateur ; run actif (pas `CANCEL_REQUESTED`/terminal) ; une seule action par tour ; participant explicite et ≠ coordinateur ; agent encore `ACTIVE` et exécutable ; demandeur encore autorisé (`workflow.execute` + `agent.execute`) ; capacité d'étapes (≤ 8) ; échéance ; réserve de jetons (500) ; aucun worker actif ; tâche bornée (1500, masquée) ; `inputRanks` = étapes worker réussies ; pas de doublon `(worker, tâche normalisée)` |
| Validation `finish` | tour courant ; run actif ; aucun worker actif ; résultat borné ; le run passe `SUCCEEDED` quand le tour coordinateur se termine |
| Idempotence | `(exécution coordinateur, tool_call_id)` unique en base ; un rejeu renvoie le même résultat, un même identifiant avec d'autres arguments = `409` ; l'action est **persistée avant** la création de toute exécution suivante ; le worker n'est créé que par le planificateur, à la fin du tour |
| Boucle | tour coordinateur → action `delegate` → worker (exécution enfant C1, avec **sa** propre identité, ses compétences, ses outils/approbations C2) → nouveau tour avec le résultat (donnée non fiable) → … → `finish` |
| Bornes | 8 worker steps max, profondeur 1, séquentiel ; tours coordinateur ≤ `maxSteps + 1` (`COORDINATOR_TURN_LIMIT`) ; 3 rejets par tour au plus (`TOO_MANY_REJECTIONS`) ; tour sans action : **une** relance corrective, puis `COORDINATOR_NO_ACTION` ; budgets de jetons/temps **cumulés** (coordinateur inclus), contrôlés avant chaque tour et chaque worker |
| Échec | `FAIL_FAST` conservé : un tour coordinateur échoué ne crée aucun worker ; reprise automatique seulement pour les erreurs fournisseur transitoires sans effet d'outil démarré ; `OUTCOME_UNKNOWN` jamais rejoué ; un run terminal n'est jamais relancé par un événement tardif (`RUN_NOT_ACTIVE`) |
| Approbations | uniquement via C2, sur les outils MCP des **workers**. Une action de plateforme ne demande **jamais** d'approbation : elle est validée par le Core API (il n'existe pas d'équivalent « approbation de délégation » dans C2 ; lacune documentée, pas contournée par une fausse invocation MCP) |
| API | `GET /workflow-runs/{runId}/actions` (acceptées et rejetées, avec code) ; `mode` sur définitions et runs ; `coordinatorAgentId` sur les participants ; API C3.1 rétro-compatible |

### Écarts assumés
* Pas de nouvel endpoint de contexte : le contexte du coordinateur est composé par le Core API dans la `task` de l'exécution (comme C3.1).
* Le coordinateur ne peut ni ajouter de participant, ni modifier limites/permissions/fournisseur, ni outrepasser une approbation : aucune de ces actions n'existe.
* **Connectivité Anthropic réelle : NON VÉRIFIÉE.** Les tests utilisent un adaptateur fournisseur simulé ; le comportement réel d'un modèle face aux deux actions n'est pas démontré.
* Un échec de l'appel `workflow-actions` (Core API injoignable) fait échouer le tour (`COORDINATION_UNAVAILABLE`) : l'appel n'est jamais rejoué automatiquement par l'orchestrateur.
* Séquentiel uniquement ; parallélisme, messages worker↔worker, délégation récursive, mémoire partagée, OpenAI/Gemini en tool-use, interface : différés.

## 12. Réalité de C3.3 (implémenté, vérifié) — fiabilité, reprise et gestion des échecs

Principe : **le Core API possède les décisions de reprise ; LangGraph exécute des tours approuvés ; une action externe au résultat inconnu n'est
jamais rejouée à l'aveugle.** Aucun second automate d'état n'a été introduit : l'état reste dans PostgreSQL, le planificateur (C3.1) et ses passes
idempotentes restent le moteur **et** le mécanisme de reprise. Une ligne `workflow_steps` est déjà une *tentative* (unique `(run, rang, tentative)`) :
aucune table de tentatives n'a été ajoutée.

### Taxonomie des échecs (`FailureCategory`, `WorkflowFailureClassifier`)
| Catégorie | Exemples de codes | Retry automatique | Reprise manuelle |
|---|---|---|---|
| `TRANSIENT_PROVIDER_FAILURE` | `PROVIDER_TIMEOUT/UNAVAILABLE/RATE_LIMIT`, `ORCHESTRATOR_UNAVAILABLE`, `TIMEOUT` (reaper) | oui, borné, **si aucun outil n'a démarré** | oui (même condition) |
| `PERMANENT_PROVIDER_FAILURE` | `PROVIDER_AUTHENTICATION`, `PROVIDER_BAD_REQUEST`, `PROVIDER_NOT_CONFIGURED`, `CREDENTIAL_UNAVAILABLE` | jamais | oui, après correction par une personne (aucun outil démarré) |
| `TOOL_FAILURE` | `TOOL_FAILED`, `TOOL_ROUND_LIMIT` | jamais (aucune garantie d'idempotence de l'outil) | non |
| `OUTCOME_UNKNOWN` | un appel d'outil `RUNNING` quand l'exécution se termine, ou clos `OUTCOME_UNKNOWN` | **jamais** | **non** (pas de mécanisme de réconciliation) |
| `AUTHORIZATION_FAILURE` | `AGENT_NOT_ELIGIBLE`, `REQUESTER_NOT_AUTHORIZED`, `STEP_NOT_RUNNABLE` | jamais | non |
| `WORKFLOW_LIMIT_EXCEEDED` | `WORKFLOW_TIMEOUT`, `STEP_TIMEOUT`, `DEADLINE_EXCEEDED`, `TOKEN_BUDGET_EXCEEDED`, annulation | jamais | non |
| `UNCLASSIFIED` | tout code inconnu | jamais | non |

La catégorie et `retrySafe` (« aucun appel d'outil n'a jamais démarré ») sont calculés **une fois**, à la clôture de la tentative, et stockés sur la
tentative. Un état `OUTCOME_UNKNOWN` l'emporte sur tout code d'erreur (une erreur « transitoire » après le début d'un appel d'outil reste inconnue).
Choix assumé : une génération de texte fournisseur sans outil n'a pas d'effet externe autre que la consommation de jetons (comptée) ; elle peut donc
être rejouée.

### Politiques d'échec (par définition, figées dans le snapshot)
* `FAIL_FAST` (défaut) : aucune étape ne démarre après l'échec ; les résultats précédents sont conservés ; le run devient `FAILED` (`STEP_FAILED`).
* `CONTINUE` : l'échec est conservé et **jamais présenté comme un succès**. PIPELINE : l'étape suivante continue ; si elle a déclaré le rang échoué dans
  `inputsFrom`, elle reçoit `[This step FAILED (<catégorie>). No result is available.]` (donnée, pas résultat) ; à la fin, un run avec au moins un rang
  sans tentative réussie est `FAILED` avec le code `PARTIAL_FAILURE` (les résultats réussis sont conservés). COORDINATED : le coordinateur reçoit au tour
  suivant un résumé borné (`FAILED (<catégorie>, nothing external happened | an external effect may have happened)`) et décide (autre worker, autre
  tâche, `finish`) ; une tâche identique au même worker reste refusée (anti-doublon). `CONTINUE` ne passe jamais outre une autorisation révoquée ni une limite
  du workflow, et un tour **coordinateur** échoué ne continue jamais.
* `RETRY` : retries automatiques bornés (`maxRetriesPerStep` ≥ 1 obligatoire) puis `FAIL_FAST`. (Les retries automatiques de C3.1 restent actifs pour toute
  politique dont `maxRetriesPerStep` > 0.)
* `recoveryMode: MANUAL` (avec `FAIL_FAST`/`RETRY` uniquement, contrainte en base) : un échec **connu et sans effet externe** (`manuallyRecoverable`) place le run en
  `WAITING_RECOVERY` (statut non terminal, échéance toujours active) au lieu de `FAILED`.

### Retry automatique, backoff, durabilité
Décidé à la clôture de la tentative (`scheduleAutomaticRetry`) : catégorie transitoire **et** `retrySafe`, tentative ≤ `maxRetriesPerStep`, jamais après une tentative
manuelle, jamais si le run est `CANCEL_REQUESTED`, jamais si l'échéance serait dépassée. Délai = `base × 2^(n-1)` plafonné (`app.workflows.retry-base-delay` 5 s,
`retry-max-delay` 60 s) avec gigue ±20 %, **stocké** dans `workflow_steps.next_attempt_at` : un redémarrage (Core API ou worker) ne perd rien, la passe suivante le
lit. Chaque tentative est une **nouvelle exécution C1** ; l'ancienne et son résultat sont conservés ; les jetons de toutes les tentatives comptent dans le budget cumulé.

### Reprise manuelle
`POST /workflow-runs/{runId}/steps/{rank}/retry` (`workflow.execute` **et** `agent.execute`) : uniquement pour un run `WAITING_RECOVERY`, sur l'étape échouée qui l'a
arrêté, si la catégorie est reprise-able, `manual_retries` < 3, échéance et budget non épuisés, l'agent est toujours participant du snapshot, `ACTIVE`, fournisseur
valide. Puis une **nouvelle** tentative (`MANUAL_RETRY`) est lancée ; le demandeur du run est réautorisé par le planificateur (accès révoqué = run `FAILED`
`REQUESTER_NOT_AUTHORIZED`) ; les outils restent soumis aux grants et aux approbations C2. Une requête répétée reçoit `409`. `OUTCOME_UNKNOWN` ne peut jamais être
repris. Pas de « retry all », pas de saut d'étape.

### Délais (hiérarchie)
Fournisseur/outil MCP (C1/C2, inchangés) → **exécution** (`app.executions.timeout`, reaper) → **étape** (`stepTimeoutSeconds`, `workflow_steps.deadline_at`, fin de l'enfant
avec `STEP_TIMEOUT` ; un appel d'outil en vol devient `OUTCOME_UNKNOWN`, jamais « annulé ») → **workflow** (échéance globale : coordinateur, workers, attentes de retry et
de récupération) ; approbation (`APPROVAL_EXPIRED`, C2). Le Core API renvoie à l'orchestrateur `deadlineSeconds` (min étape/workflow) dans la réponse `start` ; l'orchestrateur
borne son exécution (`DEADLINE_EXCEEDED`) sans jamais l'étendre.

### Observabilité
`GET /workflow-runs/{runId}/summary` : durées, tentatives, retries automatiques/manuels, échecs par catégorie, jetons entrée/sortie, jetons coordinateur vs workers, budget
restant, prochain retry planifié, état de reprise. Les étapes exposent `launchReason`, `failureCategory`, `retrySafe`, `nextAttemptAt`, `deadlineAt`. Les **jetons ne sont pas des
coûts monétaires** (aucune donnée de prix fiable) ; la corrélation passe par les identifiants run/étape/tentative/exécution des audits (`workflow.step.retry-scheduled`,
`workflow.step.retry-requested`, `workflow.step.continued`, `workflow.run.waiting-recovery`).

### Lacunes de distribution (audit) et limites assumées
* Aucun nouvel outbox : le C1 (dispatch après commit + reaper des `PENDING`) et le planificateur C3.1 couvrent déjà « événement accepté / exécution créée / démarrée / terminée »
  (une exécution jamais reçue est échouée par le reaper en `TIMEOUT`, classée transitoire, donc rejouée si aucun outil n'a démarré). La livraison HTTP ne prouve pas l'exécution.
* **Pas de réconciliation** des résultats inconnus : `OUTCOME_UNKNOWN` est terminal (`FAILED`) ; une réconciliation (vérifier l'effet côté système externe) reste à concevoir.
* `TOOL_FAILURE` n'est jamais rejouable : il faudrait un contrat d'idempotence par outil, absent du registre.
* Les tests de redémarrage utilisent un **nouveau worker sans mémoire** sur PostgreSQL réel (pas un conteneur recréé en cours de run).
* Pas d'exactly-once pour les effets externes : au mieux « au plus une fois » pour un appel d'outil, « au moins une fois » pour la livraison des événements.

## 13. Réalité de C3.4 (implémenté, vérifié) — exécution parallèle contrôlée

Principe : **le Core API possède l'état du workflow et les décisions de concurrence ; l'orchestrateur exécute des tours d'agents isolés ; PostgreSQL garantit
l'ordonnancement durable ; C3.3 garantit la reprise sûre.** Le défaut reste **séquentiel** (`maxParallelWorkers` = 1) : aucun workflow existant ne change.

### Ce que le modèle existant représentait déjà (non dupliqué)
Un worker = une ligne `workflow_steps` (le C3.1 autorisait déjà plusieurs rangs vivants : l'index d'unicité est par rang) ; une délégation = une ligne `workflow_actions`
reliée à son étape ; retries, catégories d'échec, échéances = colonnes C3.3. **Aucune table n'a été ajoutée.**

### V10 (additive ; V1–V9 intacts) et pourquoi
| Changement | Raison |
|---|---|
| `workflow_steps.execution_id` nullable + statut `QUEUED` (contrainte : sans exécution seulement `QUEUED`/`CANCELLED`) | un travail accepté doit être **persisté avant** d'être dispatché et survivre à un redémarrage quand aucune capacité n'est libre ; la même ligne porte ensuite la tentative |
| `workflow_steps.group_no`, `reserved_tokens` | quel groupe (étape de pipeline ou tour du coordinateur) ; réservation de jetons par worker |
| `workflow_runs.max_parallel_workers`, `reserved_tokens` ; `workflow_definitions.max_parallel_workers` (1..3, défaut 1) | borne de concurrence figée dans le snapshot ; réservation atomique (run verrouillé) |
| index `uq_workflow_steps_live_rank` étendu à `QUEUED` | un rang ne peut être ni mis en file ni lancé deux fois |
| `uq_workflow_actions_one_per_turn` remplacé par `uq_workflow_actions_one_finish_per_turn` | plusieurs délégations acceptées dans un tour ; un seul `finish` |
| contrainte : `max_parallel_workers > 1` incompatible avec `recovery_mode = MANUAL` | la reprise manuelle suppose UNE étape échouée qui arrête le run |

### Modèle d'exécution
`WorkflowScheduler.advanceParallel` (une passe idempotente, run verrouillé, tout depuis PostgreSQL) : (1) fins imposées à tous les enfants (échéance globale, annulation, délai
d'étape) → (2) règlement des enfants terminés → (3) échec que la politique ne permet pas de continuer : annulation des pairs en file, arrêt des pairs actifs (délai de grâce 30 s
pour un appel d'outil en vol, puis `OUTCOME_UNKNOWN`, jamais « annulé ») → (4) **dispatch** des travaux en file et des retries dus dans les bornes → (5) **fan-in** seulement quand
**chaque rang du groupe** est résolu (ni en file, ni vivant, ni retry en attente) → étape suivante / tour coordinateur suivant.

* **Pipeline** : `stage` optionnel sur chaque étape (tout ou rien) ; mêmes stages = parallèles ; stages contigus à partir de 1 ; taille d'un stage ≤ `maxParallelWorkers` ; une
  dépendance (`inputsFrom`) doit viser un stage **strictement antérieur** (pas de cycle) ; par défaut une étape reçoit **toutes** les étapes du stage précédent (fan-in), dans l'ordre des
  rangs. Sans `stage`, le pipeline est exactement celui de C3.1. Le résultat final d'un dernier stage multi-étapes est la concaténation ordonnée des résultats.
* **Coordinateur** : `workflow.delegate` appelé plusieurs fois dans **un** tour (jusqu'à `maxParallelWorkers`, annoncé dans la réponse `start` : `maxDelegationsPerTurn`) ; chaque
  appel garde son idempotence `(exécution, tool_call_id)` ; `finish` n'est jamais combiné avec des délégations ; les délégations acceptées comptent déjà contre `maxSteps` ;
  `GROUP_FULL` au-delà de la borne. Les délégations acceptées sont **persistées ensemble** (une transaction) puis dispatchées indépendamment ; le coordinateur reprend seulement
  quand le groupe est résolu et reçoit **tous** les résultats et échecs (bornés, comme données non fiables). Pas de délégation récursive, pas de worker→worker.
* **Atomicité** : la création des lignes `QUEUED` d'un groupe est atomique (tout ou rien). Le dispatch est **par worker** : un worker qui ne peut pas démarrer (capacité C1, budget)
  reste `QUEUED` et sera dispatché par une passe ultérieure — jamais de groupe à moitié « accepté » sans trace persistée ; un agent devenu inutilisable met fin proprement au groupe
  (`STEP_NOT_RUNNABLE`, pairs arrêtés).

### Concurrence, capacité, équité
Borne par workflow : `min(maxParallelWorkers, app.workflows.max-parallel-workers=3)`. Capacité organisation : **inchangée**, 3 exécutions ouvertes (C1) : un travail sans slot reste `QUEUED`.
Politique : une exécution `WAITING_APPROVAL` **garde son slot** (elle est « ouverte » en C1) ; un tour coordinateur terminé libère le sien avant le dispatch du groupe. Un seul groupe à la fois
par workflow. Équité : la maintenance traite les runs par `updatedAt` croissant (un run qui vient d'avancer passe derrière), par lots de 50 ; un grand workflow ne peut pas monopoliser les slots
indéfiniment (ses workers se terminent et libèrent). Pas de limite par fournisseur (non implémentée). Les passes concurrentes se sérialisent sur le verrou de ligne du run (pas de `SKIP LOCKED` :
une seconde instance attend brièvement au lieu de sauter).

### Budget de jetons (réservation atomique)
Au dispatch, un worker réserve `min(app.workflows.worker-token-reservation=4000, reste)` (jamais moins de 500) sur `tokens_used + reserved_tokens ≤ budget` — sous le verrou du run, donc sans
course entre workers. À la fin de l'enfant, la réservation est **remplacée** par l'usage réel (une seule fois). Aucune capacité disponible : le travail reste en file ; plus rien ne tourne et plus de
budget → `TOKEN_BUDGET_EXCEEDED`, file annulée. Limite : l'usage réel peut dépasser la réservation (non connue d'avance) ; le dépassement est borné par la réservation de l'étape suivante. Les tentatives
**échouées** comptent aussi : `fail` transporte maintenant l'usage déjà consommé (correction C3.3, voir le rapport).

### Politiques d'échec dans un groupe
`FAIL_FAST`/`RETRY` : un échec définitif annule les pairs en file et arrête les pairs actifs (sauf appel d'outil en vol : grâce puis `OUTCOME_UNKNOWN`), conserve les résultats terminés, n'ouvre pas la suite, et
le run n'est `FAILED` que quand tous les pairs sont résolus. `CONTINUE` : les pairs continuent, l'échec est conservé à part et rapporté (`FAILED (<catégorie>, nothing external happened | an external effect may have
happened)`), mais **jamais** au-delà d'une limite ou d'une autorisation révoquée (le délai d'étape en est une : les pairs sont arrêtés). `RETRY` : éligibilité C3.3 inchangée ; **seul** le worker échoué est relancé (nouvelle
exécution, backoff durable), ses pairs ne sont pas touchés ; `OUTCOME_UNKNOWN` n'est jamais rejoué. La reprise manuelle (`WAITING_RECOVERY`) n'existe pas avec le parallélisme (limite assumée).

### Approbations C2
Inchangées : chaque worker garde sa politique d'outils ; chaque approbation référence **son** invocation/exécution (jamais d'objet partagé) ; approuver l'une n'approuve pas l'autre ; un rejet suit la politique d'échec ;
l'expiration et la clôture des approbations obsolètes restent celles de C2. Le run reste `RUNNING` tant qu'un worker travaille et n'est `WAITING_APPROVAL` que lorsque tous les workers vivants attendent une personne.

### Ordonnancement durable et reprise
Tout l'état (travaux en file, réservations, échéances de retry, groupe) est en PostgreSQL ; une passe d'un worker sans mémoire reconstruit le groupe, ne relance pas un worker terminé, dispatche le travail en file et ne
crée aucun doublon (index d'unicité par rang). Un run terminal ne crée rien. Livraison : au moins une fois, traitement idempotent ; **pas d'exactly-once** pour les effets externes.

### Orchestrateur Python
`ExecutionRunner` exécutait déjà chaque exécution dans sa propre tâche asyncio, avec un `RunContext` et un graphe par exécution : aucun état mutable partagé. Ajouts : `deadlineSeconds` (C3.3), `maxDelegationsPerTurn`
(plusieurs appels `workflow_delegate` transmis individuellement, bornés), usage consommé transmis sur `fail`. Capacité par défaut de l'orchestrateur : 4 exécutions simultanées (`AIO_MAX_CONCURRENT_EXECUTIONS`) — au-delà,
503 → `ORCHESTRATOR_UNAVAILABLE` (transitoire, rejoué si sûr).

### Limites
Séquence uniquement entre groupes (un groupe à la fois) ; pas de limite par fournisseur ; pas de reprise manuelle en parallèle ; réservation conservatrice et configurable ; pas de `SKIP LOCKED` ; validation sur conteneurs
recréés non rejouée (voir le rapport) ; connectivité Anthropic réelle NON VÉRIFIÉE.
