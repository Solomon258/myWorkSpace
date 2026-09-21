-- 备忘 → 收藏（第 2/3 步）：数据库物理名统一改名。
--
-- 背景：产品把「备忘」整体更名为「收藏」，界面文案 / 前端标识符 / 后端包与路由 / 数据库对象一起改。
--
-- 为什么不回头改 V1~V10，而是新开迁移：
--   Flyway 配了 validate-on-migrate=true，已 apply 过的迁移文件**内容或文件名**一旦变动，
--   checksum / description 就对不上，所有跑过旧版的库（本机、同事的包、打包分发包）
--   都会在下次启动时卡在 FlywayValidateException，表现为容器反复崩溃重启、端口永远连不上。
--   所以 V1~V10 一律当只读，改名全部收在 V11~V13 这三步里。V4 文件名里的 "memo" 也因此保留。
--
-- 这次要改的四处（行数按开发库实测）：
--   ① activity_log.log_type   'memo' → 'favorite'   18 行
--   ② attachment.owner_type   'memo' → 'favorite'    1 行
--   ③ inbox_item.ai_category  'memo' → 'favorite'    4 行
--   ④ memo 表 → favorite（表名 / 索引名 / 约束名一起）  11 行
--
-- SQLite 不能修改 CHECK 约束，只能「建新表 → 搬数据 → DROP → RENAME → 重建索引」（同 V4/V5/V6）。
--
-- 本文件只有 DDL 与 DML，**是纯事务性的**，Flyway 会把它整个套在一个事务里 ——
-- 中途任何一句失败都会整体回滚，用户的收藏一条都不会半途消失。
-- 两个必需的连接级开关（foreign_keys / legacy_alter_table）放在 V11 打开、V13 复位：
-- PRAGMA 在 Flyway 眼里是非事务性语句，与 DDL 混在同一个文件里会被直接拒绝执行。
--
-- 风险最大的一步是 ④ 之前的 inbox_item 重建：它被 5 张表用外键引用
--   （task / schedule_event / favorite / knowledge_note / wechat_msg_log），
--   DROP TABLE 在外键开启时会先做一次隐式 DELETE，子表还有行引用它时必然失败 ——
--   这就是 V11 必须先关外键的原因。V11 的注释里有完整说明。

-- ① 先记下 AUTOINCREMENT 计数器。
--    DROP TABLE 会连 sqlite_sequence 里的那一行一起删掉，重建后计数器回落到「搬过去的最大 id」。
--    开发库里 memo 的 seq=17 而 max(id)=16（末尾删过一行），不保住的话新收藏会复用 id 17。
CREATE TEMP TABLE _seq_keep(name TEXT PRIMARY KEY, seq INTEGER);
INSERT INTO _seq_keep(name, seq) SELECT name, seq FROM sqlite_sequence
 WHERE name IN ('memo', 'inbox_item', 'activity_log', 'attachment');

-- ② 活动日志：log_type 增加 'favorite'，去掉 'memo'
CREATE TABLE activity_log_v12 (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    log_type   TEXT NOT NULL,
    category   TEXT,
    content    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    is_demo    INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1)),
    CONSTRAINT chk_activity_type CHECK (log_type IN ('inbox', 'task', 'plan', 'pomo', 'praise', 'favorite', 'knowledge'))
);

-- ⚠️ 枚举值必须在**搬迁的那一刻**就换掉。
--    新表的 CHECK 已经只认 'favorite'，直接原样搬 'memo' 的行会被 chk_activity_type 当场拒掉
--    （`CHECK constraint failed: chk_activity_type`）—— 不能等搬完再 UPDATE。
--    这条是 2026-09-20 在预演副本上真踩到的，别改回去。
INSERT INTO activity_log_v12 (id, log_type, category, content, created_at, is_demo)
SELECT id, CASE log_type WHEN 'memo' THEN 'favorite' ELSE log_type END,
       category, content, created_at, is_demo FROM activity_log;

DROP TABLE activity_log;
ALTER TABLE activity_log_v12 RENAME TO activity_log;

-- DROP TABLE 会把索引一起带走，必须重建（丢了就是静默全表扫描）
CREATE INDEX idx_log_created ON activity_log(created_at);
CREATE INDEX idx_log_demo ON activity_log(is_demo);

-- ③ 附件：owner_type 增加 'favorite'，去掉 'memo'
CREATE TABLE attachment_v12 (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_type    TEXT,
    owner_id      INTEGER,
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
        ('task', 'schedule_event', 'favorite', 'knowledge_note', 'inbox_item')
    ),
    CONSTRAINT chk_attachment_owner_pair CHECK (
        (owner_type IS NULL AND owner_id IS NULL)
        OR (owner_type IS NOT NULL AND owner_id IS NOT NULL)
    ),
    CONSTRAINT chk_attachment_deleted_at CHECK (deleted_at IS NULL OR deleted = 1)
);

INSERT INTO attachment_v12 (id, owner_type, owner_id, file_name, original_name, mime_type,
                            byte_size, width, height, sha256, sort_order,
                            created_at, updated_at, deleted, deleted_at, is_demo)
SELECT id, CASE owner_type WHEN 'memo' THEN 'favorite' ELSE owner_type END,
       owner_id, file_name, original_name, mime_type,
       byte_size, width, height, sha256, sort_order,
       created_at, updated_at, deleted, deleted_at, is_demo FROM attachment;

DROP TABLE attachment;
ALTER TABLE attachment_v12 RENAME TO attachment;

CREATE INDEX idx_attachment_owner  ON attachment(owner_type, owner_id) WHERE deleted = 0;
CREATE INDEX idx_attachment_sha256 ON attachment(sha256);
CREATE INDEX idx_attachment_orphan ON attachment(created_at) WHERE owner_id IS NULL AND deleted = 0;

-- ④ 收录条目：ai_category 增加 'favorite'，去掉 'memo'。五张子表引用它，见文件头。
CREATE TABLE inbox_item_v12 (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    raw_content           TEXT    NOT NULL,
    content_type          TEXT    NOT NULL DEFAULT 'text',
    source                TEXT    NOT NULL DEFAULT 'web',
    audio_path            TEXT,
    status                TEXT    NOT NULL DEFAULT 'pending',
    ai_category           TEXT,
    ai_confidence         REAL,
    ai_payload            TEXT,
    processed_at          TEXT,
    deleted               INTEGER NOT NULL DEFAULT 0,
    created_at            TEXT    NOT NULL,
    updated_at            TEXT,
    is_demo               INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1)),
    deleted_at            TEXT    CHECK (deleted_at IS NULL OR deleted = 1),
    origin                TEXT NOT NULL DEFAULT 'text',
    parse_status          TEXT,
    parse_error           TEXT,
    raw_truncated         INTEGER NOT NULL DEFAULT 0,
    source_attachment_id  INTEGER,
    CONSTRAINT chk_inbox_content_type CHECK (content_type IN ('text', 'voice')),
    CONSTRAINT chk_inbox_source CHECK (source IN ('web', 'wecom')),
    CONSTRAINT chk_inbox_status CHECK (status IN ('pending', 'processed', 'failed', 'archived')),
    CONSTRAINT chk_inbox_ai_category CHECK (ai_category IS NULL OR ai_category IN ('task', 'schedule', 'favorite', 'knowledge')),
    CONSTRAINT chk_inbox_ai_confidence CHECK (ai_confidence IS NULL OR (ai_confidence >= 0 AND ai_confidence <= 1))
);

INSERT INTO inbox_item_v12 (id, raw_content, content_type, source, audio_path, status,
                            ai_category, ai_confidence, ai_payload, processed_at, deleted,
                            created_at, updated_at, is_demo, deleted_at, origin,
                            parse_status, parse_error, raw_truncated, source_attachment_id)
SELECT id, raw_content, content_type, source, audio_path, status,
       CASE ai_category WHEN 'memo' THEN 'favorite' ELSE ai_category END,
       ai_confidence, ai_payload, processed_at, deleted,
       created_at, updated_at, is_demo, deleted_at, origin,
       parse_status, parse_error, raw_truncated, source_attachment_id FROM inbox_item;

DROP TABLE inbox_item;
ALTER TABLE inbox_item_v12 RENAME TO inbox_item;

CREATE INDEX idx_inbox_status  ON inbox_item(status, created_at);
CREATE INDEX idx_inbox_source  ON inbox_item(source);
CREATE INDEX idx_inbox_deleted ON inbox_item(deleted);
CREATE INDEX idx_inbox_demo    ON inbox_item(is_demo);

-- ⑤ 备忘表本体 → favorite。
--    没有任何表引用 memo，本可以直接 RENAME；但约束名（chk_memo_* / fk_memo_inbox）
--    是表定义的一部分，RENAME 改不掉，只能重建，否则 schema 里会永远留着 "memo" 字样。
CREATE TABLE favorite_v12 (
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
    is_demo         INTEGER NOT NULL DEFAULT 0 CHECK (is_demo IN (0, 1)),
    deleted_at      TEXT    CHECK (deleted_at IS NULL OR deleted = 1),
    CONSTRAINT chk_favorite_group   CHECK (grp IN ('work', 'life')),
    CONSTRAINT chk_favorite_pinned  CHECK (pinned IN (0, 1)),
    CONSTRAINT chk_favorite_status  CHECK (status IN ('active', 'archived')),
    CONSTRAINT chk_favorite_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT fk_favorite_inbox FOREIGN KEY (source_inbox_id) REFERENCES inbox_item(id)
);

INSERT INTO favorite_v12 (id, title, content, url, tags, grp, pinned, status,
                          source_inbox_id, deleted, created_at, updated_at, is_demo, deleted_at)
SELECT id, title, content, url, tags, grp, pinned, status,
       source_inbox_id, deleted, created_at, updated_at, is_demo, deleted_at FROM memo;

DROP TABLE memo;
ALTER TABLE favorite_v12 RENAME TO favorite;

CREATE INDEX idx_favorite_status  ON favorite(status, pinned DESC);
CREATE INDEX idx_favorite_grp     ON favorite(grp, status);
CREATE INDEX idx_favorite_deleted ON favorite(deleted);
CREATE INDEX idx_favorite_demo    ON favorite(is_demo);

-- ⑥ 还原 AUTOINCREMENT 计数器（取「迁移前」与「搬迁后」的较大值，只增不减：
--    计数器若被回落，会把已经进过回收站的那些 id 重新发出去）。
--    ⚠️ 这里必须用 UPDATE，不能用 INSERT OR REPLACE：
--    sqlite_sequence 的建表语句是 `CREATE TABLE sqlite_sequence(name,seq)` —— **没有唯一约束**，
--    OR REPLACE 不会覆盖，而是插进第二条同名记录，于是同一张表出现两行、
--    普通 SELECT 取到的是第一条（旧的、偏小的那个），看起来像「复位没生效」。
--    这条同样是 2026-09-20 在预演副本上真踩到的。
UPDATE sqlite_sequence SET seq=(SELECT seq FROM _seq_keep WHERE name='memo')
 WHERE name='favorite'    AND (SELECT seq FROM _seq_keep WHERE name='memo')        > seq;
UPDATE sqlite_sequence SET seq=(SELECT seq FROM _seq_keep WHERE name='inbox_item')
 WHERE name='inbox_item'  AND (SELECT seq FROM _seq_keep WHERE name='inbox_item')  > seq;
UPDATE sqlite_sequence SET seq=(SELECT seq FROM _seq_keep WHERE name='activity_log')
 WHERE name='activity_log' AND (SELECT seq FROM _seq_keep WHERE name='activity_log') > seq;
UPDATE sqlite_sequence SET seq=(SELECT seq FROM _seq_keep WHERE name='attachment')
 WHERE name='attachment'  AND (SELECT seq FROM _seq_keep WHERE name='attachment')  > seq;

DROP TABLE _seq_keep;
