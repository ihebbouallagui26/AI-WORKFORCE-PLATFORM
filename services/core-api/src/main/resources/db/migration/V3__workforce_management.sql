-- V3 — Workforce management (Sprint B2): departments, positions, employee profiles, teams.
-- Model: docs/database/WORKFORCE_DATA_MODEL.md
--
-- Concepts kept apart: user_accounts (platform identity) / memberships (access to an organization)
-- / employee_profiles (professional record inside one organization, with or without a login) /
-- positions (job titles, NOT authorization roles) / departments / teams.
--
-- Tenant isolation in the schema: every table carries organization_id and every relation between
-- them is a composite foreign key (organization_id, id), so a row can never reference a department,
-- position, employee, team or membership of another organization.

-- ---------------------------------------------------------------- permissions
INSERT INTO permissions (code, description) VALUES
    ('employee.read',       'View employee profiles'),
    ('employee.create',     'Create employee profiles'),
    ('employee.manage',     'Update employee profiles and assignments'),
    ('employee.deactivate', 'Deactivate and reactivate employees'),
    ('department.read',     'View departments'),
    ('department.manage',   'Create, update, archive and delete departments'),
    ('position.read',       'View positions'),
    ('position.manage',     'Create, update, archive and delete positions'),
    ('team.read',           'View teams'),
    ('team.manage',         'Create and update teams, manage team members');

-- Built-in roles of organizations created before V3 receive the matching permissions
-- (new organizations get them from BuiltInRole). OWNER and ADMIN: everything; MEMBER: read only.
INSERT INTO role_permissions (role_id, organization_id, permission_code)
SELECT r.id, r.organization_id, p.code
FROM roles r
JOIN permissions p ON p.code IN (
    'employee.read', 'employee.create', 'employee.manage', 'employee.deactivate',
    'department.read', 'department.manage', 'position.read', 'position.manage',
    'team.read', 'team.manage')
WHERE r.code IN ('OWNER', 'ADMIN')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, organization_id, permission_code)
SELECT r.id, r.organization_id, p.code
FROM roles r
JOIN permissions p ON p.code IN ('employee.read', 'department.read', 'position.read', 'team.read')
WHERE r.code = 'MEMBER'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- departments
-- Hierarchical (parent), configurable per organization. The head FK is added after employee_profiles.
CREATE TABLE departments (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    name             text        NOT NULL,
    code             text        NOT NULL,
    description      text        NOT NULL DEFAULT '',
    parent_id        uuid,
    head_employee_id uuid,
    status           text        NOT NULL DEFAULT 'ACTIVE',
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_departments PRIMARY KEY (id),
    CONSTRAINT fk_departments_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uq_departments_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_departments_organization_id_code UNIQUE (organization_id, code),
    CONSTRAINT fk_departments_parent FOREIGN KEY (organization_id, parent_id)
        REFERENCES departments (organization_id, id),
    CONSTRAINT ck_departments_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_departments_name CHECK (length(name) BETWEEN 2 AND 100),
    CONSTRAINT ck_departments_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,23}$'),
    CONSTRAINT ck_departments_description CHECK (length(description) <= 500),
    CONSTRAINT ck_departments_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);
CREATE INDEX ix_departments_organization_id_parent_id ON departments (organization_id, parent_id);
CREATE INDEX ix_departments_organization_id_status ON departments (organization_id, status);

-- ---------------------------------------------------------------- positions (job titles)
CREATE TABLE positions (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    title            text        NOT NULL,
    code             text        NOT NULL,
    description      text        NOT NULL DEFAULT '',
    department_id    uuid,
    status           text        NOT NULL DEFAULT 'ACTIVE',
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_positions PRIMARY KEY (id),
    CONSTRAINT fk_positions_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uq_positions_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_positions_organization_id_code UNIQUE (organization_id, code),
    CONSTRAINT fk_positions_department FOREIGN KEY (organization_id, department_id)
        REFERENCES departments (organization_id, id),
    CONSTRAINT ck_positions_title CHECK (length(title) BETWEEN 2 AND 100),
    CONSTRAINT ck_positions_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,23}$'),
    CONSTRAINT ck_positions_description CHECK (length(description) <= 500),
    CONSTRAINT ck_positions_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);
CREATE UNIQUE INDEX uq_positions_organization_id_lower_title ON positions (organization_id, lower(title));
CREATE INDEX ix_positions_organization_id_department_id ON positions (organization_id, department_id);

-- ---------------------------------------------------------------- employee profiles
-- Professional record of a HUMAN employee in one organization. membership_id is optional: an
-- employee may have no platform login, and a suspended membership never touches this record.
-- No credentials, no Keycloak data, no salary / birth date / address.
CREATE TABLE employee_profiles (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    membership_id       uuid,
    employee_number     text,
    display_name        text        NOT NULL,
    professional_email  text,
    position_id         uuid,
    department_id       uuid,
    manager_id          uuid,
    status              text        NOT NULL DEFAULT 'ACTIVE',
    start_date          date        NOT NULL,
    end_date            date,
    description         text        NOT NULL DEFAULT '',
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_employee_profiles PRIMARY KEY (id),
    CONSTRAINT fk_employee_profiles_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uq_employee_profiles_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT fk_employee_profiles_membership FOREIGN KEY (organization_id, membership_id)
        REFERENCES memberships (organization_id, id),
    CONSTRAINT fk_employee_profiles_position FOREIGN KEY (organization_id, position_id)
        REFERENCES positions (organization_id, id),
    CONSTRAINT fk_employee_profiles_department FOREIGN KEY (organization_id, department_id)
        REFERENCES departments (organization_id, id),
    CONSTRAINT fk_employee_profiles_manager FOREIGN KEY (organization_id, manager_id)
        REFERENCES employee_profiles (organization_id, id),
    CONSTRAINT ck_employee_profiles_not_own_manager CHECK (manager_id IS NULL OR manager_id <> id),
    CONSTRAINT ck_employee_profiles_status CHECK (status IN ('ACTIVE', 'ON_LEAVE', 'INACTIVE')),
    CONSTRAINT ck_employee_profiles_display_name CHECK (length(display_name) BETWEEN 1 AND 200),
    CONSTRAINT ck_employee_profiles_employee_number CHECK (employee_number IS NULL OR length(employee_number) BETWEEN 1 AND 50),
    CONSTRAINT ck_employee_profiles_professional_email CHECK (
        professional_email IS NULL OR (professional_email = lower(professional_email) AND length(professional_email) BETWEEN 3 AND 254)),
    CONSTRAINT ck_employee_profiles_dates CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT ck_employee_profiles_description CHECK (length(description) <= 1000)
);
-- One employee profile per membership, one employee number per organization.
CREATE UNIQUE INDEX uq_employee_profiles_organization_id_membership_id
    ON employee_profiles (organization_id, membership_id) WHERE membership_id IS NOT NULL;
CREATE UNIQUE INDEX uq_employee_profiles_organization_id_employee_number
    ON employee_profiles (organization_id, employee_number) WHERE employee_number IS NOT NULL;
CREATE INDEX ix_employee_profiles_organization_id_status ON employee_profiles (organization_id, status);
CREATE INDEX ix_employee_profiles_organization_id_department_id ON employee_profiles (organization_id, department_id);
CREATE INDEX ix_employee_profiles_organization_id_position_id ON employee_profiles (organization_id, position_id);
CREATE INDEX ix_employee_profiles_organization_id_manager_id ON employee_profiles (organization_id, manager_id);
CREATE INDEX ix_employee_profiles_organization_id_lower_name ON employee_profiles (organization_id, lower(display_name));

-- Department head: an employee of the same organization.
ALTER TABLE departments ADD CONSTRAINT fk_departments_head FOREIGN KEY (organization_id, head_employee_id)
    REFERENCES employee_profiles (organization_id, id);
CREATE INDEX ix_departments_organization_id_head_employee_id ON departments (organization_id, head_employee_id);

-- ---------------------------------------------------------------- teams
CREATE TABLE teams (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    name                text        NOT NULL,
    description         text        NOT NULL DEFAULT '',
    department_id       uuid,
    leader_employee_id  uuid,
    status              text        NOT NULL DEFAULT 'ACTIVE',
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_teams PRIMARY KEY (id),
    CONSTRAINT fk_teams_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uq_teams_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT fk_teams_department FOREIGN KEY (organization_id, department_id)
        REFERENCES departments (organization_id, id),
    CONSTRAINT fk_teams_leader FOREIGN KEY (organization_id, leader_employee_id)
        REFERENCES employee_profiles (organization_id, id),
    CONSTRAINT ck_teams_name CHECK (length(name) BETWEEN 2 AND 100),
    CONSTRAINT ck_teams_description CHECK (length(description) <= 500),
    CONSTRAINT ck_teams_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);
CREATE UNIQUE INDEX uq_teams_organization_id_lower_name ON teams (organization_id, lower(name));
CREATE INDEX ix_teams_organization_id_department_id ON teams (organization_id, department_id);
CREATE INDEX ix_teams_organization_id_leader_employee_id ON teams (organization_id, leader_employee_id);

-- Human members of a team, with history (left_at). B3 will add AI agents through a SEPARATE table
-- (team_agent_members) with its own composite foreign key: no polymorphic member_type/member_id.
CREATE TABLE team_members (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    team_id          uuid        NOT NULL,
    employee_id      uuid        NOT NULL,
    joined_at        timestamptz NOT NULL,
    left_at          timestamptz,
    added_by         uuid,
    CONSTRAINT pk_team_members PRIMARY KEY (id),
    CONSTRAINT fk_team_members_team FOREIGN KEY (organization_id, team_id)
        REFERENCES teams (organization_id, id),
    CONSTRAINT fk_team_members_employee FOREIGN KEY (organization_id, employee_id)
        REFERENCES employee_profiles (organization_id, id),
    CONSTRAINT fk_team_members_added_by FOREIGN KEY (added_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_team_members_dates CHECK (left_at IS NULL OR left_at >= joined_at)
);
-- An employee is a current member of a team at most once; former memberships stay as history.
CREATE UNIQUE INDEX uq_team_members_current ON team_members (team_id, employee_id) WHERE left_at IS NULL;
CREATE INDEX ix_team_members_organization_id_team_id ON team_members (organization_id, team_id);
CREATE INDEX ix_team_members_organization_id_employee_id ON team_members (organization_id, employee_id);
