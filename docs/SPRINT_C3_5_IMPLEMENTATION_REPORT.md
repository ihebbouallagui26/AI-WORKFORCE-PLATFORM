# Sprint C3.5 — Advanced Workflow UI & Collaboration

Branche `feat/workflow-ui-collaboration-c3-5`, créée depuis `origin/main` = `bdf87a6` (merge de la PR #9, C3.4 `357d316` vérifié présent). **Aucun commit, push ni merge.** C3.6 n'est pas commencé (il attend la confirmation de merge de C3.5).

> Le Core API reste l'autorité ; l'interface lit et demande. Aucune modification du Core API ni de l'orchestrateur (code de production) : l'OpenAPI versionné est inchangé et identique au fichier généré.

## Architecture UI
Conventions existantes réutilisées (aucun second design system, aucune dépendance ajoutée) : `BackendClient` via le proxy Electron, `orgResource` (état par organisation), `AccessStore` (permissions), composants partagés (`Panel`, `Badge`, `Tabs`, `StatCard`, `Segmented`, `EmptyState`…), `ConfirmService`, `ToastService`, et la **carte d'approbation C2** existante (réutilisée, non dupliquée).

| Couche | Fichiers (`ui/src/app/…`) |
|---|---|
| Contrats typés | `core/workflows/workflows.models.ts`, `workflows.api.ts` (identifiants validés avant tout appel) |
| État | `workflows.store.ts` (définitions, runs, filtres, par organisation), `run-monitor.ts` (polling d'un run) |
| Logique pure (testée) | `workflow-view.model.ts` (états, disposition fan-out/fan-in, chronologie, budget, éligibilité du retry, impact d'une annulation, analytique), `pipeline-builder.model.ts` |
| Pages | `features/workflows/` : `workflows-page` (aperçu, workflows, runs, approbations), `workflow-editor` + `pipeline-builder`, `workflow-detail`, `run-page` + `run-graph`, `worker-card`, `worker-detail`, `run-timeline-view`, `run-analytics-panel` |
| Routes | `/workflows`, `/workflows/new`, `/workflows/:id`, `/workflows/:id/edit`, `/workflows/runs/:runId` (+ entrée de navigation « Workflows », permissions `workflow.read/manage/execute` ajoutées au type) |
| Electron | allowlist `auth-policy.ts` : endpoints workflow nommés uniquement (jamais `/internal/**`, aucun forçage d'état), filtre `definitionId`, **aucun nouvel IPC**, `contextIsolation`/`nodeIntegration` inchangés |

## Capacités
- **Aperçu** : définitions, runs actifs/terminés/échoués, en attente d'approbation / de récupération, runs parallèles ; statistiques calculées **uniquement** sur les runs chargés (taux de réussite, durée moyenne, jetons, utilisation du budget) avec leur base (« sur N ») ; « Non disponible » sans base ; recherche et filtres (mode, état).
- **Définitions** : création/édition (nouvelle version ; un run déjà lancé garde son snapshot), mode PIPELINE ou COORDINATED (verrouillé en édition), participants (agents actifs), coordinateur et responsabilités expliqués, politique d'échec, limites, workers parallèles, récapitulatif avant enregistrement. L'ordre des appels respecte les règles du Core API (ajout des participants, étapes, retrait).
- **Constructeur de pipeline visuel** : étapes en colonnes, cartes groupées pour les workers parallèles, dépendance « reçoit les résultats de l'étape précédente », boutons accessibles (pas de glisser-déposer), validation avant enregistrement ; un pipeline sans stage parallèle est envoyé exactement comme avant C3.4.
- **Suivi d'un run** : fan-out/fan-in, états distincts (en attente de capacité ≠ dispatché ≠ en cours ≠ en attente d'approbation ≠ terminé ≠ échoué ≠ annulé ≠ **résultat externe inconnu**), budget (utilisé, réservé, restant, dépassement), « pourquoi ça attend », détail par worker (toutes les tentatives, catégorie d'échec, sûreté du retry, tâche, résultat), résultats séparés par worker, actions du coordinateur (acceptées/refusées), métriques mesurées, chronologie.
- **Actions** (demandes que le Core API peut refuser) : lancer (clé d'idempotence par intention), annuler (conséquences expliquées, jamais « annulé » pour un résultat inconnu), réessayer UNE étape éligible d'un run séquentiel en récupération (confirmation) ; pour les workers parallèles l'interface **explique** que la récupération manuelle n'existe pas (aucun bouton trompeur) ; décisions d'approbation via la carte C2.
- **Mise à jour** : polling borné, aucune WebSocket/SSE (inexistantes) ; la chronologie est reconstruite à chaque rafraîchissement et le dit.

## Défauts trouvés par les tests et corrigés
1. Collision entre la sortie `change` du constructeur et l'événement DOM natif `change` (un `<select>` écrasait la liste des étapes) → sortie renommée puis `model()`.
2. Modifications perdues si deux événements arrivaient avant un rendu (entrée périmée) → le constructeur est en `model()` à double liaison.

## Tests (Windows, ce poste)
| Suite | Résultat |
|---|---|
| Angular (vitest) | **17 fichiers, 280 tests, 0 échec** (177 avant : +56 logique/contrat, +37 composants, +10 cycle de vie du polling) |
| Electron | **46 tests, 0 échec** (44 avant : +2 allowlist workflow : 16 requêtes autorisées, 18 refusées) |
| Core API `clean verify` | **362 tests, 0 échec** (361 avant : +1 test de capture) ; OpenAPI inchangé |
| Builds de production | Angular (`ng build`) et Electron (`tsc`) OK |
| Linux | **non exécuté localement** (CI) |

**Contrats réels, pas de fixtures inventées** : `WorkflowContractCaptureIntegrationTest` (vrai Core API + PostgreSQL, orchestrateur joué par son API interne) capture 24 réponses (pipeline parallèle avec file d'attente, retry, `OUTCOME_UNKNOWN`, `WAITING_RECOVERY`, coordinateur avec délégation refusée) ; les modèles Angular y sont comparés champ par champ (`satisfies Record<keyof …>`) et ces données alimentent les tests de logique et de pages. Polling testé : run ouvert suivi, run terminal lu une fois, jamais deux cycles simultanés, conservation des données et back-off en cas d'erreur, nettoyage à la destruction, pause bornée, réponse tardive ignorée après changement d'organisation.

## Limites connues
- **Non vérifié visuellement** : aucune capture d'écran, aucun essai dans une vraie fenêtre Electron contre la pile Docker/Keycloak (cela relève du scénario d'intégration C3.6). Les pages sont validées en jsdom avec les réponses réelles du Core API.
- Les statistiques de l'aperçu portent sur les 50 runs les plus récents chargés ; pas d'historique agrégé côté serveur.
- Le « dispatché » vs « en cours » repose sur la lecture de l'exécution enfant (`agent.execution.read`) ; sans cette permission, un worker vivant est affiché « dispatché, non confirmé ».
- Récupération manuelle uniquement pour les runs séquentiels ; pas de réconciliation des résultats inconnus (limites du backend C3.3/C3.4).
- Pas d'historique d'approbations par worker dans la chronologie hors approbations en attente (l'API ne filtre que par exécution) ; durée d'attente d'approbation « Non disponible » sans historique chargé.
- Mode mock (démo hors ligne) : la section affiche qu'elle nécessite le Core API.
- Connectivité Anthropic réelle non vérifiée (inchangé).
