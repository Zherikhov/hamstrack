CREATE EXTENSION IF NOT EXISTS citext;

CREATE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- users

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email CITEXT NOT NULL,
    password_hash TEXT NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    email_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_users_email ON users (email);

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- workspace

CREATE TABLE workspace (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(60) NOT NULL CHECK (slug ~ '^[a-z0-9-]+$'),
    owner_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    created_by UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    updated_by UUID REFERENCES users (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_workspace_slug ON workspace (slug);

CREATE TRIGGER trg_workspace_updated_at BEFORE UPDATE ON workspace
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- workspace_member

CREATE TABLE workspace_member (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    invited_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_workspace_member ON workspace_member (workspace_id, user_id);
CREATE INDEX idx_workspace_member_user ON workspace_member (user_id);

-- project

CREATE TABLE project (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    key VARCHAR(10) NOT NULL CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    name VARCHAR(120) NOT NULL,
    description TEXT,
    issue_seq BIGINT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    updated_by UUID REFERENCES users (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_project_workspace_key ON project (workspace_id, key) WHERE deleted_at IS NULL;
CREATE INDEX idx_project_workspace ON project (workspace_id) WHERE deleted_at IS NULL;

CREATE TRIGGER trg_project_updated_at BEFORE UPDATE ON project
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- project_member

CREATE TABLE project_member (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('LEAD', 'MEMBER', 'VIEWER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_project_member ON project_member (project_id, user_id);

-- issue_type

CREATE TABLE issue_type (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name VARCHAR(40) NOT NULL,
    position INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_issue_type_workspace_name ON issue_type (workspace_id, lower(name));
CREATE UNIQUE INDEX uq_issue_type_workspace_position ON issue_type (workspace_id, position);

CREATE TRIGGER trg_issue_type_updated_at BEFORE UPDATE ON issue_type
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- status

CREATE TABLE status (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    category VARCHAR(20) NOT NULL CHECK (category IN ('TODO', 'IN_PROGRESS', 'DONE')),
    position INTEGER NOT NULL,
    created_by UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    updated_by UUID REFERENCES users (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, project_id)
);

CREATE UNIQUE INDEX uq_status_project_position ON status (project_id, position);
CREATE UNIQUE INDEX uq_status_project_name ON status (project_id, lower(name));

CREATE TRIGGER trg_status_updated_at BEFORE UPDATE ON status
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- project_issue_type: which catalog issue types are enabled for a project, and in what order

CREATE TABLE project_issue_type (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    issue_type_id UUID NOT NULL REFERENCES issue_type (id) ON DELETE RESTRICT,
    position INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, project_id)
);

CREATE UNIQUE INDEX uq_project_issue_type ON project_issue_type (project_id, issue_type_id);
CREATE UNIQUE INDEX uq_project_issue_type_position ON project_issue_type (project_id, position);

CREATE TRIGGER trg_project_issue_type_updated_at BEFORE UPDATE ON project_issue_type
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- project_issue_type_status: which of the project's statuses are valid for a given type binding.
-- project_id is denormalized and trigger-maintained purely so both FKs below can be enforced
-- against the same project, without trusting the application to keep them in sync.

CREATE FUNCTION sync_project_issue_type_status_project_id() RETURNS TRIGGER AS $$
BEGIN
    SELECT project_id INTO NEW.project_id FROM project_issue_type WHERE id = NEW.project_issue_type_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE project_issue_type_status (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_issue_type_id UUID NOT NULL,
    project_id UUID NOT NULL,
    status_id UUID NOT NULL,
    position INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (project_issue_type_id, project_id) REFERENCES project_issue_type (id, project_id) ON DELETE CASCADE,
    FOREIGN KEY (status_id, project_id) REFERENCES status (id, project_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_pits_type_status ON project_issue_type_status (project_issue_type_id, status_id);
CREATE UNIQUE INDEX uq_pits_position ON project_issue_type_status (project_issue_type_id, position);

CREATE TRIGGER trg_pits_project_id BEFORE INSERT OR UPDATE ON project_issue_type_status
    FOR EACH ROW EXECUTE FUNCTION sync_project_issue_type_status_project_id();

-- board

CREATE TABLE board (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_board_project_default ON board (project_id) WHERE is_default;
CREATE INDEX idx_board_project ON board (project_id);

CREATE TRIGGER trg_board_updated_at BEFORE UPDATE ON board
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- board_column

CREATE TABLE board_column (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id UUID NOT NULL REFERENCES board (id) ON DELETE CASCADE,
    status_id UUID NOT NULL REFERENCES status (id) ON DELETE RESTRICT,
    position INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_board_column_status ON board_column (board_id, status_id);
CREATE UNIQUE INDEX uq_board_column_position ON board_column (board_id, position);

-- issue

CREATE TABLE issue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    number BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    project_issue_type_id UUID NOT NULL,
    status_id UUID NOT NULL,
    priority VARCHAR(20) NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    reporter_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    assignee_id UUID REFERENCES users (id) ON DELETE SET NULL,
    position NUMERIC NOT NULL,
    due_date DATE,
    updated_by UUID REFERENCES users (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,
    -- the issue's type must be enabled for its own project
    FOREIGN KEY (project_issue_type_id, project_id) REFERENCES project_issue_type (id, project_id) ON DELETE RESTRICT,
    -- the issue's status must be one of the statuses allowed for its type
    FOREIGN KEY (project_issue_type_id, status_id) REFERENCES project_issue_type_status (project_issue_type_id, status_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_issue_project_number ON issue (project_id, number);
CREATE INDEX idx_issue_board_query ON issue (project_id, status_id, position) WHERE deleted_at IS NULL;
CREATE INDEX idx_issue_assignee ON issue (assignee_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_issue_project_issue_type ON issue (project_issue_type_id) WHERE deleted_at IS NULL;

CREATE TRIGGER trg_issue_updated_at BEFORE UPDATE ON issue
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- issue_comment

CREATE TABLE issue_comment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id UUID NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    body TEXT NOT NULL,
    edited_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_comment_issue_created ON issue_comment (issue_id, created_at) WHERE deleted_at IS NULL;

CREATE TRIGGER trg_issue_comment_updated_at BEFORE UPDATE ON issue_comment
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- issue_history

CREATE TABLE issue_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id UUID NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    field VARCHAR(50) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_issue_history_issue_created ON issue_history (issue_id, created_at);
