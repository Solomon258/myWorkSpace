-- 活动日志支持备忘分类：
--   1. log_type 增加 'memo'（此前备忘流水借用 'task'，无法区分工作/生活配色）
--   2. 新增 category 列（work/life），供时间线按备忘空间着色
-- SQLite 不能修改 CHECK 约束，只能重建表；索引随 DROP TABLE 丢失，必须重建。

CREATE TABLE activity_log_v4 (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    log_type   TEXT NOT NULL,
    category   TEXT,
    content    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    is_demo    INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1)),
    CONSTRAINT chk_activity_type CHECK (log_type IN ('inbox', 'task', 'plan', 'pomo', 'praise', 'memo'))
);

INSERT INTO activity_log_v4 (id, log_type, content, created_at, is_demo)
SELECT id, log_type, content, created_at, is_demo FROM activity_log;

DROP TABLE activity_log;
ALTER TABLE activity_log_v4 RENAME TO activity_log;

CREATE INDEX idx_log_created ON activity_log(created_at);
CREATE INDEX idx_log_demo ON activity_log(is_demo);

-- 历史备忘流水归类：创建时文案带「（工作）/（生活）」后缀，可直接还原分类；
-- 修改/删除文案无后缀，仅归类型，category 置空（前端按默认备忘色渲染）。
UPDATE activity_log SET log_type='memo', category='work'
WHERE log_type='task' AND content LIKE '保存备忘%（工作）';
UPDATE activity_log SET log_type='memo', category='life'
WHERE log_type='task' AND content LIKE '保存备忘%（生活）';
UPDATE activity_log SET log_type='memo'
WHERE log_type='task' AND (content LIKE '修改备忘%' OR content LIKE '删除备忘%');
