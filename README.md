# 个人工作台 · Personal Workbench

> **一个纯本地、单用户、断网可用的 Windows 个人工作台。**
> 脑子里冒出来的事 5 秒记下来，系统帮你分好类，每天早上打开就知道今天先干什么。

[![Java](https://img.shields.io/badge/Java-8-orange.svg)](#技术栈)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](#技术栈)
[![SQLite](https://img.shields.io/badge/SQLite-3.41-blue.svg)](#技术栈)
[![Frontend](https://img.shields.io/badge/frontend-vanilla%20JS-yellow.svg)](#技术栈)
[![Tests](https://img.shields.io/badge/tests-265%20passing-success.svg)](personal-workbench/server/src/test)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

---

## 界面预览

<p align="center">
  <img src="docs/images/01-dashboard.png" width="49%" alt="驾驶舱：今日聚焦与六项可下钻指标">
  <img src="docs/images/02-tasks.png" width="49%" alt="任务看板：待办 / 进行中 / 已完成三泳道">
</p>
<p align="center">
  <img src="docs/images/03-schedule.png" width="49%" alt="日程：下一周 / 本周 / 上一周三段手风琴">
  <img src="docs/images/04-inbox.png" width="49%" alt="收录：一句话入库，确认后生成任务 / 日程 / 收藏 / 知识">
</p>
<p align="center">
  <img src="docs/images/05-favorites.png" width="70%" alt="收藏：分组、置顶、全文检索">
</p>

> 截图取自全新实例的首次配置向导（勾选示例数据后自动装载），不含任何真实用户数据。

---

## 这是什么

给每天要处理大量碎片事务的人，做一个**装在自己电脑上、不用联网、解压即用**的个人工作台。

它**不是一个待办清单 App**。市面上待办工具失败的原因不是功能不够，而是**录入太慢**：打开 App、选清单、填标题、设日期、选优先级——五步之后，那件事已经忘了。

本项目的全部设计都围绕一个目标：

> **把「记录一件事」的摩擦降到接近零，把「决定今天先做什么」的成本降到零。**

### 三条不可妥协的产品原则

| 原则 | 含义 | 违反的代价 |
|---|---|---|
| **P1 零摩擦录入** | 一句话、一个回车、进收集箱。不要求当场分类、定优先级、选日期 | 用户不用了，因为记一条太麻烦 |
| **P2 每天 15 分钟清空** | 收集箱必须能快速清空，而不是越积越多 | 收集箱变成第二个垃圾桶 |
| **P3 本地优先、可复制** | 一台没装任何开发环境的 Windows 电脑，解压即用；数据只在本机 | 无法交付给他人，产品失去传播价值 |

### 三条产品定义约束

任何需求与它冲突时以此为准：

1. **纯本地** — 不依赖任何公网服务；AI 是可选增强，关掉后核心功能 100% 可用
2. **单用户** — 无多账号、无协作、无权限体系；登录口令只防家人误触
3. **无需网络** — 断网状态下除 AI 整理外，所有功能正常

---

## 功能一览

| 模块 | 说明 |
|---|---|
| **收集箱** | 一句话回车即入库，不做任何分类要求。可接本地规则或 LLM 自动整理为任务 / 日程 / 收藏 / 知识四类 |
| **驾驶舱** | 今日聚焦：只推 Top 3，六个指标全部可下钻；附今日番茄钟与每日计划 |
| **任务看板** | 待办 / 进行中 / 已完成三泳道，拖动即改状态，严格后端状态机；支持顺延与**一键复制到今天** |
| **日程周视图** | 纵向「下一周 / 本周 / 上一周」三段手风琴，任何时刻只展开一周；支持待定时间区与重复日程 |
| **收藏** | 工作 / 生活分组、主题配色、置顶、全文检索；标题与正文至少写一项即可 |
| **知识库** | 收录知识条目，对接本地 Obsidian 仓库，支持 AI 问答（可选增强） |
| **全局搜索** | 顶栏常驻入口，跨五类实体 + 回收站，左列表右预览分栏，支持语义联想 |
| **附件与图片解析** | 图 10MB / 文档 100MB；图片走多模态模型自动识别，可「识别填表」或生成待确认条目 |
| **文章收录** | 粘贴分享链接或直接拖入分享卡片，解析标题 / 作者 / 正文后归档进 Obsidian |
| **番茄钟** | 单次时长与休息可配，计入驾驶舱今日统计 |
| **回收站** | 任务 / 日程 / 收藏 / 知识 / 收录五类软删统一入回收站，保留 30 天可恢复 |
| **时间线** | 全量操作流水（`activity_log`），物理删除需二次确认 |
| **跨实体搬运** | 一条记录在任务 ↔ 日程 ↔ 收藏 ↔ 知识之间转移，一个事务内完成「新建 + 软删 + 记流水」，有损字段明确告警 |
| **备份与恢复** | 每日自动备份 + 手动备份 / 恢复，SQLite 连接池挂起式安全替换 |
| **两侧竖排诗词** | 宽屏（≥1380px）时在左右留白区渲染竖排诗词，刷新换一首，可选中复制；点开可全屏品读 |

---

## 快速开始

### 方式一：解压即用（普通用户，推荐）

拿到 `personal-workbench-vX.Y.Z-clean-win.zip` 之后：

```text
1. 解压到普通目录（例如 D:\个人工作台）
   ⚠ 不要放桌面 / OneDrive 或任何自动同步目录 —— 同步软件会锁住数据库文件
2. 双击「start-workbench.cmd」
   ├─ 自动检查端口占用 / 数据目录权限 / 防止重复启动
   ├─ 首次自动建库，用户无感
   └─ 健康检查通过后自动打开浏览器
3. 首次进入会引导完成初始化（管理员用户名 / 密码 / 时区），其余配置可「稍后设置」
```

包内**自带裁剪好的 JRE 8**，目标机不需要装 Java、Maven、Node 或 Docker。

> 使用者**永远不需要知道** Java、Spring Boot、SQLite、Flyway、Maven 这些词——它们不应出现在交付包的用户可见文本里。

### 方式二：源码运行（开发者）

前置条件：**JDK 8**（必须是 8，`pom.xml` 里有强制校验）+ **Maven 3.8 ~ 3.9**（或直接用内置 wrapper）。

```bash
git clone https://github.com/Solomon258/myWorkSpace.git
cd myWorkSpace/personal-workbench/server

./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
# 打开 http://localhost:18080
```

打开 `http://localhost:18080` 会跳到首次配置页；完成初始化后即可使用。

> **端口说明**：默认 **18080**。若被占用，用 `--server.port=你的端口` 覆盖，注意要写在 `-jar` 之后。

### 方式三：Docker（可选）

目标机已经装了 Docker Desktop 时可用：

```bash
cd personal-workbench
docker compose -f docker-compose.dev.yml up -d --build
docker compose logs -f
```

宿主机访问 `http://127.0.0.1:18080`。开发档会把源码目录挂进容器，改前端资源即时生效。

### 构建与测试

```bash
cd personal-workbench/server

./mvnw clean test        # 运行全部单测与集成测试（265 个用例）
./mvnw clean package     # 产出 target/workbench.jar
```

---

## 技术栈

| 层 | 选型 | 理由 |
|---|---|---|
| 运行时 | **Java 8** | 免安装包需自带 JRE，JRE 8 体积与兼容性最优 |
| 框架 | **Spring Boot 2.7.18** | 2.7.x 是最后一个支持 Java 8 的 Spring Boot 主线 |
| 数据库 | **SQLite + HikariCP** | 单文件、零运维；连接池规避 Windows 上单连接固定开销 |
| 迁移 | **Flyway** | 首次启动自动建库，用户无感 |
| 前端 | **原生 HTML / CSS / JS** | 零框架、零构建步骤，免安装包内直接静态托管 |
| 打包 | **Maven Wrapper** | 使用者无需预装 Maven |

**刻意不引入的东西**：Node 构建链、前端框架、Redis、消息队列、Docker（免安装包形态不依赖）。

---

## 架构

```mermaid
flowchart LR
    subgraph Browser["浏览器 · 零框架前端"]
        UI["index.html<br/>app.js · style.css"]
    end

    subgraph Server["Spring Boot 2.7.18 · Java 8"]
        API["REST API<br/>55 个端点 / /api/v1/**"]
        SVC["Service 层<br/>22 个业务模块"]
        FLY["Flyway<br/>V1 - V13 迁移"]
    end

    DB[("SQLite<br/>workbench.db")]
    OPT["可选增强<br/>LLM 整理 / 图片解析 / Obsidian"]

    UI -->|"fetch JSON"| API
    API --> SVC
    SVC --> DB
    FLY --> DB
    SVC -.->|"关掉不影响核心功能"| OPT
```

后端为单模块 Maven 工程，包名 `com.icecode.workbench`，按业务域分包：

```text
auth        登录与首次初始化            poem        两侧诗词与全屏品读
attachment  附件与多态外键绑定          pomodoro    番茄钟
collect     文章收录（分享页解析）       schedule    日程（周视图 / 待定区 / 重复期次）
common      统一响应 / 错误码 / 异常     search      全局搜索与语义联想
config      数据源 / WebMvc / 目录初始化  settings    设置（AI / Obsidian / 个人资料）
dashboard   驾驶舱指标与下钻            system      备份与恢复、每日定时备份
demo        演示数据                    task        任务与状态机
favorite    收藏                        timeline    操作时间线
inbox       收集箱与 AI 整理            transfer    跨实体搬运
knowledge   知识库与 Obsidian           trash       回收站
vision      图片多模态解析              util        文本 / 时间工具
```

分层约定：`Controller → Service → Repository`。Controller 不写业务逻辑，SQL 收敛在 `*Repository`。

---

## 项目结构

```text
myWorkSpace/
├── README.md                    # 本文件
├── 开发需知.md                   # 开发前必读：环境、规范、红线、发布
├── CHANGELOG.md                 # 版本历史
└── personal-workbench/          # 主项目
    ├── server/                  # Spring Boot 后端 + 静态前端（唯一需要构建的部分）
    │   ├── src/main/java/       # 22 个业务模块
    │   ├── src/main/resources/
    │   │   ├── static/          # 零框架前端（index.html / app.js / api.js / style.css / poems.js）
    │   │   ├── db/migration/    # Flyway V1 - V13
    │   │   └── application.yml
    │   └── src/test/java/       # 集成测试（26 个测试类 / 265 个用例）
    ├── demo/                    # 可交互 HTML 原型（视觉与交互的事实标准）
    ├── deploy/                  # 启动 / 停止 / 备份 / 打包脚本
    │   ├── build_packages.py    # 唯一打包入口，一条命令产出两个交付包
    │   ├── docker/              # 可选的容器跑法
    │   └── scripts/             # 启动器（launcher.ps1）与前端冒烟脚本
    ├── docs/                    # 需求、设计、验收文档
    └── scripts/                 # 辅助校验脚本
```

> `dev-data/`、`dist/`、`logs/`、`output/`、`*.db` 均已加入忽略规则，**真实数据不会被提交**。

---

## 常见问题

<details>
<summary><b>端口 18080 被占用怎么办？</b></summary>

改写 `config/application.yml` 里的 `server.port`，或在启动命令的 `-jar` **之后**追加 `--server.port=18081`（写在前面会被 JVM 当成无法识别的选项）。
</details>

<details>
<summary><b>杀毒软件报毒 / 拦截启动？</b></summary>

火绒、360、Windows Defender 可能拦截「java.exe 监听端口 + 脚本启动器」这类组合行为。把整个解压目录加入信任区即可。
</details>

<details>
<summary><b>解压到桌面后数据丢了 / 起不来？</b></summary>

桌面、OneDrive、坚果云等同步目录会锁住 `workbench.db-wal`，导致启动失败或数据损坏。请解压到普通路径（推荐 D 盘根目录）。
</details>

<details>
<summary><b>AI 功能是必需的吗？</b></summary>

不是。AI 是**可选增强**：不配置 API Key 时，收集箱走本地关键词规则分类，核心功能 100% 可用；配置后才有 LLM 整理、图片识别、语义搜索与知识库问答。
</details>

<details>
<summary><b>数据存在哪？怎么迁移到别的电脑？</b></summary>

全部数据都在解压目录的 `data/` 下（`workbench.db` + `attachments/`）。整个 `data/` 拷走即可迁移；也可以用包内 `backup-workbench.cmd` 或界面上的「备份与恢复」。
</details>

<details>
<summary><b>为什么不用 Docker 做交付形态？</b></summary>

Docker 版要求目标机先装 Docker Desktop（约 1.2GB 安装包 + WSL2/Hyper-V + 通常需重启一次），与「解压即用」直接冲突。所以主交付形态是自带 JRE 的免安装包，包内仍附 `docker/` 目录作为可选跑法。
</details>

---

## 文档

| 你想知道的 | 看哪份 |
|---|---|
| **怎么改这份代码**（环境、规范、红线、发布） | [`开发需知.md`](开发需知.md) |
| 这项目是什么、解决什么问题、做到什么程度 | [`docs/产品设计说明.md`](personal-workbench/docs/产品设计说明.md) |
| 数据库表结构、DDL、API 契约、字段名 | [`docs/开发文档-Java8版.md`](personal-workbench/docs/开发文档-Java8版.md) |
| 打包成 ZIP、启动脚本、JRE 裁剪 | [`deploy/打包与分发说明.md`](personal-workbench/deploy/打包与分发说明.md) |
| Windows 免安装包的整体落地方案 | [`docs/版本A-Windows免安装包落地方案.md`](personal-workbench/docs/版本A-Windows免安装包落地方案.md) |
| 需求拆解、里程碑与数据模型 | [`docs/P0-MVP需求拆解与数据模型设计.md`](personal-workbench/docs/P0-MVP需求拆解与数据模型设计.md) |
| 全局搜索的设计取舍 | [`docs/全文搜索功能设计.md`](personal-workbench/docs/全文搜索功能设计.md) |
| 图片上传与解析的链路设计 | [`docs/图片上传与解析功能设计.md`](personal-workbench/docs/图片上传与解析功能设计.md) |
| 日程为何从按日改为周视图 | [`docs/日程周视图设计.md`](personal-workbench/docs/日程周视图设计.md) |
| 干净机验收步骤 | [`docs/干净机验收清单.md`](personal-workbench/docs/干净机验收清单.md) |
| 每个版本改了什么 | [`CHANGELOG.md`](CHANGELOG.md) |

> **文档与实现冲突时的裁决顺序**：`demo/` 下的原型（交互与视觉）＞ 产品设计说明（产品规则）＞ 开发文档（技术实现）＞ 本文档。
> 设计文档会滞后于代码，**以 Service 层的实际实现为准**。

---

## 参与贡献

欢迎提交 Issue 与 Pull Request。动手之前请先读 [`开发需知.md`](开发需知.md)，里面有环境要求、代码规范、**会造成静默失效的硬红线**和提交流程。

- [`开发需知.md`](开发需知.md) — 开发环境、架构约定、代码规范、分支与提交、发布流程
- [`CHANGELOG.md`](CHANGELOG.md) — 版本历史
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — 社区行为准则
- [`SECURITY.md`](SECURITY.md) — 如何私密报告安全问题

---

## 许可证

本项目基于 [MIT License](LICENSE) 开源。
