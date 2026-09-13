# personal-workbench

> 纯本地、单用户、断网可用的 Windows 个人工作台。完整介绍见[仓库根 README](../README.md)。

本目录是主项目本体。本文件只讲**这一层怎么组织、怎么开发**；产品规则与技术契约请查阅 `docs/`。

---

## 目录说明

| 目录 | 内容 | 是否入库 |
|---|---|---|
| `server/` | Spring Boot 后端 + 静态前端（唯一需要构建的部分） | ✅ |
| `demo/` | 可交互 HTML 原型，**视觉与交互的事实标准** | ✅ |
| `docs/` | 需求、设计、验收文档 | ✅ |
| `deploy/` | 启动 / 停止 / 备份 / 打包脚本；`deploy/docker/` 为版本 B 预留，`deploy/scripts/` 含启动器与前端冒烟脚本 | ✅ |
| `docs/` | 需求、设计、验收文档 | ✅ |
| `scripts/` | 辅助校验脚本 | ✅ |
| `dev-data/` | 本地开发数据库与备份 | ❌ 含个人数据，已忽略 |
| `dist/` | 打包产物（含 JRE 与发行 ZIP） | ❌ 已忽略 |
| `logs/` | 运行日志 | ❌ 已忽略 |
| `output/` | 页面预览截图与冒烟产物 | ❌ 已忽略 |

> **注意**：`dev-data/workbench.db` 是真实使用数据，永远不要提交。`.gitignore` 已拦截 `*.db` / `dev-data/` / `dist/` / `logs/` / `output/`，提交前请用 `git status` 复核。

---

## 后端结构

```text
server/src/main/
├── java/com/icecode/workbench/
│   ├── auth/       登录与首次初始化
│   ├── common/     统一响应体 / 错误码 / 全局异常处理
│   ├── config/     数据源、WebMvc、工作目录初始化
│   ├── dashboard/  驾驶舱指标与下钻
│   ├── demo/       演示数据装载
│   ├── inbox/      收集箱与 AI 整理（本地规则 + LLM 双通道）
│   ├── knowledge/  知识库、Obsidian 对接、AI 问答
│   ├── memo/       备忘
│   ├── pomodoro/   番茄钟
│   ├── schedule/   日程（周视图 / 待定时间区 / 重复期次）
│   ├── search/     全局搜索与语义联想
│   ├── settings/   设置
│   ├── system/     备份与恢复
│   ├── task/       任务与状态机
│   ├── timeline/   操作时间线
│   ├── transfer/   跨实体搬运
│   ├── trash/      回收站
│   └── util/       文本与时间工具
└── resources/
    ├── application.yml
    ├── logback-spring.xml
    ├── db/migration/      Flyway V1 - V8
    └── static/            零框架前端
        ├── index.html
        ├── setup.html
        ├── app.js
        ├── api.js
        └── style.css
```

分层约定：`Controller → Service → Repository`，Controller 不写业务逻辑，SQL 收敛在 `*Repository`。

---

## 开发命令

```bash
cd server

./mvnw spring-boot:run          # 启动，默认 http://localhost:18080
./mvnw clean test               # 跑全部测试
./mvnw clean package            # 产出 target/workbench.jar
./mvnw -Dtest=TaskFlowTest test # 只跑某个测试类
```

**端口冲突**：用 `--server.port=端口` 覆盖，且必须写在 `-jar` 之后（写在前面会被 JVM 当成无法识别的选项）。

```bash
java -jar target/workbench.jar --server.port=18081
```

---

## 辅助脚本

```bash
# 扫描 CSS 中「注释夹在选择器中间」导致的规则静默失效
python scripts/check_css_selectors.py

# 前端冒烟（需先启动服务）
node scripts/smoke-frontend.js
```

---

## 提交前自查

- [ ] `git status` 中没有 `dev-data/`、`dist/`、`logs/`、`output/`、`*.db`
- [ ] `./mvnw clean test` 全绿
- [ ] 仅使用 Java 8 语法
- [ ] 新增数据库变更走新的 Flyway 版本号，不回改历史脚本
