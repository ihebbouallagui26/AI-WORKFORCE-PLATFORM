#!/usr/bin/env bash
# Database privilege separation (Sprint B1). Idempotent: safe to run on every `docker compose up`,
# on a fresh volume and on the existing B0 volume. Never drops data.
#
# Runs as the PostgreSQL bootstrap superuser and creates:
#   - DB_MIGRATION_USERNAME : owns the application schema objects, used by Flyway only (DDL).
#   - DB_APP_USERNAME       : Core API runtime, data access only (SELECT/INSERT/UPDATE/DELETE).
#   - KEYCLOAK_DB_USERNAME  : owner of the separate Keycloak database, no access to the app database.
#
# Passwords are passed as psql variables and quoted with format(%L): no shell interpolation in SQL.
set -euo pipefail

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER (bootstrap superuser) is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${APP_DB_NAME:?APP_DB_NAME is required}"
: "${DB_MIGRATION_USERNAME:?DB_MIGRATION_USERNAME is required}"
: "${DB_MIGRATION_PASSWORD:?DB_MIGRATION_PASSWORD is required}"
: "${DB_APP_USERNAME:?DB_APP_USERNAME is required}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD is required}"
: "${KEYCLOAK_DB_NAME:?KEYCLOAK_DB_NAME is required}"
: "${KEYCLOAK_DB_USERNAME:?KEYCLOAK_DB_USERNAME is required}"
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD is required}"

psql --no-psqlrc -v ON_ERROR_STOP=1 --dbname "$APP_DB_NAME" \
  -v app_db="$APP_DB_NAME" \
  -v migrator="$DB_MIGRATION_USERNAME" -v migrator_password="$DB_MIGRATION_PASSWORD" \
  -v app="$DB_APP_USERNAME" -v app_password="$DB_APP_PASSWORD" \
  -v kc_db="$KEYCLOAK_DB_NAME" -v kc_user="$KEYCLOAK_DB_USERNAME" -v kc_password="$KEYCLOAK_DB_PASSWORD" \
  <<'SQL'
-- 1. Login roles (created once, password and attributes re-applied on every run for rotation).
SELECT format('CREATE ROLE %I LOGIN', :'migrator') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migrator') \gexec
SELECT format('CREATE ROLE %I LOGIN', :'app')      WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app') \gexec
SELECT format('CREATE ROLE %I LOGIN', :'kc_user')  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'kc_user') \gexec

SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'migrator', :'migrator_password') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'app', :'app_password') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'kc_user', :'kc_password') \gexec

-- 2. Keycloak gets its own database; it cannot connect to the application database.
SELECT format('CREATE DATABASE %I OWNER %I', :'kc_db', :'kc_user') WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'kc_db') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'kc_db') \gexec
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I', :'kc_db', :'kc_user') \gexec

-- 2b. Extensions need superuser rights, which the migration role deliberately lacks: they are
--     provisioned here (a DBA step on managed databases). V1's CREATE EXTENSION IF NOT EXISTS
--     is then a no-op when Flyway runs as the migration role on a fresh database.
CREATE EXTENSION IF NOT EXISTS vector;

-- 3. Application database: nobody connects unless explicitly allowed.
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'app_db') \gexec
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I', :'app_db', :'migrator') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'app_db', :'app') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'migrator') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'app') \gexec

-- 4. Objects created before the separation (B0, applied by the bootstrap user) move to the migrator.
SELECT format('ALTER TABLE public.%I OWNER TO %I', tablename, :'migrator')
  FROM pg_tables WHERE schemaname = 'public' AND tableowner <> :'migrator' \gexec
SELECT format('ALTER SEQUENCE public.%I OWNER TO %I', sequencename, :'migrator')
  FROM pg_sequences WHERE schemaname = 'public' AND sequenceowner <> :'migrator' \gexec

-- 5. Runtime role: data access on everything the migrator creates, never DDL.
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'migrator', :'app') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO %I', :'migrator', :'app') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.%I TO %I', tablename, :'app')
  FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history' \gexec

-- 6. The migration history is never writable by the runtime role.
SELECT format('REVOKE ALL ON TABLE public.flyway_schema_history FROM %I', :'app')
  WHERE to_regclass('public.flyway_schema_history') IS NOT NULL \gexec
SQL

echo "Database roles and privileges are up to date."
