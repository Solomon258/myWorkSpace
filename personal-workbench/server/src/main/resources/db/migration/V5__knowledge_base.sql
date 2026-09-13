-- 知识库（v1.1）：
--   1. knowledge_note 增加正文、标签与同步文件名（v1.0 仅有标题与同步状态，无法支撑重试同步与列表展示）
--   2. activity_log 的 log_type CHECK 增加 'knowledge'（知识收藏/同步写入时间线）
-- SQLite 不能修改 CHECK 约束，activity_log 再次重建；索引随 DROP TABLE 丢失，必须重建。

CREATE TABLE activity_log_v5 (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    log_type   TEXT NOT NULL,
    category   TEXT,
    content    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    is_demo    INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1)),
    CONSTRAINT chk_activity_type CHECK (log_type IN ('inbox', 'task', 'plan', 'pomo', 'praise', 'memo', 'knowledge'))
);

INSERT INTO activity_log_v5 (id, log_type, category, content, created_at, is_demo)
SELECT id, log_type, category, content, created_at, is_demo FROM activity_log;

DROP TABLE activity_log;
ALTER TABLE activity_log_v5 RENAME TO activity_log;

CREATE INDEX idx_log_created ON activity_log(created_at);
CREATE INDEX idx_log_demo ON activity_log(is_demo);

ALTER TABLE knowledge_note ADD COLUMN content TEXT;
ALTER TABLE knowledge_note ADD COLUMN tags TEXT;
ALTER TABLE knowledge_note ADD COLUMN file_name TEXT;
