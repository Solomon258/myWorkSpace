-- 备忘 → 收藏（第 3/3 步）：复原 V11 关掉的外键约束。
--
-- ⚠️ 这一步不能省。Flyway 的连接是从 Spring Boot 的 Hikari 池借的，用完要还回去；
--    而 sqlite-jdbc 只在**新建连接时**才按 SQLiteConfig 施加 enforceForeignKeys(true)
--   （见 config/DataSourceConfig.java），还回去的连接不会被自动纠正 ——
--    漏了这句，池里那个连接会带着 foreign_keys=OFF 被后续所有请求复用，
--    等于整个运行时都失去外键保护，而且**不报任何错**。
--    SchemaMigrationTest 里两条断言守着它：
--    `appliesAllConnectionPragmas`（foreign_keys 必须是 1）与
--    `renameMigrationsRanInOrderAndLeftNoPragmaBehind`（V11/V12/V13 都执行过、且没有残留中转表）。
--
-- 本文件也只放一条 PRAGMA —— 理由同 V11（Flyway 不允许单个迁移里混用语句类型）。

PRAGMA foreign_keys=ON;
