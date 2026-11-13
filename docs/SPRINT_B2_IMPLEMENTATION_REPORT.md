# Sprint B2 — Gestion des employés et structure organisationnelle : rapport d'implémentation

Branche : `feat/organization-workforce-b2`, créée sur le commit B1 `34756a3`. Rien n'est commité ni poussé. Date : 2026-11-14.

## 1. Résumé

Un OWNER authentifié peut désormais, via le vrai Core API et l'application Angular/Electron : créer des départements imbriqués, un catalogue de positions, des profils d'employés (avec ou sans compte de connexion), définir managers et chefs de département, créer des équipes, y affecter des employés et gérer leurs responsables, inviter de nouveaux collaborateurs avec l'infrastructure B1, visualiser toute la structure et changer d'organisation sans fuite de données.

Points forts de la conception :

- **Concepts séparés** : `UserAccount` (identité), `Membership` + `Role` (accès), `EmployeeProfile` (profil professionnel), `Position` (intitulé de poste, **sans aucun droit**), `Department`, `Team`. Un profil peut exister sans membership ; suspendre un membership ne touche pas au profil ; changer de position ne change jamais les permissions.
- **Isolation des tenants dans le schéma** : toutes les relations sont des clés étrangères composites `(organization_id, id)`.
- **Intégrité hiérarchique** : cycles de départements et de lignes hiérarchiques refusés, sous verrou d'organisation, testés en concurrence.
- **Modules sans cycle** : noyau partagé `workforce` (ports `EmployeeDirectory` et `EmployeeLifecycleParticipant`), ordre de dépendance verrouillé par ArchUnit.
- **Les agents IA ne sont pas des employés** : B3 les ajoutera aux équipes via une table dédiée, sans référence polymorphe.

## 2. Audit initial

| Constat | Traitement |
| --- | --- |
| Arbre de travail propre, branche B1 à `34756a3` | Branche `feat/organization-workforce-b2` créée dessus |
| Migrations appliquées : V1, V2 | **Nouvelle V3** ; V1 et V2 inchangées (vérifié) |
| Permissions B1 : `resource.action` (12 codes) ; rôles intégrés stockés **par organisation** dans `role_permissions` | 10 permissions ajoutées avec la même convention, **et rétro-remplissage** des rôles OWNER/ADMIN/MEMBER des organisations existantes |
| Les dépôts B1 sont package-private et `@TenantScoped` ; modules à plat | Même convention conservée (documentée) pour que l'encapsulation soit imposée par le compilateur |
| Desktop : page Organization de la phase A sur données de démo (modèles incompatibles : `firstName/lastName`, statuts « online/idle ») | Nouvelle page réelle en mode `api` ; la page de démo reste pour le build `mock` et les tests |
| Allowlist IPC limitée à B1 | Étendue opération par opération (pas de suppression d'employé) |

## 3. Réalisations

### 3.1 Base de données — `V3__workforce_management.sql`
Tables `departments`, `positions`, `employee_profiles`, `teams`, `team_members` : UUID, UTC, contraintes de statut/format/dates, **clés étrangères composites** vers membership, position, département, manager, chef de département, responsable d'équipe, index uniques partiels (un profil par membership, un numéro par organisation, une appartenance courante par équipe), pas de cascade. Dix permissions + rétro-remplissage. Privilèges d'exécution inchangés (DML seulement).

### 3.2 Backend (Spring Boot)
Modules `workforce`, `department`, `position`, `employee`, `team` ; services transactionnels, DTO et vues explicites (jamais d'entité exposée), validation Bean Validation, RFC 9457, journal d'audit dans la même transaction, pagination et filtres, verrou de version optionnel (`expectedVersion`).
Règles principales : manager/chef/responsable/membre = employé **courant** ; désactivation refusée tant que l'employé dirige un département, une équipe ou des employés courants ; à la désactivation il quitte ses équipes (historique conservé) ; réactivation exige département/position actifs et retire un manager parti ; département archivable seulement sans employé courant ni sous-département actif ; suppression limitée aux éléments inutilisés ; équipes figées une fois archivées ; le responsable doit être membre courant.
Les services vérifient **membership + permission avant** de prendre le verrou d'organisation (un utilisateur sans droit ne bloque personne). Tout id présent dans un corps de requête est résolu **dans l'organisation de la requête** : id d'une autre organisation → 404.

### 3.3 Onboarding (invitations B1 réutilisées)
Choix : **étape séparée après l'acceptation** (ADR-0013). Invitation (`invitation.create`) → acceptation → membership → création du profil lié (`employee.create`, `membershipId`) ou liaison ultérieure (`PUT …/membership`) → équipe. Aucune modification des tables ni des jetons d'invitation. Le profil n'expose jamais l'e-mail du compte (seulement l'id du membership et son statut).

### 3.4 Desktop
- **Electron** : 39 opérations ajoutées à la liste blanche, filtres de requête stricts (`q` borné sans caractères de contrôle, ids en UUID, énumérations). Le renderer ne voit toujours aucun jeton.
- **Angular** : `WorkforceApi` (client typé, ids validés), `WorkforceStore`, `orgResource` (chaque résultat est étiqueté avec son organisation et **n'est exposé que tant qu'elle est l'organisation courante**). Onglets Structure (arbre + détail), Employés (recherche, filtres, pagination, affectations, statut, équipes), Positions (catalogue + titulaires), Équipes (membres, responsable, département). Actions affichées selon les permissions renvoyées par l'API (qui les ré-applique). Bouton « Inviter un collaborateur ». Les agents, projets, communication et activité restent **données de démo** signalées.

## 4. Résultats réels des vérifications

| Vérification | Commande | Résultat |
| --- | --- | --- |
| Backend complet (unitaires, web, ArchUnit, intégration PostgreSQL + Mailpit + Keycloak) | `./mvnw clean verify` | **144 tests, 0 échec, 0 ignoré** — BUILD SUCCESS (105 hérités de B0/B1 + 39 nouveaux) |
| Contrat OpenAPI | export puis `diff` avec `contracts/openapi/core-api.v1.json` | **synchronisé** |
| Electron (politique, loopback, session, proxy, surface IPC, E2E Keycloak réel) | `AIW_KEYCLOAK_ADMIN_PASSWORD=… npm run electron:test` | **43/43** (dont le login PKCE réel contre Keycloak) |
| Build Electron | `npm run electron:build` | OK |
| Angular | `ng test --watch=false` | **64/64** (40 hérités + 24 nouveaux) |
| Builds Angular | `ng build` (production) et `ng build --configuration mock` | OK |
| Hygiène Git | `git diff --check` | aucune erreur (seulement des avertissements de fins de ligne LF/CRLF) |

### Tests nouveaux (backend, 39)
- **CRUD, validation, règles métier** : départements/positions (9), employés (10), équipes (4).
- **Multi-tenancy** (5) : deux organisations ; refus A↔B sur chaque endpoint ; ids de B passés via les chemins de A ; **chaque référence inter-organisations** (parent, chef, position, manager, membership, responsable, membre) refusée en 404 ; un manager ne gère pas les employés d'une autre organisation ; **contraintes de base testées en écrivant directement** des références inter-organisations avec le rôle d'exécution (employé↔département/position/manager/membership, département↔parent/chef, position↔département, équipe↔responsable, membre↔équipe/employé) ; unicités et autoréférences.
- **Hiérarchie** : cycles de départements (soi-même, descendant, indirect), profondeur max, cycles de lignes hiérarchiques (soi-même, direct, indirect), manager inactif refusé.
- **Concurrence** (3) : deux déplacements de départements ou deux affectations de managers formant un cycle ne réussissent jamais ensemble (8 tours chacun) ; trois ajouts simultanés du même employé dans une équipe → un seul succès.
- **Autorisations** : rôle MEMBER en lecture seule ; rôle personnalisé aux droits exacts (peut créer/éditer mais pas désactiver) ; position/département/manager sans effet sur les permissions ; invitation → membership → profil lié ; suspension/suppression du membership sans effet sur le profil.
- **Migrations** : base **B1 avec organisations existantes → V3** (rétro-remplissage 10/10/4, rôles personnalisés intacts, validation Flyway V1–V3, privilèges d'exécution) ; base vierge (tous les tests d'intégration) ; B0 → V3.
- **ArchUnit** : 7 nouvelles règles (ordre de dépendance des modules, noyau sans dépendance, `access`/`identity`/`organization`/`invitation` ignorent le workforce, tout `*Service` dépend de `TenantAccessService`).

### Tests nouveaux (Angular, 24) et Electron (2)
Client API (requêtes, ids invalides) ; `orgResource` ne montre jamais la réponse lente d'une autre organisation ; store (aplatissement, aucune requête sans permission, rechargement, changement d'organisation) ; onglets Structure/Employés/Positions/Équipes (rendu, filtres, pagination, recherche différée, états vide/erreur, actions masquées sans permission, désactivation seulement avec `employee.deactivate`, responsable non supprimable, sélection effacée au changement d'organisation) ; routage (page réelle en mode api, page de démo en mock). Electron : allowlist workforce autorisée/refusée, filtres malformés refusés.

### Test de fumée sur la pile locale réelle
Volume local existant migré en V3 (rétro-remplissage vérifié en base : OWNER 10, ADMIN 10, MEMBER 4 permissions B2 sur l'organisation B1 déjà présente). Avec les modules Electron compilés (login PKCE Keycloak, proxy avec allowlist) : création d'organisation, départements imbriqués (201), cycle refusé (409), position, deux employés avec manager, cycle de managers refusé (409), chef de département, équipe avec responsable, ajout de membre (201), doublon (409), désactivation du chef refusée (409), arbre, recherche `q=dan` (1 résultat avec position et manager), suppression d'employé **hors allowlist**, accès via l'id d'une autre organisation (404).

## 5. Règles du cahier des charges

| Règle | Statut |
| --- | --- |
| V1/V2 non modifiées | Vérifié (aucun diff) ; V3 nouvelle |
| Aucun volume PostgreSQL supprimé | Respecté (V3 appliquée sur le volume existant) |
| `.env` locaux non écrasés, aucun identifiant commité | Respecté ; aucun fichier sensible ni artefact dans l'arbre |
| Autorisation jamais côté Angular seulement | Chaque endpoint vérifie membership + permission |
| IDs d'organisation du client jamais crus | Contrôle d'adhésion à chaque requête, ids résolus dans l'organisation |
| Agents IA ≠ employés | Respecté (table d'équipe sans polymorphisme, B3 prévu) |
| Pas de fausses données présentées comme réelles | Domaines non backend étiquetés « Demo data » |
| Aucun commit ni push | Respecté |

## 6. Décisions et limites

- **Structure des modules** : disposition à plat de B1 conservée (dépôts package-private) plutôt que les sous-paquets `api/application/domain/persistence` : c'est ce qui fait respecter l'encapsulation par le compilateur (documenté).
- **Relations modifiables par `PUT`** (valeur `null` = effacer) plutôt que `PATCH`, pour distinguer « absent » de « effacer » dans un client typé. Les endpoints demandés sont tous présents, avec des ajouts (arbre, archivage, statut, membres, équipes d'un employé…).
- **Pas de suppression d'employé** : cycle de vie par statut (ACTIVE / ON_LEAVE / INACTIVE).
- **Pas d'historique de position** : on connaît la position courante ; l'historique existe pour les équipes. La suppression d'un département/position se fonde donc sur les références courantes.
- **Sélecteurs** (manager, chef, responsable, compte) limités aux 100 premiers employés/membres ; un sélecteur à saisie assistée reste à faire.
- **Un test Angular a échoué une fois sur cinq exécutions** (sans que j'aie conservé son nom) avant que j'aie remplacé les attentes à durée fixe par de l'interrogation et allongé la boucle de stabilisation ; ensuite 6 exécutions consécutives et la passe finale sont toutes vertes. Une instabilité résiduelle ne peut pas être exclue formellement.
- **CI** : le workflow existant exécute déjà ces suites ; il n'a pas été modifié et n'a pas tourné sur GitHub.
- Docker Desktop s'était arrêté en début de session et a été relancé, sans impact sur les données.

## 7. Fichiers principaux

Backend : `V3__workforce_management.sql`, paquets `workforce/`, `department/`, `position/`, `employee/`, `team/` ; `access/OrganizationLock`, `MembershipService.linksOf/requireLinkable`, `Permission`, `BuiltInRole`. Electron : `auth/auth-policy.ts`. Angular : `core/workforce/*`, `features/workforce/*`, `app.routes.ts`. Documentation : `docs/architecture/WORKFORCE_MANAGEMENT_ARCHITECTURE.md`, `docs/database/WORKFORCE_DATA_MODEL.md`, ADR-0012 et ADR-0013, README du Core API, du desktop et des contrats mis à jour, tables RBAC de `MULTI_TENANCY_ARCHITECTURE.md`.
