CREATE TABLE IF NOT EXISTS operations (
    seq BIGSERIAL PRIMARY KEY,
    workspace_id TEXT NOT NULL,
    op_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    clock BIGINT NOT NULL CHECK (clock > 0 AND clock <= 9007199254740991),
    field TEXT NOT NULL CHECK (field IN ('title', 'completed', 'deleted')),
    value TEXT NOT NULL,
    UNIQUE (workspace_id, op_id),
    UNIQUE (workspace_id, actor_id, clock)
);
CREATE INDEX IF NOT EXISTS operations_workspace_seq ON operations(workspace_id, seq);

-- Small, additive migration from the original three-field task board.
ALTER TABLE operations ADD COLUMN IF NOT EXISTS created_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE operations ADD COLUMN IF NOT EXISTS author TEXT NOT NULL DEFAULT '';
ALTER TABLE operations DROP CONSTRAINT IF EXISTS operations_field_check;
ALTER TABLE operations DROP CONSTRAINT IF EXISTS operations_field_v2_check;
ALTER TABLE operations ADD CONSTRAINT operations_field_v2_check CHECK (
    field IN ('title','completed','deleted','description','project','priority','dueDate','assignee','notes','gps','createdBy','status')
    OR field ~ '^check:[a-zA-Z0-9_-]{1,80}:(label|result|note|deleted)$'
    OR field ~ '^photo:[a-f0-9]{64}$'
);

CREATE TABLE IF NOT EXISTS app_users (
    id TEXT PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    password_hash BYTEA NOT NULL
);
CREATE TABLE IF NOT EXISTS teams (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    invite_code TEXT UNIQUE NOT NULL
);
CREATE TABLE IF NOT EXISTS memberships (
    team_id TEXT NOT NULL REFERENCES teams(id),
    user_id TEXT NOT NULL REFERENCES app_users(id),
    role TEXT NOT NULL CHECK (role IN ('owner','editor','viewer')),
    PRIMARY KEY(team_id,user_id)
);

