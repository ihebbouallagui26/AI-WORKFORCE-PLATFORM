-- V2 — Identity, organizations, memberships, RBAC, invitations and audit (Sprint B1).
-- Conventions: docs/database/DATABASE_CONVENTIONS.md · Model: docs/database/IDENTITY_AND_ORGANIZATION_SCHEMA.md
--
-- Tenant isolation in the schema:
--   * every organization-owned table carries organization_id NOT NULL;
--   * relations between tenant tables use composite foreign keys (organization_id, id), so a row
--     can never reference a role, membership or invitation of another organization.

-- ---------------------------------------------------------------- users and external identities
CREATE TABLE user_accounts (
    id               uuid        NOT NULL,
    email            text        NOT NULL,
    email_verified   boolean     NOT NULL DEFAULT false,
    display_name     text        NOT NULL,
    status           text        NOT NULL DEFAULT 'ACTIVE',
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_accounts PRIMARY KEY (id),
    CONSTRAINT ck_user_accounts_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_user_accounts_display_name CHECK (length(display_name) BETWEEN 1 AND 200)
);
-- E-mail is profile data, never an identity key (several accounts may share an unverified e-mail).
CREATE INDEX ix_user_accounts_email ON user_accounts (lower(email));

-- One external identity = one (issuer, subject) pair from an OpenID Connect provider.
CREATE TABLE external_identities (
    id             uuid        NOT NULL,
    user_id        uuid        NOT NULL,
    issuer         text        NOT NULL,
    subject        text        NOT NULL,
    created_at     timestamptz NOT NULL,
    last_login_at  timestamptz NOT NULL,
    CONSTRAINT pk_external_identities PRIMARY KEY (id),
    CONSTRAINT fk_external_identities_user_id FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT uq_external_identities_issuer_subject UNIQUE (issuer, subject)
);
CREATE INDEX ix_external_identities_user_id ON external_identities (user_id);

-- ---------------------------------------------------------------- organizations
CREATE TABLE organizations (
    id           uuid        NOT NULL,
    name         text        NOT NULL,
    description  text        NOT NULL DEFAULT '',
    timezone     text        NOT NULL DEFAULT 'UTC',
    created_by   uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_organizations PRIMARY KEY (id),
    CONSTRAINT fk_organizations_created_by FOREIGN KEY (created_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_organizations_name CHECK (length(name) BETWEEN 2 AND 100),
    CONSTRAINT ck_organizations_description CHECK (length(description) <= 500)
);

-- ---------------------------------------------------------------- memberships
CREATE TABLE memberships (
    id                 uuid        NOT NULL,
    organization_id    uuid        NOT NULL,
    user_id            uuid        NOT NULL,
    status             text        NOT NULL,
    joined_at          timestamptz NOT NULL,
    status_changed_at  timestamptz NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_memberships PRIMARY KEY (id),
    CONSTRAINT fk_memberships_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_memberships_user_id FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT uq_memberships_organization_id_user_id UNIQUE (organization_id, user_id),
    CONSTRAINT uq_memberships_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT ck_memberships_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REMOVED'))
);
CREATE INDEX ix_memberships_user_id_status ON memberships (user_id, status);
CREATE INDEX ix_memberships_organization_id_status ON memberships (organization_id, status);

-- ---------------------------------------------------------------- permissions and roles
-- Global catalog: the codes are referenced by the application (Permission enum).
CREATE TABLE permissions (
    code         text NOT NULL,
    description  text NOT NULL,
    CONSTRAINT pk_permissions PRIMARY KEY (code)
);

INSERT INTO permissions (code, description) VALUES
    ('organization.read',   'View the organization profile'),
    ('organization.update', 'Update the organization profile'),
    ('member.read',         'View members and their roles'),
    ('member.manage',       'Suspend, reactivate and remove members'),
    ('member.roles.assign', 'Assign and unassign roles to members'),
    ('role.read',           'View roles and their permissions'),
    ('role.manage',         'Create, update and delete custom roles'),
    ('invitation.read',     'View pending invitations'),
    ('invitation.create',   'Invite people to the organization'),
    ('invitation.revoke',   'Revoke pending invitations'),
    ('audit.read',          'View the organization audit trail'),
    ('ownership.manage',    'Grant, revoke and manage the OWNER role');

-- Roles are organization-scoped. Built-in roles (OWNER, ADMIN, MEMBER) are created with each
-- organization and cannot be changed or deleted; custom roles have code NULL.
CREATE TABLE roles (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    code             text,
    name             text        NOT NULL,
    description      text        NOT NULL DEFAULT '',
    built_in         boolean     NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT fk_roles_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uq_roles_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_roles_organization_id_code UNIQUE (organization_id, code),
    CONSTRAINT ck_roles_code CHECK (code IS NULL OR code IN ('OWNER', 'ADMIN', 'MEMBER')),
    CONSTRAINT ck_roles_built_in_code CHECK (built_in = (code IS NOT NULL)),
    CONSTRAINT ck_roles_name CHECK (length(name) BETWEEN 2 AND 60),
    CONSTRAINT ck_roles_description CHECK (length(description) <= 300)
);
CREATE UNIQUE INDEX uq_roles_organization_id_lower_name ON roles (organization_id, lower(name));

CREATE TABLE role_permissions (
    role_id          uuid NOT NULL,
    organization_id  uuid NOT NULL,
    permission_code  text NOT NULL,
    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (organization_id, role_id)
        REFERENCES roles (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission_code FOREIGN KEY (permission_code) REFERENCES permissions (code)
);

CREATE TABLE membership_roles (
    membership_id    uuid        NOT NULL,
    role_id          uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    assigned_at      timestamptz NOT NULL,
    assigned_by      uuid,
    CONSTRAINT pk_membership_roles PRIMARY KEY (membership_id, role_id),
    CONSTRAINT fk_membership_roles_membership FOREIGN KEY (organization_id, membership_id)
        REFERENCES memberships (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_roles_role FOREIGN KEY (organization_id, role_id)
        REFERENCES roles (organization_id, id),
    CONSTRAINT fk_membership_roles_assigned_by FOREIGN KEY (assigned_by) REFERENCES user_accounts (id)
);
CREATE INDEX ix_membership_roles_organization_id_role_id ON membership_roles (organization_id, role_id);

-- ---------------------------------------------------------------- invitations
CREATE TABLE organization_invitations (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    email            text        NOT NULL,
    role_id          uuid        NOT NULL,
    -- SHA-256 of the high-entropy token; the token itself is never stored.
    token_hash       text        NOT NULL,
    status           text        NOT NULL,
    expires_at       timestamptz NOT NULL,
    invited_by       uuid        NOT NULL,
    accepted_by      uuid,
    created_at       timestamptz NOT NULL,
    accepted_at      timestamptz,
    revoked_at       timestamptz,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_organization_invitations PRIMARY KEY (id),
    CONSTRAINT fk_organization_invitations_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_organization_invitations_role FOREIGN KEY (organization_id, role_id)
        REFERENCES roles (organization_id, id),
    CONSTRAINT fk_organization_invitations_invited_by FOREIGN KEY (invited_by) REFERENCES user_accounts (id),
    CONSTRAINT fk_organization_invitations_accepted_by FOREIGN KEY (accepted_by) REFERENCES user_accounts (id),
    CONSTRAINT uq_organization_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_organization_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    CONSTRAINT ck_organization_invitations_email CHECK (email = lower(email) AND length(email) BETWEEN 3 AND 254)
);
-- At most one pending invitation per e-mail and organization.
CREATE UNIQUE INDEX uq_organization_invitations_pending_email
    ON organization_invitations (organization_id, email) WHERE status = 'PENDING';
CREATE INDEX ix_organization_invitations_organization_id_status
    ON organization_invitations (organization_id, status, created_at DESC);

-- ---------------------------------------------------------------- audit trail
-- Append-only: the runtime role is denied UPDATE and DELETE (see afterMigrate.sql).
CREATE TABLE audit_events (
    id               uuid        NOT NULL,
    organization_id  uuid,
    actor_user_id    uuid,
    action           text        NOT NULL,
    target_type      text,
    target_id        uuid,
    -- JSON object with non-sensitive context (never tokens or secrets).
    details          text        NOT NULL DEFAULT '{}',
    request_id       text,
    occurred_at      timestamptz NOT NULL,
    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    CONSTRAINT fk_audit_events_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_audit_events_actor_user_id FOREIGN KEY (actor_user_id) REFERENCES user_accounts (id)
);
CREATE INDEX ix_audit_events_organization_id_occurred_at ON audit_events (organization_id, occurred_at DESC);
