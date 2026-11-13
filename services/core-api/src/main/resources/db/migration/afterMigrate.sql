-- Flyway callback, executed by the migration role after every migrate run (idempotent).
-- Keeps the runtime role (${app_role}) limited to data access, whatever the default privileges.
-- Statements on specific tables are guarded so the callback also works on intermediate versions.
GRANT USAGE ON SCHEMA public TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${app_role};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${app_role};

DO $$
BEGIN
    -- The migration history is never readable or writable at runtime.
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        EXECUTE format('REVOKE ALL ON TABLE public.flyway_schema_history FROM %I', '${app_role}');
    END IF;
    -- The permission catalog is owned by migrations.
    IF to_regclass('public.permissions') IS NOT NULL THEN
        EXECUTE format('REVOKE INSERT, UPDATE, DELETE ON TABLE public.permissions FROM %I', '${app_role}');
    END IF;
    -- The audit trail is append-only at runtime.
    IF to_regclass('public.audit_events') IS NOT NULL THEN
        EXECUTE format('REVOKE UPDATE, DELETE ON TABLE public.audit_events FROM %I', '${app_role}');
    END IF;
END
$$;
