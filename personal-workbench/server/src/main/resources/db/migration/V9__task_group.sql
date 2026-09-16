-- 任务的工作 / 生活分组（用户 2026-09-14）：
--   「任务，需要能筛选工作和生活，默认选择工作。」
--
-- 为什么跟备忘共用一套 `grp` 语义：备忘页早就有「空间 = 工作 / 生活」，
--   界面叫法、取值（work / life）、配色都已经是既成约定。
--   任务这里另起一套（比如 task.category）只会让两个页签对同一件事用两套词——
--   用户在备忘里看到「工作/生活」，切到任务又变成别的说法，得不偿失。
--
-- 为什么默认值是 'work' 而不是 NULL：
--   用户的诉求是「默认选择工作」，也就是打开任务页看到的第一屏就是工作事项。
--   若历史数据留 NULL，默认筛选「工作」时它们既不属于工作也不属于生活，
--   会**集体从默认视图里消失**——用户攒了几百条任务，升级完一打开发现是空的，
--   只会以为数据丢了。所以这里给 NOT NULL DEFAULT 'work'：
--   新增的任务由 Service 层判定（关键词命中判 life，否则 work），
--   存量任务在下面的 UPDATE 里按同一套关键词规则回填，回填不到的留在 'work'。
--
-- 为什么不用重建表：`grp` 只是**新增一列**，不涉及修改 CHECK 约束、也不用去掉 NOT NULL，
--   ALTER TABLE ADD COLUMN 就够（V3 / V7 / V8 同理；V4/V5/V6 整表重建是因为要改 CHECK 或 NOT NULL）。
--   代价是 SQLite 没法给新列加 CHECK —— 也就是说这一列的取值由 `TaskService.normalizeGroup()`
--   在**服务层**守（非法值报 400/1002，文案带中文原因），而不是靠数据库兜底。
--
-- 与 memo 的一致性：`memo.grp` 当年是在 V1 建表时就带 CHECK (grp IN ('work','life')) 的，
--   任务这一列没有 —— 这是两处已知的不对称，Service 层校验是唯一的约束点，别绕过它直接写库。
ALTER TABLE task ADD COLUMN grp TEXT NOT NULL DEFAULT 'work';

-- 存量任务按关键词回填（规则与 `MemoService.WORK_PATTERN` 保持一致）。
-- 顺序很重要：先无条件把含「工作关键词」的标成 work（其实默认已是 work，这条是为了让
-- 「默认值 + 回填」这两步的语义都写明白），再把命中最强的生活关键词的记录改成 life。
--
-- ⚠️ 这份关键词清单与 `MemoService.WORK_PATTERN` 是**同一套规则的第二次表达**，
--    写在 SQL 里是因为迁移必须一次性把历史数据算完（不能回放 Java 代码）。
--    改 Java 那侧的词表时，请同步评估这里的回填口径是否要跟着走；
--    上线之后这一步只在升级时跑一次，改动它只影响还没升级的库。
UPDATE task SET grp = 'life'
WHERE lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%买%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%购%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%奶粉%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%超市%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%快递%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%班车%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%通勤%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%地铁%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%停车%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%买菜%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%接孩子%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%水电%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%物业%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%体检%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%旅游%'
   OR lower(coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(note, '')) LIKE '%装修%';

-- 回填之后必须有一个索引，否则任务页每次切换「工作 / 生活」都是全表扫描 + 过滤。
-- 注意它把 status 放在前面：任务页的取数顺序永远是「先按状态排序、再分组过滤」，
-- `(status, grp)` 与既有的 `idx_task_status_priority` 组合起来能覆盖默认视图（grp='work'）。
CREATE INDEX idx_task_grp ON task(grp);
