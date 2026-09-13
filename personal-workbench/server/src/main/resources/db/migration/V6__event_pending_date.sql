-- 待定时间区（US-4.2）：
--   需求原文（docs/P0-MVP需求拆解与数据模型设计.md:69）：
--     「AI 整理出的日程确认后进入日程表；时间解析失败的进入『待定时间』区，人工补全」
--   同文档第 164 行也把日程时间标为「可空（时间待定）」。
--   但 V1 建表时把 event_date 写成了 NOT NULL，导致「日期还没定下来」的日程根本存不进去：
--   规则整理器一旦命中「会议/约/对齐/评审会/站会」就会给出 schedule@0.84（高置信、不受
--   批量确认保护影响），却常常抽不出日期；用户点「确认生成」只会撞上「生成日程必须填写日期」，
--   在界面上没有任何出口。本次把 event_date 放开为可空，NULL 即「待定时间」。
--   SQLite 不支持直接去掉 NOT NULL，只能重建表；索引随 DROP TABLE 丢失，必须重建。

CREATE TABLE schedule_event_v6 (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    title           TEXT    NOT NULL,
    event_type      TEXT    NOT NULL DEFAULT 'other',
    event_date      TEXT,
    start_time      TEXT,
    end_time        TEXT,
    source_inbox_id INTEGER,
    deleted         INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT,
    is_demo         INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1)),
    CONSTRAINT chk_event_type CHECK (event_type IN ('meeting', 'deep_block', 'other')),
    CONSTRAINT chk_event_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT fk_event_inbox FOREIGN KEY (source_inbox_id) REFERENCES inbox_item(id)
);

INSERT INTO schedule_event_v6 (id, title, event_type, event_date, start_time, end_time,
                               source_inbox_id, deleted, created_at, updated_at, is_demo)
SELECT id, title, event_type, event_date, start_time, end_time,
       source_inbox_id, deleted, created_at, updated_at, is_demo
FROM schedule_event;

DROP TABLE schedule_event;
ALTER TABLE schedule_event_v6 RENAME TO schedule_event;

CREATE INDEX idx_event_date ON schedule_event(event_date, start_time);
CREATE INDEX idx_event_demo ON schedule_event(is_demo);
