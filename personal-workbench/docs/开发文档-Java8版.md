# 个人工作台 开发文档（Java 8 版）

> 版本：v2.0 | 日期：2026-09-05 | 目标：AI 可依据本文档 + `demo/` 下现有 HTML 页面直接开工
> 上一版：Java 17 + Spring Boot 3 → 本版：**Java 8 + Spring Boot 2.7**
> 相关文档：`docs/P0-MVP需求拆解与数据模型设计.md`、`docs/版本A-Windows免安装包落地方案.md`

---

## 0. 本次版本变更的影响（先讲代价）

### 0.1 变更清单

| 组件 | 原方案 | 本方案 | 变更原因 |
|---|---|---|---|
| JDK | 17 | **1.8** | 用户指定 |
| Spring Boot | 3.x | **2.7.18** | Boot 3 最低要求 Java 17，无 Java 8 版本 |
| Spring Framework | 6.x | **5.3.31** | 随 Boot 2.7.18 |
| Servlet 容器 | Tomcat 10（`jakarta.*`） | **Tomcat 9.0（`javax.*`）** | Boot 2.7 内置 |
| 命名空间 | `jakarta.servlet.*` | **`javax.servlet.*`** | Jakarta EE 8 |
| Flyway | 10.x | **8.5.13**（Boot 依赖管理锁定） | Flyway 9/10 随 Boot 3 走 |
| 数据库 | SQLite | **SQLite（不变）** | — |
| JDBC 驱动 | xerial sqlite-jdbc | **3.41.2.2** | 主 jar 编译目标为 Java 8 |
| ORM | JdbcTemplate | **JdbcTemplate（不变）** | 反而受益：Boot 2.7 下选 JdbcTemplate 规避了 Hibernate 5 + SQLite 方言问题 |
| 运行时打包 | **jlink 裁剪** | **预置 JRE 8 + 手工裁剪** | **Java 8 没有 jlink（JEP 282 是 Java 9 引入的）** |

### 0.2 三个真实代价（必须知道）

1. **没有 jlink，包体变大。** Java 17 方案用 jlink 把运行时压到 22 MB（压缩后）；Java 8 只能整包塞 JRE 8 目录，裁剪后约 70 MB，压缩后约 30 MB。**交付包从约 40 MB 涨到约 70 MB。**
2. **Boot 2.7 开源支持已于 2023-06-30 结束**（2.7.18 是最终版，2023-11 发布），仅剩商业支持至 2029-06-30。新依赖需挑仍支持 Java 8 的版本。
3. **语言特性受限**：无 `record`、无 `var`、无文本块、无 `switch` 表达式。DTO 用普通类 + Lombok `@Data`。

### 0.3 但有一件事完全没变

**对同事的交付承诺不变。** 免安装包自带 JRE，同事电脑上装的是 Java 8、Java 21 还是没装 Java，都跟他无关。降 Java 8 只影响开发侧，不影响使用者侧。

### 0.4 JRE 8 的裁剪清单（替代 jlink）

从 Eclipse Temurin **JRE 8**（zip 版，非安装包）出发：

**必删（安全）**

```
bin/javaw.exe                   # 我们用 java.exe + 隐藏窗口
bin/javaws.exe  bin/jabswitch.exe  bin/javacpl.exe  bin/jp2launcher.exe
bin/policytool.exe  bin/rmid.exe  bin/rmiregistry.exe  bin/servertool.exe
bin/tnameserv.exe  bin/klist.exe  bin/ktab.exe
lib/deploy/  lib/deploy.jar  lib/javaws.jar  lib/plugin.jar  lib/desktop/
lib/security/US_export_policy.jar  lib/security/local_policy.jar   # 8u161+ 已无强度限制
lib/ext/jfxrt.jar               # JavaFX，约 17 MB，本项目完全不用
bin/decora_sse.dll  bin/prism_*.dll  bin/glass.dll
bin/javafx_font.dll  bin/javafx_iio.dll
bin/glib-lite.dll  bin/gstreamer-lite.dll
lib/amd64/libjfxmedia.so  lib/amd64/libprism_*.so
```

**可删但需验证**（删完必须跑全功能冒烟）

```
lib/ext/nashorn.jar             # JS 引擎，约 2 MB，Spring Boot 不用
lib/ext/zipfs.jar               # 不用 zip 文件系统时
```

**禁止删**

```
bin/server/                     # JVM server 模式，必须
lib/rt.jar  lib/resources.jar  lib/charsets.jar   # charsets 含 GBK 等中文编码
lib/ext/sunec.jar  lib/ext/sunjce_provider.jar     # TLS 椭圆曲线与 JCE
lib/ext/localedata.jar  lib/ext/cldrdata.jar       # zh_CN 区域数据
lib/amd64/server/libjvm.so / bin/server/jvm.dll
```

> 裁剪目标：约 **70 MB**（未压缩）。**每次调整清单后必须在一台干净 Windows 上跑完整冒烟测试**，缺文件会在运行期才炸。

---

## 1. 技术栈版本锁定表

**以下版本号为硬性锁定，不得随意升级（升级前需重新验证 Java 8 兼容性）。**

| 层次 | 组件 | 版本 | 说明 |
|---|---|---|---|
| 运行时 | JDK | **1.8（8u342+）** | 开发与运行环境 |
| 框架 | Spring Boot | **2.7.18** | 最终 2.7 版 |
| 框架 | Spring Framework | 5.3.31 | 随 Boot 管理 |
| Web | spring-boot-starter-web | 随 Boot | Tomcat 9.0 + `javax.servlet` |
| 数据 | spring-boot-starter-jdbc | 随 Boot | Spring JDBC 5.3（JdbcTemplate） |
| 数据 | org.xerial:sqlite-jdbc | **3.41.2.2** | 内置 SQLite 3.41，支持 FTS5、VACUUM INTO |
| 迁移 | org.flywaydb:flyway-core | **8.5.13** | **由 Boot 依赖管理自动锁定，不要手动覆盖版本** |
| 校验 | spring-boot-starter-validation | 随 Boot | Hibernate Validator 6.x（`javax.validation`） |
| 监控 | spring-boot-starter-actuator | 随 Boot | health / shutdown |
| HTTP 客户端 | **RestTemplate**（spring-web 自带） | — | **不引入 OkHttp/HttpClient**，Java 8 无 `java.net.http` |
| JSON | Jackson | 2.13.x | 随 Boot 管理 |
| 日志 | Logback | 1.2.x | 随 Boot 管理 |
| 工具 | Lombok | 1.18.30（可选） | 仅用于 DTO 的 getter/setter |
| 构建 | Maven | 3.5+ | Boot 2.7 要求 3.3+，建议 3.8+ |
| 前端 | 原生 HTML + CSS + JS | — | **无构建链，无 Node，无 Vue** |

### 1.1 明确不要引入的依赖

| 依赖 | 不要引入的原因 |
|---|---|
| `spring-boot-starter-data-jpa` | Hibernate 5 无官方 SQLiteDialect，引第三方 dialect 是额外风险 |
| `flyway-mysql` / `flyway-firebird` / `flyway-sqlserver` | Flyway 8.2.1+ 只把这三个库拆出去了，**SQLite 仍在 flyway-core 内** |
| `okhttp` | Java 8 下需 OkHttp 4（拉 Kotlin stdlib）；RestTemplate 已够用 |
| 任何 CDN / 外部字体 / 图表库 | 离线可用是硬要求 |

### 1.2 数据库版本特性要求

| 特性 | 最低 SQLite 版本 | 我们用 |
|---|---|---|
| `VACUUM INTO`（一致性备份） | 3.27+ | 3.41 ✅ |
| FTS5（后续全文检索） | 3.9+ | 3.41 ✅ |
| WAL 模式 | 3.7+ | 3.41 ✅ |

---

## 2. 工程结构

```
personal-workbench/
├── server/                                  ← Spring Boot 2.7 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/icecode/workbench/
│       │   ├── WorkbenchApplication.java
│       │   ├── config/
│       │   │   ├── DataSourceConfig.java         SQLite DataSource + PRAGMA
│       │   │   ├── WebConfig.java                CORS / 静态资源 / 拦截器
│       │   │   └── JacksonConfig.java            日期序列化格式
│       │   ├── common/
│       │   │   ├── ApiResponse.java              统一响应包装
│       │   │   ├── BizException.java
│       │   │   ├── GlobalExceptionHandler.java
│       │   │   └── ErrorCode.java
│       │   ├── auth/
│       │   │   ├── AuthController.java           init / login / logout / status
│       │   │   ├── LoginInterceptor.java         会话校验
│       │   │   └── InitRequiredException.java
│       │   ├── module/
│       │   │   ├── dashboard/  DashboardController.java, DashboardService.java
│       │   │   ├── inbox/      InboxController.java, InboxService.java, ClassifyService.java
│       │   │   ├── task/       TaskController.java, TaskService.java, TaskRepository.java
│       │   │   ├── event/      EventController.java, EventService.java
│       │   │   ├── memo/       MemoController.java, MemoService.java
│       │   │   ├── pomo/       PomoController.java, PomoService.java
│       │   │   ├── activity/   ActivityController.java, ActivityService.java
│       │   │   ├── plan/       PlanController.java, PlanService.java, RuleEngine.java
│       │   │   └── system/     SystemController.java, BackupService.java, ConfigService.java
│       │   ├── seed/           DemoDataInitializer.java    首次初始化写示例数据
│       │   └── util/           TimeUtil.java, JsonUtil.java, IdUtil.java
│       └── resources/
│           ├── application.yml
│           ├── logback-spring.xml
│           ├── db/migration/
│           │   ├── V1__init_schema.sql
│           │   └── V2__add_index_and_config.sql
│           └── static/                         ← 前端（由 demo 改造）
│               ├── index.html
│               ├── style.css
│               ├── api.js
│               └── app.js
├── web/                                       ← 前端源文件（构建时复制到 static/）
├── deploy/                                    ← 打包与启停脚本（见 §9）
└── docs/                                      ← 本文档等
```

---

## 3. 数据模型与 DDL

### 3.1 类型映射（MySQL 8 → SQLite）

| MySQL 8 | SQLite | 说明 |
|---|---|---|
| `BIGINT PK AUTO_INCREMENT` | `INTEGER PRIMARY KEY AUTOINCREMENT` | |
| `DATETIME` | `TEXT` | 存 `yyyy-MM-dd HH:mm:ss` |
| `DATE` | `TEXT` | 存 `yyyy-MM-dd` |
| `DECIMAL(3,2)` / `DECIMAL(4,3)` | `REAL` | 置信度、完成率 |
| `TINYINT` | `INTEGER` | 0/1 布尔 |
| `VARCHAR(n)` | `TEXT` | 长度约束移到应用层 |
| `JSON` | `TEXT` | `ai_payload`，可用 `json_extract()` |

### 3.2 时间字段规则（重要）

> **所有时间字段一律由 Java 侧用 `LocalDateTime.now()` 生成并格式化为字符串后写入。**
> SQL 里的 `DEFAULT (datetime('now','localtime'))` 仅作兜底。

原因：SQLite 的 `'localtime'` 取的是 **操作系统时区**，而 `-Duser.timezone` 只影响 JVM。两者一旦不一致，会出现「同一个页面里两个时间对不上」。统一由 Java 生成即可根治。

### 3.3 `V1__init_schema.sql`（完整）

```sql
-- ============ 收集箱 ============
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
    updated_at     TEXT
);
CREATE INDEX idx_inbox_status ON inbox_item(status, created_at);
CREATE INDEX idx_inbox_source ON inbox_item(source);

-- ============ 任务 ============
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
    updated_at      TEXT
);
CREATE INDEX idx_task_status_priority ON task(status, priority);
CREATE INDEX idx_task_due ON task(due_date);

-- ============ 日程 ============
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
    updated_at      TEXT
);
CREATE INDEX idx_event_date ON schedule_event(event_date, start_time);

-- ============ 备忘 ============
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
    updated_at      TEXT
);
CREATE INDEX idx_memo_status ON memo(status, pinned DESC);
CREATE INDEX idx_memo_grp ON memo(grp, status);

-- ============ 番茄钟 ============
CREATE TABLE pomodoro (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id    INTEGER,
    minutes    INTEGER NOT NULL DEFAULT 25,
    ended_at   TEXT    NOT NULL,
    created_at TEXT    NOT NULL
);
CREATE INDEX idx_pomo_ended ON pomodoro(ended_at);

-- ============ 使用流水（时间线） ============
CREATE TABLE activity_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    log_type   TEXT NOT NULL,
    content    TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_log_created ON activity_log(created_at);

-- ============ 知识笔记（同步 Obsidian，版本 A 可选） ============
CREATE TABLE knowledge_note (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    title           TEXT NOT NULL,
    vault_path      TEXT,
    sync_status     TEXT NOT NULL DEFAULT 'pending',
    source_inbox_id INTEGER,
    deleted         INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT
);

-- ============ 每日计划 ============
CREATE TABLE daily_plan (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_date       TEXT NOT NULL UNIQUE,
    theme           TEXT,
    completion_rate REAL,
    closed          INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL,
    updated_at      TEXT
);

CREATE TABLE daily_plan_item (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id         INTEGER NOT NULL,
    task_id         INTEGER NOT NULL,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    ai_reason       TEXT,
    is_ai_suggested INTEGER NOT NULL DEFAULT 0,
    done            INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_plan_item ON daily_plan_item(plan_id, sort_order);

-- ============ 企业微信消息流水 ============
-- 版本 A 不写入此表，保留为版本 B 留口子，建表成本为零
CREATE TABLE wechat_msg_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    msg_id        TEXT NOT NULL UNIQUE,
    msg_type      TEXT,
    content       TEXT,
    handle_status TEXT NOT NULL DEFAULT 'received',
    inbox_item_id INTEGER,
    created_at    TEXT NOT NULL,
    updated_at    TEXT
);

-- ============ AI 调用流水 ============
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
    updated_at        TEXT
);

-- ============ 应用配置（键值对） ============
CREATE TABLE app_config (
    config_key   TEXT PRIMARY KEY,
    config_value TEXT,
    updated_at   TEXT
);
```

### 3.4 `V2__add_index_and_config.sql`

```sql
CREATE INDEX idx_task_deleted ON task(deleted);
CREATE INDEX idx_memo_deleted ON memo(deleted);
CREATE INDEX idx_inbox_deleted ON inbox_item(deleted);

INSERT OR IGNORE INTO app_config(config_key, config_value) VALUES
    ('ai.enabled',      'false'),
    ('ai.base_url',     ''),
    ('ai.api_key',      ''),
    ('ai.model',        ''),
    ('obsidian.enabled','false'),
    ('obsidian.vault_path',''),
    ('obsidian.vault_name',''),
    ('pomo.work',       '25'),
    ('pomo.short',      '5'),
    ('pomo.long',       '15'),
    ('pomo.auto',       'false');
```

### 3.5 `V3__add_demo_markers.sql`

为用户可见示例数据增加 `is_demo INTEGER NOT NULL DEFAULT 0` 标记，覆盖：`inbox_item`、`task`、`schedule_event`、`memo`、`activity_log`，并为各表建立 `is_demo` 索引。

> 必须用独立的 V3 迁移，不能回改已发布的 V1/V2；后续「清空示例数据」只按 `is_demo=1` 清理，禁止用标题或创建时间猜测，以免误删用户数据。

### 3.6 示例数据不用 SQL，用 Java 写

示例数据需要**相对当前日期**计算（`今天`、`昨天`、`+3 天`），静态 SQL 做不到。因此由 `DemoDataInitializer` 在首次初始化账号后写入，内容与 demo 的 `seed()` 保持一致：

- 收集箱 4 条（其中 1 条 `source='wecom'`、1 条 `type='voice'`）
- 任务 4 条，**其中 1 条 `due_date = 昨天`（逾期）**
- 日程 3 条（今天）
- 备忘 4 条（1 条 `pinned=1`）
- 时间线 3 条

同时提供 `DELETE /api/v1/system/demo-data` 一键清空示例数据。

### 3.7 SQLite 连接配置（`DataSourceConfig`）

```java
@Configuration
public class DataSourceConfig {

    @Value("${workbench.data-dir}")
    private String dataDir;

    @Bean
    public DataSource dataSource() {
        // Windows 反斜杠必须转成正斜杠，否则 SQLite 路径解析异常
        String dir = dataDir.replace("\\", "/");
        String url = "jdbc:sqlite:" + dir + "/workbench.db";

        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);   // 读写不互相阻塞
        cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        cfg.setBusyTimeout(5000);                            // 锁等待 5s，避免 SQLITE_BUSY
        cfg.enforceForeignKeys(true);                        // SQLite 默认关闭外键！

        SQLiteDataSource ds = new SQLiteDataSource(cfg);
        ds.setUrl(url);
        return ds;
    }
}
```

> **为什么不用 HikariCP**：`PRAGMA` 里 `foreign_keys`、`synchronous`、`busy_timeout` 都是**连接级**的，HikariCP 的 `connection-init-sql` 只能可靠执行单条语句；`SQLiteConfig` 是 xerial 官方 API，逐连接设置，语义确定。单用户场景下每次查询开连接的开销可忽略（SQLite 打开连接 < 1ms）。
>
> **如果后续要加连接池**：引入 HikariCP 并把 `maximumPoolSize` 设为 **1**（匹配 SQLite 单写者模型，从根上消除 `SQLITE_BUSY`），PRAGMA 改用 `spring.datasource.hikari.connection-init-sql`。

---

## 4. API 契约

### 4.1 通用约定

- 前缀：`/api/v1`
- 鉴权：`HttpSession`（JSESSIONID Cookie，同源自动携带）。除 `/api/v1/auth/**` 和 `/actuator/health` 外全部需要登录。
- 未初始化（还没建管理员账号）时，除 `/api/v1/auth/**` 外返回 **HTTP 409 + `code=2001`**，前端据此跳转首次配置向导。

**成功响应**

```json
{ "code": 0, "message": "ok", "data": { }, "timestamp": 1693890000000 }
```

**失败响应**

```json
{ "code": 1001, "message": "任务不存在", "data": null, "timestamp": 1693890000000 }
```

**错误码表**（与 `common/ErrorCode.java` 及 `GlobalExceptionHandler` 的实际映射一致，2026-09-11 校正）

| code | HTTP | 含义 | 常量 |
|---|---|---|---|
| 0 | 200 | 成功 | — |
| 1001 | 404 | 请求的内容不存在 | `RESOURCE_NOT_FOUND` |
| 1002 | 400 | 参数校验失败 | `INVALID_PARAMETER` |
| 1003 | 500 | 系统暂时不可用 | `INTERNAL_ERROR` |
| 2001 | 409 | 工作台尚未完成首次配置 | `NOT_INITIALIZED` |
| 2002 | 401 | 请先登录（未登录或会话过期） | `NOT_LOGGED_IN` |
| 2003 | 403 | 用户名或密码错误 | `INVALID_PASSWORD` |
| 2004 | 409 | 工作台已经完成首次配置 | `ALREADY_INITIALIZED` |
| 2005 | 400 | 时区设置无效 | `INVALID_TIMEZONE` |
| 2006 | 400 | 密码按 UTF-8 编码后不能超过 72 字节 | `PASSWORD_TOO_LONG` |
| 3001 | 400 | 当前任务状态不允许这样流转 | `INVALID_TASK_TRANSITION` |
| 3002 | 400 | 已完成或已取消的任务不能执行此操作 | `TASK_NOT_ACTIONABLE` |
| 3003 | 400 | 结束时间必须晚于开始时间；不支持跨日日程 | `INVALID_EVENT_TIME` |
| 3004 | 400 | 备份失败，请稍后重试 | `BACKUP_FAILED` |
| 3005 | 400 | 备份文件无效或与本应用版本不兼容 | `RESTORE_INVALID` |
| 3006 | 400 | 该操作有风险，需要显式确认 | `CONFIRM_REQUIRED` |
| 3007 | 400 | 尚未配置 Obsidian Vault | `VAULT_NOT_CONFIGURED` |

除上表「业务异常」的映射外，框架层异常也统一映射到 4xx，**不会落到 500 兜底**：

| 异常 | HTTP | code | 说明 |
|---|---|---|---|
| `HttpRequestMethodNotSupportedException` | 405 | 1002 | 用错 HTTP 方法，提示里给出允许的方法 |
| `HttpMessageNotReadableException` | 400 | 1002 | 请求体不是合法 JSON，或压根没传 |
| `MethodArgumentTypeMismatchException` | 400 | 1002 | 路径/查询参数类型不对 |
| `MethodArgumentNotValidException` | 400 | 1002 | 字段校验失败，透传注解上的 `message` |
| 其他未捕获异常 | 500 | 1003 | 记 `Unhandled application error` 日志 |

**文案约定**：前端 toast 直接显示响应体的 `message`，所以

- 所有请求 DTO 的校验注解**必须**自带能指导操作的中文 `message`
  （格式类字段写清期望格式，如「请按 HH:mm 填写，例如 09:30」）。
  否则会透出 Hibernate 的英文默认文案，如 `size must be between 0 and 72`、
  `must match "^$|([01]\d|2[0-3]):[0-5]\d"`。`common/ValidationMessageTest`
  会扫描全部 `*Request` 强制这条约定。
- `INVALID_PARAMETER(1002)` 自带文案笼统（「请求参数不正确」），
  抛 `BizException` 时**必须**带自定义原因；文案本身清楚的码（1001 / 3003 / 3007 等）单参即可。

**未映射路径的约定**（2026-09-11 定）：请求一个不存在的 `/api/...` 时，
未登录会被鉴权拦截器提前拦成 2002「请先登录」；已登录则返回 **HTTP 404**，
响应体是 Spring 默认错误结构 `{"timestamp","status","error","path"}`——
**注意它没有 `code` 字段**，不属于上面的 `ApiResponse` 契约。

前端 `api.js` 对此的判断是「4xx 且响应体没有 `code`」→ 抛出
「请求失败（HTTP 404）：<路径> 不存在，请核对路径或确认后端已就绪」。
这样处理的理由：原本会走到 `body.message || "操作失败"`，报出笼统的「操作失败」，
把**永久性**的路径错误说成了含糊的失败。

> **不要**为了统一 404 响应体而加 `@RequestMapping("/api/**")` 兜底控制器。
> 它按**任意方法**匹配，会抢在 `HttpRequestMethodNotSupportedException` 之前命中，
> 于是 `GET /api/v1/auth/login`（只有 POST）、`GET /api/v1/events/{id}`（只有 PATCH/DELETE）
> 全都从 **405 退化成 404**——而 405 会告诉你「允许的方法是 [PATCH, DELETE]」，
> 比 404 有用得多。已实测验证，并由
> `ClientErrorMappingTest.wrongMethodOnAnExistingPathStays405EvenForPathVariables` 守着。

### 4.2 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/auth/status` | `{initialized, loggedIn, username, timezone}` |
| POST | `/api/v1/auth/init` | 首次初始化，仅可调用一次 |
| POST | `/api/v1/auth/login` | 登录 |
| POST | `/api/v1/auth/logout` | 退出 |

`POST /api/v1/auth/init`

```json
// request
{ "username": "admin", "password": "******", "timezone": "Asia/Shanghai", "seedDemo": true }
// response data
{ "initialized": true, "username": "admin" }
```

> `seedDemo=true` 时写入 §3.6 的示例数据。密码用 BCrypt 存 `app_config`（key = `app.password_hash`），严禁明文存储。

### 4.3 驾驶舱（首页聚合，一次拿全）

`GET /api/v1/dashboard/today`

```json
{
  "date": "2026-09-05",
  "theme": "把限流方案收口",
  "metrics": {
    "taskDone": 3, "taskTotal": 8,
    "inboxPending": 4,
    "overdue": 1,
    "eventToday": 3,
    "pomoToday": 2
  },
  "brief": "收集箱有 4 条待整理，建议 16:30 固定时段清空；1 个任务已逾期：支付网关 MR 代码评审。",
  "events": [
    { "id": 11, "title": "团队站会", "type": "meeting", "date": "2026-09-05", "start": "09:30" }
  ],
  "topTasks": [
    { "id": 3, "title": "支付网关 MR 代码评审", "priority": "P2",
      "due": "2026-09-04", "overdueDays": 1,
      "reason": "已逾期 1 天", "deep": false, "blocking": false }
  ],
  "todayTasks": [ { "id": 2, "title": "回复业务方日配额口径疑问", "priority": "P1", "status": "todo" } ]
}
```

> `topTasks` 由规则引擎计算，排序权重：**逾期 > 阻塞他人 > 临近截止 > 深度工作标记**，每条必须带可解释的 `reason`。

**界面行为：驾驶舱的每个数据都能下钻（2026-09-12）**

- 六个指标卡可点击：`进行中与待办` / `已完成·全部` / `逾期` / `今天截止` → 任务页并带上对应筛选；
  `待整理` → 收录页；`今日番茄` → 打开番茄钟。
- `topTasks` / `todayTasks` / `events` 的每一条都能点：跳到任务页 / 日程页并高亮那一条
  （日程会先把该日期所在的**那一周**放进可见列表 —— `ensureWeek()` → `loadEvents()` → `scrollToWeek()`
  → 高亮，顺序不能反；日程页是周视图，见 `docs/日程周视图设计.md`）。
- **筛选口径必须与后端一致**：是否逾期读 `task.overdue`（后端算的），「今天」读 `dashboard.date`，
  前端不要用 `new Date()` 另算一套 —— 两套算法会让同一天在不同页面上贴不同的标签。
- 任务页的筛选**必须写在页面上**（「当前只显示「逾期任务」，共 N 条」+ 一键取消）：
  静默过滤会让用户以为任务凭空少了一半，然后开始怀疑数据丢了。

### 4.4 收集箱

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/inbox?status=pending` | 列表，按 `created_at` 倒序；`status=processed` 即整理页要确认的那批 |
| POST | `/api/v1/inbox` | 录入 |
| DELETE | `/api/v1/inbox/{id}` | 软删除 |
| POST | `/api/v1/inbox/classify` | 触发整理，**同步完成**后返回 `{jobId}`；`jobId` 只用于审计，前端不需要轮询 |
| GET | `/api/v1/inbox/jobs/{jobId}` | 查整理任务记录（附带 `processed` 列表）。前端不使用，保留给审计与排障 |
| POST | `/api/v1/inbox/{id}/confirm` | 确认整理结果并生成实体；**置信度低于 0.70 不阻止手动确认**，`0.70` 只决定是否参与下面的批量确认 |
| POST | `/api/v1/inbox/confirm-high-confidence` | 一键确认：把置信度 ≥ 0.70 的条目按建议直接生成，返回生成的 `ConfirmResultVO` 列表；知识类在未配置 Vault 时跳过 |
| POST | `/api/v1/inbox/{id}/reclassify` | 人工改分类后重新生成 |

`POST /api/v1/inbox`

```json
// request
{ "raw": "明天下午3点约业务方对齐Q4需求评审", "contentType": "text", "source": "web" }
// response data
{ "id": 21, "raw": "明天下午3点约业务方对齐Q4需求评审", "status": "pending", "createdAt": "2026-09-05 12:10:33" }
```

`GET /api/v1/inbox?status=pending` 返回项：

```json
{
  "id": 21, "raw": "记得给小李的方案写评审意见", "contentType": "text",
  "source": "wecom", "status": "pending",
  "ai": { "category": "task", "confidence": 0.86,
          "payload": { "title": "给小李的方案写评审意见", "due": null, "priority": "P1" } },
  "createdAt": "2026-09-05 10:02:11"
}
```

> `confidence < 0.70` 时前端标「待人工确认」，**不自动生成实体**。

### 4.5 任务

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/tasks?status=&priority=&keyword=&page=1&size=50` | 列表 |
| POST | `/api/v1/tasks` | 新建 |
| PATCH | `/api/v1/tasks/{id}` | 编辑（标题/优先级/截止/备注/标记） |
| POST | `/api/v1/tasks/{id}/status` | 状态流转 |
| POST | `/api/v1/tasks/{id}/postpone` | 顺延（due +1 天，postponed +1） |
| DELETE | `/api/v1/tasks/{id}` | 软删除 |

`POST /api/v1/tasks`

```json
// request
{ "title": "限流方案定稿", "priority": "P0", "due": "2026-09-06", "deep": true, "blocking": false, "note": "" }
```

`GET /api/v1/tasks` 返回项（含前端渲染所需的派生字段）：

```json
{
  "id": 3, "title": "支付网关 MR 代码评审", "priority": "P2", "status": "todo",
  "due": "2026-09-04", "overdueDays": 1, "postponed": 0,
  "deep": false, "blocking": false, "note": "",
  "sourceInboxId": 12,
  "createdAt": "2026-09-02 09:15:00", "completedAt": null
}
```

`POST /api/v1/tasks/{id}/status`

```json
// request
{ "status": "done" }
// 副作用：写入一条 activity_log(type='praise')，内容为随机鼓励语
```

### 4.6 日程

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/events?q=客户` | **全文检索**：命中**全部日期**（含待定区），按标题与时间段模糊匹配。传了 `q` 就忽略 `date` / `from` / `to` —— 用户既然在搜，就不该再被日期限制 |
| GET | `/api/v1/events?from=2026-09-07&to=2026-09-13` | **区间查询**（日程页周视图用，闭区间、首尾都含）：一次取回整段日期，省掉按天循环调 7 次。`from` 与 `to` **必须成对**，缺一个返回 400/1002 并说明格式；`from > to` 同样报错。待定日程（`event_date IS NULL`）不在任何区间里 |
| GET | `/api/v1/events?date=2026-09-05` | **单日查询**，按 `start_time` 升序（`start_time` 为空的排最后）；`date` 省略即今天，格式非法返回 400「日期格式不正确，请按 YYYY-MM-DD 填写」 |
| GET | `/api/v1/events/pending` | 待定时间区：`event_date` 为空的日程（US-4.2）。单独子路径而非 `date=pending` 哨兵值，避免一个参数承担两种语义 |
| POST | `/api/v1/events` | 新建；`date` 留空则落为待定日程（待定时 `repeatWeeks` 会被忽略）。`repeatWeeks`（1–12，默认 1）大于 1 时**物化**成 N 条「每周同一天」的独立记录，组标识取首期 id —— 所以之后单独改某一期不会动到其余几期，见 `docs/日程周视图设计.md` §9 |
| PATCH | `/api/v1/events/{id}` | 编辑；`date` 传空串可把已有日程退回待定区。**重复日程的每一期就是一条普通记录**，改这里只影响这一期 |
| DELETE | `/api/v1/events/{id}` | 删除（同理：只删这一期） |

> 上面三条 GET 是**同一个入口的三种形态**，优先级 `q` > `from`+`to` > `date`，
> 与 `tasks?keyword=` / `memos?q=` 的形态一致 —— 列表与检索是同一个资源。
> 前端对应 `WorkbenchApi.events(params)`（对象入参，三种形态同一入口）。
> 周视图的形态与取舍见 `docs/日程周视图设计.md`。

```json
{ "id": 11, "title": "团队站会", "type": "meeting", "date": "2026-09-05", "start": "09:30", "end": "09:45" }
```

> `type` 取值：`meeting` / `deep_block` / `other`。时间重叠时**警告但不阻止**（返回 `warnings` 字段）。

### 4.7 备忘

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/memos?grp=&status=&keyword=` | 列表；`keyword` 覆盖标题/内容/标签/链接 |
| POST | `/api/v1/memos` | 新建 |
| PATCH | `/api/v1/memos/{id}` | 编辑 |
| POST | `/api/v1/memos/{id}/pin` | 切换置顶 |
| POST | `/api/v1/memos/{id}/archive` | 归档 / 恢复 |
| DELETE | `/api/v1/memos/{id}` | 软删除 |

```json
{
  "id": 5, "title": "公司班车时间表",
  "content": "早班 7:50 软件园东门发车；晚班 18:30 / 19:30 两班…",
  "url": null, "tags": ["通勤"], "grp": "life",
  "pinned": true, "status": "active",
  "sourceInboxId": null,
  "createdAt": "2026-09-03 20:00:00", "updatedAt": "2026-09-04 08:12:00"
}
```

排序规则：`pinned DESC, updated_at DESC`。`tags` 在库中存逗号分隔字符串，出参转数组。

### 4.7.1 移至：任务 / 日程 / 备忘互转

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/transfers` | 把一条记录从任务 / 日程 / 备忘中的一个菜单搬到另一个菜单 |

```json
// 请求
{ "fromType": "task", "id": 12, "toType": "event" }

// 响应
{
  "fromType": "task", "toType": "event",
  "fromLabel": "任务", "toLabel": "日程",
  "id": 31, "title": "写季度复盘",
  "warnings": ["日程没有「任务描述、备注、优先级 P1」这些字段，移动后不再保留。"]
}
```

语义：三张表的列不是一一对应的，所以「移动」的实质是**目标表新建一条 + 源记录软删 + 记一条流水**，
三件事在同一个事务里完成，不会出现「两个菜单里都有」或「两边都没有」的中间态。

1. 目标菜单新建一条；标题带过去，日期按来源映射（`task.due` ↔ `event.date`）。
   **日期为空就是「待定时间」（US-4.2），不默认今天** —— 后端不替用户猜。
2. 源记录**软删**（进回收站，30 天内可恢复，见 US-1.5）：走错菜单时还能捞回来，不是物理删除。
3. 写一条 `activity_log`：`从「任务」移至「日程」：<标题>`。
4. `is_demo` 与 `source_inbox_id` 跟着走 —— 否则「清空示例数据」清不掉搬过来的那条，
   用户会看到「已清空」却还剩一条。

字段映射与必然的有损（**有值才提示**，逐项写进 `warnings`，界面必须展示）：

| 方向 | 带过去 | 会丢失 |
|---|---|---|
| 任务 → 日程 | 标题、截止日期 → 日程日期 | 描述、备注、优先级、状态、深度/阻塞标记 |
| 任务 → 备忘 | 标题、描述+备注 → 备忘正文 | 优先级、状态、截止日期、深度/阻塞标记 |
| 日程 → 任务 | 标题、日期 → 截止日期 | 开始/结束时间、日程类型 |
| 日程 → 备忘 | 标题、时间信息 → 正文括注 | 日程类型 |
| 备忘 → 任务 | 标题、正文 → 任务描述 | 标签、链接、置顶、归档状态；正文超 2000 字会截断并说明 |
| 备忘 → 日程 | 标题 | 正文（日程只有标题一个文本字段；提示里会建议改移到「任务」） |

错误：

- 源与目标相同 → `400 / 1002`：「这条记录已经在「任务」里了，请选择「日程」或「备忘」」
- `fromType` / `toType` 取值非法 → `400 / 1002`：「菜单类型只能填 任务(task) / 日程(event) / 备忘(memo)」
- 源记录不存在（或已在回收站里）→ `404 / 1001`

### 4.8 番茄钟

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/pomos?date=2026-09-05` | 当日番茄记录 |
| POST | `/api/v1/pomos` | 完成一个番茄 |
| GET | `/api/v1/pomo-config` | 读配置 |
| PUT | `/api/v1/pomo-config` | 写配置 |

```json
// POST /api/v1/pomos
{ "taskId": 3, "minutes": 25 }
// pomo-config
{ "work": 25, "short": 5, "long": 15, "auto": false }
```

### 4.9 时间线

`GET /api/v1/activity?limit=500`

```json
{ "date": "2026-09-05", "items": [
  { "id": 88, "type": "praise", "content": "完成任务「梳理限流方案初稿」 — 漂亮，这事终于落地了", "createdAt": "2026-09-05 11:02:00" }
]}
```

`type` 取值：`inbox` / `task` / `praise` / `memo` / `pomo` / `plan`。按日分组返回。

`DELETE /api/v1/activity/{id}` —— **物理删除**单条流水。

- 时间线是操作流水而非正式实体，是 §6.3 软删除规则的例外：不设 `deleted` 字段、不做软删除，**删除后不可恢复**
- 记录不存在时返回 `404` + `code 1001`（`RESOURCE_NOT_FOUND`），message 为「这条时间线记录不存在或已经被删除」
- 后端实现：`TimelineService.delete(long)` 执行 `DELETE FROM activity_log WHERE id=?`，影响行数为 0 时抛 `BizException(ErrorCode.RESOURCE_NOT_FOUND, ...)`

### 4.10 每日计划

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/plan/{date}` | 取当日计划 |
| POST | `/api/v1/plan/generate` | 规则引擎生成 Top3 |
| PATCH | `/api/v1/plan/{date}` | 调整顺序 / 设定主题 |
| POST | `/api/v1/plan/{date}/close` | 日结，回写完成率 |

### 4.11 系统

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/system/config` | 读全部配置（AI Key 打码返回） |
| PUT | `/api/v1/system/config` | 写配置 |
| POST | `/api/v1/system/backup` | 一致性备份 |
| DELETE | `/api/v1/system/demo-data` | 清空示例数据 |
| GET | `/actuator/health` | 供启动脚本轮询 |
| POST | `/api/v1/system/shutdown` | M2 后实现受本机会话保护的优雅停机；`/actuator/shutdown` 不对外暴露 |

`POST /api/v1/system/backup`

```json
// request
{ "targetPath": "D:/personal-workbench/data/backups/workbench-20260905-1200.db" }
// response
{ "file": "workbench-20260905-1200.db", "sizeBytes": 208896 }
```

实现必须是 `VACUUM INTO ?`（**不是复制文件**）：

```java
jdbcTemplate.execute("VACUUM INTO '" + targetPath.replace("'", "''") + "'");
```

### 4.12 版本 A 明确不实现的接口

以下接口**不在版本 A 范围内**（需要公网 HTTPS 或外部服务），前端不得出现入口：

- `/api/v1/wecom/callback` 及一切企业微信相关
- 语音 ASR 上传与转写（`content_type` 恒为 `text`）
- 知识笔记的 Vault 实际写入（表保留，UI 隐藏）

> **决策 D-1（来自《产品设计说明.md》§5.9）**：原型中的「知识助手」tab（`#v-assistant`，`demo/index.html` 第 365 行）在**版本 A 中移除入口**。
> 原因：该视图是**知识库 RAG 问答**（需索引 Vault + 向量检索，属 P1），要求普通同事先安装配置 Obsidian，违反「解压即用」的产品定义。
> 处理：前端删除该 tab；`knowledge_note` 表**保留**（为版本 B 预留）；`/api/v1/knowledge/*` 接口不实现、不暴露。
>
> **注意**：D-1 只砍「问」的界面，**不砍「写」的能力**。整理出的「知识」类条目写入 Vault md 文件（US-2.4）仍保留为可选功能——填了 Vault 路径即可用，未填则该分类不出现在整理结果中。

---

## 5. 配置文件

### 5.1 `pom.xml` 关键片段

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.7.18</version>
    <relativePath/>
</parent>

<properties>
    <java.version>1.8</java.version>
    <maven.compiler.source>1.8</maven.compiler.source>
    <maven.compiler.target>1.8</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <sqlite-jdbc.version>3.41.2.2</sqlite-jdbc.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.xerial</groupId>
        <artifactId>sqlite-jdbc</artifactId>
        <version>${sqlite-jdbc.version}</version>
    </dependency>
    <!-- 版本由 Boot 依赖管理锁定为 8.5.13，不要手写 version -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <finalName>workbench</finalName>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

### 5.2 `application.yml`

```yaml
server:
  port: ${SERVER_PORT:18080}
  address: 127.0.0.1          # 只监听本机，个人工作台不外露
  servlet:
    session:
      timeout: 7d

spring:
  application:
    name: personal-workbench
  datasource:
    driver-class-name: org.sqlite.JDBC
    # url 由 DataSourceConfig 用 workbench.data-dir 拼装，此处仅占位避免自动配置报错
    url: jdbc:sqlite:${workbench.data-dir:./data}/workbench.db
    username:
    password:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
    clean-disabled: true
    validate-on-migrate: true
  jackson:
    default-property-inclusion: non_null
    time-zone: Asia/Shanghai

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    shutdown:
      enabled: false
    health:
      show-details: never

workbench:
  data-dir: ${workbench.data:./data}
  timezone: Asia/Shanghai

logging:
  file:
    name: ${workbench.home:./logs}/workbench.log
  level:
    root: INFO
    com.icecode.workbench: DEBUG
    org.flywaydb: INFO
```

### 5.3 `logback-spring.xml`

按天滚动，保留 14 天，单文件 20MB 切分，写到 `${workbench.home}/logs/`。

### 5.4 JVM 启动参数（launcher 已内置）

```
-Xms128m -Xmx256m
-XX:MaxMetaspaceSize=128m      # Java 8 是元空间，不是永久代
-XX:+UseSerialGC               # 小堆单用户，比并行 GC 更省内存
-Dfile.encoding=UTF-8
-Duser.timezone=Asia/Shanghai  # 必须显式指定
-Dworkbench.home=<包目录>
-Dworkbench.data=<包目录>/data
```

预期：RSS 约 150–220 MB，冷启动到可访问 ≤ 15s（SSD）。

---

## 6. 关键实现要点

### 6.1 分层与调用方向（单向，禁止环）

```
Controller  →  Service  →  Repository(JdbcTemplate)  →  SQLite
                  ↑
              RuleEngine / 工具类
```

- Controller 只做参数校验与响应包装，**不写 SQL、不做业务判断**
- Service 负责事务边界（`@Transactional`）
- Repository 只做单表 CRUD，**不感知业务**
- **禁止** Repository 调 Service、Service 互相调用成环

### 6.2 事务边界

必须在同一事务内的操作：

| 操作 | 事务内容 |
|---|---|
| 确认收集条目 | 更新 `inbox_item.status` + 插入 `task`/`event`/`memo`/`knowledge_note` |
| 日结 | 更新 `daily_plan.completion_rate` + `closed` + 写 `activity_log` |
| 完成任务 | 更新 `task.status` + 写 `activity_log`（praise） |

### 6.3 软删除

所有列表查询统一追加 `WHERE deleted = 0`。**不要**依赖调用方记得加，在 Repository 的查询模板里写死。

### 6.4 派生字段

`overdueDays`、`overdue` 等是**查询时计算**，不落库：

```sql
-- SQLite 计算天数差
CAST(julianday('now','localtime') - julianday(due_date) AS INTEGER) AS overdue_days
```

### 6.5 全文检索（备忘）

MVP 用 `LIKE`，多字段 OR：

```sql
WHERE deleted = 0
  AND (title   LIKE '%' || ? || '%'
    OR content LIKE '%' || ? || '%'
    OR tags    LIKE '%' || ? || '%'
    OR url     LIKE '%' || ? || '%')
```

量上来后升级为 SQLite **FTS5**（sqlite-jdbc 已内置编译，无需新依赖）。

### 6.6 AI 整理（可选功能，可完全不配）

- 无配置时 `ai.enabled=false`，前端隐藏「整理」按钮，**其余功能不受影响**
- 有配置时后端用 `RestTemplate` 调 LLM，走 `ai_job` 表记录耗时与 token
- **失败时只把 `inbox_item.status` 置为 `failed`，原始内容绝不丢失**，可一键重试
- 前端现有 `aiClassify()` 规则引擎保留为**本地兜底**：后端不可用时用它给出建议，标注「本地规则」

### 6.7 安全边界（说明清楚，别夸大）

- `server.address=127.0.0.1`：只监听本机，局域网访问不到
- 会话认证：防家人/同事误触，**不是强安全**，不要在文档里宣称"加密"
- 密码用 BCrypt 存本地数据库
- `/actuator/shutdown` 不对外暴露；停止脚本在 M2 后改用受本机会话保护的 `/api/v1/system/shutdown`，当前阶段仅允许兜底结束已识别的工作台进程

### 6.8 定时任务

- 每日 02:00 自动备份（`VACUUM INTO`），保留最近 14 份
- 每日 16:30 触发 AI 整理（仅当 `ai.enabled=true`）
- 用 `@EnableScheduling` + `@Scheduled(cron = "0 0 2 * * ?")`，cron 时区用 `${workbench.timezone}`

---

## 7. 前端改造指引（针对现有 demo）

### 7.1 现有代码结构（`demo/index.html`）

| 位置 | 内容 | 处置 |
|---|---|---|
| 411–465 行 | 数据层：`KEY` / `S` / `load()` / `save()` / `seed()` / `seedMemos()` | **整体替换为 API 调用** |
| 467+ | `aiClassify()` 本地规则引擎 | **保留**，作为无 AI 配置时的兜底 |
| 899+ | `render()` 统一渲染入口 | **保留并强化**，作为唯一刷新入口 |
| 各 `renderXxx()` | 分模块渲染 | 保留，**不得互相调用** |

### 7.2 改造原则：**保 `S`、换数据层**

现有 `S` 的结构与后端返回高度一致，**不要重写渲染逻辑**，只做三件事：

1. `load()` → 改为 `await api.bootstrap()`，一次性拉齐组装 `S`
2. `save()` → 删除；每个写操作改为 `await api.xxx()` 后再 `render()`
3. `uid()` → 删除；ID 由后端生成

### 7.3 `api.js` 骨架

```javascript
const API = '/api/v1';

async function req(path, options) {
  const res = await fetch(API + path, Object.assign({
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' }
  }, options));
  if (res.status === 409) { location.hash = '#setup'; throw new Error('需要初始化'); }
  if (res.status === 401) { location.hash = '#login';  throw new Error('未登录'); }
  const body = await res.json();
  if (body.code !== 0) throw new Error(body.message || '请求失败');
  return body.data;
}

const api = {
  get:    p            => req(p),
  post:   (p, b)       => req(p, { method: 'POST',   body: JSON.stringify(b || {}) }),
  patch:  (p, b)       => req(p, { method: 'PATCH',  body: JSON.stringify(b || {}) }),
  put:    (p, b)       => req(p, { method: 'PUT',    body: JSON.stringify(b || {}) }),
  del:    p            => req(p, { method: 'DELETE' }),

  bootstrap: async () => {
    const [dash, inbox, tasks, memos, activity, pomoCfg] = await Promise.all([
      api.get('/dashboard/today'),
      api.get('/inbox?status=pending'),
      api.get('/tasks'),
      api.get('/memos'),
      api.get('/activity?limit=100'),
      api.get('/pomo-config')
    ]);
    return { theme: dash.theme, inbox, tasks, events: dash.events, memos,
             pomos: [], pomoCfg, log: activity.items, doneRate: 0 };
  }
};
```

### 7.4 时间格式转换（必做）

| 现有前端 | 后端返回 | 处理 |
|---|---|---|
| `ts: Date.now()`（毫秒数） | `createdAt: "2026-09-05 12:10:33"` | 新增 `toTs(s)`：`new Date(s.replace(/-/g,'/')).getTime()` |
| `due: '2026-09-06'`（日期串） | `due: "2026-09-06"` | **一致，无需转换** |
| `date: '2026-09-05'` + `start: '09:30'` | `date` + `start` | **一致，无需转换** |

### 7.5 渲染铁律（改造时必须守住）

`render()` 是**唯一刷新入口**，各 `renderXxx()` 只渲染自己那块 DOM，**严禁在 `renderXxx()` 内部调用另一个 `renderXxx()` 或 `render()`**。

```javascript
// 正确
async function onTaskDone(id) {
  await api.post('/tasks/' + id + '/status', { status: 'done' });
  await refreshAll();
}
function refreshAll() {
  renderMetrics(); renderToday(); renderInbox();
  renderTasks();  renderEvents(); renderMemos();
  renderTimeline();
}

// 错误：会栈溢出
function renderTasks() { /* ... */ renderToday(); }
function renderToday() { /* ... */ renderTasks(); }
```

### 7.6 文件拆分

交付包内前端不再是单文件，拆为（都在 jar 的 `static/` 下，离线可用）：

```
static/index.html     页面骨架
static/style.css      样式
static/api.js         接口封装
static/app.js         状态与渲染
```

> 这不是违反"零外链"——所有资源都在 jar 内、同源提供，不引任何 CDN。

### 7.7 首次配置向导（新增页面）

新增 `static/setup.html`，检测 `GET /api/v1/auth/status` 返回 `initialized=false` 时自动跳转。

- 必填 3 项：用户名、密码、时区（默认 `Asia/Shanghai`）
- 可选：AI 配置（地址 / Key / 模型名）、Obsidian Vault 路径
- 每项都有「稍后设置」，提交时调 `POST /api/v1/auth/init`

---

## 8. 编码规范

| 项 | 规范 |
|---|---|
| 包名 | `com.icecode.workbench.module.<模块>` |
| 类名 | Controller / Service / Repository 后缀固定 |
| DTO | 入参 `XxxRequest`，出参 `XxxVO`，禁止直接返回 Entity |
| 日期 | 一律 `LocalDateTime` / `LocalDate`，经 `TimeUtil` 格式化为字符串入库 |
| 时间字段命名 | 库里 `xxx_at`，DTO 里 `xxxAt` |
| 布尔字段 | 库里 `is_xxx INTEGER`，DTO 里 `xxx` (Boolean) |
| 事务 | 标注在 Service 方法上，`@Transactional(rollbackFor = Exception.class)` |
| 异常 | 业务异常抛 `BizException(ErrorCode)`，由 `GlobalExceptionHandler` 统一转 JSON |
| 日志 | `slf4j` + `@Slf4j`，禁止 `System.out.println` |
| SQL | 全部写在 Repository 里，用 `?` 占位符，**禁止字符串拼接**（`VACUUM INTO` 路径除外，必须转义单引号） |
| 前端 JS | ES6+ 语法可用（`const/let/箭头函数/async-await`），但**不要用可选链 `?.` 和空值合并 `??`** 以外的 Java 8 不存在概念——这条只约束后端；前端由浏览器决定，可放心用 ES2020 |

---

## 9. 分阶段任务清单

按顺序做，每阶段结束必须能跑起来。

### 阶段 1：骨架与数据层（2.5 人日）

- [ ] 建 `server/` Maven 工程，parent = Boot 2.7.18，`java.version=1.8`
- [ ] `DataSourceConfig` 接通 SQLite，启动后 `data/workbench.db` 自动生成
- [x] `V1__init_schema.sql` + `V2__add_index_and_config.sql` + `V3__add_demo_markers.sql`，Flyway 自动执行
- [ ] `ApiResponse` / `ErrorCode` / `BizException` / `GlobalExceptionHandler`
- [ ] `/actuator/health` 可访问
- [ ] **验收**：通过 `docker compose -f docker-compose.dev.yml up dev` 启动后数据库文件生成，12 张表 + `flyway_schema_history` 齐全（开发、编译、测试均在 Docker 内执行）

### 阶段 2：认证与初始化（1 人日）

- [ ] `AuthController` 四个接口 + `LoginInterceptor`
- [ ] `DemoDataInitializer` 写示例数据（含 1 条逾期）
- [ ] `setup.html` 首次配置向导
- [ ] **验收**：未初始化时访问业务接口返回 409；初始化后能看到示例数据

### 阶段 3：任务与日程（2 人日）

- [ ] 任务 CRUD + 状态流转 + 顺延 + 软删除
- [ ] 日程 CRUD + 按日查询 + 重叠警告
- [ ] 完成任务写 `activity_log`（praise）
- [ ] **验收**：能建任务、改状态、顺延、逾期标红

### 阶段 4：收集箱与整理（1.5 人日）

- [ ] 收集箱录入 / 列表 / 删除
- [ ] `ClassifyService` 先接本地规则引擎（复用前端逻辑移植到 Java）
- [ ] 确认整理结果生成实体（同一事务）
- [ ] **验收**：录一条 → 整理 → 确认 → 生成任务，全过程 3 次点击内

### 阶段 5：备忘 / 番茄 / 时间线 / 驾驶舱（2 人日）

- [ ] 备忘 CRUD + 检索 + 置顶 + 归档
- [ ] 番茄记录 + 配置
- [ ] 时间线按日分组
- [ ] `/dashboard/today` 聚合 + 规则引擎 Top3（带 reason）
- [ ] **验收**：首页一次请求拿到全部数据，逾期项置顶

### 阶段 6：备份 / 配置 / 定时（1 人日）

- [ ] `VACUUM INTO` 备份 + 保留 14 份 + 每日 02:00 定时
- [ ] 配置读写（AI Key 打码返回）
- [ ] 清空示例数据接口
- [ ] **验收**：备份文件能在另一台机器完整恢复

### 阶段 7：前端改造（2 人日）

- [ ] `api.js` + 拆 `style.css` / `app.js`
- [ ] 替换 `load()/save()/seed()`，改 `bootstrap()`
- [ ] 所有写操作改 async + `refreshAll()`
- [ ] 时间格式转换
- [ ] 移动端适配（<768px 单列，按钮 ≥44px，输入框 ≥16px）
- [ ] **验收**：PC 与手机都能用，数据与后端同步

### 阶段 8：打包与交付（2 人日）

- [ ] 准备裁剪后的 JRE 8 放入 `runtime/`
- [ ] `build-release.ps1`（去掉 jlink，改为复制 `runtime/`）
- [ ] `launcher.ps1` + 三个 `.cmd`
- [ ] 干净机验收 3 台
- [ ] `使用说明.txt`
- [ ] **验收**：见 §10

**合计：14 人日**（比 Java 17 版多 0.5 人日，增量在 JRE 8 裁剪与验证）

---

## 10. 验收标准

### 10.1 功能验收

| # | 项 | 标准 |
|---|---|---|
| 1 | 干净机启动 | 一台**没有 Java、没有 MySQL、没有 Node** 的 Windows 10/11 能跑起来 |
| 2 | 启动耗时 | 双击到首次配置页 ≤ 60s |
| 3 | 核心闭环 | 录入 → 整理 → 确认 → 生成任务，≤ 3 次点击/条 |
| 4 | 示例数据 | 首次进入有 4 条收集箱、4 条任务（含 1 条逾期）、3 条日程、4 条备忘 |
| 5 | 今天要处理 | 首页置顶逾期项，逾期标红，昨天没做完的自动滚到今天 |
| 6 | 数据持久化 | 关闭重启后数据仍在 |
| 7 | 备份恢复 | 备份文件能在另一台机器完整恢复（用 `VACUUM INTO`） |
| 8 | 降级可用 | 不配 AI、不配 Obsidian 时，核心功能完整可用 |
| 9 | 干净实例 | 首次打开是配置向导，**不含任何开发者个人数据** |
| 10 | 移动适配 | 手机浏览器单列可用，按钮 ≥44px，输入框 ≥16px |

### 10.2 性能验收

| 项 | 标准 |
|---|---|
| 首页加载（本地） | ≤ 1s |
| 常规接口 P95 | ≤ 300ms |
| 内存占用 | RSS ≤ 250 MB |
| 包体 | ZIP ≤ 80 MB |

### 10.3 Java 8 专项验收（新增）

| # | 项 | 标准 |
|---|---|---|
| 1 | 字节码版本 | `javap -v` 确认 class 文件 major version = 52 |
| 2 | 无 jlink | 构建脚本不再调用 `jlink` / `jdeps` |
| 3 | JRE 完整性 | 裁剪后的 JRE 8 能跑完整功能冒烟，无 `NoClassDefFoundError` |
| 4 | 中文编码 | 中文标题、中文路径（`D:\工作台`）下读写均正常 |
| 5 | 时区 | `date` 与页面显示一致，跨时区机器不出错 |
| 6 | 元空间 | 连续运行 24h 无 `OutOfMemoryError: Metaspace` |

---

## 11. 给 AI 的开工顺序建议

1. 先做**阶段 1**，跑通「启动即建库」——这是全链路最短的验证闭环
2. 再按 §3.3 把 DDL 落成 `V1__init_schema.sql`，别偷懒跳过任何一张表
3. 前端先只改一个接口：让 `demo/index.html` 的 `/api/v1/tasks` 跑通，验证跨域、鉴权、日期格式三件事
4. 一个接口通了再批量推，不要一次改完再调
5. **打包放最后**——阶段 1–7 全部跑通后再做阶段 8
