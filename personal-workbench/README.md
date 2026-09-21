# personal-workbench

> 纯本地、单用户、断网可用的 Windows 个人工作台。
> **完整介绍见[仓库根 README](../README.md)；开发规范、硬红线与发布流程见[《开发需知》](../开发需知.md)。**

本目录是主项目本体。本文件只讲**这一层怎么组织**；产品规则、技术契约、开发命令都不在这里重复（它们会漂，且已有各自唯一的归属）。

---

## 目录说明

| 目录 | 内容 | 是否入库 |
|---|---|---|
| `server/` | Spring Boot 后端 + 静态前端（**唯一需要构建的部分**） | ✅ |
| `demo/` | 可交互 HTML 原型，**视觉与交互的事实标准** | ✅ |
| `docs/` | 需求、设计、验收文档（**会滞后于代码，以 Service 实现为准**） | ✅ |
| `deploy/` | 启动 / 停止 / 备份 / 打包脚本 | ✅ |
| `scripts/` | 辅助校验脚本 | ✅ |
| `dev-data/` | 本地开发数据库与备份 | ❌ 含真实数据 |
| `dist/` | 打包产物（含裁剪好的 JRE 与发行 ZIP） | ❌ |
| `logs/` | 运行日志 | ❌ |
| `output/` | 页面预览截图、冒烟与验证产物 | ❌ |
| `target/` | Maven 构建产物 | ❌ |
| `vault/` | Obsidian 仓库挂载点 | ❌ |

> ⚠️ **`dev-data/workbench.db` 是真实使用数据，永远不要提交。**
> `.gitignore` 已拦截上述目录与 `*.db`，提交前仍请用 `git status` 复核一遍。

---

## 后端包结构

源码位于 `server/src/main/java/com/icecode/workbench/`，按业务域分包，共 22 个模块：

```text
auth        登录与首次初始化            poem        两侧诗词与全屏品读
attachment  附件上传与多态外键绑定       pomodoro    番茄钟
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

## 前端

前端在 `server/src/main/resources/static/`，**没有构建步骤** —— 这里的文件就是最终产物。

| 文件 | 作用 |
|---|---|
| `index.html` | 单页应用外壳（含全部视图骨架） |
| `setup.html` | 首次配置与登录页 |
| `app.js` | 全部前端逻辑，按视图分块 |
| `api.js` | 接口封装，**唯一的 fetch 出口** |
| `style.css` | 全部样式 |
| `poems.js` | 诗词库（独立文件，便于整体替换） |

开发时若只改了前端，复制进 `server/target/classes/static/` 即可生效（Spring 对 classpath 静态资源是每次请求现场解析的）。
**但改了 Java 类必须重启服务** —— `@RequestMapping` 是启动时扫描的，否则新路径在运行中的进程里根本不存在。

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

打包成 Windows 免安装 ZIP 见 [`deploy/打包与分发说明.md`](deploy/打包与分发说明.md)。

---

## 提交前自查

- [ ] `git status` 中没有 `dev-data/`、`dist/`、`logs/`、`output/`、`target/`、`*.db`
- [ ] `./mvnw clean test` 全绿
- [ ] 仅使用 Java 8 语法
- [ ] 新增数据库变更走新的 Flyway 版本号，**不回改历史迁移脚本**
- [ ] 未触碰[《开发需知》](../开发需知.md#8-硬红线违反会静默失效)第 8 节的任何一条红线
