CREATE TABLE inbox_item (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    raw_content    TEXT    NOT NULL,
    content_type   TEXT    NOT NULL DEFAULT 'text',
    source         TEXT    NOT NULL DEFAULT 'web',
    audio_path     TEXT,
    status         TEXT    NOT NULL DEFAULT 'pending',
    ai_category    TEXT,
    ai_confidence  REAL,
    ai_payload     TEXT,
    processed_at   TEXT,
    deleted        INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT    NOT NULL,
    updated_at     TEXT,
    CONSTRAINT chk_inbox_content_type CHECK (content_type IN ('text', 'voice')),
    CONSTRAINT chk_inbox_source CHECK (source IN ('web', 'wecom')),
    CONSTRAINT chk_inbox_status CHECK (status IN ('pending', 'processed', 'failed', 'archived')),
    CONSTRAINT chk_inbox_ai_category CHECK (ai_category IS NULL OR ai_category IN ('task', 'schedule', 'memo', 'knowledge')),
    CONSTRAINT chk_inbox_ai_confidence CHECK (ai_confidence IS NULL OR (ai_confidence >= 0 AND ai_confidence <= 1))
);

CREATE TABLE task (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    title           TEXT    NOT NULL,
    description     TEXT,
    priority        TEXT    NOT NULL DEFAULT 'P2',
    status          TEXT    NOT NULL DEFAULT 'todo',
    due_date        TEXT,
    is_deep_work    INTEGER NOT NULL DEFAULT 0,
    is_blocking     INTEGER NOT NULL DEFAULT 0,
    note            TEXT,
    postponed       INTEGER NOT NULL DEFAULT 0,
    source_inbox_id INTEGER,
    completed_at    TEXT,
    deleted         INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT,
    CONSTRAINT chk_task_priority CHECK (priority IN ('P0', 'P1', 'P2', 'P3')),
    CONSTRAINT chk_task_status CHECK (status IN ('todo', 'doing', 'done', 'canceled')),
    CONSTRAINT chk_task_deep CHECK (is_deep_work IN (0, 1)),
    CONSTRAINT chk_task_blocking CHECK (is_blocking IN (0, 1)),
    CONSTRAINT chk_task_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT fk_task_inbox FOREIGN KEY (source_inbox_id) REFERENCES inbox_item(id)
);

CREATE TABLE schedule_event (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    title           TEXT    NOT NULL,
    event_type      TEXT    NOT NULL DEFAULT 'other',
    event_date      TEXT    NOT NULL,
    start_time      TEXT,
    end_time        TEXT,
    source_inbox_id INTEGER,
    deleted         INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT,
    CONSTRAINT chk_event_type CHECK (event_type IN ('meeting', 'deep_block', 'other')),
    CONSTRAINT chk_event_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT fk_event_inbox FOREIGN KEY (source_inbox_id) REFERENCES inbox_item(id)
);

CREATE TABLE memo (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    title           TEXT    NOT NULL,
    content         TEXT,
    url             TEXT,
    tags            TEXT,
    grp             TEXT    NOT NULL DEFAULT 'work',
    pinned          INTEGER NOT NULL DEFAULT 0,
    status          TEXT    NOT NULL DEFAULT 'active',
    source_inbox_id INTEGER,
    deleted         INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT,
    CONSTRAINT chk_memo_group CHECK (grp IN ('work', 'life')),
    CONSTRAINT chk_memo_pinned CHECK (pinned IN (0, 1)),
    CONSTRAINT chk_memo_status CHECK (status IN ('active', 'archived')),
    CONSTRAINT chk_memo_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT fk_memo_inbox FOREIGN KEY (source_inbox_id) REFERENCES inbox_item(id)
);

CREATE TABLE pomodoro (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id    INTEGER,
    minutes    INTEGER NOT NULL DEFAULT 25,
    ended_at   TEXT    NOT NULL,
    created_at TEXT    NOT NULL,
    CONSTRAINT chk_pomodoro_minutes CHECK (minutes > 0),
    CONSTRAINT fk_pomodoro_task FOREIGN KEY (task_id) REFERENCES task(id)
);

CREATE TABLE activity_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    log_type   TEXT NOT NULL,
    content    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    CONSTRAINT chk_activity_type CHECK (log_type IN ('inbox', 'task', 'plan', 'pomo', 'praise'))
);

CREATE TABLE knowledge_note (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    title           TEXT NOT NULL,
    vault_path      TEXT,
    sync_status     TEXT NOT NULL DEFAULT 'pending',
    source_inbox_id INTEGER,
    deleted         INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT,
    CONSTRAINT chk_knowledge_status CHECK (sync_status IN ('pending', 'synced', 'failed')),
    CONSTRAINT chk_knowledge_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT fk_knowledge_inbox FOREIGN KEY (source_inbox_id) REFERENCES inbox_item(id)
);

CREATE TABLE daily_plan (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_date       TEXT NOT NULL UNIQUE,
    theme           TEXT,
    completion_rate REAL,
    closed          INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL,
    updated_at      TEXT,
    CONSTRAINT chk_plan_rate CHECK (completion_rate IS NULL OR (completion_rate >= 0 AND completion_rate <= 1)),
    CONSTRAINT chk_plan_closed CHECK (closed IN (0, 1))
);

CREATE TABLE daily_plan_item (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id         INTEGER NOT NULL,
    task_id         INTEGER NOT NULL,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    ai_reason       TEXT,
    is_ai_suggested INTEGER NOT NULL DEFAULT 0,
    done            INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_plan_item_suggested CHECK (is_ai_suggested IN (0, 1)),
    CONSTRAINT chk_plan_item_done CHECK (done IN (0, 1)),
    CONSTRAINT fk_plan_item_plan FOREIGN KEY (plan_id) REFERENCES daily_plan(id),
    CONSTRAINT fk_plan_item_task FOREIGN KEY (task_id) REFERENCES task(id)
);

CREATE TABLE wechat_msg_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    msg_id        TEXT NOT NULL UNIQUE,
    msg_type      TEXT,
    content       TEXT,
    handle_status TEXT NOT NULL DEFAULT 'received',
    inbox_item_id INTEGER,
    created_at    TEXT NOT NULL,
    updated_at    TEXT,
    CONSTRAINT fk_wechat_inbox FOREIGN KEY (inbox_item_id) REFERENCES inbox_item(id)
);

CREATE TABLE ai_job (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    job_type          TEXT NOT NULL,
    ref_id            TEXT,
    status            TEXT NOT NULL DEFAULT 'running',
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    duration_ms       INTEGER,
    error_msg         TEXT,
    created_at        TEXT    NOT NULL,
    updated_at        TEXT,
    CONSTRAINT chk_ai_job_status CHECK (status IN ('pending', 'running', 'success', 'failed'))
);

CREATE TABLE app_config (
    config_key   TEXT PRIMARY KEY,
    config_value TEXT,
    updated_at   TEXT
);

CREATE INDEX idx_inbox_status ON inbox_item(status, created_at);
CREATE INDEX idx_inbox_source ON inbox_item(source);
CREATE INDEX idx_task_status_priority ON task(status, priority);
CREATE INDEX idx_task_due ON task(due_date);
CREATE INDEX idx_event_date ON schedule_event(event_date, start_time);
CREATE INDEX idx_memo_status ON memo(status, pinned DESC);
CREATE INDEX idx_memo_grp ON memo(grp, status);
CREATE INDEX idx_pomo_ended ON pomodoro(ended_at);
CREATE INDEX idx_log_created ON activity_log(created_at);
CREATE INDEX idx_plan_item ON daily_plan_item(plan_id, sort_order);
