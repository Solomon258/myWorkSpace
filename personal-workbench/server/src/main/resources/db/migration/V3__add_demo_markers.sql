ALTER TABLE inbox_item ADD COLUMN is_demo INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1));
ALTER TABLE task ADD COLUMN is_demo INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1));
ALTER TABLE schedule_event ADD COLUMN is_demo INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1));
ALTER TABLE memo ADD COLUMN is_demo INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1));
ALTER TABLE activity_log ADD COLUMN is_demo INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1));

CREATE INDEX idx_inbox_demo ON inbox_item(is_demo);
CREATE INDEX idx_task_demo ON task(is_demo);
CREATE INDEX idx_event_demo ON schedule_event(is_demo);
CREATE INDEX idx_memo_demo ON memo(is_demo);
CREATE INDEX idx_log_demo ON activity_log(is_demo);
