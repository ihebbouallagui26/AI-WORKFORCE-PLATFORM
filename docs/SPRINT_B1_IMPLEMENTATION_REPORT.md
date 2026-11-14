# Sprint B1 — Authentification, autorisation et multi-tenancy : rapport d'implémentation

Branche : `feat/auth-multitenancy` (non commitée, non poussée). Date : 2026-11-12.

## 1. Résumé

La phase B1 est implémentée de bout en bout :

- **Keycloak 26.8.2** (image épinglée) dans Docker Compose, base et rôle PostgreSQL dédiés, realm `ai-workforce` versionné (client desktop public, PKCE S256 obligatoire, redirection loopback, flux implicite et mot de passe désactivés, audience `ai-workforce-api`), Mailpit pour les e-mails.
- **Séparation des privilèges PostgreSQL** : `aiw_migrator` (DDL, Flyway), `aiw_app` (DML seulement), `keycloak` (sa propre base). Script idempotent, appliqué au volume B0 existant sans perte de données.
- **Core API** : resource server OAuth2 (signature, `iss`, `aud`, `exp`, `nbf`, `typ=Bearer` — les ID tokens sont refusés), provisioning des utilisateurs par `(issuer, subject)`, organisations, adhésions, RBAC (OWNER/ADMIN/MEMBER + rôles personnalisés, anti-escalade, dernier propriétaire protégé), isolation stricte des tenants, invitations (jetons à haute entropie hachés, usage unique, expiration, révocation, e-mail vérifié), journal d'audit, endpoints REST `/api/v1` documentés (OpenAPI exporté).
- **Electron** : flux OIDC complet dans le processus principal avec **openid-client 6.8.9** (navigateur système, PKCE, state/nonce, serveur loopback temporaire sur 127.0.0.1, rotation des refresh tokens, révocation à la déconnexion). Le refresh token est chiffré par `safeStorage` (ou gardé en mémoire seulement si l'OS ne peut pas le protéger). Le renderer n'a **jamais** accès à un jeton : les appels API passent par un IPC `api:request` limité à une liste blanche.
- **Angular** : écran de connexion, garde de routes, session (expiration), sélection et création d'organisation, membres, rôles et permissions, invitations, acceptation d'invitation, déconnexion, interface pilotée par les permissions. Les domaines non encore implémentés côté backend (agents, projets, communication, supervision, départements) restent sur des **données de démonstration clairement signalées** (« Demo data »).
- **CI GitHub Actions**, ADR 0008 à 0011, documentation d'architecture et de schéma.

## 2. Audit initial (résumé)

Constats faits avant modification sur la base B0 :

| Constat | Traitement |
| --- | --- |
| L'API et Flyway utilisaient le superutilisateur PostgreSQL | Rôles séparés (ADR-0011) |
| `SecurityConfig` sans mécanisme d'authentification (401 partout, voulu en B0) | Resource server JWT ajouté sans relâcher le *deny by default* |
| `V1` crée l'extension `vector`, ce qui exige un superutilisateur | Extension créée par `init-roles.sh` ; V1 devient sans effet. **V1 n'a pas été modifiée** |
| Desktop : toutes les données en mémoire, utilisateur fictif, pas d'IPC réseau | Nouvelle couche `api` + IPC restreint, mode `mock` conservé |
| Pas de CI | `.github/workflows/ci.yml` |

## 3. Réalisations par jalon

### 3.1 Infrastructure
- `infra/docker/compose.yaml` : services `db-init` (rôles), `keycloak` (26.8.2, `start-dev --import-realm`, healthcheck), `mailpit` (v1.33.1), variables OIDC/mail pour `core-api`. Tous les ports liés à `127.0.0.1`.
- `infra/docker/postgres/init-roles.sh` : création idempotente des rôles et de la base Keycloak, `REVOKE` publics, transfert de propriété des objets B0, privilèges par défaut, extension `vector`.
- `infra/docker/scripts/ensure-env.sh` : complète `infra/docker/.env` avec des mots de passe aléatoires **sans jamais modifier une valeur existante**.
- `infra/keycloak/realm/ai-workforce-realm.json` : aucun utilisateur, aucun secret.
- `.gitattributes` : fins de ligne LF pour les scripts shell et le contrat OpenAPI.

### 3.2 Base de données
- `V2__identity_and_organizations.sql` (nouvelle migration ; V1 intacte) : `user_accounts`, `external_identities`, `organizations`, `memberships`, `permissions` (catalogue initialisé), `roles`, `role_permissions`, `membership_roles`, `organization_invitations`, `audit_events`. UUID, horodatages UTC, index, contraintes d'unicité, **clés étrangères composites `(organization_id, id)`** pour l'isolation au niveau du schéma.
- `afterMigrate.sql` : réapplique les droits du rôle d'exécution, rend `audit_events` en ajout seul, le catalogue des permissions en lecture seule, et l'historique Flyway inaccessible.

### 3.3 Backend
Modules `auth`, `identity`, `audit`, `access`, `organization`, `invitation`, sans cycle de dépendances (contrôlé par ArchUnit). Points clés :
- chaque requête suit authentification → résolution de l'utilisateur → adhésion ACTIVE (sinon 404) → permission lue en base (sinon 403) → accès aux données filtré par organisation ;
- les dépôts `@TenantScoped` n'exposent que des méthodes prenant l'id d'organisation, et ArchUnit interdit `findById`/`findAll` sur ces dépôts ;
- le verrou de ligne sur l'organisation sérialise les changements de propriété (invariant du dernier propriétaire) ;
- les invitations ne stockent que le SHA-256 du jeton. Le jeton n'apparaît que dans l'e-mail : jamais dans les logs, l'audit ou les réponses API ;
- toutes les erreurs suivent la RFC 9457 (ajout de `BadRequestException` et du challenge `error="invalid_token"`).

### 3.4 Desktop
- Electron : `electron/src/auth/` (`auth-policy`, `loopback-server`, `token-store`, `auth-session`, `openid-provider`, `api-proxy`, `setup`) ; IPC étendu à `auth:get-session`, `auth:login`, `auth:cancel-login`, `auth:logout`, `api:request` et à l'événement `auth:session-changed`. Toutes les protections de la phase A sont conservées.
- Angular : `core/auth` (session, gardes), `core/identity` (client, API typée, `AccessStore`), `core/data/backend` (workspace réel, périmètre démo), pages `login`, `organizations/new`, `invitations/accept`, sections de paramètres Members / Roles & permissions / Invitations, déconnexion et création d'organisation dans le menu du compte, écran d'accueil lorsqu'aucune organisation n'existe.
- Environnements : `api` par défaut (build production et développement), nouvelle configuration `mock` (`ng serve --configuration mock`, utilisée par les tests unitaires).

## 4. Tests exécutés (résultats réels)

| Suite | Commande | Résultat |
| --- | --- | --- |
| Core API (unitaires, web, ArchUnit, intégration PostgreSQL + Mailpit + Keycloak) | `./mvnw clean verify` | **105 tests, 0 échec, 0 ignoré** — BUILD SUCCESS |
| Electron (politique, loopback, stockage, session, proxy, surface IPC, E2E Keycloak réel) | `AIW_KEYCLOAK_ADMIN_PASSWORD=… npm run electron:test` | **40/40** (le test E2E Keycloak est ignoré sans la variable) |
| Angular (existants + authentification/permissions) | `ng test --watch=false` | **40/40** (27 existants + 13 nouveaux) |
| Builds | `ng build` (production, development, mock), `npm run electron:build` | OK |
| Workflow CI | `actionlint 1.7.7` (via Docker) | aucune erreur |
| Audit npm (dépendances d'exécution) | `npm audit --omit=dev` | 0 vulnérabilité |

Couverture demandée par le cahier des charges :
- **Keycloak/OIDC** : découverte, PKCE (absent ou `plain` refusés), configuration du client, audience, redirections (port loopback quelconque accepté ; autre chemin, `localhost`, domaine externe refusés), grants mot de passe et implicite désactivés, code inutilisable sans le verifier, rotation (ancien refresh token refusé), révocation.
- **JWT** : jeton valide, mauvaise signature, mauvais émetteur, mauvaise audience, expiré, `nbf` futur, ID token refusé, `typ` absent, malformé, `alg=none`.
- **Base de données** : migration depuis B0 (test dédié **et** volume local réel), contraintes, adhésions multiples, expiration et réutilisation d'invitation, droits du rôle d'exécution (pas de DDL, pas d'accès à l'historique Flyway, audit en ajout seul), catalogue des permissions synchronisé avec le code.
- **Multi-tenancy** : deux organisations, refus A↔B sur chaque endpoint, ids de B utilisés via les chemins de A, membres suspendus et retirés refusés immédiatement, aucune escalade, un ADMIN ne peut pas accorder OWNER, dernier OWNER protégé, invitations non autorisées refusées.
- **Electron** : PKCE, state (callback falsifié ignoré), loopback, stockage sécurisé et repli mémoire, rafraîchissement *single-flight*, déconnexion, restrictions IPC, aucune fuite de jeton.
- **Frontend** : connexion et déconnexion, gardes, expiration de session, interface selon les permissions, périmètre démo, tests existants des mocks toujours verts.

**Test de fumée réel** (stack locale, API lancée sur le volume B0 migré en V2) : création de deux comptes Keycloak, connexion PKCE via les modules Electron compilés, `GET /me` 200, création d'organisation 201 (OWNER), invitation 201 sans jeton dans la réponse, e-mail reçu dans Mailpit, non-membre → 404, acceptation 200, accès membre 200, réutilisation du jeton → 409, chemin hors liste blanche refusé, appel après déconnexion → `unauthenticated`.

## 5. Sécurité — règles du cahier des charges

| Règle | Statut |
| --- | --- |
| Migrations déjà appliquées non modifiées | V1 intacte, V2 nouvelle |
| Volumes PostgreSQL non supprimés | Aucun `down -v` ; volume B0 migré en place |
| `.env` locaux non écrasés | `ensure-env.sh` n'ajoute que les clés manquantes |
| Aucun identifiant commité, aucun secret client dans Electron | Vérifié, et contrôlé par la CI |
| Jetons jamais dans `localStorage` | Jetons seulement dans le processus principal (mémoire et `safeStorage`) |
| Id d'organisation du client jamais pris pour argent comptant | Contrôle d'adhésion à chaque requête |
| Pas d'autorisation par e-mail seul | Identité `(iss, sub)` ; l'e-mail vérifié n'est qu'une condition supplémentaire pour accepter une invitation |
| Entités JPA jamais exposées | DTO et vues, contrôlé par ArchUnit |
| Sécurité jamais désactivée pour les tests | Les tests utilisent le décodeur de production (JWKS de test ou vrai Keycloak) |
| Aucun commit ni push | Respecté |

## 6. Limites et points ouverts

- Keycloak tourne en `start-dev` en local. La production exige `start`, TLS, un `KC_HOSTNAME` réel et un vrai SMTP (voir `infra/keycloak/README.md`).
- Le lien d'invitation est un **code à coller** dans l'application : il n'y a pas encore de deep link `app://`.
- La déconnexion ouvre la page de fin de session Keycloak dans le navigateur, pour fermer aussi la session SSO.
- Le job title du profil reste local (non modélisé côté API). Les DTO Angular sont écrits à la main d'après le contrat OpenAPI, sans génération automatique.
- Les domaines agents, projets, communication, supervision et départements restent en données de démonstration (organisation de démo fixe).
- La RLS PostgreSQL n'est pas activée ; elle est envisagée comme défense en profondeur (ADR-0009).
- Docker Desktop s'est arrêté deux fois pendant le travail et a été relancé ; aucun impact sur les données.

## 7. Démarrer en local

```bash
bash infra/docker/scripts/ensure-env.sh
docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env up -d --wait
cd services/core-api && ./mvnw spring-boot:run          # API sur le port 8081 (SERVER_PORT)
cd apps/desktop && npm run dev                           # Electron + Angular (mode api)
```

Créer un compte via « Register » sur la page de connexion (e-mail de vérification dans Mailpit, http://localhost:8025), ou depuis la console d'administration Keycloak (http://localhost:8082/admin).
