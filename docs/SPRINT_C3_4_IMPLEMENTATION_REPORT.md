# Sprint C3.4 — Controlled Parallel Multi-Agent Execution (+ validation de C3.3)

Branche `feat/parallel-multi-agent-execution-c3-4`, créée depuis `origin/main` = `0b82b15` (merge de la PR #8, C3.3 `86725ef`). **Aucun commit, push ni merge.**

> Le Core API possède l'état du workflow et les décisions de concurrence. L'orchestrateur exécute des tours d'agents isolés. PostgreSQL garantit l'ordonnancement durable. C3.3 garantit la reprise sûre.

## Partie 1 — Validation de C3.3
- C3.3 est bien dans `origin/main` ; migrations V1–V9 présentes et validées (tests de migration V8→V9 puis V9→V10).
- Baseline relancée sur `origin/main` avant tout ajout : **110 tests, 0 échec** (fiabilité, pipeline, coordinateur, schéma, outils C2, reprise des approbations).
- Les 10 points de validation sont couverts par des tests existants (retry durable, doublons/concurrence de retry, échéances, reprise manuelle revalidée, `OUTCOME_UNKNOWN`, états terminaux, C2 inchangé).
- **Défaut concret trouvé et corrigé** : le budget cumulé de C3.3 ne comptait que les tentatives *réussies* — l'endpoint interne `fail` ne transportait pas l'usage de jetons déjà consommé (ex. tentative échouée après plusieurs tours payants). Correction minimale : `fail` accepte `inputTokens`/`outputTokens` (optionnels, ≥ 0), l'orchestrateur les renvoie (usage de la boucle), le planificateur les compte une fois. Rien d'autre de C3.3 n'a été réorganisé.

## Partie 2 — C3.4
### Décisions d'architecture
- **Aucune nouvelle table** : un worker = une ligne `workflow_steps` (le C3.1 autorisait déjà plusieurs rangs vivants), une délégation = une ligne `workflow_actions`. Le défaut reste séquentiel (`maxParallelWorkers` = 1) : les workflows existants sont inchangés (28 + 16 + 23 tests C3.1/C3.2/C3.3 verts).
- **V10** (additive) : travail `QUEUED` persisté avant dispatch (`execution_id` nullable seulement en `QUEUED`/`CANCELLED`), `group_no`, `reserved_tokens`, `max_parallel_workers` (1..3, incompatible avec la reprise manuelle), plusieurs délégations acceptées par tour (un seul `finish`).
- **Moteur** `advanceParallel` : une passe idempotente, run verrouillé → fins imposées (échéance, annulation, délai d'étape) → règlement → échec non « continuable » (pairs annulés/arrêtés, grâce 30 s puis `OUTCOME_UNKNOWN`, jamais « annulé ») → dispatch borné (concurrence, capacité C1, budget) → **fan-in seulement quand chaque rang du groupe est résolu**.
- **Pipeline** : `stage` optionnel (tout ou rien), stages contigus, taille ≤ borne, dépendances seulement vers des stages antérieurs (pas de cycle), fan-in par défaut = tout le stage précédent, ordre déterministe.
- **Coordinateur** : plusieurs `workflow.delegate` dans un tour (borne annoncée au `start`), idempotence par appel, `finish` jamais combiné, délégations acceptées comptées contre `maxSteps`, `GROUP_FULL`, groupe persisté atomiquement puis dispatché worker par worker ; le coordinateur reprend avec **tous** les résultats/échecs.
- **Budget** : réservation atomique sous le verrou du run (`tokens_used + reserved_tokens ≤ budget`), remplacée par l'usage réel une seule fois ; plus de budget et rien en cours → `TOKEN_BUDGET_EXCEEDED`.
- **Capacité** : 3 exécutions ouvertes par organisation (C1) respectées — le travail sans slot reste `QUEUED` ; `WAITING_APPROVAL` garde son slot (politique documentée) ; un groupe à la fois par workflow.
- **Python** : concurrence déjà par exécution (tâche asyncio + `RunContext` + graphe propres) ; ajouts : multi-délégation bornée, usage sur `fail`, `maxDelegationsPerTurn`.

### Observabilité
`GET …/summary` : `groups` (statut, workers, en file, actifs, en attente d'approbation, réussis, échoués), `peakConcurrency` (recalculée depuis les horodatages réels), `reservedTokens`, `queueWaitMs` par tentative, jetons coordinateur/workers ; étapes : `groupNo`, statut `QUEUED`.

## Tests (Windows, ce poste)
| Suite | Résultat |
|---|---|
| Core API `clean verify` | **361 tests, 0 échec** (331 avant : +18 pipeline parallèle, +10 coordinateur parallèle, +1 mesures, +1 migration V9→V10) |
| Python | **306 passed, 4 skipped**, ruff OK (295 avant : +11) |
| OpenAPI | contrat versionné identique au fichier généré (LF) |
| Angular / Electron | non modifiés, non relancés (derniers résultats : 177 / 44) |
| Linux | **non exécuté localement** (CI) |

Scénarios (PostgreSQL réel, workers simulés, **chevauchement prouvé** : les 3 exécutions sont `RUNNING` simultanément avant qu'aucune ne se termine, `peakConcurrency` = 3 ; côté Python, une barrière qui ne s'ouvre que si 3 appels fournisseur sont en vol en même temps) : A parallèle + synthèse ; B retry du seul worker échoué ; C `OUTCOME_UNKNOWN` jamais rejoué, résultat du pair conservé, état truthful (`PARTIAL_FAILURE`) ; D un worker sans mémoire reconstruit le groupe depuis PostgreSQL sans doublon ; E approbations parallèles indépendantes (une approuvée, une rejetée). Aussi : capacité/file, réservation de jetons, FAIL_FAST/CONTINUE, annulation, échéance, délai d'étape, passes concurrentes, autorisations, isolation entre organisations, requêtes périmées.

## Mesures contrôlées (travail simulé 400 ms/worker, vrai planificateur + PostgreSQL Testcontainers, poste Windows)
N workers indépendants + 1 synthèse ; colonnes : durée totale ms | 1er dispatch ms | passe moyenne ms | concurrence max | tours.

| N | séquentiel | parallèle |
|---|---|---|
| 1 | 1303 \| 173 \| 57 \| 1 \| 2 | 1113 \| 70 \| 34 \| 1 \| 2 |
| 2 | 1578 \| 74 \| 37 \| 1 \| 3 | 1111 \| 115 \| 32 \| 2 \| 2 |
| 3 | 2025 \| 61 \| 32 \| 1 \| 4 | 1129 \| 121 \| 35 \| 3 \| 2 |

Lecture honnête : le gain vient du chevauchement (N=3 : ≈ 1,1 s contre ≈ 2,0 s), le coût d'orchestration par passe est ≈ 30–60 ms, le dispatch d'un groupe de 3 ≈ 120 ms. Ces chiffres ne mesurent **ni** la latence d'un fournisseur **ni** la charge multi-organisations ; une seule exécution à la fois par essai, pas de test de charge. Goulots identifiés (non mesurés sous charge) : limite C1 de 3 exécutions ouvertes par organisation, capacité de l'orchestrateur (4 exécutions), limites de débit fournisseur, verrou de ligne du run, capacité MCP, `WAITING_APPROVAL` qui garde son slot, période de la maintenance (3 s).

## Sécurité
Aucune constatation CRITICAL/HIGH ouverte. Revue : toutes les requêtes sont limitées à l'organisation ; références opaques pour le coordinateur (aucun UUID dans son contexte, testé) ; aucun contexte partagé entre workers (la tâche d'un worker ne contient pas celle d'un autre, testé) ; autorisation du demandeur revalidée à chaque passe qui dispatche ; actions périmées refusées (409) ; approbations jamais partagées ; garde ajoutée pour qu'un run terminal ne dispatche rien.

## Limites connues
- **Redémarrage** : testé avec un worker sans mémoire sur PostgreSQL réel ; **pas de recréation de conteneur** Core API/orchestrateur en plein groupe (non rejoué sur la pile Docker).
- Pas de reprise manuelle (`WAITING_RECOVERY`) avec le parallélisme ; un seul groupe à la fois par workflow ; pas de limite par fournisseur ; pas de `SKIP LOCKED` (les passes concurrentes se sérialisent sur le verrou du run).
- La réservation est conservatrice et configurable ; l'usage réel peut la dépasser (non connu d'avance).
- **Pas d'exactly-once** pour les effets externes : livraison au moins une fois, traitement idempotent.
- Connectivité Anthropic réelle **NON VÉRIFIÉE** ; fournisseurs simulés.
- Hors périmètre : délégation récursive, messages worker↔worker, mémoire partagée, courtier, refonte du frontend (C3.5).
