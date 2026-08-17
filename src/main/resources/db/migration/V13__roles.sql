-- ---------------------------------------------------------------------------
-- HD-123 (Flexible roles & permissions) — roles-permissions-proposal §8.1
-- Slice S1 of 8. This migration is DARK: it creates the model and seeds the
-- built-in role templates. Nothing reads these tables for an authorization
-- decision that changes an outcome until S2/S3 move the call sites.
-- ---------------------------------------------------------------------------
--
-- WHY A TABLE FOR ROLES BUT NOT FOR PERMISSIONS (§6): the 29 permission keys
-- live in the Java enum `common.security.Permission` and nowhere else. A
-- permission without a call site is meaningless and call sites are code, so a
-- `permissions` table could only ever drift from the enum. `role_permissions`
-- stores the *grants* (which role holds which key) as VARCHAR(64) validated on
-- read by `common.security.PermissionConverter` — never a PG ENUM type
-- (CLAUDE.md: Hibernate 7 + PG ENUM = JDBC cast errors on INSERT).
--
-- BUILT-IN ROLES HAVE FIXED LITERAL UUIDs. They are product constants, not
-- data: the same seven ids exist in every install, in every environment, and in
-- `workspace/entity/BuiltInRoles.java`. That is what lets the resolver name a
-- built-in role with ZERO queries, and what makes the V14 backfill a plain
-- UPDATE instead of a correlated subquery against a lookup by name. The ids are
-- valid UUID v7s by shape (version nibble 7, variant 8) so nothing downstream
-- that assumes v7 is surprised; they are deliberately human-readable rather
-- than time-ordered, because a constant that a human must recognise in a
-- migration, in a test fixture and in a psql session is worth more than a
-- sortable one.
--
-- `built_in = (workspace_id IS NULL)` is a CHECK, not a convention: a built-in
-- is by definition shared by every workspace, and a workspace-owned role is by
-- definition not built-in. Encoding it lets the delete/edit guards in S4 test
-- one column and be right.

-- ---------------------------------------------------------------------------
-- 1) The role catalog
-- ---------------------------------------------------------------------------
CREATE TABLE roles (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         REFERENCES workspaces(id) ON DELETE CASCADE, -- NULL = built-in template
    scope        VARCHAR(20)  NOT NULL,                 -- WORKSPACE | PROJECT (Java enum, never a PG enum)
    key          VARCHAR(40)  NOT NULL,                 -- OWNER/ADMIN/MEMBER | MANAGER/MEMBER/COMMENTER/VIEWER | custom slug
    name         VARCHAR(80)  NOT NULL,                 -- display name
    description  VARCHAR(500),
    built_in     BOOLEAN      NOT NULL DEFAULT FALSE,
    position     SMALLINT     NOT NULL DEFAULT 0,
    version      BIGINT       NOT NULL DEFAULT 0,       -- @Version, optimistic locking (§11.4)
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT roles_builtin_ck   CHECK (built_in = (workspace_id IS NULL)),
    -- NULLS NOT DISTINCT so the seven built-ins (workspace_id IS NULL) collide
    -- with each other on (scope, key) — the same idiom V1 uses for the
    -- scope_workspace_id/scope_project_id taxonomy uniqueness.
    CONSTRAINT roles_scope_key_uk UNIQUE NULLS NOT DISTINCT (workspace_id, scope, key)
);

CREATE TRIGGER trg_roles_updated_at
    BEFORE UPDATE ON roles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_roles_workspace ON roles(workspace_id) WHERE workspace_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2) The grants
-- ---------------------------------------------------------------------------
-- Mapped as a JPA @ElementCollection on Role (a value collection, no id of its
-- own). `own_only = TRUE` narrows the grant to objects the actor owns (§6.4) —
-- an unrestricted grant resolves to "issue.edit", an own-only one to
-- "issue.edit:own". A role never holds both rows for one permission: the PK
-- forbids it, and holding ".any" without ".own" would be nonsense anyway.
CREATE TABLE role_permissions (
    role_id    UUID        NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission VARCHAR(64) NOT NULL,     -- validated by the Java enum, never a PG enum
    own_only   BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (role_id, permission)
);

-- ---------------------------------------------------------------------------
-- 3) The seven built-in role templates (§7)
-- ---------------------------------------------------------------------------
-- KEYS are deliberately the existing Java enum names (OWNER/ADMIN/MEMBER,
-- MANAGER/MEMBER/COMMENTER/VIEWER) so `myRole` stays wire-compatible with the
-- SPA and the V14 backfill is a join on the key. DISPLAY NAMES are the new
-- vocabulary.
INSERT INTO roles (id, workspace_id, scope, key, name, description, built_in, position) VALUES
    ('00000000-0000-7000-8000-000000000001', NULL, 'WORKSPACE', 'OWNER',  'Owner',
     'Full control of the workspace. A workspace must always keep at least one Owner.', TRUE, 0),
    ('00000000-0000-7000-8000-000000000002', NULL, 'WORKSPACE', 'ADMIN',  'Admin',
     'The same abilities as an Owner. Owner is a guardrail on assignment, not a bigger role.', TRUE, 1),
    ('00000000-0000-7000-8000-000000000003', NULL, 'WORKSPACE', 'MEMBER', 'Member',
     'Can create projects and labels. Everything else is granted per project.', TRUE, 2),
    ('00000000-0000-7000-8000-000000000011', NULL, 'PROJECT',   'MANAGER',   'Project admin',
     'Full control of this project, including its settings, members and taxonomy.', TRUE, 0),
    ('00000000-0000-7000-8000-000000000012', NULL, 'PROJECT',   'MEMBER',    'Contributor',
     'Everyday delivery work: file, edit, transition, assign, rank, comment and attach.', TRUE, 1),
    ('00000000-0000-7000-8000-000000000013', NULL, 'PROJECT',   'COMMENTER', 'Commenter',
     'Can comment and attach files, and may be assigned work, but cannot change issues.', TRUE, 2),
    ('00000000-0000-7000-8000-000000000014', NULL, 'PROJECT',   'VIEWER',    'Viewer',
     'Read-only. Grants nothing; reads are open to every workspace member in v1.', TRUE, 3);

-- --- Workspace / Owner + Admin: 8 of the 9 workspace permissions ------------
-- Identical sets, exactly as today: `isAtLeast(ADMIN)` is the only workspace
-- role test anywhere in the codebase (§7.1). The difference between the two is
-- a constraint on ASSIGNMENT (§11.1/§11.2), not a capability, and encoding it
-- as a permission would be dishonest.
--
-- THE ONE CATALOG ENTRY THEY DO NOT GET IS `project.administer.all`, and that
-- is the point. Their bypass today lives ONLY inside
-- `ScopeResolver.requireProjectCurator` — project settings, components,
-- versions, sprints. It does NOT give them `project.archive`,
-- `project.member.manage`, `project.taxonomy.manage`, `issue.delete` or
-- unrestricted `attachment.delete`: those are project-MANAGER-only and a
-- workspace Owner with no `project_members` row is refused all five right now.
-- So they hold `project.curate.all` (= exactly that curator set) instead.
-- `project.administer.all` stays in the catalog, grantable to a custom
-- "Program manager" role (§17.2) — it is simply not seeded, because seeding it
-- would silently open five gates for every Owner and Admin in every install.
-- PermissionParityTest is what caught this; keep it that way.
INSERT INTO role_permissions (role_id, permission, own_only)
SELECT r.id, p.permission, FALSE
  FROM roles r
  CROSS JOIN (VALUES
        ('workspace.edit'),
        ('workspace.member.manage'),
        ('workspace.role.manage'),
        ('workspace.taxonomy.manage'),
        ('project.create'),
        ('project.curate.all'),
        ('label.create'),
        ('label.manage')
  ) AS p(permission)
 WHERE r.id IN ('00000000-0000-7000-8000-000000000001',
                '00000000-0000-7000-8000-000000000002');

-- --- Workspace / Member (§7.1) ----------------------------------------------
-- `label.manage` OWN-ONLY is load-bearing, not a nicety: `requireEditor`
-- (rename / recolor / describe) returns early for the label's CREATOR before
-- the workspace role is consulted, so a plain member can already edit a label
-- they made. Omitting this row would take that ability away on upgrade — a
-- silent narrowing, which is the §18 failure mode.
--
-- own_only = TRUE is also exactly the right width. The archive / unarchive /
-- merge / delete sites (`requireCurator`) are ADMIN-only and become
-- `require(label.manage)` with NO ownership argument, which an own-only grant
-- can never satisfy. One key, two arities, today's behaviour on the nose.
INSERT INTO role_permissions (role_id, permission, own_only) VALUES
    ('00000000-0000-7000-8000-000000000003', 'project.create', FALSE),
    ('00000000-0000-7000-8000-000000000003', 'label.create',   FALSE),
    ('00000000-0000-7000-8000-000000000003', 'label.manage',   TRUE);

-- --- Project / MANAGER "Project admin": all 20 project permissions ----------
-- `comment.edit` is own_only even here: editing another person's words is not a
-- capability this product ships at any role (§17.3). `comment.delete` and
-- `attachment.delete` ARE unrestricted — that is moderation, and the one
-- deliberate widening in the epic (§10.3.5).
INSERT INTO role_permissions (role_id, permission, own_only)
SELECT '00000000-0000-7000-8000-000000000011', p.permission, p.own_only
  FROM (VALUES
        ('project.edit',            FALSE),
        ('project.archive',         FALSE),
        ('project.member.manage',   FALSE),
        ('project.taxonomy.manage', FALSE),
        ('issue.create',            FALSE),
        ('issue.edit',              FALSE),
        ('issue.transition',        FALSE),
        ('issue.assign',            FALSE),
        ('issue.assignable',        FALSE),
        ('issue.rank',              FALSE),
        ('issue.delete',            FALSE),
        ('comment.create',          FALSE),
        ('comment.edit',            TRUE),
        ('comment.delete',          FALSE),
        ('attachment.create',       FALSE),
        ('attachment.delete',       FALSE),
        ('sprint.manage',           FALSE),
        ('sprint.assign',           FALSE),
        ('version.manage',          FALSE),
        ('component.manage',        FALSE)
  ) AS p(permission, own_only);

-- --- Project / MEMBER "Contributor" -----------------------------------------
-- THE LOAD-BEARING ROW (§7.2). This set is, verbatim, everything a workspace
-- member can do in a project TODAY. It is what makes the V14 upgrade a no-op:
-- a workspace member with no project_members row inherits this via the
-- project-access default (§5.2), so their abilities are unchanged.
-- Do not "tidy" an entry out of it without re-reading §8.4.
INSERT INTO role_permissions (role_id, permission, own_only)
SELECT '00000000-0000-7000-8000-000000000012', p.permission, p.own_only
  FROM (VALUES
        ('issue.create',      FALSE),
        ('issue.edit',        FALSE),
        ('issue.transition',  FALSE),
        ('issue.assign',      FALSE),
        ('issue.assignable',  FALSE),
        ('issue.rank',        FALSE),
        ('comment.create',    FALSE),
        ('comment.edit',      TRUE),
        ('comment.delete',    TRUE),
        ('attachment.create', FALSE),
        ('attachment.delete', TRUE),
        ('sprint.assign',     FALSE)
  ) AS p(permission, own_only);

-- --- Project / COMMENTER ----------------------------------------------------
INSERT INTO role_permissions (role_id, permission, own_only)
SELECT '00000000-0000-7000-8000-000000000013', p.permission, p.own_only
  FROM (VALUES
        ('comment.create',    FALSE),
        ('comment.edit',      TRUE),
        ('comment.delete',    TRUE),
        ('attachment.create', FALSE),
        ('attachment.delete', TRUE),
        ('issue.assignable',  FALSE)
  ) AS p(permission, own_only);

-- --- Project / VIEWER: deliberately empty (§7.2) -----------------------------
-- No rows. "Read-only" must be expressible, and a role granting nothing is the
-- only honest way to express it. NOTE that VIEWER CHANGES MEANING here: today
-- it is the everybody-fallback returned when no project_members row exists and
-- `isAtLeast(VIEWER)` is true for everyone, so it grants everything. That is
-- precisely why V14 remaps every EXISTING explicit VIEWER row to MEMBER.
