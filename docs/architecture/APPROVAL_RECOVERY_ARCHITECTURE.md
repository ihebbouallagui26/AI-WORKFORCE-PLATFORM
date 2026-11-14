# Approbations humaines : durabilité et reprise (Sprint C2.2)

Le Core API reste le système de référence. L'orchestrateur Python n'écrit jamais dans les tables métier ; il est
notifié, puis appelle les endpoints internes (`/resume`, `/tool-calls/{id}/begin`, ...).

## 1. Machine à états

| Objet | États | Transitions |
|---|---|---|
| Exécution | `PENDING`, `RUNNING`, `WAITING_APPROVAL`, `SUCCEEDED`, `FAILED` | `RUNNING → WAITING_APPROVAL → RUNNING \| FAILED` |
| Approbation | `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED` | seul `PENDING` change, une seule fois |
| Appel d'outil | `AWAITING_APPROVAL → APPROVED \| REJECTED \| EXPIRED \| FAILED`, `APPROVED → RUNNING → SUCCEEDED \| FAILED` | un appel démarre au plus une fois |

`CANCELLED` (déjà prévu par le modèle) est utilisé pour une approbation dont l'exécution est devenue terminale avant
toute décision. Aucun nouvel état ni nouvelle migration n'a été nécessaire (la reprise s'appuie sur V6).

## 2. Livraison durable de la reprise (outbox PostgreSQL existant)

La ligne `tool_approvals` est elle-même l'outbox : `resume_acknowledged_at`, `resume_attempts`,
`last_resume_attempt_at`. Il n'y a ni broker ni table supplémentaire.

1. La décision (`APPROVED` / `REJECTED`) est validée **dans la transaction** qui change l'approbation et l'appel.
   Elle ne dépend jamais de la livraison HTTP.
2. Après commit, une notification immédiate est tentée au mieux (`ApprovalDecided`, pool borné).
3. `ApprovalMaintenance` (toutes les `app.executions.approval-maintenance-ms`, 10 s par défaut) relit la base :
   décisions non acquittées, fenêtre de 10 s depuis la dernière tentative. Après un redémarrage du Core API, rien
   n'est perdu : tout l'état est en base.
4. L'envoi est précédé d'une **réclamation** (`claimResumeAttempt`, verrou de ligne, REQUIRES_NEW) : par fenêtre de
   retry, un seul worker envoie, même avec plusieurs instances du Core API.
5. Quatre faits distincts et explicites : décision enregistrée (`decided_at`), tentative faite
   (`resume_attempts`, `last_resume_attempt_at`), notification livrée (réponse HTTP, non persistée), reprise effective
   (`resume_acknowledged_at`, posé uniquement quand l'orchestrateur appelle `/resume` et que l'exécution repasse
   `RUNNING`). Une livraison HTTP réussie n'est **pas** une exécution d'outil.
6. `resume` réussit une seule fois par pause (verrou execution → appel → approbation). L'outil ne démarre que par
   `begin`, qui revalide agent actif, grant, version de politique et approbation liée au même hash.

## 3. Garanties et limites

* Livraison : **au moins une fois** (durable). Pas « exactement une fois ».
* Traitement : **idempotent**. Un doublon de notification est inoffensif (`/resume` renvoie 409 la deuxième fois).
* Exécution de l'outil : **au plus une fois** côté Core API (`begin` une seule fois). Si le résultat est perdu après
  `begin`, l'appel n'est jamais rejoué.
* Le retry est à intervalle fixe (10 s), borné par l'abandon (`resume-give-up`, 15 min) ; il n'y a pas de backoff
  exponentiel.

## 4. Exécutions abandonnées et expiration

* Pas de décision avant `expires_at` (`approval-timeout`, 15 min) : approbation et appel `EXPIRED`, exécution
  `FAILED/APPROVAL_EXPIRED`, audit `agent.approval.expired`, arguments scellés supprimés. Exactement à l'échéance
  (`now >= expires_at`) l'approbation est expirée ; une milliseconde avant, elle est encore valide. Une tentative
  d'approbation tardive rend l'expiration durable avant de répondre 409.
* Décision jamais reprise pendant `resume-give-up` : exécution `FAILED/RESUME_FAILED`, entrée d'outbox fermée.
* **Correction C2.2** — exécution devenue terminale alors qu'une approbation reste ouverte (échec rapporté par
  l'orchestrateur, délai dépassé, etc.) : `closeObsolete` ferme l'approbation (`PENDING → CANCELLED`, ou décision
  non reprise → acquittée), marque l'appel `FAILED/EXECUTION_ENDED`, supprime les arguments scellés et audite
  `agent.approval.cancelled`. Avant la correction, une décision non reprise sur une exécution terminale était
  renvoyée indéfiniment et ses arguments chiffrés restaient stockés ; une approbation `PENDING` restait listée
  jusqu'à son expiration.
* Une exécution terminale ne peut pas être relancée : `resume` et `begin` répondent 409.
* Une exécution `WAITING_APPROVAL` reste « ouverte » (compte dans la capacité) jusqu'à décision, expiration ou
  abandon ; elle est donc bornée par `approval-timeout` + `resume-give-up`.

## 5. Concurrence

Ordre de verrous global : exécution → appel → approbation. Décisions simultanées, approbation contre rejet,
approbation contre expiration, approbation contre fin d'exécution et workers concurrents ont un seul vainqueur ;
les perdants reçoivent 409. Aucun scénario n'autorise un second démarrage de l'appel.

## 6. Limites restantes

* Les scans sont globaux (toutes organisations) mais chaque action passe par des méthodes à portée d'organisation.
* La reprise après redémarrage de l'orchestrateur dépend du checkpoint Python ; la tolérance de ce côté est testée
  avec checkpointer en mémoire, pas encore avec une panne réelle de processus.
* Pas de backoff exponentiel ni de file prioritaire.
