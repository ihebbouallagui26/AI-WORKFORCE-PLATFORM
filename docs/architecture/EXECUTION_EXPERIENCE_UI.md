# Expérience d'exécution : chronologie, activité d'outils et approbation humaine (Sprint C2.4)

Interface Angular/Electron. Aucune extension du backend n'a été nécessaire : tout vient d'endpoints existants du Core API.

## 1. Où ça se trouve

| Élément | Fichier (`apps/desktop/ui/src/app/features/ai-agents/`) |
|---|---|
| Panneau d'exécution (existant, étendu) | `agent-executions.ts` |
| Conteneur : décision à prendre, chronologie, outils | `execution-activity.ts` |
| Chronologie | `execution-timeline.ts`, logique pure `execution-timeline.model.ts` |
| Carte d'activité d'un outil | `tool-activity-card.ts` |
| Carte d'approbation | `approval-card.ts` |
| Onglet « Approvals » (historique, pagination) | `approvals-tab.ts`, branché dans `ai-agents-page.ts` |
| Modèles et client API | `core/agents/agents.models.ts`, `core/agents/agents.api.ts` |

## 2. API consommée (rien d'inventé)

| Usage | Endpoint | Permission |
|---|---|---|
| Exécution (existant) | `GET /organizations/{org}/executions/{id}` | `agent.execution.read` |
| Appels d'outils d'une exécution | `GET …/executions/{id}/tool-calls` (page) | `agent.execution.read` |
| Approbations | `GET …/approvals?status&executionId&page&size` (page), `GET …/approvals/{id}` | `approval.read` |
| Décision | `POST …/approvals/{id}/approve` `{argumentsHash}`, `POST …/approvals/{id}/reject` `{reason?}` | `approval.decide` |
| Catalogue d'outils (description, serveur MCP, risque) | `GET …/tools` (tableau) | `skill.read` |

La liste blanche du proxy Electron (`auth-policy.ts`) a reçu ces cinq routes et le paramètre `executionId` (UUID).
Aucune route `/api/v1/internal/**` n'est autorisée, et aucune capacité Electron privilégiée n'a été ajoutée
(`contextIsolation`, pas de Node dans le renderer : inchangés).

**Écarts constatés** : il n'existe pas d'endpoint d'« événements » ; la chronologie est donc reconstruite à partir de
l'exécution (création, démarrage, fin) et de ses appels d'outils (demande, approbation, démarrage, fin). Le backend ne
date pas la reprise d'une exécution : l'événement « reprise » n'est pas affiché. Il n'existe ni état `CANCELLED` ni état
`TIMEOUT` d'exécution : un dépassement de délai est une exécution `FAILED` avec `errorCode = TIMEOUT` (titre « Execution
timed out »). Aucun événement de « réflexion » de l'agent n'existe et aucun n'est affiché.

## 3. Statuts

| Exécution | Affichage |
|---|---|
| `PENDING` | « Queued » : enregistrée, en attente de démarrage |
| `RUNNING` | « Running » : l'agent travaille et peut utiliser ses outils |
| `WAITING_APPROVAL` | « Needs approval » (avertissement) : une personne doit décider |
| `SUCCEEDED` / `FAILED` | résultat / message d'erreur sûr (finaux) |

Appel d'outil : `DENIED`, `AWAITING_APPROVAL`, `APPROVED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `REJECTED`, `EXPIRED`.
Approbation : `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED` (fermée parce que l'exécution s'est terminée).
Le statut est toujours écrit en toutes lettres, jamais porté par la seule couleur.

## 4. Flux d'approbation

1. Une exécution `WAITING_APPROVAL` affiche une section « A person must decide… » avec une carte par approbation en
   attente (agent, outil, serveur MCP, risque, action, **arguments exacts masqués**, empreinte SHA-256, échéance).
2. « Review and approve… » ouvre une confirmation délibérée : case « I reviewed the arguments » obligatoire, puis
   « Confirm approval ». Le rejet propose un motif optionnel (500 caractères).
3. L'approbation envoie **uniquement** `argumentsHash` ; le serveur refuse si les arguments ont changé. Elle n'autorise
   que cet appel. Aucune approbation groupée ni automatique.
4. **Pas de mise à jour optimiste** : la carte reste « Pending decision » (bouton « Recording… ») jusqu'à la réponse du
   Core API ; seule la réponse confirmée change l'affichage. La carte décidée reste visible (« Decision recorded »).
5. Erreurs : 403 « not allowed », 404 « no longer available », 409 (déjà décidée, expirée, exécution plus en attente)
   avec le texte du serveur ; sur 409/404 la carte relit l'approbation et affiche l'état réel.
6. L'interface n'autorise pas l'approbation après l'échéance (indication d'usage : un minuteur unique ferme la carte à
   l'échéance) ; le Core API reste l'autorité. L'interface ne contourne jamais une autorisation manquante : sans
   `approval.decide`, la carte est en lecture seule ; sans `approval.read`, un message invite à solliciter un
   responsable.
7. Après décision, le panneau relit immédiatement l'exécution (`refreshNow`), puis suit la reprise et le résultat.

## 5. Interrogation (polling) et reprise après rechargement

* Intervalle de 2 s, arrêt à l'état final, **limite de 20 minutes** (au-delà de l'expiration d'une approbation, 15 min) ;
  arrêt à la destruction du composant, au changement d'agent ou d'organisation.
* Le conteneur d'activité ne boucle pas : il relit les appels d'outils seulement quand l'enregistrement d'exécution change
  (statut ou version).
* Rien n'est conservé côté client : après un rechargement, une exécution non terminale de l'historique est rouverte
  automatiquement ; une exécution décidée pendant que l'interface était fermée s'ouvre dans son état final.

## 6. Sécurité et masquage

* Les arguments sont ceux **déjà masqués par le serveur** ; le client masque en plus (défense en profondeur) les formes
  `sk-…`, `Bearer …`, JWT et `clé/valeur` de type mot de passe/jeton.
* Arguments (2 000 caractères) et résultats (4 000) sont bornés à l'affichage ; l'indication « tronqué par la plateforme »
  est distincte de « seul le début est affiché ».
* Tout est rendu en **texte** (interpolation Angular) : une sortie d'outil contenant du HTML n'est jamais interprétée.
* Isolation : chaque lecture est liée à l'organisation courante (`orgResource`) ; une réponse tardive d'une autre
  organisation n'est jamais affichée et une décision confirmée après un changement d'organisation est ignorée.
* Aucun identifiant fournisseur, jeton de service ni secret MCP n'existe dans les types ou les requêtes du renderer.

## 7. Accessibilité

Chronologie en liste ordonnée étiquetée ; cartes d'outils repliables (`aria-expanded`, `aria-controls`) ; groupes de
confirmation étiquetés ; erreurs en `role="alert"` ; états de chargement et décision annoncés ; filtres avec
`aria-pressed` ; focus visible sur les contrôles.

## 8. Tests

`execution-timeline.model.spec.ts` (14), `tool-approval-ui.spec.ts` (32), `real-api-contract.spec.ts` (17, charges **réelles** du Core API capturées lors de la validation C2.5) : chronologie vide / multiple / ordre /
chargement / erreur ; appels réussis, échoués, en attente, résultat volumineux, masquage, HTML rendu en texte ;
approbation, rejet, échéance exacte, décision en double ou concurrente, 403, 404, lecture seule ; reprise après
rechargement, décision pendant la fermeture, reprise échouée, arrêt du polling ; isolation entre organisations,
réponses tardives ; historique filtré et paginé. Les fixtures suivent les contrats réels (tableau pour le catalogue,
pages pour les listes) et toute route inattendue répond 404.

## 9. Limites connues

* Pas d'événement « reprise » ni de « réflexion » (le backend n'en expose pas) ; pas de streaming.
* L'auteur d'une décision n'est affiché que par un identifiant court de membre (pas de nom : l'API de décision n'en
  fournit pas).
* Les arguments sont les rendus masqués fournis par le serveur : on ne peut pas afficher ce qui n'a pas été stocké en clair.
* L'interface n'a pas été pilotée dans une fenêtre Electron **authentifiée** (la connexion exige un navigateur interactif). Validé à la
  place : vrai Core API + vraies classes du processus principal Electron (28 contrôles), vraie application Electron démarrée et inspectée
  (surface IPC, liste blanche), et composants Angular alimentés par des réponses réelles capturées. Connectivité Anthropic réelle : non vérifiée.
