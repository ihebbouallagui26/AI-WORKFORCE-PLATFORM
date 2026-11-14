# Sprint B3 — Employés IA et gestion des agents : rapport d'implémentation

- **Branche** : `feat/ai-agent-management-b3` (créée depuis le commit B2 `06e18b2`)
- **Date** : 14 novembre 2026
- **État Git** : modifications **non commitées et non poussées**, conformément à la consigne.
- **Périmètre** : configuration et gouvernance des agents IA. **Aucune exécution** d'agent, d'outil, de shell, de serveur MCP ni de mémoire (Phase C).

## 1. Fonctionnalités implémentées

### Agents
- Entité `agents` (UUID, organisation, nom, handle unique par organisation, avatar, titre, description, département optionnel, responsabilités, instructions système, statut `DRAFT/ACTIVE/PAUSED/ARCHIVED`, `config_version`, verrouillage optimiste).
- Création, liste paginée avec recherche et filtres (statut, département), détail, modification, changement de département, activation, pause, archivage, historique de configuration (journal d'audit filtré, sans instructions ni secrets).
- Activation conditionnée à une liaison **principale** sur une connexion **valide** ; archivage terminal, qui ferme les appartenances aux équipes ; un agent archivé n'est plus modifiable ni affectable.
- Un agent n'est ni un utilisateur Keycloak, ni un `EmployeeProfile`.
- Instructions système visibles uniquement avec `agent.manage`.

### Fournisseurs et secrets
- `ProviderConnection` propre à l'organisation (Anthropic Claude, OpenAI, Google Gemini) derrière une abstraction `ProviderAdapter`.
- `SecretStore` : adaptateur AES-256-GCM, nonce aléatoire de 96 bits, AAD = organisation + entrée, clé maître uniquement via `SECRETS_MASTER_KEY` (aucune valeur par défaut ni codée en dur, démarrage refusé sans elle).
- Identifiant **écriture seule** : jamais renvoyé (même masqué), jamais dans l'audit, les logs, ni PostgreSQL en clair.
- Ajout, remplacement, test et révocation (la révocation détruit le secret ; refusée tant qu'un agent `ACTIVE` l'utilise comme modèle principal).
- Enregistrer ne valide pas : statut `PENDING` jusqu'au test. Le test appelle l'endpoint officiel de **liste des modèles** (aucune complétion facturable).
- URL de base fixes issues de la configuration (HTTPS, ou HTTP loopback pour les tests) : aucune URL arbitraire, pas de redirection, réponse plafonnée à 1 Mo, délais d'attente, messages d'erreur fixes.
- Catalogues de modèles découverts par connexion ; aucun nom de modèle « latest » codé en dur.
- `AgentProviderBinding` : modèle, rôle principal/secours, priorité, options ; un seul principal par agent.

### Compétences, outils, mémoire
- Compétences par organisation (catégories, activation/désactivation, affectation aux agents).
- Registre d'outils global en lecture seule avec métadonnées MCP ; autorisations par agent en **moindre privilège** (aucun outil par défaut, niveau plafonné par l'outil, approbation forcée pour le risque `HIGH`). Rien n'est exécuté.
- Configuration de mémoire (désactivée par défaut, portée projet/organisation, accès, rétention). Aucun embedding ni récupération.

### Intégration organisationnelle
- Table dédiée `team_agent_members` (clés étrangères composites, pas de table polymorphe) ; plusieurs équipes par agent ; pas de doublon d'appartenance courante ; agents/équipes archivés refusés.
- Départements et équipes exposent `agentCount` séparément des effectifs humains ; l'archivage d'un département est refusé tant que des agents y sont affectés.

### Base de données, RBAC, API
- Migration **V4** additive (V1–V3 intacts) : 9 tables métier + `secret_entries`, 12 permissions `ressource.action`, rétroaffectation aux rôles intégrés uniquement (OWNER/ADMIN : 12 ; MEMBER : `agent.read`, `skill.read`), rôles personnalisés non élargis, `tool_definitions` en lecture seule pour le rôle d'exécution.
- Endpoints REST sous `/api/v1/organizations/{organizationId}` (agents, fournisseurs, liaisons, compétences, outils, mémoire, agents d'équipe), DTO, validation, pagination, RFC 9457, contrat OpenAPI synchronisé.
- Audit des changements sensibles sans secret.

### Desktop
- **Electron** : liste d'autorisation étendue (opérations B3 explicites, filtres de requête validés) ; aucune route pour lire un identifiant, exécuter un agent ou un outil ; pas de nouveau canal IPC.
- **Angular** : en mode `api`, `/agents` devient la vraie section (onglets Agents, Fournisseurs, Compétences ; détail avec cycle de vie, liaisons, compétences, outils, mémoire, équipes, historique) ; l'onglet Équipes affiche humains et agents IA séparément avec ajout/retrait ; état par organisation (`orgResource`) qui empêche l'affichage de réponses d'une autre organisation après un changement ; champ d'identifiant `password` vidé immédiatement ; actions masquées selon les permissions ; démo conservée pour le build `mock`.

## 2. Résultats vérifiés (exécutés)

| Vérification | Résultat |
| --- | --- |
| `./mvnw clean verify` (services/core-api) | **188 tests, 0 échec**, BUILD SUCCESS (144 hérités + 44 nouveaux), ArchUnit : 21 règles |
| Migration depuis une base B2 (test) et depuis une base vide | passent dans la suite |
| Migration V4 sur la base locale existante (volume non supprimé) | appliquée (`version 4`, succès), 6 outils semés, 0 secret ; API démarrée, `/health` 200, `/agents` sans jeton 401 |
| `npm run ui:test` (Angular, Vitest) | **89 tests, 0 échec** (64 hérités + 25 nouveaux) |
| `ng build` production et configuration `mock` | réussis |
| `npm run electron:build` / `electron:test` | build réussi ; **44 tests, 0 échec** |
| Contrat OpenAPI exporté vs `contracts/openapi/core-api.v1.json` | identiques |
| `git diff --check` | aucune erreur d'espaces (avertissements de fins de ligne CRLF/LF uniquement) |

Tests B3 notables : cycle de vie, propriété des connexions, chiffrement/déchiffrement et AAD, remplacement et révocation, absence de secret dans réponses/logs/audit/colonnes (capture des logs en DEBUG), validation contre de faux serveurs fournisseurs locaux (clé valide, invalide, limitée, indisponible ; en-têtes d'authentification vérifiés), règles principal/secours, compétences, outils, mémoire, équipes, rejets inter-organisations, restrictions d'archivage, RBAC et rôles personnalisés, concurrence, liste d'autorisation Electron, états vide/chargement/erreur Angular, changement d'organisation.

## 3. Limites et points non vérifiés (à lire)

- **Connectivité réelle avec Anthropic, OpenAI, Gemini non vérifiée par la suite automatisée** : seuls des faux serveurs locaux sont utilisés (aucune clé réelle en CI). Le schéma d'authentification de chaque fournisseur a été confronté aux API officielles avec des clés volontairement invalides.
- **Test Keycloak réel d'Electron** (variable `AIW_KEYCLOAK_ADMIN_PASSWORD`) et **parcours graphique complet de l'application Electron** : non relancés dans cette étape ; le smoke test live se limite à l'API (migration, santé, refus sans jeton).
- La clé API est saisie une fois dans le moteur de rendu : elle transite par sa mémoire et l'IPC vers le processus principal, sans être stockée, affichée ni renvoyée. Une saisie native côté processus principal supprimerait ce transit.
- Pas de rotation de la clé maître (`key_version` stocké pour la préparer) ; en production, brancher un adaptateur KMS/Vault.
- Portée d'outil par projet réservée (`project_id` doit être `NULL`) : les projets n'existent pas encore côté backend.
- Les identifiants de modèle sont libres (validés par motif) ; la saisie manuelle sert de repli quand le catalogue du fournisseur est indisponible.
- En mode `api`, les liens de la recherche globale vers des agents de démonstration retombent sur la liste.

## 4. Reporté à la Phase C et au-delà

Exécution et orchestration des agents (Python/FastAPI/LangGraph), exécution d'outils et de serveurs MCP, mémoire vectorielle (pgvector : embeddings, récupération), envoi de tâches, quotas et facturation, rotation des clés, portée d'outils par projet.

## 5. Documentation

Nouvelle : `docs/architecture/AI_AGENT_ARCHITECTURE.md`, `docs/architecture/PROVIDER_ARCHITECTURE.md`, `docs/database/AI_AGENT_DATA_MODEL.md`, ADR-0014 (domaine agent et ports) et ADR-0015 (SecretStore et adaptateurs). Mise à jour : index des ADR, matrice multi-tenant, authentification desktop, README du Core API (variable `SECRETS_MASTER_KEY`, endpoints, 188 tests), README desktop, contrat OpenAPI.

## 6. Exploitation locale

`infra/docker/scripts/ensure-env.sh` ajoute `SECRETS_MASTER_KEY` (généré avec `openssl rand -base64 32`) au `.env` local sans modifier les valeurs existantes ; `compose.yaml` la transmet à l'API. Aucun volume PostgreSQL n'a été supprimé, aucun `.env` écrasé, aucun secret commité.
