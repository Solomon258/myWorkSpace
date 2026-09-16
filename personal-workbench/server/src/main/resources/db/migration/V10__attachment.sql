-- 图片上传与解析：附件表 + 收录来源标记
--
-- 设计要点（见 docs/图片上传与解析功能设计.md）：
-- 1. 附件用「独立表 + 多态外键」而不是给四个实体各加一列 —— 上传 / 预览 / 删除 /
--    下载 / 引用计数只有一份实现，实体表也保持干净。
-- 2. owner_type / owner_id 允许同时为 NULL，语义是**明确的「待绑定」状态**，
--    不是垃圾数据：用户传了图还没提交表单时就是它。两者必须同时有或同时无。
-- 3. 本迁移只加列与建新表，没有改动任何既有 CHECK / NOT NULL 约束，
--    因此不需要「建新表→搬数据→DROP→RENAME→重建索引」那一套（见 V9 的判据）。

-- ① 附件表
CREATE TABLE attachment (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    -- task / schedule_event / memo / knowledge_note / inbox_item；NULL = 待绑定
    owner_type    TEXT,
    owner_id      INTEGER,
    -- 落盘文件名 = sha256 前 16 位 + 白名单后缀。**永不使用用户原始文件名拼路径**，
    -- 否则文件名里的 / .. 与中文会变成目录穿越。original_name 只用于展示与报错。
    file_name     TEXT NOT NULL,
    original_name TEXT,
    mime_type     TEXT NOT NULL,
    byte_size     INTEGER NOT NULL,
    width         INTEGER,
    height        INTEGER,
    sha256        TEXT NOT NULL,
    sort_order    INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL,
    updated_at    TEXT,
    deleted       INTEGER NOT NULL DEFAULT 0,
    deleted_at    TEXT,
    is_demo       INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_attachment_owner_type CHECK (
        owner_type IS NULL OR owner_type IN
        ('task', 'schedule_event', 'memo', 'knowledge_note', 'inbox_item')
    ),
    CONSTRAINT chk_attachment_owner_pair CHECK (
        (owner_type IS NULL AND owner_id IS NULL)
        OR (owner_type IS NOT NULL AND owner_id IS NOT NULL)
    ),
    CONSTRAINT chk_attachment_deleted_at CHECK (deleted_at IS NULL OR deleted = 1)
);

-- 按实体查附件（详情面板 / 编辑面板）
CREATE INDEX idx_attachment_owner ON attachment(owner_type, owner_id) WHERE deleted = 0;
-- 同内容去重：命中已有 sha256 时复用行，不重复占盘
CREATE INDEX idx_attachment_sha256 ON attachment(sha256);
-- 孤儿清理：owner_id 为空且超过保留期的记录
CREATE INDEX idx_attachment_orphan ON attachment(created_at) WHERE owner_id IS NULL AND deleted = 0;

-- ② 收录来源标记
-- 'image' = 本条目来自图片解析。这类条目**不参与「一键确认高置信度」批量落库**：
-- 视觉模型对结构清晰的截图置信度常超过 0.70，若被自动确认，用户还没看到解析结果
-- 任务就已经建好了 —— 而图片解析恰恰是最需要人工过目的场景。
ALTER TABLE inbox_item ADD COLUMN origin TEXT NOT NULL DEFAULT 'text';

-- ③ 解析进度（只对 origin='image' 有意义）
-- 与 status 的区别：status 是收录条目的生命周期（pending/processed/failed/archived），
-- parse_status 只描述「视觉解析」这一步。解析失败时 status='pending' + parse_status='failed'，
-- 条目仍留在收集箱等用户手动补文字。
ALTER TABLE inbox_item ADD COLUMN parse_status TEXT;
ALTER TABLE inbox_item ADD COLUMN parse_error  TEXT;

-- raw_content 是否被 4000 字上限截断过。
-- 视觉模型会把整张图的文字吐出来，塞进 raw_content 前必须裁到 4000 字（那是 DTO 与
-- requireRaw 双重把关的既有硬上限）。裁了就得**留痕**：否则用户看到「解析结果只有半截」
-- 会以为模型漏读了，而实际上是服务端悄悄裁的。
ALTER TABLE inbox_item ADD COLUMN raw_truncated INTEGER NOT NULL DEFAULT 0;

-- 解析用的图片附件 id（origin='image' 时才有）。
-- 冗余在收录条目上而不是每次去 attachment 表反查，原因是「重试解析」要能在
-- 附件被挪走之后仍然找到原图；同时也让解析任务不必先扫全表。不加外键约束：
-- 附件是软删的，外键会让「删附件」这条路径平白多一个失败点。
ALTER TABLE inbox_item ADD COLUMN source_attachment_id INTEGER;

-- ④ 视觉模型配置：同一个 base_url 下文本模型与视觉模型通常不同名，所以要单独一项
INSERT OR IGNORE INTO app_config(config_key, config_value) VALUES
    ('ai.vision_model',   ''),
    ('ai.vision_enabled', 'false');
