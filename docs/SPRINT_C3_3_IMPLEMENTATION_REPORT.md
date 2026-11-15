# Sprint C3.3 — Advanced Workflow Reliability, Recovery & Failure Management

Branche `feat/workflow-reliability-c3-3`, créée depuis `origin/main` = `357025b` (C3.2 vérifié présent : PR #7). **Aucun commit, push ni merge.**

> Le Core API possède les décisions de reprise. LangGraph exécute des tours approuvés. Une action externe au résultat inconnu n'est jamais rejouée à l'aveugle.

## Architecture inspectée (audit)
Une ligne `workflow_steps` est déjà une tentative (unique `(run, rang, tentative)`, index « une seule tentative vivante/réussie par rang ») : **pas de table d'attempts**.
Le C3.1 ne savait que : FAIL_FAST, retry immédiat des 3 codes fournisseur transitoires, sans backoff, sans catégorie, sans reprise manuelle, sans timeout d'étape.
Dispatch/reprise : C1 (dispatch après commit + reaper des `PENDING`) et le planificateur C3.1 (passes idempotentes, run verrouillé) couvrent déjà la durabilité : **aucun outbox ajouté**.

## Livré
- **V9** (additive ; V1–V8 intacts) : politique/mode de reprise/timeout d'étape (définitions), `failure_policy` + `manual_retries` + statut `WAITING_RECOVERY` (runs), `launch_reason`, `failure_category`, `retry_safe`, `next_attempt_at`, `deadline_at`, `retry_requested_by` (étapes) ; bornes en base.
- **Taxonomie** `FailureCategory` + `WorkflowFailureClassifier` (7 catégories ; `OUTCOME_UNKNOWN` l'emporte sur tout code ; `retrySafe` = aucun appel d'outil démarré).
- **Politiques** `FAIL_FAST` / `CONTINUE` / `RETRY`, `recoveryMode` `NONE` / `MANUAL`, valables en PIPELINE et COORDINATED (échec rapporté à l'étape suivante ou au coordinateur, jamais présenté comme un succès ; `PARTIAL_FAILURE`).
- **Retry automatique** : transitoire + sans outil démarré, borné, backoff exponentiel plafonné avec gigue, échéance stockée en base (survit aux redémarrages), nouvelle exécution par tentative, jamais après une reprise manuelle ni au-delà de l'échéance.
- **Reprise manuelle** `POST …/steps/{rank}/retry` (`workflow.execute` + `agent.execute`), 3 par run, tout revalidé ; `OUTCOME_UNKNOWN` jamais reprenable.
- **Délais** : timeout d'étape (`STEP_TIMEOUT`), échéance globale incluant retries et attente de reprise ; `deadlineSeconds` renvoyé à l'orchestrateur qui s'y borne (`DEADLINE_EXCEEDED`).
- **Observabilité** : `GET …/summary`, champs d'étape (catégorie, raison de lancement, prochain retry, échéance), audits dédiés. Jetons ≠ coût monétaire.
- OpenAPI régénéré (LF, identique au fichier généré) ; docs : design §12, README Core API / orchestrateur, architecture d'orchestration §11.

## Résultats de tests (Windows, ce poste)
| Suite | Résultat |
|---|---|
| Core API `clean verify` | **331 tests, 0 échec** (baseline 301 : +23 `WorkflowReliabilityIntegrationTest`, +6 `WorkflowFailureClassifierTest`, +1 migration V8→V9) |
| Python | **295 passed, 4 skipped**, ruff OK (baseline 272 : +23 `test_workflow_reliability.py`) |
| Régressions C0–C3.2 | incluses dans les totaux, aucune modification de comportement (les tests C3.1/C3.2 fixent `retry-base-delay=PT0S`) |
| Angular / Electron | non modifiés, **non relancés** dans ce sprint (derniers résultats : 177 / 44) |
| Linux | **non exécuté localement** (CI) |

Scénarios couverts sur PostgreSQL réel : retry transitoire → succès (3 exécutions distinctes, historique conservé) ; appel d'outil à résultat perdu → aucun rejeu, catégorie `OUTCOME_UNKNOWN`, retry manuel refusé ; FAIL_FAST ; CONTINUE (pipeline et coordonné) ; reprise manuelle (autorisations, autre organisation, doublon, budget, agent mis en pause, demandeur suspendu, échéance, annulation) ; timeout d'étape ; complétion tardive ; retries concurrents (une seule tentative) ; échéance de retry stockée (nouveau worker sans mémoire).

## Sécurité
Aucune constatation CRITICAL/HIGH ouverte. Vérifié : isolation d'organisation (404), permissions (403), reprise qui ne rétablit aucun accès révoqué (réautorisation du demandeur), pas de contournement C2 (grants/approbations inchangés sur les outils), run terminal jamais ressuscité, actions de coordinateur périmées refusées, messages d'échec issus d'une table fixe (ni tâche ni credential).

## Limites connues (non contournées)
- **Redémarrage** : testé avec un nouveau worker sans mémoire sur PostgreSQL réel ; **pas de recréation de conteneur** pendant un run pour C3.3 (non rejoué).
- **Pas de réconciliation** des résultats inconnus : `OUTCOME_UNKNOWN` est terminal.
- `TOOL_FAILURE` jamais rejouable (aucun contrat d'idempotence par outil dans le registre).
- Génération de texte sans outil jugée rejouable (jetons comptés) ; pas d'exactly-once pour les effets externes.
- Un participant retiré de la *définition* n'affecte pas un run en cours (snapshot immuable) ; la reprise vérifie le snapshot.
- Connectivité Anthropic réelle **NON VÉRIFIÉE** ; fournisseurs simulés.
- Hors périmètre : parallélisme, messages worker↔worker, récursion, mémoire partagée, OpenAI/Gemini, interface, courtier.
