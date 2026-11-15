# Chiffrement de l'état d'orchestration persisté (Sprint C2.3)

## 1. Ce qui est chiffré, et ce qui ne l'est pas

Une exécution suspendue dans l'attente d'une approbation humaine doit survivre à un redémarrage de l'orchestrateur.
Sa conversation (instruction, sorties d'outils déjà reçues, arguments de l'appel en attente) est donc conservée dans le
checkpoint LangGraph, **scellée** par l'application avant d'y être écrite.

| Donnée | Où | Protection appliquée par l'application |
|---|---|---|
| Conversation d'une exécution suspendue (`loop_blob`) | base `ai_orchestrator`, table `orchestrator_runtime.checkpoints` (JSON) | AES-256-GCM, clé `AIO_STATE_ENCRYPTION_KEY`, AAD = `organisation:exécution` |
| Identifiants, noms d'étapes, statut, compteurs | mêmes tables | **en clair** (aucune donnée sensible : garde `assert_state_safe`) |
| Identifiants fournisseur, jetons d'accès, en-têtes d'authentification | jamais persistés | conservés uniquement en mémoire le temps d'un segment d'exécution |
| Arguments sensibles d'un appel en attente côté Core API | table `secret_entries` du Core API (référencée par `tool_invocations.arguments_ref`) | **mécanisme indépendant** : SecretStore B3 (`SECRETS_MASTER_KEY`), clé différente |
| Autres données métier du Core API | base du Core API | non chiffrées par l'application (hors périmètre ; ne pas affirmer le contraire) |

Le chiffrement ne remplace pas l'autorisation : l'accès à une exécution reste contrôlé par le Core API, qui revalide
chaque appel d'outil.

## 2. Mécanisme (`app/core/crypto.py`) — format 2 depuis le sprint C3.6 (correctif 8.3)

**Format 2 (écriture) :** clé de scellement **dérivée** par HKDF-SHA256 de la clé configurée (info `checkpoint-loop|key-id=1`, jamais la clé brute), octet de version `0x02`, octet d'identifiant de clé `0x01`, nonce aléatoire de 96 bits. L'AAD lie le but, la version, l'identifiant de clé, l'organisation, l'exécution, le **thread** et l'**invocation d'outil** sur laquelle le run est en pause : un blob copié vers une autre invocation, un autre thread ou une autre exécution est refusé (`STATE_UNAVAILABLE`, aucun outil exécuté).
**Format 1 (lecture seule) :** les blobs écrits avant C3.6 (clé brute, AAD organisation + exécution) restent lisibles tant que `AIO_STATE_ACCEPT_LEGACY_FORMAT` vaut `true` (défaut) ; mettre `false` une fois les anciens runs terminés. Aucune écriture au format 1.
Vérifié : tests unitaires et PostgreSQL réel (`test_checkpoint_protection.py`, `test_state_persistence_postgres.py`), et copie restaurée d'une base de checkpoints (69 blobs format 1 ouverts avec la clé existante, tous refusés avec une autre clé). Le texte ci-dessous décrit le socle C2.3, toujours valable pour le reste (AES-256-GCM, validation de la clé, absence de repli en clair).

* AES-256-GCM (bibliothèque `cryptography`), nonce aléatoire de 96 bits par scellement, octet de version `0x01`.
* Format : `hex(version ‖ nonce ‖ ciphertext+tag)`. Le tag GCM authentifie le contenu **et** l'AAD.
* Toute modification, troncature, mauvaise clé, mauvaise exécution ou mauvaise organisation lève `StateIntegrityError`
  (jamais de déchiffrement partiel ni de repli en clair). L'exécution échoue avec le code `STATE_UNAVAILABLE`, sans
  contenu sensible dans le message.
* La clé : base64 de 32 octets exactement. Validée au démarrage (format et longueur), jamais affichée : les erreurs de
  configuration ne contiennent plus aucune valeur d'entrée (`hide_input_in_errors`, corrigé en C2.3 : auparavant la fin
  de la clé pouvait apparaître dans un message d'erreur de validation).

## 3. Provisionnement

| Environnement | Source de la clé | Comportement |
|---|---|---|
| Développement local | `infra/docker/.env` (ignoré par Git), variable `ORCHESTRATOR_STATE_ENCRYPTION_KEY` | `bash infra/docker/scripts/ensure-env.sh` la génère **une seule fois** (`openssl rand -base64 32`) et ne la réaffiche jamais |
| Compose | `AIO_STATE_ENCRYPTION_KEY: ${ORCHESTRATOR_STATE_ENCRYPTION_KEY:?...}` | obligatoire, aucune valeur par défaut, passée à l'exécution comme variable d'environnement : ni argument de build, ni couche d'image |
| Production | injection de secret de la plateforme (variable d'environnement ou secret monté) | `AIO_ENVIRONMENT=prod` **refuse de démarrer** sans clé valide |

`ensure-env.sh` : n'ajoute la clé que si elle est absente ; conserve toute valeur existante (y compris un `.env` CRLF) ;
si une clé existante est vide ou malformée, il s'arrête avec le code 1 **sans rien modifier** et sans afficher la
valeur (la remplacer rendrait l'état scellé illisible). Il ne tente jamais de pivoter une clé.

## 4. Redémarrages, réplicas, sauvegardes

* **Redémarrage** : la clé vient du `.env`/du secret, donc stable ; un nouveau processus relit le checkpoint et le
  déchiffre. Tester sans la clé (ou avec une autre) échoue de façon authentifiée.
* **Réplicas** : tous les réplicas qui peuvent reprendre la même exécution doivent recevoir **la même clé**. Une
  reprise est possible sur n'importe quel réplica ayant la clé.
* **Sauvegardes** : sauvegarder la base `ai_orchestrator` **et** la clé, séparément et dans un coffre protégé. Une
  sauvegarde de base sans la clé est inutilisable pour les exécutions suspendues ; une clé stockée à côté de la base
  annule l'intérêt du chiffrement.
* **Perte de la clé** : les exécutions actuellement suspendues deviennent irrécupérables (elles échouent avec
  `STATE_UNAVAILABLE` ; le Core API les clôt via son expiration/abandon). Aucune autre donnée n'est perdue : les
  résultats d'exécutions terminées et les identifiants fournisseur ne dépendent pas de cette clé.

## 5. Rotation : différée, non implémentée

Ne **jamais** remplacer la valeur de la variable tant que des checkpoints scellés existent : ils deviendraient illisibles.
Une rotation contrôlée exigerait : un identifiant de version de clé dans le format scellé (l'octet de version existe
mais ne code aujourd'hui qu'un format), un trousseau (nouvelle clé pour sceller, anciennes pour ouvrir), puis un
rescellement hors ligne de tous les checkpoints ouverts, avant retrait de l'ancienne clé. Rien de cela n'est fait. (C3.6 : l'identifiant de clé existe maintenant dans le format 2 — `key-id=1` — ce qui prépare le trousseau, mais **aucun outil de rotation ni de rescellement n'est livré** ; voir `docs/SECURITY_AUDIT.md`, N-2.)

## 6. Tests réellement exécutés

* `tests/test_state_key_config.py` : prod sans clé / clé vide, formats et longueurs invalides, clé jamais affichée
  (objet de configuration, erreurs, `/health/*`, `/api/v1/runtime/info`, journaux), scellement/ouverture, mauvaise clé,
  AAD différente, altération, troncature, version inconnue.
* `tests/test_state_persistence_postgres.py` (vrai PostgreSQL, Testcontainers) : pause, relecture avec la même clé dans
  un nouveau cycle de vie, mauvaise clé, trois altérations réalisées par `UPDATE` SQL sur la ligne de checkpoint, absence
  de secret/instruction/résultat en clair dans les trois tables de checkpoints.
* `tests/test_env_provisioning.py` : génération unique, relance sans changement, valeurs existantes conservées, CRLF,
  clé malformée ou vide, exemple sans valeur par défaut, validation `docker compose config` (clé exigée, non présente
  dans la section `build`).
* Conteneur : l'image se construit ; aucune occurrence de la variable dans `docker inspect`/`docker history` ;
  `AIO_ENVIRONMENT=prod` sans clé quitte avec le code 1 et sans fuite de secret.

Limites de ces tests : un « cycle de vie » est un pool de connexions, un checkpointer, un exécuteur et un gestionnaire
MCP indépendants dans un même processus de test (pas un sous-processus ni un redémarrage de conteneur) ; le Core API et
Anthropic sont simulés. La connectivité réelle avec Anthropic reste **non vérifiée**.

## 7. Validation réelle d'un redémarrage de conteneur (C2.3.1)

Pile utilisée : PostgreSQL, Keycloak et Mailpit (Compose, déjà démarrés), service Compose `ai-orchestrator` (image
reconstruite avec le code C2), Core API lancé sur l'hôte (JVM, pas en conteneur), faux fournisseur déterministe
(jamais Anthropic). Déroulé : exécution suspendue sur un outil à approbation, `docker compose up -d --no-deps
--force-recreate ai-orchestrator` (nouveau conteneur, nouvel identifiant, même `.env`), puis approbation humaine via le
Core API.

Constats : conteneur remplacé et ancien supprimé ; empreinte de la clé identique avant/après ; PostgreSQL non touché
(même conteneur, même heure de démarrage) ; 7 lignes de checkpoint et blob scellé identiques avant/après ; l'exécution
reprend, déchiffre l'état, exécute l'outil une fois, termine `SUCCEEDED` et le résultat est persisté par le Core API ;
le faux fournisseur reçoit au tour 2 la conversation restaurée (message utilisateur, appel d'outil, résultat) ; aucune
clé ni identifiant dans les journaux ; aucun texte d'instruction, argument ou résultat dans les trois tables.

Deux défauts trouvés par cette validation et corrigés (tests de régression ajoutés, le second vérifié comme échouant
sans le correctif) :
1. une notification de reprise tardive, reçue après la fin de l'exécution (conversation scellée déjà effacée), était
   traitée comme un état perdu (`STATE_UNAVAILABLE`) au lieu d'être reconnue comme un doublon : le Core API est
   désormais interrogé en premier ;
2. le `error_code` laissé dans le checkpoint par une invocation précédente était rapporté de nouveau par une reprise
   ultérieure : il est remis à zéro à chaque reprise.
Dans les deux cas le Core API refusait déjà de faire échouer une exécution terminale : aucune donnée n'était corrompue.

Limites : le Core API n'était pas en conteneur ; le conteneur a été recréé (`--force-recreate`), pas simplement `docker
restart` ; l'orchestrateur n'a pas été arrêté brutalement pendant un appel d'outil ; Anthropic réel non vérifié.
