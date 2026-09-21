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
│       │   │   ├── favorite/       FavoriteController.java, FavoriteService.java
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

-- ============ 收藏 ============
CREATE TABLE favorite (
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
CREATE INDEX idx_favorite_status ON favorite(status, pinned DESC);
CREATE INDEX idx_favorite_grp ON favorite(grp, status);

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
CREATE INDEX idx_favorite_deleted ON favorite(deleted);
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

### 3.5 示例数据不用 SQL，用 Java 写

示例数据需要**相对当前日期**计算（`今天`、`昨天`、`+3 天`），静态 SQL 做不到。因此由 `DemoDataInitializer` 在首次初始化账号后写入，内容与 demo 的 `seed()` 保持一致：

- 收集箱 4 条（其中 1 条 `source='wecom'`、1 条 `type='voice'`）
- 任务 4 条，**其中 1 条 `due_date = 昨天`（逾期）**
- 日程 3 条（今天）
- 收藏 4 条（1 条 `pinned=1`）
- 时间线 3 条

同时提供 `DELETE /api/v1/system/demo-data` 一键清空示例数据。

### 3.6 SQLite 连接配置（`DataSourceConfig`）

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

**错误码表**

| code | HTTP | 含义 |
|---|---|---|
| 0 | 200 | 成功 |
| 1001 | 404 | 资源不存在 |
| 1002 | 400 | 参数校验失败 |
| 1003 | 500 | 服务端异常 |
| 2001 | 409 | 系统未初始化，需先完成配置向导 |
| 2002 | 401 | 未登录或会话过期 |
| 2003 | 403 | 用户名或密码错误 |
| 3001 | 502 | AI 服务调用失败（不影响已收录数据） |

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

> `seedDemo=true` 时写入 §3.5 的示例数据。密码用 BCrypt 存 `app_config`（key = `security.password_hash`）。

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

### 4.4 收集箱

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/inbox?status=pending` | 列表，按 `created_at` 倒序 |
| POST | `/api/v1/inbox` | 录入 |
| DELETE | `/api/v1/inbox/{id}` | 软删除 |
| POST | `/api/v1/inbox/classify` | 触发 AI 整理（异步，返回 jobId） |
| POST | `/api/v1/inbox/{id}/confirm` | 确认整理结果并生成实体 |
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
| GET | `/api/v1/events?date=2026-09-05` | 按日查询，按 `start_time` 升序 |
| POST | `/api/v1/events` | 新建 |
| PATCH | `/api/v1/events/{id}` | 编辑 |
| DELETE | `/api/v1/events/{id}` | 删除 |

```json
{ "id": 11, "title": "团队站会", "type": "meeting", "date": "2026-09-05", "start": "09:30", "end": "09:45" }
```

> `type` 取值：`meeting` / `deep_block` / `other`。时间重叠时**警告但不阻止**（返回 `warnings` 字段）。

### 4.7 收藏

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/favorites?grp=&status=&keyword=` | 列表；`keyword` 覆盖标题/内容/标签/链接 |
| POST | `/api/v1/favorites` | 新建 |
| PATCH | `/api/v1/favorites/{id}` | 编辑 |
| POST | `/api/v1/favorites/{id}/pin` | 切换置顶 |
| POST | `/api/v1/favorites/{id}/archive` | 归档 / 恢复 |
| DELETE | `/api/v1/favorites/{id}` | 软删除 |

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

`GET /api/v1/activity?date=&limit=100`

```json
{ "date": "2026-09-05", "items": [
  { "id": 88, "type": "praise", "content": "完成任务「梳理限流方案初稿」 — 漂亮，这事终于落地了", "createdAt": "2026-09-05 11:02:00" }
]}
```

`type` 取值：`inbox` / `task` / `praise` / `favorite` / `pomo` / `plan`。按日分组返回。

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
| POST | `/actuator/shutdown` | 供停止脚本优雅停机 |

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

### 4.13 回收站（2026-09-11 新增，兑现 US-1.5）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/trash` | 回收站列表：5 类实体（收集箱 / 任务 / 日程 / 收藏 / 知识）统一成一条按删除时间倒序的流 |
| POST | `/api/v1/trash/{type}/{id}/restore` | 恢复一条记录；`type` ∈ `inbox` / `task` / `event` / `favorite` / `knowledge` |

**列表响应字段**（`TrashItemVO`）：`type`（上面五选一）、`id`、`title`、`detail`（辨认用的中文摘要）、
`deletedAt`、`daysLeft`（距不可恢复还剩几天，保留期 30 天）。

**契约要点**：

- 保留期 30 天。窗口取 `COALESCE(deleted_at, updated_at, created_at)`——V7 之前删掉的历史记录
  没有 `deleted_at`，用 `updated_at` 兜底，否则迁移会把它们直接变成不可恢复。
- 超期记录**不出现在列表里**，直接调恢复接口返回 `400 / 1002`，文案写明「已于 xxx 删除，超过 30 天保留期，不能再恢复」。
- 恢复失败的三种原因分别有具体文案，不要合成一句「操作失败」：
  记录不存在 → `404 / 1001`；记录没被删（`deleted=0`）→ `400 / 1002`「没有被删除，不需要恢复」；
  类型写错 → `400 / 1002`，并把合法取值一起列出来。
- 恢复在同一个事务里翻转 `deleted=0`、清空 `deleted_at`、并写一条 `activity_log`（「从回收站恢复「标题」」）。
- `event` 类恢复后**保持原来的待定状态**（`event_date` 为 NULL 就还是待定，不会被塞上一个日期）。
- 时间线（`activity_log`）**不进回收站**：它是操作流水，按 R6 走物理删除。

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
  port: 8080
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
        include: health,info,shutdown
  endpoint:
    shutdown:
      enabled: true
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
| 确认收集条目 | 更新 `inbox_item.status` + 插入 `task`/`event`/`favorite`/`knowledge_note` |
| 日结 | 更新 `daily_plan.completion_rate` + `closed` + 写 `activity_log` |
| 完成任务 | 更新 `task.status` + 写 `activity_log`（praise） |

### 6.3 软删除

所有列表查询统一追加 `WHERE deleted = 0`。**不要**依赖调用方记得加，在 Repository 的查询模板里写死。

软删必须**同时写下删除时刻**（`deleted_at`，V7 加的列），并带上 `AND deleted = 0`：

```sql
UPDATE task SET deleted=1, deleted_at=?, updated_at=? WHERE id=? AND deleted=0
```

- 少了 `deleted_at`：回收站算不出 30 天窗口。
- 少了 `AND deleted=0`：对已删记录再删一次会把删除时间重置，白等一个 30 天窗口
  （`KnowledgeRepository.sync` 的 `UPDATE ... SET updated_at=?` 就曾漏过这个条件）。
- 表上有 `CHECK (deleted_at IS NULL OR deleted = 1)`：**已删除 ⇒ 有删除时间**，反之则是脏数据。
  所以「恢复」时必须把 `deleted_at` 一起清空，否则这条 CHECK 会直接拒绝更新（`TrashService.restore`）。

### 6.4 派生字段

`overdueDays`、`overdue` 等是**查询时计算**，不落库：

```sql
-- SQLite 计算天数差
CAST(julianday('now','localtime') - julianday(due_date) AS INTEGER) AS overdue_days
```

### 6.5 全文检索（收藏）

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
- `/actuator/shutdown` 仅本机可访问

### 6.8 定时任务

- 每日 02:00 自动备份（`VACUUM INTO`），保留最近 14 份
- 每日 16:30 触发 AI 整理（仅当 `ai.enabled=true`）
- 用 `@EnableScheduling` + `@Scheduled(cron = "0 0 2 * * ?")`，cron 时区用 `${workbench.timezone}`

---

## 7. 前端改造指引（针对现有 demo）

### 7.1 现有代码结构（`demo/index.html`）

| 位置 | 内容 | 处置 |
|---|---|---|
| 411–465 行 | 数据层：`KEY` / `S` / `load()` / `save()` / `seed()` / `seedFavorites()` | **整体替换为 API 调用** |
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
    const [dash, inbox, tasks, favorites, activity, pomoCfg] = await Promise.all([
      api.get('/dashboard/today'),
      api.get('/inbox?status=pending'),
      api.get('/tasks'),
      api.get('/favorites'),
      api.get('/activity?limit=100'),
      api.get('/pomo-config')
    ]);
    return { theme: dash.theme, inbox, tasks, events: dash.events, favorites,
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
  renderTasks();  renderEvents(); renderFavorites();
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
- [ ] `V1__init_schema.sql` + `V2__add_index_and_config.sql`，Flyway 自动执行
- [ ] `ApiResponse` / `ErrorCode` / `BizException` / `GlobalExceptionHandler`
- [ ] `/actuator/health` 可访问
- [ ] **验收**：`mvn spring-boot:run` 后数据库文件生成，12 张表 + `flyway_schema_history` 齐全

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

### 阶段 5：收藏 / 番茄 / 时间线 / 驾驶舱（2 人日）

- [ ] 收藏 CRUD + 检索 + 置顶 + 归档
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
| 4 | 示例数据 | 首次进入有 4 条收集箱、4 条任务（含 1 条逾期）、3 条日程、4 条收藏 |
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
