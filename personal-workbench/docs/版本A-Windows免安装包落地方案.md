# 版本 A：Windows 免安装包 落地方案

> 项目：个人工作台 personal-workbench
> 版本：v1.1 | 日期：2026-09-05
> 目标：交付一个 ZIP，同事解压后双击即用，无需安装 Java / MySQL / Docker / Node
>
> **v1.1 变更：技术栈已从 Java 17 + Spring Boot 3 调整为 Java 8 + Spring Boot 2.7.18。**
> 受影响的章节：§0 结论、§1 技术栈评估、§2.2 包体预算、§3.2 运行时、§3.3 JVM 参数、§7 排期。
> 完整技术细节与 DDL / API 契约见 `docs/开发文档-Java8版.md`。

---

## 0. 结论先行

**版本 A 的本质不是换架构，而是把「Java 8 + Spring Boot 2.7 + SQLite + 静态前端」焊成一个自带运行时的绿色 ZIP，并用一组脚本把所有技术细节挡在用户视线之外。**

三个判断：

1. **能做，且比 Docker 版更适合普通同事。** Docker 版要求同事先装 Docker Desktop（约 1.2GB 安装包、需开启 WSL2/Hyper-V、通常要重启一次），且开机常驻一个虚拟机。免安装包没有这些前置条件。
2. **主要成本在「打包工程」和「后端从 0 到可用」，不在架构设计。** 约 14 人日中，真正属于打包/脚本/验收的是 4 人日。
3. **必须做三处技术栈收敛**：数据库 MySQL 8 → SQLite、前端独立构建 → 静态资源进 jar、Java 运行时 → **预置裁剪后的 JRE 8 随包分发**（Java 8 没有 jlink，只能整包塞）。

**最终交付体量：ZIP 约 70 MB**（微信文件传输可直接发送；邮件附件可能超限，建议用网盘链接）。

---

## 1. Docker 里的技术栈，除了 Java 8 都不采用

> **v1.1 更新**：技术栈已定为 **Java 8 + Spring Boot 2.7.18**，因此本节结论相对 v1.0 出现一处反转——
> Docker 里的 `app-backend-java`（Java 8）从「不迁就」变成「**版本 B 可直接复用**」。
> 但请注意：**免安装包（版本 A）仍然不依赖 Docker 里的任何镜像**，它自带 JRE 8。

扫了你本地 Docker 28.5.2 的镜像，逐个评估：

| 本地镜像 | 版本 | 采用情况 | 判断依据 |
|---|---|---|---|
| `app-backend-java` | openjdk-8u342（**Java 8**） | **版本 B 可复用** | 现在后端就是 Java 8，Docker 版可直接用这个镜像跑，省一次镜像构建 |
| `mysql` | 8.0 / 8.0.11 | **不采用** | 免安装包不能内置 MySQL（要装服务、占 1GB+、要建账号）；两个版本统一用 SQLite |
| `nginx` | alpine | **不采用** | 单进程单端口，Spring Boot 直接托管静态资源，不需要反向代理 |
| `redis` | 5.0.9-alpine | **不采用** | 单用户场景无缓存层需求 |
| `app-backend-ai` | Python 3.10 + uvicorn | **不采用** | 自带 Python 会让包体 +80MB 并引入第二个进程；AI 改为 Java 侧 RestTemplate 直接调外部 LLM |
| `nacos` / `rocketmq` | 2.2.3 / 4.5.2 | **不采用** | 微服务治理组件，单体应用完全用不到 |

### 1.1 改到 Java 8 的代价与收益（实测口径）

| 维度 | Java 8 的代价 | Java 8 的收益 |
|---|---|---|
| 框架 | Spring Boot 2.7.18（**开源支持已于 2023-06-30 结束**，仅剩商业支持至 2029-06-30） | — |
| 命名空间 | `javax.servlet.*`（回不到 Jakarta EE 9） | — |
| 数据库迁移 | Flyway 8.5.13（Boot 依赖管理锁定） | — |
| ORM | 若用 JPA 则是 Hibernate 5 | **我们本来就用 JdbcTemplate，这条代价为零** |
| 运行时打包 | **没有 jlink**，只能整包塞 JRE 8 | — |
| 包体 | ZIP 从约 40 MB 涨到约 **70 MB** | — |
| 版本 B（Docker） | — | **可直接复用已有的 Java 8 镜像**，省一次构建 |
| 同事侧体验 | — | **完全不变**（自带 JRE，同事装没装 Java 都无所谓） |

**结论：代价集中在开发侧与包体，交付承诺不变。** 唯一实质收益是版本 B 能复用现成的 Java 8 镜像。

> 已确认的版本事实（不要凭记忆改）：
> - Spring Boot **2.7.18** 要求 Java 8，兼容至 Java 21（官方文档 §4.2 System Requirements）
> - Boot 2.7 依赖管理把 Flyway 锁定在 **8.5.13**
> - Flyway 8.2.1+ 只把 **mysql / firebird / sqlserver** 拆成了独立模块，**SQLite 仍在 `flyway-core` 内**，无需额外依赖
> - `org.xerial:sqlite-jdbc` 主 jar 编译目标就是 Java 8，推荐锁 **3.41.2.2**

### 1.2 但有一个反直觉的收敛建议：版本 B（Docker 版）也用 SQLite

原方案是「版本 A 用 SQLite、版本 B 用 MySQL」，这会导致**两套 DDL、两套方言、两套迁移脚本**，是长期维护负债。

建议：**两个版本都用 SQLite**，Docker 版只是把 `data/` 目录挂到 volume：

```yaml
services:
  workbench:
    image: personal-workbench:1.0.0
    ports: ["18080:8080"]
    volumes:
      - ./data:/app/data
```

好处：一套 SQL、一套 Flyway 脚本、一套测试；MySQL 8.0 镜像只服务你的审核平台项目。

---

## 2. 目标交付形态

### 2.1 目录结构

```
personal-workbench-v1.0.0-win.zip          ← 约 40 MB
└── personal-workbench/
    ├── 启动工作台.cmd                      ← 同事只双击这一个
    ├── 停止工作台.cmd
    ├── 备份数据.cmd
    ├── 使用说明.txt
    ├── runtime/                            ← 手工裁剪的 JRE 8（约 70 MB 未压缩）
    │   ├── bin/java.exe
    │   ├── conf/
    │   ├── lib/
    │   └── release
    ├── app/
    │   ├── workbench.jar                   ← 后端 + 前端静态资源（约 45 MB）
    │   └── VERSION
    ├── scripts/
    │   └── launcher.ps1                    ← 全部启动逻辑
    ├── config/
    │   └── application.yml                 ← 端口 / 数据目录 / 时区
    ├── data/                               ← 唯一需要备份的目录
    │   ├── workbench.db
    │   ├── workbench.db-wal
    │   ├── workbench.db-shm
    │   ├── workbench.pid
    │   ├── attachments/
    │   └── backups/
    └── logs/
        ├── stdout.log
        ├── stderr.log
        └── workbench.log
```

### 2.2 包体预算

| 组成 | 未压缩 | ZIP 后 | 说明 |
|---|---|---|---|
| 裁剪后 **JRE 8** | ~70 MB | ~30 MB | **Java 8 没有 jlink**，手工裁剪，清单见 §3.2 |
| Spring Boot fat jar | ~42 MB | ~36 MB | 内嵌 Tomcat 9 + Spring 5.3 + Jackson + sqlite-jdbc + Flyway |
| 前端静态资源（在 jar 内） | ~2 MB | — | 复用现有 demo，原生 HTML/CSS/JS |
| 脚本 + 配置 + 说明 | < 0.1 MB | < 0.1 MB | |
| 空数据库 | 0 | 0 | 首次启动由 Flyway 创建 |
| **合计** | **~114 MB** | **~70 MB** | 微信文件传输可直接发；**邮件附件可能超限，建议用网盘** |

---

## 3. 七个必做组件

### 3.1 单 jar 交付：前端静态资源打进 jar

**不要**让同事跑两个进程（后端 18080 + 前端 Node/Vite 5173），那只会出现「打不开」「端口不对」「跨域」三连问。

做法：
- 前端构建产物（`index.html` + `assets/`）放到 `src/main/resources/static/`
- Maven 打包时自动进 jar
- Spring Boot 直接以 `/` 提供，浏览器只认 `http://localhost:18080` 一个地址

**省掉 Node 构建链的建议**：现有 demo 是纯静态 HTML（70KB 主页面），MVP 阶段**直接用原生 HTML/CSS/JS + fetch 调 API**，不引入 Vue 构建。这样：
- 你本机不需要装 Node
- 不需要 `frontend-maven-plugin`
- 打包链路只剩 `Maven` + `复制预置 runtime/` 两步

**切换点**：当前端代码超过约 3000 行、或需要复杂组件复用时，再引入 Vue + Vite。届时构建链多一步，但不影响同事侧。

### 3.2 预置裁剪后的 JRE 8（Java 8 没有 jlink）

> **v1.1 变更**：`jlink` 是 Java 9（JEP 282）才引入的，Java 8 用不了。

改为三段式：

1. 下载 **Eclipse Temurin JRE 8**（选 zip 版，不要安装包），解压
2. 按裁剪清单删文件，目标约 **70 MB**
3. 放进 `runtime/`，并在构建缓存里留一份基准副本

裁剪清单（必删 / 可删需验证 / 禁止删三档）见 `docs/开发文档-Java8版.md` §0.4。

三条硬要求：

1. **每次改裁剪清单后，必须在一台干净 Windows 上跑完整功能冒烟**——缺文件要到运行期才炸
2. 裁剪好的 `runtime/` 存一份基准副本到 `dist/runtime/`，后续构建用 `-SkipJre` 直接复制，避免重复裁剪出错
3. `runtime/bin/java.exe` 必须存在，`build-release.ps1` 里有硬校验

### 3.3 启动器（start-workbench.cmd + launcher.ps1）

启动器是「解压即用」体验的成败关键，必须做完这 7 件事：

| # | 能力 | 实现 |
|---|---|---|
| 1 | 定位自带 JRE | 用 `runtime\bin\java.exe`，**绝不使用系统 `JAVA_HOME`** |
| 2 | 准备目录 | 自动创建 `data/`、`data/attachments/`、`data/backups/`、`logs/` |
| 3 | 防重复启动 | 检测是否已有 `workbench.jar` 进程，有则直接开浏览器 |
| 4 | 端口占用检测 | 18080 被占时给出**中文提示 + 改端口指引**，而不是抛异常 |
| 5 | 后台启动 | 隐藏窗口启动，stdout/stderr 重定向到 `logs/` |
| 6 | 健康检查 | 轮询 `/actuator/health`，最长等 60s |
| 7 | 自动开浏览器 | 就绪后 `start http://localhost:18080` |

JVM 参数（单用户小堆，SerialGC 更省内存）：

```
-Xms128m -Xmx256m
-XX:MaxMetaspaceSize=128m            ← Java 8 是元空间，不设上限长期运行有 OOM 风险
-XX:+UseSerialGC                     ← 小堆单用户，比并行 GC 更省内存
-Dfile.encoding=UTF-8
-Duser.timezone=Asia/Shanghai        ← 必须显式指定，否则跨时区同事的「今天」会错
-Dworkbench.home=<包目录>
-Dworkbench.data=<包目录>/data
```

预期占用：RSS 约 150–220 MB，冷启动到可访问 ≤ 15s（SSD）/ ≤ 25s（机械盘）。

### 3.4 停止与优雅关闭

不要用 `taskkill /IM java.exe`（会误杀同事的其他 Java 程序）。

正确顺序：
1. 读 `data/workbench.pid` 拿到 PID
2. 先调 `POST /api/v1/system/shutdown` 请求优雅停机（受本机会话保护；Flush 数据、关闭 SQLite 连接；`/actuator/shutdown` 不对外暴露）
3. 等待 8s，若进程仍在则 `taskkill /PID <pid>`
4. 删除 pid 文件

### 3.5 数据隔离：包里绝不能带你的个人信息

打进 ZIP 的内容必须是「干净实例」。构建脚本里必须有一步清空检查：

| 必须剔除 | 位置 |
|---|---|
| 你的 SQLite 数据库 | `data/workbench.db`（打包前删掉） |
| 你的登录口令 | 无预置账号，首次启动由向导创建 |
| 你的 AI API Key | `config/application.yml` 只留占位符 |
| 你的企业微信凭证 | 版本 A 不含此功能 |
| 你的 Obsidian Vault 路径 | 无预置，首次配置时选 |
| 你的语音文件 | `data/attachments/` 打包前清空 |
| 你的日志 | `logs/` 打包前清空 |

构建完成后，**必须在一台干净机上解压验证一次**，确认首屏是「首次配置向导」而不是你的数据。

### 3.6 首次配置向导

浏览器内完成，不要求改任何配置文件。

**必填（只有 3 项）**
1. 管理员用户名
2. 管理员密码
3. 时区（默认 `Asia/Shanghai`）

**可跳过**

| 可选能力 | 需填内容 |
|---|---|
| AI 整理 | 模型地址、API Key、模型名称 |
| Obsidian 同步 | Vault 文件夹路径、Vault 名称 |

配置写入 `data/workbench.db` 的 `app_config` 表，不写回 jar 内的 `application.yml`（否则升级会被覆盖）。

### 3.7 备份、恢复与升级

**备份（关键细节）**：SQLite 在 WAL 模式下直接复制 `workbench.db` 三个文件**可能得到不一致快照**。正确做法是用 SQLite 自带的 `VACUUM INTO`（3.27+ 支持，xerial sqlite-jdbc 3.4x 内置）：

```sql
VACUUM INTO 'data/backups/workbench-20260905-0200.db';
```

策略：
- 每日 02:00 应用内定时备份，保留 14 份
- 附件目录单独压缩
- 升级前强制备份一次

**恢复**：停服务 → 用备份文件覆盖 `data/workbench.db` → 启动。

**升级（同事侧 4 步）**：
1. 双击 `备份数据.cmd`
2. 解压新版本 ZIP 到**新目录**（不要覆盖旧目录）
3. 把旧目录的 `data/` 整个复制到新目录
4. 双击 `启动工作台.cmd` → Flyway 自动迁移表结构

旧目录保留到确认新版无问题再删——这是升级失败的兜底。

---

## 4. 数据层改造：MySQL 8 → SQLite

现有 `P0-MVP需求拆解与数据模型设计.md` 的表定义基于 MySQL 8，需按下表改写：

| MySQL 8 类型 | SQLite 类型 | 说明 |
|---|---|---|
| `BIGINT PK AUTO_INCREMENT` | `INTEGER PRIMARY KEY` | SQLite 的 `INTEGER PRIMARY KEY` 即 rowid 别名，自增 |
| `DATETIME` | `TEXT` | 存 ISO-8601 `YYYY-MM-DD HH:MM:SS`，**统一本地时区** |
| `DATE` | `TEXT` | `YYYY-MM-DD` |
| `DECIMAL(3,2)` / `DECIMAL(4,3)` | `REAL` | 置信度、完成率不需要精确十进制 |
| `TINYINT` | `INTEGER` | 0/1 布尔 |
| `VARCHAR(n)` | `TEXT` | 长度约束移到应用层校验 |
| `JSON` | `TEXT` | `ai_payload`，可用 SQLite json1 扩展的 `json_extract` |

**连接参数（必须设置）**：

```sql
PRAGMA journal_mode = WAL;       -- 读写不互相阻塞
PRAGMA synchronous = NORMAL;     -- WAL 下兼顾安全与性能
PRAGMA foreign_keys = ON;        -- SQLite 默认是关的！
PRAGMA busy_timeout = 5000;      -- 锁等待 5s，避免瞬时 SQLITE_BUSY
```

**ORM 选型建议：用 JdbcTemplate，不用 Hibernate + JPA。**

理由：Hibernate 6 没有官方 SQLiteDialect，需要引第三方 community dialect，会平白引入版本兼容风险。本项目 SQL 简单、数据量小、单用户，JdbcTemplate + 手写 SQL 更轻、更可控，也让 Flyway 脚本和代码 SQL 保持同一套方言。

**全文检索**：MVP 沿用 `LIKE`（单用户数据量小，够用）；量上来后 SQLite 内置 **FTS5**（xerial sqlite-jdbc 已编译进去），比 MySQL 的 LIKE 更强，无需引入外部搜索引擎。

**Flyway**：`flyway-core` 社区版支持 SQLite。注意 SQLite 不支持并发迁移，而我们本就是单实例单进程，正好满足。

**迁移脚本目录**：

```
src/main/resources/db/migration/
├── V1__init_schema.sql
├── V2__add_task_postponed.sql
├── V3__add_memo_fields.sql
└── ...
```

---

## 5. 功能取舍：版本 A 只保留「纯本地」能力

要让「解压即用」真正成立，就必须砍掉一切依赖公网或外部服务的功能。

| 能力 | 版本 A | 原因 |
|---|---|---|
| 驾驶舱 / 收集箱 / 任务 / 日程 / 备忘 / 番茄钟 / 时间线 | **完整可用** | 纯本地，零外部依赖 |
| AI 整理 | 可选，需填配置 | 调外部 LLM HTTPS API；不填则功能隐藏，其他一切照常 |
| Obsidian 同步 | 可选，需选 Vault 路径 | 需用户授权本地目录 |
| 企业微信接入 | **不提供** | 需要公网 HTTPS 回调地址 + 自建应用凭证，普通同事无法满足。版本 A 直接不暴露入口 |
| 语音 ASR | **不提供** | 需额外 ASR 服务或本地模型（体积大）。改为文字录入 |
| 远程访问 / 手机访问 | **不提供** | 需内网穿透或 HTTPS 域名，超出「普通同事免安装」范畴 |

**这就是版本 A 的产品定义：一个纯本地、单用户、无需网络的个人工作台。** 需要企业微信和手机访问的人，走版本 B（Docker）。

---

## 6. 构建流水线（你本机一次性投入）

```
1. mvn clean package -Pprod
   └─> target/workbench.jar（含前端静态资源）

2. 复制已裁剪好的 JRE 8（`dist/runtime/`）→ `runtime/`

3. build-release.ps1 组装
   ├─ 复制 workbench.jar      → app/
   ├─ 复制 runtime/           → runtime/
   ├─ 复制 launcher.ps1       → scripts/
   ├─ 生成 config/application.yml（占位符）
   ├─ 创建空的 data/ logs/
   ├─ 清理一切个人数据（硬校验）
   └─ 压缩为 personal-workbench-v1.0.0-win.zip
```

仓库内建议目录：

```
personal-workbench/
├── server/                 ← Spring Boot 2.7.18 后端（Java 8）
├── web/                    ← 静态前端（现有 demo 改造）
├── deploy/
│   ├── start-workbench.cmd
│   ├── stop-workbench.cmd
│   ├── backup-workbench.cmd
│   ├── build-release.ps1
│   └── scripts/launcher.ps1
└── docs/
```

---

## 7. 排期与工作量

| # | 任务 | 人日 | 关键产出 |
|---|---|---|---|
| 1 | Spring Boot **2.7** 骨架 + Flyway + SQLite 接通 | 1.0 | 启动即建库 |
| 2 | SQLite DDL 全量表 + JdbcTemplate 仓储层 | 1.5 | 12 张表 + DAO |
| 3 | API 层（收集箱/任务/日程/备忘/驾驶舱/番茄/时间线） | 3.5 | REST 接口 |
| 4 | 前端：现有 demo 去 mock、接真实 API | 2.0 | 静态资源 |
| 5 | 首次配置向导（前后端） | 1.0 | 3 必填 + 2 可选 |
| 6 | 备份/恢复 + 每日定时任务 | 1.0 | VACUUM INTO |
| 7 | JRE 8 裁剪 + build-release.ps1 + ZIP 产出 | 1.5 | 构建脚本 |
| 8 | launcher.ps1 + 三个 .cmd | 1.0 | 启动/停止/备份 |
| 9 | 干净机验收（3 台：无 Java / 有 Java 8 / Win11） | 1.0 | 验收报告 |
| 10 | 使用说明.txt + 常见问题 | 0.5 | 文档 |
| | **合计** | **14** | |

> 比 Java 17 版多 0.5 人日，增量在 JRE 8 的裁剪与干净机验证。
> 分阶段明细见 `docs/开发文档-Java8版.md` §9。

后置（不计入版本 A）：AI 整理 1.5、Obsidian 同步 1.0、企业微信 2.0。

---

## 8. 验收清单（8 条硬指标）

版本 A 只有全部满足才能说「解压即用」：

| # | 验收项 | 标准 |
|---|---|---|
| 1 | 干净机可用 | 一台**没有 Java、没有 MySQL、没有 Node** 的 Windows 10/11 能跑起来 |
| 2 | 启动耗时 | 双击到浏览器打开首次配置页 ≤ 60s |
| 3 | 核心闭环 | 设置账号后能创建任务、写备忘、建日程 |
| 4 | 数据持久化 | 关闭后重新启动，数据仍在 |
| 5 | 升级不丢数据 | 换新版后旧数据 100% 可读 |
| 6 | 可备份可恢复 | 备份文件能在另一台机器完整恢复 |
| 7 | 降级可用 | 无 AI、无 Obsidian 时，核心功能完整可用 |
| 8 | 干净实例 | 首次打开是配置向导，不含任何开发者个人数据 |

---

## 9. Windows 特有的 10 个坑

| # | 坑 | 处理 |
|---|---|---|
| 1 | **中文/空格路径** | 解压到 `D:\personal-workbench`。**绝对不要放桌面或 OneDrive**——OneDrive 实时同步会锁住 `workbench.db-wal`，导致数据库损坏 |
| 2 | **脚本编码** | `.cmd` 用 **GBK**，`launcher.ps1` 用 **UTF-8 with BOM**。混用必乱码 |
| 3 | **PowerShell 执行策略** | 必须用 `powershell -ExecutionPolicy Bypass -File`。ps1 双击默认用记事本打开，所以必须有 `.cmd` 外壳 |
| 4 | **杀毒软件误报** | java.exe 监听端口 + 自写启动器，常被火绒/360/Defender 拦截。使用说明里要写明「若被拦截，加入信任区」 |
| 5 | **18080 端口被占** | 本机已有其他本地服务。脚本需自动检测并给出改端口的具体指引 |
| 6 | **UAC 与写权限** | 解压到 `C:\Program Files` 会因无写权限导致建库失败。默认引导到非系统盘 |
| 7 | **关机/休眠强杀进程** | WAL 可恢复，但仍需 stop 脚本走优雅停机；不要直接断电后怪数据库 |
| 8 | **时区** | 启动参数必须显式 `-Duser.timezone=Asia/Shanghai`，否则跨时区同事的「今天」会算错 |
| 9 | **解压软件** | Win10+ 自带 zip 支持；如需更稳，可额外提供 7z 自解压 exe |
| 10 | **开机自启（可选）** | 用 `shell:startup` 放快捷方式，**不要注册 Windows 服务**（需要管理员权限） |

---

## 10. 下一步

1. 建 `server/` Spring Boot **2.7.18** 工程（**Java 8**），接 Flyway + SQLite，先跑通「启动即建库」
2. 按 §4 把 12 张表的 DDL 落成 `V1__init_schema.sql`
3. 把现有 demo 的 `index.html` 复制进 `web/`，先接 `/api/v1/tasks` 一个接口跑通全链路
4. 跑通后再做 jlink 和打包脚本——**打包是最后一公里，不要提前做**

打包脚本骨架已生成在 `deploy/` 目录，可直接作为起点。
