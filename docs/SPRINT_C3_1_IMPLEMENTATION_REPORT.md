# Sprint C3.1 — Pipeline multi-agents déterministe : rapport d'implémentation

Branche : `feat/multi-agent-workflows-c3`, créée depuis `origin/main` = `ba80866` (fusion de la PR #5 ; C2 `ccac7f7` et le correctif
`94e717e` vérifiés comme présents). **Rien n'est committé ni poussé.** Les migrations V1–V6 sont intactes ; V7 est nouvelle et additive.

## Niveaux de preuve

| Niveau | Contenu |
|---|---|
| Tests d'intégration PostgreSQL réel | 28 tests du pipeline + 1 test de montée de schéma V6→V7 ; orchestrateur **joué** par ses endpoints internes, aucun fournisseur |
| **Pile réelle** | Keycloak (connexion par mot de passe d'un utilisateur temporaire), Core API avec V7, PostgreSQL, **conteneur orchestrateur réel**, gestionnaire MCP réel, faux fournisseur déterministe : pipeline à 2 agents puis annulation |
| Anthropic réel | **NON VÉRIFIÉ** (le faux fournisseur est déterministe ; aucune requête réelle) |
| Interface | hors périmètre C3.1 |

## 1. Architecture implémentée
Le Core API possède tout l'état du workflow ; chaque étape est une exécution C1 enfant (`agent_executions`, inchangée) lancée par
`ExecutionService.launch` avec l'identité de l'agent de l'étape et les droits du demandeur ; LangGraph n'exécute que les tours d'agent
(checkpoints par exécution enfant, aucun état global de workflow dans les checkpoints). L'orchestrateur Python **n'a pas été modifié**.

## 2. Migration V7
`workflow_definitions` (version, étapes JSON, limites : 1–8 étapes, 0–2 reprises, budget de jetons 1000–10 M, délai 30 s–24 h),
`workflow_participants` (clés composites vers `agents`, unique par définition et agent), `workflow_runs` (instantané immuable,
`request_id` unique par demandeur, budget, échéance, états, contraintes de cohérence), `workflow_steps` (FK composite vers
`agent_executions`, unique `(run, rang, tentative)`, **index partiel unique : une seule étape vivante ou réussie par rang**, une
exécution = au plus une étape), `workflow_messages` (journal en ajout seul : trigger refusant UPDATE et DELETE) ; permissions
`workflow.read|manage|execute` pour OWNER et ADMIN uniquement. Toutes les tables sont limitées à l'organisation par clés composites.

## 3. Machines à états
Run : `PENDING → RUNNING ⇄ WAITING_APPROVAL → SUCCEEDED | FAILED | TIMEOUT` ; `CANCEL_REQUESTED` (depuis tout état ouvert) `→ CANCELLED`
(ou `TIMEOUT`). Étape : `RUNNING ⇄ WAITING_APPROVAL → SUCCEEDED | FAILED | CANCELLED`. Transitions gardées dans les entités, sous verrou de ligne.

## 4. Exécution d'une étape
`WorkflowScheduler.advance(run)` (idempotent, run verrouillé) : échéance globale → étape active terminée ? la régler (jetons cumulés,
résumé masqué, message) → annulation demandée ? → étape suivante / fin / échec. `WorkflowMaintenance` appelle `advance` toutes les 3 s pour
chaque run ouvert (moteur **et** reprise). La capacité d'exécutions de l'organisation saturée n'est pas un échec : l'étape attend.

## 5. Contexte et messages
Un worker reçoit : objectif, sa consigne, les résumés (≤ 1500 caractères, masqués) des seules étapes de `inputsFrom`, encadrés comme
**données non fiables**, et le budget restant (≤ 8000 caractères au total). Jamais la conversation d'un autre agent, ni la consigne d'une autre
étape, ni d'identifiant. Le journal (`OBJECTIVE`, `ASSIGNMENT`, `RESULT`, `STATUS`) est borné à 4000 caractères et masqué.

## 6. Autorisation
`workflow.manage` (+ `agent.read`) pour configurer ; participants explicites, `ACTIVE`, fournisseur valide, de la même organisation (404 sinon) ;
`workflow.execute` **et** `agent.execute` pour lancer ; le demandeur est réautorisé avant chaque étape ; l'agent et son fournisseur sont
revalidés par `launch` à chaque étape ; chaque enfant utilise ses propres outils et approbations (C2). Aucune équipe n'implique de participation.

## 7. Annulation et reprise
Annulation coopérative (voir README du Core API). Reprise : nouveau worker sans mémoire sur PostgreSQL réel, passes répétées et concurrentes
(6 threads) : une seule étape et une seule exécution par rang. Nouvelle tentative automatique limitée aux erreurs transitoires du fournisseur
sans effet d'outil démarré (`OUTCOME_UNKNOWN` jamais rejoué).

## 8. Résultats exacts

| Suite | Résultat |
|---|---|
| Core API `./mvnw clean verify` (Windows) | **284 tests**, 0 échec, 0 ignoré, code 0 (255 avant ; +28 workflows, +1 montée de schéma) ; ArchUnit 21 |
| Python `pytest` + `ruff` (Windows) | 256 réussis, 4 ignorés (tests live, opt-in) ; ruff OK — **inchangé** |
| Angular | 12 fichiers, 177 tests, aucune erreur — **inchangé** |
| Electron | 44 / 44 — **inchangé** |
| OpenAPI | contrat publié = contrat généré (octet pour octet, LF), reproductible |
| Linux | non exécuté dans cette session |

### Scénarios couverts (tests d'intégration PostgreSQL réel)
pipeline 2 agents ; pipeline 3 agents et isolation du contexte (`inputsFrom`) ; instantané inchangé malgré modification et archivage ;
participants explicites, équipe sans effet, agent d'une autre organisation (404), agent inactif (409), participant sans étape (409),
retrait d'un agent utilisé (409) ; `workflow.execute` sans `agent.execute` (403) ; isolation entre organisations (404 sur toutes les lectures
et sur l'annulation) ; limites d'étapes (définition et plafond 8) ; limite de 3 runs ouverts et libération ; capacité d'exécutions saturée
(l'étape attend, une seule étape après plusieurs passes) ; budget de jetons ; échéance globale (enfant terminé, `TIMEOUT`) ; échec d'étape
(FAIL_FAST, résultats précédents conservés) ; nouvelle tentative transitoire (nouvelle exécution, ancienne conservée) et absence de reprise
après démarrage d'un outil ; secrets masqués ; agent mis en pause ; demandeur suspendu ; approbation dans une étape (run en
`WAITING_APPROVAL`, outil exécuté une fois) ; approbation expirée ; annulation avant étape, enfant actif, outil en vol (issue connue),
délai de grâce dépassé (`OUTCOME_UNKNOWN`, jamais annulé a posteriori) ; notifications et passes dupliquées ; passes concurrentes ; reprise avec un
worker neuf ; `requestId` idempotent ; journal en ajout seul, unicité des étapes vivantes et des exécutions ; absence de colonne workflow
dans `agent_executions` ; refus de `workflow.delegate` (outil inconnu).

### Validation sur la pile réelle
Pipeline Researcher → Writer : les deux enfants sont de vraies exécutions de deux agents distincts traitées par le conteneur orchestrateur ;
run `SUCCEEDED`, 84 jetons cumulés, journal `OBJECTIVE, ASSIGNMENT, RESULT, ASSIGNMENT, RESULT, STATUS`, la tâche du second enfant contient le
résultat du premier comme donnée non fiable. Run annulé aussitôt après son démarrage : `CANCELLED`, étape `CANCELLED`, aucune étape 2,
orchestrateur sain. Ni clé fournisseur, ni clé de chiffrement, ni secret de service dans les journaux, le journal de workflow ou les instantanés.

## 9. Constats de sécurité de cette étape
| Sév. | Constat | État |
|---|---|---|
| (corrigé) | `save()` d'une entité à identifiant assigné fait un `merge` : modifier l'original provoquait des collisions de séquence de messages | détecté par les tests, corrigé (instance gérée) |
| LOW | `workflow.read` expose des sorties d'agents bornées (résumés, résultat final) sans exiger `agent.execution.read` | documenté ; accordé à OWNER/ADMIN seulement par défaut |
| LOW | Le budget de jetons et l'échéance sont appliqués au grain d'une passe (3 s) et entre les étapes | documenté |
| LOW | Un enfant annulé n'est informé que par le 409 de son prochain appel au Core API (un appel fournisseur en cours se termine) | documenté |

## 10. Limites et reporté à C3.2
Pas de coordinateur LLM, de `workflow.delegate`/`workflow.finish`, de parallélisme, de communication entre workers, d'interface, d'API de reprise
manuelle ni d'endpoint interne `workflow-context` (le contexte est dans l'instruction). Anthropic réel non vérifié. Linux non exécuté.
La recréation du conteneur orchestrateur *pendant* un run de workflow n'a pas été rejouée (validée pour C2 ; la reprise du workflow lui-même est testée avec un worker neuf).
