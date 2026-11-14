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
