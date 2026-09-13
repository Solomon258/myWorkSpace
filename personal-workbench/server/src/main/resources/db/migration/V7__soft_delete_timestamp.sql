-- 「回收站 / 恢复」——兑现 US-1.5「软删除，30 天内可恢复」。
--   需求原文（docs/P0-MVP需求拆解与数据模型设计.md:43）：US-1.5「我可以删除误录的条目 → 软删除，30 天内可恢复」
--   同文档 docs/产品设计说明.md:225 也写了「支持软删除（30 天内可恢复）」。
--   但此前只有 `deleted` 标记位，没有「什么时候删的」这个事实，30 天窗口无从计算；
--   界面上也没有任何恢复入口，于是「可恢复」成了一句无处可点的承诺（2026-09-11 核实）。
--
-- 为什么不复用 updated_at 当删除时间：约定太脆。`KnowledgeRepository.sync` 的
--   `UPDATE knowledge_note SET ... updated_at=? WHERE id=?` 就没带 `AND deleted=0`，
--   一旦对已删记录触发就会把「删除时间」冲掉。显式的 deleted_at 才是能查、能约束的事实。
--
-- 为什么不用重建表：这里只是**新增可空列**，不涉及修改 CHECK 约束、也不用去掉 NOT NULL，
--   所以 SQLite 的 ALTER TABLE ADD COLUMN 直接就够（V3 给 activity_log 加 is_demo 时同理）。
--   V4/V5/V6 之所以整表重建，是因为它们要改 CHECK 或 NOT NULL——本迁移没有这个需求。
--
-- CHECK 约束保证「有删除时间 ⇒ 必然处于已删除状态」，避免出现 deleted=0 却带着 deleted_at 的脏数据。
--   历史已删记录（迁移前就 deleted=1 的）deleted_at 为 NULL，不违反该约束；
--   读取时用 COALESCE(deleted_at, updated_at, created_at) 兜底，把它们也算进窗口。

ALTER TABLE inbox_item     ADD COLUMN deleted_at TEXT CHECK (deleted_at IS NULL OR deleted = 1);
ALTER TABLE task           ADD COLUMN deleted_at TEXT CHECK (deleted_at IS NULL OR deleted = 1);
ALTER TABLE schedule_event ADD COLUMN deleted_at TEXT CHECK (deleted_at IS NULL OR deleted = 1);
ALTER TABLE memo           ADD COLUMN deleted_at TEXT CHECK (deleted_at IS NULL OR deleted = 1);
ALTER TABLE knowledge_note ADD COLUMN deleted_at TEXT CHECK (deleted_at IS NULL OR deleted = 1);

-- schedule_event 的 deleted 索引在 V6 整表重建时漏了（V6 只还原了 idx_event_date / idx_event_demo），
-- 其余四张表都有 idx_*_deleted。回收站要按「已删除」过滤，这里补齐。
CREATE INDEX idx_event_deleted ON schedule_event(deleted);
