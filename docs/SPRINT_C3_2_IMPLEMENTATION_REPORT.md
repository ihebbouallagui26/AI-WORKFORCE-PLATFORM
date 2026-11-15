# Sprint C3.2 — AI Coordinator & Dynamic Multi-Agent Delegation

Branche `feat/multi-agent-coordinator-c3-2` (base `origin/main` = `16533e7`). **Aucun commit, push ni merge** n'a été fait ; tout est dans le working tree.

> Le Core API décide ce qui est autorisé. Le coordinateur décide ce qui doit se passer ensuite. Les workers n'exécutent que les tâches pour lesquelles ils sont explicitement autorisés.

## Livré
- **V8** (additive, V1–V7 intacts) : `mode`/`coordinator_agent_id` (définitions), `mode` (runs), rôle d'étape `COORDINATOR`, table `workflow_actions`.
- **Core API** : `WorkflowMode`, `WorkflowAction(+Repository)`, `WorkflowActionService` (validation + enregistrement, idempotent), `InternalWorkflowActionController`, `WorkflowActionCatalog` (schémas), `WorkflowScheduler` (branche COORDINATED), `WorkflowText` (contexte coordinateur/worker), `WorkflowRunService` (snapshot avec références opaques), `ToolCallCoordinator` (aucun outil MCP pour un tour coordinateur), `GET /workflow-runs/{id}/actions`.
- **Orchestrateur** : contrats (`PlatformAction`, `WorkflowActionResult`), `submit_workflow_action`, routage des actions de plateforme dans `tool_loop`, prompt coordinateur, codes `COORDINATION_UNAVAILABLE/REJECTED`.
- **Doc** : design C3 §11, README Core API et orchestrateur, architecture d'orchestration §10, OpenAPI synchronisé (LF, identique au généré).

## Cycle d'une action
Tour coordinateur (exécution C1) → le fournisseur émet un tool_use → l'orchestrateur transmet → le Core API valide et **enregistre** (ACCEPTED/REJECTED, idempotent par `(exécution, tool_call_id)`) → fin du tour → le planificateur crée le worker (ou termine le run sur `finish`) → résultat du worker (donnée non fiable) → nouveau tour.

## Sécurité / bornes
Service account uniquement (utilisateur, worker, exécution hors workflow → 403) ; participants explicites, références opaques ; coordinateur sans outil MCP ; une action par tour ; ≤ 8 workers, profondeur 1, séquentiel ; tours ≤ `maxSteps+1` ; 3 rejets/tour ; une relance corrective puis `COORDINATOR_NO_ACTION` ; budgets jetons/temps cumulés (coordinateur inclus) ; anti-répétition `(worker, tâche)` ; run terminal jamais relancé ; FAIL_FAST, `OUTCOME_UNKNOWN` jamais rejoué ; demandeur réautorisé.

## Tests (Windows, ce poste)
| Suite | Résultat |
|---|---|
| Core API `mvnw clean verify` | **301 tests, 0 échec** (baseline 284 : +16 `WorkflowCoordinatorIntegrationTest`, +1 migration V7→V8) ; pipeline C3.1 28/28 inchangé |
| Python | **272 passed, 4 skipped** (baseline 256 : +16 `test_workflow_actions.py`), ruff OK |
| Angular | 12 fichiers / 177 tests ; Electron 44 — inchangés |
| Linux | **non exécuté localement** (à vérifier par la CI) |

## Limites et non vérifié
- **Connectivité Anthropic réelle NON VÉRIFIÉE** : fournisseur simulé dans les tests ; le comportement d'un vrai modèle face aux deux actions n'est pas démontré.
- Validation sur la pile Docker réelle (coordinateur → Researcher → Writer) **non rejouée** pour C3.2 ; couverte par tests d'intégration PostgreSQL + tests Python, orchestrateur simulé.
- Approbations : uniquement C2 sur les outils MCP des workers ; une action de plateforme n'a pas d'équivalent d'approbation dans C2 (lacune documentée, rien de simulé).
- Appel `workflow-actions` jamais rejoué par l'orchestrateur (le tour échoue en `COORDINATION_UNAVAILABLE`).
- Hors périmètre : parallélisme, messages worker↔worker, récursion, mémoire partagée, OpenAI/Gemini tool-use, interface.
