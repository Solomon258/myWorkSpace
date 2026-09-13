# 贡献指南

感谢你有兴趣为本项目做出贡献。本文档说明开发环境、分支模型、代码规范与提交流程。

---

## 1. 开发环境

| 依赖 | 版本要求 | 说明 |
|---|---|---|
| JDK | **必须是 8** | `pom.xml` 中 `maven-enforcer-plugin` 强制校验 `[1.8,1.9)` |
| Maven | 3.8 ~ 3.9 | 也可直接使用仓库内置的 `mvnw` / `mvnw.cmd`，无需预装 |
| 数据库 | 无需安装 | SQLite 单文件，Flyway 首次启动自动建库 |
| Node.js | **不需要** | 前端为原生 JS，无构建步骤 |

验证环境：

```bash
cd personal-workbench/server
./mvnw -v          # 确认输出 Java version: 1.8.x
```

---

## 2. 分支模型

```
main        ← 始终可发布，只接受来自 develop 的合并
  └── develop   ← 日常集成，功能完成后合入
        └── feature/xxx   ← 单个功能，从 develop 分出，完成后合并回 develop
        └── fix/xxx       ← 缺陷修复
```

- **不要**直接向 `main` 推送。
- 每个 PR 只做一件事，避免混杂无关格式化改动。
- 分支命名：`feature/全局搜索语义联想`、`fix/备忘标题为空时回落正文`。

---

## 3. 提交信息规范

采用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：

```
<type>(<scope>): <简短描述>

<可选正文：为什么这么改>
```

`type` 取值：

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `refactor` | 重构（不改变行为） |
| `perf` | 性能优化 |
| `docs` | 仅文档 |
| `test` | 仅测试 |
| `chore` | 构建、依赖、工具链 |
| `style` | 格式调整（不影响语义） |

`scope` 建议使用后端模块名（`task` / `schedule` / `memo` / `inbox` / `search` …）或 `ui` / `docs`。

示例：

```
feat(schedule): 日程页改为周视图，支持手风琴单周展开
fix(search): 回收站命中不再跳转到错误页签
docs(readme): 补充免安装包解压目录的注意事项
```

---

## 4. 代码规范

### 后端（Java 8）

- 只能使用 **Java 8 语法**：不用 `var`、不用 `List.of()`、不用 `Optional.orElseThrow()` 的无参形式以外的 Java 9+ API。
- 分层保持 `Controller → Service → Repository`，Controller 不写业务逻辑。
- **错误响应必须能指导操作**：`1002 INVALID_PARAMETER` 必须带中文自定义原因；请求 DTO 的校验注解必须自带中文 `message`。
- **客户端错误不得落 500 兜底**：非法 JSON → 400，方法不匹配 → 405。
- 字段留空要有显式语义，不要隐式兜底。
- 同一个约束在不同入口必须使用**同一个 ErrorCode**。

### 前端（原生 JS）

- 不引入任何前端框架与构建工具。
- 避免 `$("id")` 撞到不存在的元素后在顶层抛 `TypeError`——这会让整页脚本连同初始化一起静默失效。
- 任何交互分支都要有 `else`，不要留「点了没反应」的静默路径。
- 样式选择器注意：注释夹在两个选择器片段之间会被浏览器拼成一个选择器，导致整条规则静默失效。可运行 `personal-workbench/scripts/check_css_selectors.py` 扫描该类问题。

### 数据库

- 迁移脚本放在 `server/src/main/resources/db/migration/`，命名 `V<序号>__<描述>.sql`，**只增不改**。
- SQLite 改表：仅加列用 `ALTER TABLE ADD COLUMN`；改动 CHECK 约束或 NOT NULL 需走「建新表 → 搬数据 → DROP → RENAME → 重建索引」。
- 软删除必须同时写入 `deleted_at`，恢复时清空。

---

## 5. 测试要求

```bash
cd personal-workbench/server
./mvnw clean test
```

- 新增功能需附集成测试（参考 `src/test/java/.../` 下已有的 `*FlowTest`）。
- 修改接口契约时同步更新测试断言。
- 断言中文响应体时，`getContentAsString()` **必须显式传入 `StandardCharsets.UTF_8`**，否则中文乱码会造成假失败。

---

## 6. 提交 Pull Request

1. Fork 仓库并从 `develop` 切出功能分支。
2. 确保 `./mvnw clean test` 全绿。
3. 向 **`develop`** 分支发起 PR（不是 `main`）。
4. PR 描述中说明：**改了什么、为什么改、怎么验证**。
5. 关联相关 Issue（如 `Closes #12`）。

评审关注点：

- 是否违反了上面三条产品原则（尤其 P1 零摩擦录入）
- 是否引入了非必要的依赖
- 错误路径是否有可指导操作的提示

---

## 7. 报告问题

- 功能缺陷 / 功能建议 → 使用 Issue 模板提交
- 安全漏洞 → **不要**开公开 Issue，请按 [SECURITY.md](SECURITY.md) 私密报告
