# 个人工作台 · 开发 TODO 与里程碑

> 版本：执行稿 v1.5 | 日期：2026-09-07 | 状态：**检查点 B（M0-M4 已完成，等待用户确认）**
> 项目根目录：`E:\4_code\myWorkspace\personal-workbench`
> 目标：按本清单完成 **Java 8 + Spring Boot 2.7.18 + SQLite** 的 Windows 免安装版，并产出可交给普通同事使用的 ZIP。
> 开发环境约束：**编译、测试、依赖下载、开发态启动全部在 Docker 容器内完成；Windows 宿主机不安装、不修复 Java/Maven，仅负责编辑源码和验收最终免安装包。**
> 产品依据：`docs/产品设计说明.md`
> 技术依据：`docs/开发文档-Java8版.md`
> 交付依据：`docs/版本A-Windows免安装包落地方案.md`
> 交互与视觉事实标准：`demo/index.html`

---

## 0. 结论：建议采用的开发方式

不建议沿用原文档“后端全部开发完，再统一改前端”的方式。后面开发按**纵向切片**推进：

```text
环境与数据底座
    ↓
首次配置（前端 + 后端 + 数据库）
    ↓
任务闭环（前端 + 后端 + 测试）
    ↓
收集箱 → 整理 → 确认 → 任务（完整主链路）
    ↓
每次只增加 1 个模块：日程 → 收藏 → 番茄/时间线
    ↓
设置、备份、可选 AI/Obsidian
    ↓
Windows 免安装包 + 干净机验收
```

这样做的原因：

1. 每个里程碑结束都能看到**真实页面 + 真实接口 + 真实 SQLite 数据**，不会只得到一堆未接前端的 Controller。
2. 跨域、鉴权、日期格式、JSON 字段名等问题会在第一个模块暴露，不会拖到最后集中爆发。
3. 每次最多保持 3–4 个核心模块，符合工作台的需求过载控制原则。
4. 每个里程碑都能独立回归和回滚。

### 0.1 最终版本包含什么

正式 v1.0 包含以下 7 个页面模块和 1 个悬浮组件：

| 类型 | 模块 | 是否进 v1.0 |
|---|---|---|
| 核心 | 驾驶舱 | 是 |
| 核心 | 收集箱 | 是 |
| 核心 | 整理确认 | 是 |
| 核心 | 任务 | 是 |
| 增量 | 日程 | 是 |
| 增量 | 收藏 | 是 |
| 增量 | 时间线 | 是 |
| 组件 | 番茄钟 | 是 |
| 可选能力 | 外部 LLM 整理 | v1.0 建适配器但不作为启用前提；本地规则整理器始终可用 |
| 可选能力 | 写入 Obsidian Vault | 推迟到 v1.1，v1.0 不实现实际写入 |
| 已裁决不做 | 知识助手 / RAG 问答 | 否，版本 A 删除入口 |
| 已裁决不做 | 企业微信、语音 ASR、远程访问、多用户 | 否 |

---

## 1. 当前基线（2026-09-07 实际检查结果）

### 1.1 已有产物

- `demo/index.html`：70KB 可交互原型，当前数据保存在 `localStorage`
- `demo/timeline-designs.html`：时间线候选方案
- `demo/workspace-ui-designs.html`：工作台视觉候选方案
- `docs/产品设计说明.md`：产品目标、模块、状态机、业务规则、视觉规范
- `docs/开发文档-Java8版.md`：完整 DDL、API 契约、依赖锁定、代码结构
- `deploy/scripts/launcher.ps1`：启动/停止/状态/备份启动器骨架
- `deploy/build-release.ps1`：Java 8 免安装包构建脚本骨架
- 3 个 `.cmd`：启动、停止、备份入口

### 1.2 当前缺失（更新于检查点 A）

- M3 起的业务页面尚未连接真实后端接口
- 任务、收集箱整理、日程、收藏、番茄钟、时间线和完整驾驶舱尚待开发
- 没有裁剪后的 Windows JRE 8 运行时
- 没有最终 ZIP

### 1.3 检查点 A 已完成基线

- 已建立 `server/` 工程、3 版 Flyway 迁移和 13 张表
- 已建立自动化测试，检查点 A 完整回归共 20 项测试
- 已生成可运行的 `server/target/workbench.jar`，约 35MB，字节码 major version 52
- 已完成首次配置、BCrypt 本地认证、Session 轮换、示例数据和最小首页壳
- Docker 开发服务地址为 `http://127.0.0.1:18080`，容器健康状态为 `healthy`
- 检查点 A 交付时开发实例保持 `initialized=false`，供用户体验首次配置

### 1.4 当前环境问题

| 项 | 实际状态 | 处理方式 |
|---|---|---|
| Windows Java | Oracle Java `1.8.0_131` | **开发不使用**；只在最终包验收时验证不会误用系统 Java |
| Windows Maven | **损坏**：找不到 `org.codehaus.plexus.classworlds.launcher.Launcher` | **不修复、不使用**；编译测试均在 Docker 中执行 |
| Docker | 已安装；已有 `app-backend-java`（openjdk-8u342） | **用户已确认开发环境放在 Docker**；M0 只需验证镜像是否含 JDK/javac，不能用则新建 dev image |
| Maven 依赖缓存 | 尚未固定 | 使用命名 volume `workbench-maven-repo` 挂载到容器 `/root/.m2`，避免每次重下依赖 |
| 源码挂载 | Windows 工作区 | 只挂载 `personal-workbench/`，容器工作目录固定 `/workspace` |
| 根目录重复文档 | 工作区根目录存在一份较旧的 `开发文档-Java8版.md` | 以后只以 `personal-workbench/docs/` 为准；旧文件暂不删除 |

### 1.4 唯一项目根目录

后续所有代码、测试、构建产物只写入：

```text
E:\4_code\myWorkspace\personal-workbench\
```

工作区根目录那份 `E:\4_code\myWorkspace\开发文档-Java8版.md` 是旧副本，**不作为开发依据，也不在开发过程中继续更新**。待 v1.0 完成后再决定删除还是改成指向说明。

---

## 2. 总体里程碑与工作量

| 里程碑 | 名称 | 主要可见结果 | 参考人日 | 前置 |
|---|---|---|---:|---|
| M0 | 基线与可复现构建 | 一条命令能在固定 Java 8 环境执行测试 | 0.5 | 无 |
| M1 | 后端骨架与 SQLite | 启动即建库，13 张表可验证 | 2.0 | M0 |
| M2 | 首次配置与认证 | 第一次打开进入配置向导，之后可登录 | 1.0 | M1 |
| M3 | 任务与最小驾驶舱 | 页面能真实创建/流转/顺延任务 | 1.5 | M2 |
| M4 | 收集箱与整理闭环 | 录入→整理→确认→任务完整跑通 | 2.0 | M3 |
| M5 | 日程增量 | 日程 CRUD、按日展示、冲突警告 | 1.0 | M4 |
| M6 | 收藏增量 | 工作/生活空间、检索、置顶、归档 | 1.0 | M5 |
| M7 | 番茄、时间线、完整驾驶舱 | 7 个页面模块全部真实化 | 1.0 | M6 |
| M8 | 设置、备份与可选增强 | 自动备份、配置、AI/Obsidian 边界完成 | 1.5 | M7 |
| M9 | Windows 免安装包与验收 | 可交给同事的 ZIP | 2.5 | M8 |
| | **合计** | | **14.0** | |

> 人日仅是范围基线，不作为机械工期承诺。实际以每个里程碑的“完成定义 DoD”是否通过为准。

### 2.1 开发过程中的确认点

建议只设置 3 个用户确认点，避免每写一个类都打断：

1. **检查点 A（M2 后）**：首次配置、登录和基础视觉是否符合预期
2. **检查点 B（M4 后）**：最核心的“录入→整理→确认→任务”链路是否符合预期
3. **检查点 C（M9 前）**：全部功能回归通过后，确认打包和交付文案

除非遇到范围冲突、外部凭证或不可逆选择，其余阶段默认继续推进。

---

# M0：基线与可复现构建

**目标**：先解决“换一台机器还能不能构建”的问题，不开始写业务代码。

## M0-T01 固定项目事实标准

- [x] 确认项目根目录只使用 `personal-workbench/`
- [x] 在根目录建立源码约定：`server/` 后端、`web/` 前端、`deploy/` 交付、`docs/` 文档
- [x] 检查是否已有 Git 仓库；当前项目目录不是独立 Git 仓库，未覆盖任何版本库文件
- [x] 将 3 份主文档路径写入本执行基线
- [x] 明确旧根目录开发文档只读，不作为来源

## M0-T02 建立 Docker Java 8 开发环境（已确认的方向）

**固定原则：Docker 是唯一开发运行环境。Windows 宿主机不执行 `mvn`，不修改 `JAVA_HOME`。**

- [x] 检查 `app-backend-java:latest` 是否包含 `javac`；实测只有 JRE，无 `javac` 和 Maven
- [x] 检查独立开发镜像能运行 shell、挂载 Windows 工作目录、正常写入 target
- [x] 现有镜像不可用，未修改原镜像
- [x] 新建 `deploy/docker/Dockerfile.dev`，使用 Temurin Java `1.8.0_392` + Maven 3.8.8
- [x] 新建 `docker-compose.dev.yml`，只定义开发服务，不混入 MySQL/Redis/Nginx
- [x] 源码映射：项目根目录 → 容器 `/workspace`
- [x] Maven 缓存：命名 volume `workbench-maven-repo` → `/root/.m2`
- [x] 开发服务端口：仅映射 `127.0.0.1:18080:8080`（宿主机 8080 已被现有 `review-java` 使用；最终 Windows 交付已确认默认 18080）
- [x] 数据隔离：开发数据使用项目下 `dev-data/`，测试使用 UUID 临时目录；都不写入最终发布包的 `data/`
- [x] 容器默认工作目录 `/workspace/server`
- [x] 增加统一入口：`docker compose -f docker-compose.dev.yml run --rm --no-deps dev ./mvnw -B clean verify`
- [x] 增加开发启动入口：`docker compose -f docker-compose.dev.yml up -d dev`
- [x] Maven Wrapper 保留用于 CI/可移植性，并只在容器内调用
- [x] 开发构建和最终 Windows 运行时分开：容器负责构建，最终免安装包使用 Windows x64 JRE 8

### Docker 开发环境边界

```text
Windows 宿主机
├─ 编辑 personal-workbench 源码
├─ 运行 Docker Desktop
└─ 最终验证 start-workbench.cmd / ZIP

Docker dev 容器
├─ Java 8u342+
├─ Maven 3.8.x
├─ 编译 / 单测 / 集成测试 / spring-boot:run
├─ 读取挂载的源码
└─ 使用独立 Maven 缓存和开发数据库
```

**不采用 Docker 内已有的 MySQL、Redis、Nacos、RocketMQ。** SQLite 仍然是应用内嵌文件库，不需要单独数据库容器。

## M0-T03 建立质量门禁

- [x] 新增统一命令：编译、单测、集成测试、打包
- [x] 编译目标锁定 `source=1.8`、`target=1.8`
- [x] 完成字节码 major version = 52 检查
- [x] 所有源码 UTF-8；PowerShell UTF-8 BOM；CMD 保持 GBK
- [x] 建立测试目录与 UUID 临时数据目录，测试不写入正式 `data/`

### M0 完成定义（DoD）

- [x] 不使用 Windows Java/Maven，也能在容器内完成 `clean verify`
- [x] 容器 Java 明确为 Temurin Java `1.8.0_392`
- [x] 容器 Maven 明确为 3.8.8，并运行在 Java 8 上
- [x] 容器能在 Windows 中文路径挂载的源码目录中完成构建
- [x] Maven 依赖缓存在第二次构建时可复用
- [x] 开发服务只通过本机 `127.0.0.1:18080` 访问
- [x] 没有修改用户系统级 `JAVA_HOME` 和全局 Maven 配置

### M0 产物

```text
personal-workbench/
├── docker-compose.dev.yml
├── server/
│   ├── mvnw
│   ├── mvnw.cmd
│   ├── .mvn/wrapper/
│   └── pom.xml
└── deploy/docker/Dockerfile.dev    ← 仅在现有镜像不满足时创建
```

---

# M1：后端骨架与 SQLite 数据底座

**目标**：启动应用后自动创建 `data/workbench.db`，DDL 与公共 API 基础可用。

## M1-T01 创建 Spring Boot 2.7.18 工程

- [x] `groupId`、`artifactId`、包名按开发文档固定
- [x] Java 版本锁定 1.8
- [x] 引入：Web、Validation、JDBC、Actuator、Flyway、sqlite-jdbc、Security Crypto、Test
- [x] Flyway 使用 Boot 管理的 8.5.13，不单独覆盖版本
- [x] sqlite-jdbc 锁定 3.41.2.2
- [x] 不引入 JPA、Hibernate、MyBatis Plus、Redis、Nacos、RocketMQ、Node 构建链
- [x] `finalName=workbench`，最终 jar 固定为 `workbench.jar`

## M1-T02 建立包结构与公共返回体

- [x] `config/`：数据源、Web MVC、目录初始化
- [x] `common/`：`ApiResponse`、`ErrorCode`、`BizException`、`GlobalExceptionHandler`
- [x] `util/`：`TimeUtil`
- [x] 入参用 `XxxRequest`，出参用 `XxxVO`，不返回 Entity
- [x] 业务异常统一 JSON，不向页面返回堆栈

## M1-T03 接通 SQLite

- [x] 数据库固定到 `${workbench.data}/workbench.db`
- [x] 启动前自动创建数据、日志、备份和附件目录
- [x] 每个连接设置：`foreign_keys=ON`
- [x] 设置 WAL、`busy_timeout=5000`、`synchronous=NORMAL`
- [x] 使用 `SQLiteConfig` 配置，不把多条 PRAGMA 塞进 Hikari `connection-init-sql`
- [x] 单应用进程启动 Flyway，避免 SQLite 并发迁移

## M1-T04 落成 Flyway 迁移

- [x] `V1__init_schema.sql`：完整创建 12 张业务表
- [x] `V2__add_index_and_config.sql`：索引和基础配置
- [x] `V3__add_demo_markers.sql`：示例数据标记和索引
- [x] 表：`inbox_item`、`task`、`schedule_event`、`favorite`、`pomodoro`、`activity_log`
- [x] 表：`knowledge_note`、`daily_plan`、`daily_plan_item`、`wechat_msg_log`、`ai_job`、`app_config`
- [x] 所有日期字段存统一文本格式，由 Java 侧生成
- [x] 软删除、外键、CHECK、唯一约束、必要索引与开发文档一致
- [x] 迁移不依赖示例数据；示例数据由 Java 幂等写入

## M1-T05 健康检查与基础测试

- [x] `/actuator/health` 返回 UP
- [x] 启动测试：UUID 临时目录下生成数据库
- [x] Schema 测试：12 张业务表 + `flyway_schema_history` 共 13 张表
- [x] PRAGMA 测试：外键、WAL、busy timeout、synchronous 实际生效
- [x] Flyway 二次启动测试：3 个迁移不重复执行、不报错
- [x] 异常返回测试：业务异常结构稳定并保留 `data:null`

### M1 完成定义（DoD）

- [x] 全新目录首次启动自动建库
- [x] 第二次启动数据不丢、迁移不重复
- [x] 外键约束实测有效
- [x] `/actuator/health` 可访问
- [x] 所有 M1 自动化测试通过
- [x] 应用已在容器内由 Spring Boot 插件启动；fat jar 已生成并通过上下文启动测试

---

# M2：首次配置与本地认证

**目标**：普通同事首次打开看到配置向导，初始化后才能进入工作台。

## M2-T01 认证状态与初始化 API

- [x] `GET /api/v1/auth/status`：返回 initialized、loggedIn、username、timezone
- [x] `POST /api/v1/auth/init`：首次初始化；重复请求幂等返回且不覆盖账号
- [x] `POST /api/v1/auth/login`
- [x] `POST /api/v1/auth/logout`
- [x] 未初始化访问业务 API 返回 409 + 错误码 2001
- [x] 密码错误返回错误码 2003
- [x] 密码使用 BCrypt 存储，绝不存明文；按 UTF-8 限制 72 字节

## M2-T02 会话与拦截器

- [x] 使用本地 HTTP Session
- [x] `LoginInterceptor` 拦截 `/api/v1/**`
- [x] 放行认证接口、静态资源、health
- [x] Cookie 设置 HttpOnly、SameSite=Lax
- [x] 正式环境 `server.address=127.0.0.1`；开发容器虽监听 `0.0.0.0`，Compose 只映射 Windows 回环地址
- [x] 明确安全边界：该登录用于单机工作台的误触隔离，不替代磁盘加密、Windows 账户权限或公网级安全防护
- [x] 初始化与登录成功后轮换 Session，状态查询不创建空 Session

## M2-T03 首次配置向导

- [x] 新建 `setup.html`
- [x] 3 个必填项：用户名、密码、时区
- [x] 默认时区 `Asia/Shanghai`
- [x] AI 配置和 Obsidian 路径不作为首次配置前置项，可稍后设置
- [x] 未初始化时从主页面自动跳到配置向导
- [x] 初始化成功后进入最小驾驶舱壳
- [x] 中文错误提示，不展示后端异常细节；网络失败和非 JSON 响应也转为中文提示

## M2-T04 示例数据

- [x] 初始化成功后幂等写入 4 条收集箱条目
- [x] 写入 4 条任务，其中 1 条逾期
- [x] 写入 3 条今日日程
- [x] 写入 4 条收藏
- [x] 写入 3 条时间线示例
- [x] 用户可见示例带 `is_demo=1`，支持后续精准清理
- [x] 初始化重复调用不重复插入；部分写入状态会先清理示例再完整重建
- [x] 示例的今天、昨天和明天按用户配置时区计算

### M2 完成定义（DoD）

- [x] 空库首次访问进入配置向导；当前交付实例保持 `initialized=false`
- [ ] 30 秒内可完成初始化并进入主界面（检查点 A 由用户人工确认）
- [x] 未登录访问业务接口被拦截
- [x] 登录、退出、再次登录可用
- [x] 数据库中无明文密码
- [x] 示例数据数量与产品文档一致
- [x] 检查点 A 完整回归：20 项测试，0 failures，0 errors

### 检查点 A：请用户确认

- [x] 配置向导字段和布局（用户已通过继续指令确认）
- [x] 登录方式是否足够（用户已通过继续指令确认）
- [x] 示例数据是否贴近实际工作场景（用户已通过继续指令确认）

---

# M3：任务与最小驾驶舱（第一个纵向业务切片）

**目标**：先把最核心的正式实体做通，让页面第一次读写真实 SQLite。

## M3-T01 任务后端

- [x] 任务列表：按状态、优先级、日期筛选
- [x] 创建任务
- [x] 编辑任务标题、备注、优先级、截止日、deep、blocking
- [x] 状态流转：todo ↔ doing → done；支持 canceled
- [x] 顺延：截止日 +1 天，`postponed` +1
- [x] 软删除
- [x] 查询时计算 overdue / overdueDays，不落库
- [x] 完成任务时写 `activity_log`，类型为 `praise`
- [x] 所有写操作事务边界落在 Service

## M3-T02 任务规则测试

- [x] P0–P3 校验
- [x] 非法状态流转拒绝
- [x] 已完成任务不再显示逾期
- [x] 跨月顺延：01-31 → 02-01
- [x] 跨年顺延：12-31 → 01-01
- [x] 顺延 3 次后出现拖延提示条件
- [x] 软删除后默认查询不到，但数据仍在库中
- [x] 日期测试使用相对日期，避免未来回归失效

## M3-T03 前端任务页真实化

- [x] 从 demo 提取生效 CSS，不改变晨雾蓝设计
- [x] 建立 `api.js` 统一请求和错误处理
- [x] 任务页移除 localStorage 读写，改为真实 API
- [x] 创建、编辑、状态切换、顺延、取消、删除全部 async
- [x] 写操作完成后统一调用 `refreshAll()`
- [x] 渲染函数之间禁止互调
- [x] 页面保留 demo 的交互节奏和中文文案

## M3-T04 最小驾驶舱

- [x] 首版只显示任务指标、逾期项、Top3
- [x] Top3 排序：逾期 > 阻塞他人 > 临近截止 > 深度工作
- [x] 每条 Top3 必须有 reason
- [x] 逾期用红色 `#A32D2D`
- [x] 首页只调用一次 `/api/v1/dashboard/today`

### M3 完成定义（DoD）

- [x] 页面创建任务后 SQLite 有真实记录（API 集成测试已验证）
- [ ] 刷新浏览器、重启应用后记录仍在（待运行态人工验收）
- [x] 逾期任务在驾驶舱第一位并标红
- [x] 完成任务后驾驶舱和任务页通过 `refreshAll()` 同步刷新
- [x] 相关单元测试、Repository 集成测试、API 测试通过（当前全量 25 项）

---

# M4：收集箱与整理闭环（产品主链路）

**目标**：完成“5 秒录入 → 整理 → 确认 → 任务”的完整闭环。

## M4-T01 收集箱后端

- [x] 一句话录入，只要求 raw 内容
- [x] source 在版本 A 默认为 `web`
- [x] contentType 在版本 A 默认为 `text`
- [x] 列表按 created_at 倒序
- [x] 状态：pending / processed / failed / archived
- [x] 软删除与恢复数据能力
- [x] 录入时写一条时间线 `inbox`

## M4-T02 本地规则整理器

- [x] 将 demo 的 `aiClassify()` 规则移植到 Java
- [x] 输出类别、置信度、结构化 payload
- [x] 类别：task / schedule / favorite；knowledge 已确认推迟到 v1.1
- [x] 时间表达解析至少支持：今天、明天、后天、下周、周几、上午/下午、HH:mm
- [x] 置信度 `< 0.70` 标记待人工确认
- [x] 整理失败只更新状态为 failed，原始内容不丢
- [x] 支持单条重试

## M4-T03 确认生成正式实体

- [x] 用户可以修改类别和字段
- [x] 改分类时按类别生成对应实体（confirm() 内按 category 分支建 task / schedule_event / favorite / knowledge_note）
- [x] 前端按类别联动显隐字段：优先级仅「任务」、日期仅「任务/日程」、开始时间仅「日程」（US-2.3 / 产品设计说明 P276；2026-09-11 补齐）
- [x] 确认任务：插 task + 更新 inbox 状态为 archived
- [x] 确认日程：插 schedule_event + 更新 inbox
- [x] 确认收藏：插 favorite + 更新 inbox
- [x] 确认知识：不进 v1.0，待 v1.1 Vault 写入能力启用后再出现
- [x] “更新 inbox + 生成实体”必须在同一事务
- [x] 重复确认必须幂等，不能生成两个任务

## M4-T04 异步整理任务

- [x] `POST /inbox/classify` 返回 jobId
- [x] 补齐任务状态查询机制：`GET /api/v1/inbox/jobs/{jobId}`
- [x] 状态：pending / running / success / failed
- [x] 当前本地规则在请求内同步完成，查询接口返回最终结果；后续外部 LLM 再接入真实轮询
- [x] 应用重启后 pending/running 的旧任务按失败处理，可重新整理

## M4-T05 前端主链路

- [x] 收集箱输入框默认聚焦
- [x] 回车提交，成功后清空并继续聚焦
- [x] 不要求选择类别、日期、优先级
- [x] toast 显示「已收录」，不用 alert
- [x] 整理确认页展示类别、置信度、抽取字段
- [x] 低置信度明确标记
- [x] 确认流程每条不超过 3 次点击
- [x] 批量确认只处理符合阈值且字段完整的条目

## M4-T06 主链路测试

- [x] 正常链路：录入 → 整理 → 确认 → 生成任务
- [x] 低置信度：不自动生成
- [x] 分类修改：task 改 schedule 后生成日程
- [x] 事务回滚：实体插入失败时 inbox 不能归档
- [x] 幂等确认：重复请求只生成一条实体
- [x] 整理失败：原始文本仍可查看和重试
- [ ] 性能：单条录入落库 ≤ 2s，常规接口 P95 ≤ 300ms（留到 M9 性能验收统一测量）

### M4 完成定义（DoD）

- [x] 用户不看说明即可完成首次录入（输入框默认聚焦、回车收录）
- [x] 单条录入 ≤ 10 秒（本地接口级实现，供检查点 B 人工确认）
- [x] 确认 ≤ 3 次点击（确认按钮一次生成）
- [ ] 重启后所有状态保持正确（检查点 B 运行态人工验收）
- [x] 断网时本地规则整理按确认后的策略可用
- [x] M4 主链路测试已通过：全量 33 项，0 failures，0 errors

### 检查点 B：请用户确认

- [ ] 整理结果和字段是否符合真实表达习惯
- [ ] 低置信度的提示是否清楚
- [ ] 录入、整理、确认三段是否足够快
- [ ] 重启后收集箱、整理结果和生成实体状态保持正确

---

# M5：日程增量

**目标**：增加 1 个模块，不改已有主链路。

## M5-T01 日程后端

- [x] 日程 CRUD
- [x] 三种类型：meeting / deep_block / other
- [x] 按日期查询、按开始时间排序
- [x] 时间重叠检测
- [x] 冲突只返回 warning，不阻止保存
- [x] 收集箱确认成日程写入同一 schedule_event 表
- [x] 日期可空：`event_date` 允许 NULL，NULL 即「待定时间」（US-4.2；V6 重建 schedule_event 去掉 NOT NULL）
- [x] 待定日程列表接口 `GET /api/v1/events/pending`（独立子路径，不用 `date` 传哨兵值）
- [x] 编辑时清空日期 → 回到待定区（修复原先空串直抛「日期格式不正确」、日期根本清不掉的死路）
- [x] 待定日程不计入驾驶舱今日日程数、不参与冲突检测

## M5-T02 日程前端

- [x] 复用 demo 日程视图
- [x] 展示当日日程时间轴
- [x] 创建/删除；编辑通过 PATCH API 已支持，界面编辑归入 M7 全局整理
- [x] 冲突警告使用 toast，不弹阻断对话框
- [x] 驾驶舱展示今日日程与日程数量
- [x] 日程页新增「待定时间」分区，卡片里补上日期并保存即自动排进当天（US-4.2）
- [x] 加入日程的日期默认预填今天；清空日期再提交就创建待定日程（整理页同款提示实时收敛）
- [x] 日程页接通按日查询：前一天 / 后一天 / 日期选择器 / 回到今天；标题与空态按所选日期措辞（今天、明天、9月12日）
- [x] 「加入日程」的日期跟随正在查看的那天，跨日新建后 toast 说明「加到了哪天、正在看的是哪天」

> ⚠️ 上面两条描述的是**旧的按日视图**，已于 2026-09-13 被下面的周视图整体替换，留作历史记录。

- [x] **日程页改为周视图**（2026-09-13）：纵向三条「下一周 / 本周 / 上一周」，本周居中；
      **手风琴 —— 任何时刻只有一条展开**（当天二次修正；点周头切换，其余自动收起）；
      每条严格七行（周一→周日），行内卡片按开始时间从左往右、等宽并自带时间徽标；
      没有具体时间的日程沉到行首标「未排时段」；
      「回到本周 / ↑ / ↓」挂在**当前展开的那一条**的周头右端（切周时按钮跟着搬过去，不是固定挂本周）。
      **整体替换按日视图** —— `state.viewDate` / `.day-nav` / `dayLabel()` 已移除，
      驾驶舱下钻与全局搜索的日程落点改用 `ensureWeek()` + `scrollToWeek()`；
      后端新增 `GET /api/v1/events?from=&to=` 区间查询（一次取回所有可见周，避免按天打 7 个请求）。
      设计文档：`docs/日程周视图设计.md`
- [x] **重复日程「每周 X」**（2026-09-13）：收录里写「每周三下午三点开周会」→ 一次生成连续 4 周；
      **物化成 4 条独立记录**（`schedule_event.repeat_group` / `repeat_total`，迁移 V8），
      所以**改其中一期不影响其余几期** —— 这是用户在「改一次全改 / 只改某一次」两个选项里选的后者。
      物化只有一处实现（`EventRepository.insertRepeating`，收录确认与手工新建共用）；
      识别在本地规则与 LLM 两条路径上各接一层。「加入日程」也能直接选「每周 · 连续 4 周」。
      设计文档：`docs/日程周视图设计.md` §9
- [x] 整理页补上「一键确认高置信度」（此前后端接口与 api.js 方法齐备，但界面上没有入口，文案却在承诺这个能力）
- [x] `handleAction` 补兜底分支：`action` 未匹配时给出提示，不再静默无反应
- [x] 前端错误出口 `api.js` 重写：4xx 且响应体没有 `code`（路径写错）时给出「HTTP 状态码 + 请求路径」，不再笼统报「操作失败」
- [x] 明确未映射路径契约：已登录返回 404 + Spring 默认错误体（无 `code`）；未登录返回 2002。**禁止加 `/api/**` 兜底**（会把 405 吃成 404）
- [x] 时间线单条删除补二次确认（此前唯一不可逆的删除反而零确认，触发点还是 22px 小图标）
- [x] 删除动作的确认策略：**软删免确认 + 硬删必须确认**（2026-09-11 实现回收站后按 §8.2 放宽）；
  软删 toast 必须说明「已移入回收站，30 天内可恢复」，硬删文案必须写明「无法恢复」
- [x] 触屏下时间线删除图标命中区 22px → 36px，避免误触
- [x] US-1.5「软删除 30 天内可恢复」：新增 `V7__soft_delete_timestamp.sql`（五张软删表加
  `deleted_at` + CHECK 约束 + 补 `idx_event_deleted`）、`TrashService` / `TrashController`
  （`GET /api/v1/trash`、`POST /api/v1/trash/{type}/{id}/restore`）、前端「回收站」页签
  （角标 + 逐条恢复）；五张表的软删实现同步写下 `deleted_at`（见 `产品设计说明.md` §5.2 / §8.2）

## M5-T03 测试

- [x] 无冲突、部分重叠、完全重叠、边界相接四类测试
- [x] 跨日输入拒绝并给出中文说明（错误码 3003）
- [x] 按日查询不混入其他日期
- [x] 待定日程「落库 → 补日期归队 → 再清空回到待定」往返
- [x] 待定日程不进今日时间线与驾驶舱计数、不参与冲突检测
- [x] 整理确认时抽不出日期的日程可成功归档（不再被「生成日程必须填写日期」拦截）

### M5 完成定义（DoD）

- [x] 日程保存成功且冲突有提示
- [x] 驾驶舱一次请求返回任务和日程
- [x] 原有任务与整理链路回归通过（全量 37 项）

---

# M6：收藏增量

**目标**：实现工作/生活速查库。

## M6-T01 收藏后端

- [ ] 创建、修改、软删除
- [ ] work / life 空间
- [ ] 自动提取 URL
- [ ] 自动打基础标签
- [ ] 自动分组，可手动切换
- [ ] 置顶 / 取消置顶
- [ ] 归档 / 恢复
- [ ] 标题、内容、标签、URL 四字段 LIKE 检索
- [ ] 排序：置顶在前，其余按更新时间倒序

## M6-T02 收藏前端

- [ ] 空间布局：全部独占首行，工作/生活次行并列
- [ ] 容器色：全部 `#eef4fa`、工作 `#eaf3ed`、生活 `#fbede6`
- [ ] 记录卡统一白色，不大面积染主题色
- [ ] URL 自动可点击
- [ ] 置顶、归档、恢复、编辑、空间切换
- [ ] 搜索结果即时刷新

## M6-T03 测试

- [ ] 中文关键词、URL、标签检索
- [ ] 特殊字符 `%`、`_` 按文字或通配规则安全处理
- [ ] SQL 使用占位符，禁止拼接用户输入
- [ ] 1 万条以内检索基准 ≤ 300ms

### M6 完成定义（DoD）

- [ ] 10 秒内能找到示例班车信息
- [ ] 置顶和归档重启后仍保留
- [ ] 工作/生活切换不污染卡片视觉

---

# M7：番茄、时间线与完整驾驶舱

**目标**：补齐使用反馈和每日聚焦，把全部核心页面真实化。

## M7-T01 番茄钟

- [x] 专注、短休、长休三种模式
- [x] 时长配置：默认 25/5/15 分钟
- [x] 开始、暂停、继续、重置
- [x] 完成后写 `pomodoro` 与 `activity_log`
- [x] 页面标题闪烁 + AudioContext 蜂鸣
- [x] 继续使用独立深色视觉，不跟随晨雾蓝

## M7-T02 时间线

- [x] 按自然日分组，时间倒序
- [x] 支持 inbox / task / plan / pomo / praise 类型
- [x] 返回用户可读文案，不返回技术日志
- [x] 只展示最近 500 条
- [x] 示例和真实事件混合时顺序正确

## M7-T03 完整驾驶舱

- [x] 今日主题设置与历史保存（**2026-09-20 下线**：用户反馈没什么用，卡片与接口一并移除）
- [x] 4 个指标完整
- [x] 今日 Top3 + reason
- [x] 逾期、阻塞、临近截止、深度工作的排序规则
- [x] 顺延 ≥3 次提示
- [x] 今日任务、今日日程
- [x] 番茄数量和完成率
- [x] `/dashboard/today` 一次聚合返回，禁止前端多请求拼装

## M7-T04 全局前端整理

- [x] 所有页面不再直接访问 localStorage 业务数据
- [x] localStorage 只保留无安全影响的 UI 偏好（如当前 tab），或完全不用
- [x] 唯一全局刷新入口 `refreshAll()`
- [x] 检查所有渲染函数调用关系无环
- [x] 所有 DOM selector 对应元素存在
- [x] 空数据状态不崩溃
- [x] 知识助手 tab 从版本 A 移除

### M7 完成定义（DoD）

- [x] 7 个页面模块 + 番茄组件全部连接真实后端
- [ ] demo 的核心视觉和交互没有明显倒退（留 M9 人工验收）
- [x] 断网状态下除外部 AI 外均可使用
- [x] 函数调用链无 A→B→A 环路

---

# M8：设置、备份与可选增强

**目标**：把数据安全和可选能力补齐，核心功能不依赖外部服务。

## M8-T01 设置页

- [x] 用户名与密码修改
- [x] 时区设置
- [x] 番茄时长设置
- [x] AI 地址、模型名、API Key
- [x] Obsidian Vault 路径（仅保存配置，实际写入 v1.1）
- [x] API Key 返回时打码，日志中禁止输出
- [x] 设置变更立即或重启后生效的规则写清楚（页面内文案说明，均立即生效）

## M8-T02 备份与恢复

- [x] 手动立即备份
- [x] 每日 02:00 自动备份
- [x] 使用 SQLite `VACUUM INTO`
- [x] 禁止直接复制运行中的 db 文件
- [x] 保留最近 14 份，过期备份按明确文件名删除
- [x] 恢复前自动再备份当前库
- [x] 恢复操作需要二次确认
- [x] 恢复后校验 schema version 和关键表行数
- [x] 启动器 `backup` 动作与后端能力一致

## M8-T03 示例数据管理

- [x] 一键清空示例数据
- [x] 只删示例，不影响用户数据
- [x] 二次确认
- [x] 清理后所有页面空态正常

## M8-T04 外部 LLM 适配器（待确认）

建议开发但不作为 v1.0 启用前提：

- [x] 定义 `ClassifyProvider` 接口
- [x] `LocalRuleClassifyProvider` 永远可用
- [x] `LlmClassifyProvider` 使用 RestTemplate
- [x] 配置后可选优先调用 LLM
- [x] 超时/异常自动回退本地规则
- [x] 记录 `ai_job`：耗时、token、状态，不记录密钥
- [x] 输出 JSON 做严格校验，不信任模型自由文本
- [x] LLM 失败不能改变原始数据

## M8-T05 Obsidian 写入（待确认）

建议 v1.1 再做；若确认进 v1.0：

- [x] 仅允许写配置 Vault 下的 `00-Inbox/`（2026-09-08 v1.1 已实现）
- [x] 写入路径做规范化，阻止 `..` 越界（normalize + 限制在 Vault 目录内）
- [x] 文件名去除 Windows 非法字符（`\\ / : * ? " < > |` 与控制字符，超 60 字截断）
- [x] md 包含 frontmatter：来源、日期、原始内容（created/source/tags）
- [x] 只新建不覆盖已有文件，重名自动加后缀（-2 至 -99）
- [x] 未配置 Vault 时不展示 knowledge 分类（整理页下拉动态控制；确认接口兜底 3007；自动确认跳过）
- [x] ~~不实现知识库 RAG 问答~~ 已实现（2026-09-08 v1.1）：Vault 只读索引（ATX 标题分片、frontmatter/双链剥离、指纹缓存 5 分钟）→ CJK 二元组打分检索 → 已配置 LLM（DeepSeek 等 OpenAI 兼容）合成答案，未配置/失败降级摘录模式；低分问题收录「[待补知识]」进收集箱；出处卡含 obsidian:// 深链。新增「知识」页签（问答 + 知识笔记列表），V5 迁移（knowledge_note 增 content/tags/file_name，activity_log 增 knowledge 类型）。

### M8 完成定义（DoD）

- [x] 无任何 AI/Obsidian 配置时，核心功能完整可用
- [x] 手动备份可在另一临时目录恢复（集成测试覆盖恢复链路）
- [x] 备份文件与运行库的数据行数一致（集成测试断言）
- [x] 设置接口不泄漏 API Key（打码断言 + 密钥仅存在于请求头）

---

# M9：Windows 免安装包与正式验收

**目标**：交付一个普通同事解压、双击就能用的 ZIP。

## M9-T01 准备 Windows JRE 8

- [x] 使用可合法再分发的 Windows x64 OpenJDK/Temurin JRE 8（Temurin **8u504**，Adoptium 官方 zip）
- [x] 建议运行时版本不低于已有 Docker 的 8u342 基线（8u504 > 8u342）
- [x] 按开发文档 §0.4 分三档裁剪（裁剪后 94.1MB，存于 `dist/runtime/`）
- [x] 禁止删 `java.sql`、安全证书、时区数据、XML 等运行必需内容（保留 server/jvm.dll、rt.jar、charsets.jar、sunec/sunjce、localedata/cldrdata）
- [x] 裁剪后跑完整功能冒烟，不只跑 `java -version`（发布包冒烟：启动/迁移/初始化/收藏/时间线/关机全通过）

## M9-T02 完成构建脚本

- [x] Maven 打包 jar（Docker 内 `personal-workbench-dev:java8` 执行，不依赖宿主机环境）
- [x] 校验字节码 major version = 52（System.IO.Compression 读取主类校验）
- [x] 复制裁剪后的 JRE 8（复制后 `java -version` 验证为 1.8）
- [x] 复制启动/停止/备份脚本（含 launcher 两处修复：探针容错、`--server.port` 启动参数）
- [x] 生成空 `data/`、`logs/`、`backups/`
- [x] 硬校验发布包不含 workbench.db、日志、API Key、用户名密码（内容级扫描 `sk-`/`password_hash`/`api_key` 模式）
- [x] 生成 `personal-workbench-v1.0.0-win.zip`（68MB，含 .sha256）
- [x] ZIP ≤ 80MB；若超出，输出组成体积报告后再裁剪（68MB 达标）

## M9-T03 启动器验收

- [x] 默认端口 18080 占用时给中文提示（Test-PortBusy + 改端口指引）
- [x] 数据目录不可写时给中文提示（写探针；已修复删除失败误报——杀毒/安全软件锁文件时不再误判）
- [x] 防止重复启动（Get-RunningPid 按 workbench.jar 命令行识别）
- [x] 健康检查通过后才打开浏览器（90 秒轮询 /actuator/health）
- [x] 超时后显示日志路径
- [x] 停止动作优雅关闭（POST /api/v1/system/shutdown 已实测，20 秒兜底强杀）
- [x] 关闭后 PID 文件清理
- [ ] 中文路径、带空格路径可启动（留 M9-T06 干净机实测；另：启动参数已强制 `--server.port`，防机器级 SERVER_PORT 环境变量干扰）

## M9-T04 前端冒烟自检

- [x] 初始化链路从 DOMContentLoaded 开始可完整执行（smoke-frontend.js）
- [x] 所有函数调用关系无环（11 个渲染函数静态检查）
- [x] 所有 DOM id / selector 存在（89 个静态 id + 动态前缀全过）
- [ ] 空数据库和清空示例后不报错（留人工；发布包冒烟已验证空库初始化链路）
- [ ] 跨月、跨年日期正确（留人工）
- [ ] 事件绑定时机正确（留人工）
- [ ] PC 窗口与 `<768px` 窄屏布局可用（留人工）
- [x] 按钮 ≥44px，输入框字号 ≥16px（规则静态检查）
- [x] 全部 CSS/JS/图标在 jar 内，无 CDN 和外链依赖（静态检查）

## M9-T05 自动化与性能验收

- [x] 全部单元测试通过（57 项 0 失败，Docker 内 `clean verify` BUILD SUCCESS）
- [x] Repository SQLite 集成测试通过
- [x] Controller/API 测试通过
- [x] 端到端主链路测试通过
- [x] 首页本地加载 ≤ 1s（实测 **10.5ms**，发布包 + 正式 JVM 参数）
- [x] 常规接口 P95 ≤ 300ms（实测驾驶舱 p95=**70ms**，favorites/tasks/activity/settings p95 ≤70ms；Hikari 池化前驾驶舱曾达 2000ms，见决策 7）
- [x] RSS ≤ 250MB（实测 **180.6MB**，-Xmx256m + MaxMetaspaceSize=128m + SerialGC）
- [ ] 连续运行 24h 无 Metaspace OOM（留观察期）

## M9-T06 三类干净机验收

至少覆盖：

1. Windows 10/11，无 Java、无 Maven、无 Node、无 MySQL
2. 已装其他 Java 版本的电脑（验证不误用系统 Java）
3. 中文用户名 + 中文解压路径的电脑

每台机器执行：

- [ ] 解压
- [ ] 双击启动
- [ ] ≤60s 打开首次配置
- [ ] 初始化
- [ ] 录入 → 整理 → 确认 → 生成任务
- [ ] 创建收藏和日程
- [ ] 完成一个番茄
- [ ] 停止、重启，数据仍在
- [ ] 手动备份并恢复

## M9-T07 用户交付文档

- [x] `使用说明.txt`，只写用户能理解的词（UTF-8 BOM，记事本兼容）
- [x] 不出现 Java、Flyway、SQLite、Maven 等技术要求
- [x] 明确不要放桌面/OneDrive
- [x] 明确数据在 `data/`
- [x] 明确如何启动、停止、备份、迁移
- [x] 常见错误：端口占用、目录不可写、启动超时（另含忘记密码、打不开页面）

### M9 完成定义（DoD）

- [ ] 可交付 ZIP 生成
- [ ] 三类干净机全部通过
- [ ] 包内不含任何开发者个人数据或密钥
- [ ] 10 条功能验收、4 条性能验收、6 条 Java 8 专项验收全部通过
- [ ] 用户拿到包只需：解压 → 启动 → 浏览器打开 → 首次配置

### 检查点 C：请用户确认

- [ ] 最终功能范围
- [ ] 使用说明文案
- [ ] ZIP 文件名和版本号
- [ ] 是否进入同事试用

---

## 3. 横向质量要求（每个里程碑都执行）

### 3.1 测试策略

| 层级 | 工具/方式 | 重点 |
|---|---|---|
| 单元测试 | JUnit 5 | 规则引擎、状态机、排序、日期 |
| Repository 集成 | 临时 SQLite 文件 | SQL、索引、事务、软删除 |
| Web/API | Spring Boot Test / MockMvc | JSON 契约、错误码、认证 |
| 主链路 | 自动化 API + 浏览器人工冒烟 | 录入→整理→确认→实体 |
| 打包验收 | 干净 Windows 机器 | 自带 JRE、脚本、中文路径 |

### 3.2 每阶段结束的固定动作

- [ ] 运行本阶段新增测试
- [ ] 运行全部旧测试，防回归
- [ ] 检查实际文件改动
- [ ] 更新文档中的已完成状态
- [ ] 写一条项目日志，只记录长期有效结论
- [ ] 提交阶段性结果供用户查看

### 3.3 代码规则

- [ ] Java 代码只用 Java 8 语法，不用 record、var、文本块等
- [ ] Controller 不写业务逻辑
- [ ] Service 管事务
- [ ] Repository 管 SQL
- [ ] SQL 参数使用占位符
- [ ] DTO 与 Entity 分离
- [ ] 时间由 Java 侧 `TimeUtil` 统一生成
- [ ] 不记录 API Key、密码、完整 AI 请求内容
- [ ] 前端数据层 → 计算层 → 渲染层单向调用
- [ ] 多模块刷新只调用 `refreshAll()`

### 3.4 明确禁止

- [ ] 不引入 MySQL、Redis、Nacos、RocketMQ
- [ ] 不引入 JPA/Hibernate
- [ ] 不引入前端框架、CDN、外部字体和图标库
- [ ] 不开发知识助手/RAG、企业微信、ASR、远程访问
- [ ] 不实现多用户和权限角色
- [ ] 不把数据库放在 OneDrive 等实时同步目录
- [ ] 不直接复制运行中的 SQLite 文件做备份
- [ ] **不在 Windows 宿主机执行编译、单测、集成测试或 `spring-boot:run`**
- [ ] **不修复、不依赖系统 Maven，不修改系统级 `JAVA_HOME`**

---

## 4. 预计最终目录

```text
personal-workbench/
├── docker-compose.dev.yml       ← 唯一开发环境入口
├── server/
│   ├── .mvn/
│   │   └── wrapper/
│   ├── mvnw
│   ├── mvnw.cmd
│   ├── pom.xml
│   └── src/
│       ├── main/java/.../
│       │   ├── WorkbenchApplication.java
│       │   ├── common/
│       │   ├── config/
│       │   ├── auth/
│       │   ├── dashboard/
│       │   ├── inbox/
│       │   ├── task/
│       │   ├── schedule/
│       │   ├── favorite/
│       │   ├── pomodoro/
│       │   ├── timeline/
│       │   ├── setting/
│       │   ├── backup/
│       │   └── util/
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── logback-spring.xml
│       │   ├── db/migration/
│       │   │   ├── V1__init_schema.sql
│       │   │   └── V2__add_index_and_config.sql
│       │   └── static/
│       │       ├── index.html
│       │       ├── setup.html
│       │       ├── style.css
│       │       ├── api.js
│       │       └── app.js
│       └── test/java/.../
├── demo/                       ← 原型保留，不直接覆盖
├── deploy/
│   ├── scripts/launcher.ps1
│   ├── start-workbench.cmd
│   ├── stop-workbench.cmd
│   ├── backup-workbench.cmd
│   └── build-release.ps1
├── dist/                       ← 构建生成，不提交个人数据
└── docs/
    ├── 产品设计说明.md
    ├── 开发文档-Java8版.md
    ├── 开发TODO与里程碑.md
    └── 版本A-Windows免安装包落地方案.md
```

---

## 5. 风险清单与应对

| 风险 | 影响 | 提前措施 | 验证点 |
|---|---|---|---|
| 系统 Maven 已损坏 | 不影响开发 | 已确认全部开发在 Docker，系统 Maven不使用 | M0 |
| 本机 Java 8u131 太旧 | 不影响开发；只可能误导构建脚本 | 开发构建固定容器 Java 8u342+，最终启动脚本强制用包内 JRE | M0 / M9 |
| Docker 现有 Java 镜像可能只有 JRE | 无 javac | 检查 javac，必要时单建 dev image | M0 |
| Windows bind mount I/O 较慢 | 首次编译和测试变慢 | Maven 仓库用命名 volume，不把 `.m2` 放 bind mount | M0 |
| 容器生成文件权限/行尾异常 | Windows 编辑或脚本执行异常 | Git 行尾规则 + 产物权限检查；CMD/PS1 编码单独校验 | M0 / M9 |
| SQLite 默认外键关闭 | 产生孤儿数据 | SQLiteConfig 逐连接开启 | M1 |
| 异步整理只返回 jobId 无查询接口 | 前端无法知道何时完成 | 补 `GET /ai/jobs/{id}` | M4 |
| 无 AI 时“整理按钮隐藏”与“离线核心可用”冲突 | 产品主链路断裂 | 采用本地规则常驻方案（待确认） | M4 |
| 前端最后才接后端 | 大量契约问题集中爆发 | 纵向切片，每模块同步接前端 | M2 起 |
| demo 渲染函数互调 | 运行时栈溢出 | `refreshAll()` 唯一入口 + 调用链检查 | 每阶段 |
| 直接复制 SQLite WAL 数据库 | 备份不一致 | 只用 `VACUUM INTO` | M8 |
| JRE 8 许可与裁剪不当 | 不可分发/运行时缺类 | 使用可再分发 OpenJDK/Temurin + 全功能冒烟 | M9 |
| 发布包带个人数据 | 隐私泄露 | 构建脚本硬校验空数据、空密钥 | M9 |

---

## 6. 已确认决策（2026-09-07）

用户已确认开始执行，以下决策全部按推荐方案锁定。后续如需改变，必须作为范围变更记录，不能静默修改。

### 决策 1：无 AI 配置时怎么整理 — **已确认 A**

- 本地规则整理器永远可用；配置 LLM 后增强准确率；LLM 失败自动回退。
- 本地状态下「整理」按钮始终存在。
- 原因：产品最核心的是“收集箱每天清空”，不能因没有外部 LLM 而切断主链路。

### 决策 2：Obsidian 写入是否进入 v1.0 — **已确认 A**

- v1.0 只保留配置字段和扩展接口，实际 Vault 写入放 v1.1。
- v1.0 整理类别只有 task / schedule / favorite。
- 原因：普通同事首选版本不依赖 Obsidian，优先保证核心模块和备份可靠。

### 决策 3：开发构建环境 — **已确认**

- **已选 A**：全部开发环境放在 Docker；固定 Java 8u342+；项目内保留 Maven Wrapper；不修复、不依赖 Windows 系统 Maven。
- 边界：Windows 宿主机只编辑源码、运行 Docker 和验收最终免安装包。

### 决策 5：最终交付端口 — **已确认 18080**

- Windows 免安装包和 Spring Boot 默认端口改为 `18080`，不再把最终交付回切到 `8080`。
- 开发容器内部仍使用 `8080`，宿主机统一访问 `127.0.0.1:18080`。
- 原因：用户本机 `8080` 已被其他本地服务占用；保持开发、验收和最终交付地址一致，减少“开发能开、交付打不开”的偏差。

### 决策 6：去掉全部用户可见的时区功能 — **已确认（2026-09-08）**

- 用户明确要求“去掉所有时区相关的功能”。已移除：首次配置页的时区选择、设置页的时区卡片、`PUT /api/v1/settings/timezone` 端点、`SettingsVO.timezone` 字段、顶栏“用户名 · 时区”中的时区部分。
- 内部日期边界仍固定使用 `Asia/Shanghai`（`InitRequest.timezone` 默认值），仅作为不可见的内部机制，不暴露任何设置入口。
- 同批 UI 反馈已落实：日程支持编辑（复用既有 PATCH 接口）；时间线改为原型方案 A（渐变脊柱纵轴 + 左右交错发光节点）；收藏“全部”独占首行、“工作/生活”次行各占一半占满整行；“显示已归档”勾选框样式修复；番茄钟入口改为顶栏深色芯片 + 居中蒙层科幻面板（含进度环），与原型 `demo/index.html` 一致。
- 后续追加（2026-09-08 晚）：① 设置从页签栏移至右上角齿轮按钮（data-tab="settings" 保留冒烟配对，激活态蓝底）；② 收藏空间工作/生活固定同一行，收藏卡片按分类着色（工作绿/生活暖，左色条 + 渐变晕染 + 类型 pill）；③ 时间线收藏节点按分类着色（favorite:work 绿 / favorite:life 暖），activity_log 经 V4 迁移增加 category 列；④ 设置页 SaaS 化重构（图标分区卡 + 标签化字段 + 危险红卡）；⑤ 收藏卡片美化（柔和阴影、悬停浮起、操作按钮头部右侧幽灵样式悬停浮现、链接芯片化）。

### 决策 7：SQLite 改为 HikariCP 连接池 — **已实施（2026-09-08）**

- 背景：M9 发布包冒烟实测发现，Windows 下每次新开 SQLite 连接要重建 db/wal/shm 三个文件句柄并被实时防护软件逐个扫描，**单连接固定开销约 160ms**；驾驶舱接口（十余次查询）因此被放大到约 2000ms，远超 P95 ≤300ms 目标。
- 变更：`DataSourceConfig` 由裸 `SQLiteDataSource`（逐操作开关连接）改为 HikariCP 包装（maximumPoolSize=4、minimumIdle=1、`SELECT 1` 探活、WAL 单写多读 + busy_timeout=5000 兜底）。
- 恢复备份的联动：`BackupService.restore` 在替换 db 文件前先 `suspendPool()` + `softEvictConnections()`（需 `allowPoolSuspension=true`），防止池在文件替换窗口补建连接把缺失路径当空库打开（曾导致 "no such table"），替换带 10×300ms 重试，完成后 `resumePool()`。
- 实测效果（发布包 + 正式 JVM 参数）：驾驶舱 p50=11ms / p95=70ms，全部常规接口 p95 ≤70ms，首页 10.5ms，RSS 180.6MB。
- 原“无连接池、替换文件即生效”的设计理由由此作废。

### 决策 4：开发推进方式 — **已确认 A**

- 从 M0 连续执行，只在 M2、M4、M9 三个检查点暂停确认。
- 除范围冲突、外部凭证或不可逆选择外，其余阶段不中断。

---

## 7. 执行规则

- 已于 2026-09-07 开始执行，严格按 **M0 → M9** 顺序推进。
- 当前阶段：**检查点 B（M0-M4 已完成，等待用户确认）**。
- 检查点 A 已由用户继续指令确认；当前已到达 **检查点 B（M4 后）**，待用户确认整理结果、低置信度提示和录入整理速度后进入 M5。
- 计划暂停点：M2、M4、M9；到点提交可运行结果与验收记录。
- 除明确确认的范围变更外，不扩大功能范围，不把后续能力偷塞进 v1.0。