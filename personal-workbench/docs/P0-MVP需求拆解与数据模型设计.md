# 个人工作台 P0-MVP 需求拆解与数据模型设计

> 版本：v1.0 | 日期：2026-09-02 | 周期目标：2 周
> 范围：收集箱 + 微信录入 + AI 整理（基础版）+ 任务/日程 + 每日驾驶舱（规则版）

---

## 1. 目标与范围

### 1.1 MVP 目标

跑通最小闭环：**随手记录（零摩擦）→ AI 自动整理 → 每天 15 分钟确认 → 驾驶舱指导当日行动**。

衡量成功的标准：连续使用 2 周后，收集箱日清空率 ≥ 90%，单次记录耗时 ≤ 10 秒。

### 1.2 范围界定

| In Scope（本期做） | Out of Scope（后续迭代） |
|---|---|
| M1 收集箱（Web/微信/语音录入） | Obsidian RAG 问答（P1） |
| M2 AI 整理引擎（分类+字段抽取） | 外部日历同步（P2） |
| M3 任务管理（状态流转） | Jira / GitLab 集成（P2） |
| M4 日程管理（手工+整理生成） | 深度工作统计与守护（P3） |
| M5 每日驾驶舱（规则版建议） | 周报/方案生成（P3） |
| M6 企业微信接入（回调+指令） | 周回顾报告（P3） |

### 1.3 角色

单用户系统（本人，Tech Lead 角色）。无权限体系，仅一个登录口令或内网部署即可。

---

## 2. 功能需求拆解

### M1 收集箱（Inbox）

| 编号 | 用户故事 | 验收标准 |
|---|---|---|
| US-1.1 | 作为用户，我在 Web 端输入一句话按回车即完成记录 | 提交到落库 ≤ 2s；输入框自动清空并聚焦，可连续录入 |
| US-1.2 | 作为用户，我在微信里发一条文本消息，自动进入收集箱 | 消息到达后 ≤ 5s 出现在收集箱；回复"已收录"确认 |
| US-1.3 | 作为用户，我发微信语音，自动转文字进收集箱 | ASR 转换 + 保留原语音文件；识别失败时按原文本消息处理并提示 |
| US-1.4 | 作为用户，我查看收集箱列表，能看到每条的状态 | 状态：待整理 / 已整理待确认 / 已归档；按时间倒序 |
| US-1.5 | 作为用户，我可以删除误录的条目 | 软删除，30 天内可恢复 |

> **US-1.5 实现说明（2026-09-11）**：最初只做了 `deleted=1` 标记位——没有恢复接口、界面上也没有入口，
> 「30 天内可恢复」是句空承诺。现已补齐：五张软删表加 `deleted_at`（`V7`），
> `GET /api/v1/trash` 列出回收站、`POST /api/v1/trash/{type}/{id}/restore` 恢复，
> 前端新增「回收站」页签（带角标）。保留期 30 天，超期不再列出也不能恢复。
> 删除时刻取 `COALESCE(deleted_at, updated_at, created_at)`，兼容迁移前删掉的历史记录。

### M2 AI 整理引擎

| 编号 | 用户故事 | 验收标准 |
|---|---|---|
| US-2.1 | 作为用户，我点"整理"（或每天 16:30 自动触发），AI 把待整理条目分类 | 四类：任务 / 日程 / 备忘 / 知识；同时抽取标题、截止时间、优先级建议 |
| US-2.2 | 作为用户，AI 拿不准的条目会被标出来 | 置信度 < 0.7 标"待人工确认"，不自动生成实体 |
| US-2.3 | 作为用户，我逐条确认或修改 AI 的整理结果，确认后生成正式数据 | 确认操作 ≤ 3 次点击/条；修改分类后字段表单联动切换 |
| US-2.4 | 作为用户，"知识"类条目确认后自动写入 Obsidian Vault | 生成 md 文件到 Vault 的 `00-Inbox/` 目录；带 frontmatter（来源、日期、原始内容） |
| US-2.5 | 作为用户，整理失败不影响已收录数据 | AI 调用失败时条目标记"整理失败"可重试，原始内容不丢 |

### M3 任务管理

| 编号 | 用户故事 | 验收标准 |
|---|---|---|
| US-3.1 | 作为用户，我查看任务列表并按状态/优先级筛选 | 状态机：待办 → 进行中 → 已完成 / 已取消；优先级 P0-P3 |
| US-3.2 | 作为用户，我从任务能看到它来自哪条收集记录 | task.source_inbox_id 可回溯原文 |
| US-3.3 | 作为用户，我给任务标记"深度工作"，它会被建议排进深度块 | 标记后驾驶舱优先建议在深度块时段执行 |
| US-3.4 | 作为用户，逾期任务会被醒目提示 | 超过 due_date 未完成 → 列表标红 + 驾驶舱置顶 |

### M4 日程管理

| 编号 | 用户故事 | 验收标准 |
|---|---|---|
| US-4.1 | 作为用户，我手工增删改日程（会议/深度块/其他） | 冲突检测：时间重叠时警告但不强制阻止 |
| US-4.2 | 作为用户，AI 整理出的日程（如"周三下午和业务对齐"）确认后进入日程表 | 时间解析失败的进入"待定时间"区，人工补全 |

### M4.5 备忘页签（速查库）

| 编号 | 用户故事 | 验收标准 |
|---|---|---|
| US-4.5.1 | 作为用户，我随手保存链接、家人交代买的东西、班车时刻表等 | 内容中的 URL 自动提取为可点击链接；按关键词自动打标签（家庭采购/通勤/链接收藏） |
| US-4.5.2 | 作为用户，我按关键词全文检索备忘 | 检索范围覆盖标题/内容/标签/链接；结果 ≤ 300ms |
| US-4.5.3 | 作为用户，我置顶高频查阅项（如班车表），归档失效项（如已买完） | 置顶项排在最前；归档默认隐藏、可筛选查看、可恢复 |
| US-4.5.4 | 作为用户，收集箱整理出的"备忘"类条目确认后进入备忘页签 | 携带原始内容与来源，可追溯 |
| US-4.5.5 | 作为用户，我可以修改任意备忘 | 可修改标题、正文、标签、链接和工作/生活分组；保存后更新全文检索内容并记录修改时间 |

界面约束：空间切换区采用“全部独占首行，工作/生活次行并列”；空间容器可使用晨雾蓝/玉簪青/暖纸珊瑚表达氛围，但记录卡统一使用中性白色载体，仅用分组徽标和标签色区分工作/生活，避免新增记录被大面积主题色染色。

### M4.6 记录互转（移至：任务 / 日程 / 备忘）

| 编号 | 用户故事 | 验收标准 |
|---|---|---|
| US-4.6.1 | 作为用户，我把记错菜单的记录移到另一个菜单（点「移至」→ 选「日程」或「备忘」） | 目标菜单立即出现该记录，当前菜单不再显示；每条记录都带「移至」入口，展开后是另外两个菜单 |

> **实现说明（2026-09-12）**：三张表的列不对应，「移动」的实质是**目标表新建 + 源记录软删 + 一条流水**，
> 同一事务完成（`POST /api/v1/transfers`）。源记录进回收站（30 天内可恢复，与 US-1.5 一致），
> 不是物理删除 —— 点错菜单时还能捞回来。
> 日期映射 `task.due ↔ event.date`，为空即「待定时间」（US-4.2），后端**不**默认今天。
> 目标表没有的字段（任务的优先级、日程的时间、备忘的标签/链接）**必须逐项告知**：
> 返回 `warnings`，界面在提示里展示，不静默丢弃。
> `is_demo` / `source_inbox_id` 跟着走，否则「清空示例数据」清不掉搬过来的那条。

### M5 每日驾驶舱

| 编号 | 用户故事 | 验收标准 |
|---|---|---|
| US-5.1 | 作为用户，我打开首页看到今日视图：日程时间线、今日任务、收集箱待整理数 | 首屏加载 ≤ 1s（本地部署） |
| US-5.2 | 作为用户，AI（规则版）给出今日优先级建议 Top3 及理由 | 规则权重：逾期 > 阻塞他人 > 临近截止 > 深度工作标记；每条附可解释理由 |
| US-5.3 | ~~作为用户，我设定"今日主题"，显示在驾驶舱顶部~~ **2026-09-20 已下线**（用户反馈没什么用，驾驶舱已无此入口与接口） | ~~每日一条，历史可追溯~~ |
| US-5.4 | 作为用户，每天结束后我勾选完成情况，生成简单的完成度数字 | 完成率 = 已完成/计划数，按日存档 |
| US-5.5 | 作为用户，驾驶舱里的每个数字与每一条记录都能点进对应页面 | 指标卡 → 任务页（带「逾期 / 今天截止 / 已完成 / 进行中」筛选）、收录页、番茄钟；Top3、今天截止、今日日程点条目 → 跳到对应页并高亮那一条；筛选状态必须显示在页面上且能一键取消 |

### M6 企业微信接入

| 编号 | 用户故事 | 验收标准 |
|---|---|---|
| US-6.1 | 作为用户，我在企业微信自建应用里发消息即完成录入 | 回调验签 + msgId 幂等去重；5s 被动回复限制内返回"已收录" |
| US-6.2 | 作为用户，我发指令"整理"触发 AI 整理，"今日"获取驾驶舱文字摘要 | 指令与内容消息区分（精确匹配指令词）；整理耗时超 5s 时先回"处理中"，完成后主动推送结果 |
| US-6.3 | 作为系统，回调失败可重放 | 消息流水表记录处理状态，支持人工重放 |

---

## 3. 非功能需求

- **部署**：单机/私有部署（个人服务器或 NAS），Docker Compose 一键起。
- **性能**：单用户，常规接口 P95 ≤ 300ms；AI 整理异步执行，单批 20 条 ≤ 60s。
- **数据安全**：数据库每日定时备份（保留 14 天）；Obsidian 写入限定 `00-Inbox/` 目录，只增不改。
- **隐私**：语音文件本地存储；LLM 调用前对内容不做额外持久化。

---

## 4. 数据模型

### 4.1 ER 关系

- `inbox_item` 1 → 0..1 `task` / `schedule_event` / `memo` / `knowledge_note`（整理产出）
- `daily_plan` 1 → N `daily_plan_item` → N `task`
- `wechat_msg_log` 1 → 0..1 `inbox_item`
- `ai_job` 记录每次 AI 调用（整理、摘要）

### 4.2 表定义（MySQL 8，统一 `id BIGINT PK AUTO_INCREMENT`、`created_at`、`updated_at`，逻辑删除 `deleted TINYINT`）

#### inbox_item（收集箱条目）

| 字段 | 类型 | 说明 |
|---|---|---|
| raw_content | TEXT | 原始内容（语音为 ASR 文本） |
| content_type | VARCHAR(16) | text / voice |
| source | VARCHAR(16) | web / wecom |
| audio_path | VARCHAR(255) | 语音文件本地路径，可空 |
| status | VARCHAR(16) | pending / processed / failed / archived |
| ai_category | VARCHAR(16) | task / schedule / memo / knowledge，整理后写入 |
| ai_confidence | DECIMAL(3,2) | 0.00-1.00，< 0.70 需人工确认 |
| ai_payload | JSON | AI 抽取的结构化结果（标题、时间、优先级等） |
| processed_at | DATETIME | 整理完成时间 |

索引：`(status, created_at)`、`(source)`

#### task（任务）

| 字段 | 类型 | 说明 |
|---|---|---|
| title | VARCHAR(200) | 标题 |
| description | TEXT | 详情，可空 |
| priority | VARCHAR(4) | P0 / P1 / P2 / P3 |
| status | VARCHAR(16) | todo / doing / done / cancelled |
| due_date | DATE | 截止日，可空 |
| is_deep_work | TINYINT | 是否深度工作 0/1 |
| is_blocking | TINYINT | 是否阻塞他人 0/1 |
| note | VARCHAR(500) | 备注，可空 |
| postponed | INT | 顺延次数（截止日后移一次 +1，列表标注"已顺延 N 次"） |
| source_inbox_id | BIGINT | 来源收集条目，可空（手工创建为 NULL） |
| completed_at | DATETIME | 完成时间 |

操作集：状态流转（点击流转 待办→进行中→完成）、编辑（标题/优先级/截止/备注/标记）、顺延（截止 +1 天，postponed+1）、取消/恢复、删除。逾期任务标注逾期天数。

索引：`(status, priority)`、`(due_date)`

#### schedule_event（日程）

| 字段 | 类型 | 说明 |
|---|---|---|
| title | VARCHAR(200) | 标题 |
| event_type | VARCHAR(16) | meeting / deep_block / other |
| start_time | DATETIME | 可空（时间待定） |
| end_time | DATETIME | 可空 |
| source_inbox_id | BIGINT | 来源，可空 |

索引：`(start_time)`

#### memo（备忘，独立页签承载，支持全文检索）

定位：**时效性事务参考**（链接收藏、家庭采买、班车时刻等）。与"知识"的边界——知识是沉淀性内容、写入 Obsidian 进入 RAG 索引；备忘留在工作台速查，买完/过期即归档。

| 字段 | 类型 | 说明 |
|---|---|---|
| title | VARCHAR(200) | 标题（AI 抽取或首行截断） |
| content | TEXT | 内容 |
| url | VARCHAR(500) | 自动从内容提取的链接，可空 |
| tags | VARCHAR(200) | 逗号分隔标签（AI 自动归类 + 人工修改） |
| grp | VARCHAR(8) | 分组：work / life（AI 按关键词自动判定，可手动切换） |
| pinned | TINYINT | 置顶 0/1（班车表等高频查阅） |
| status | VARCHAR(16) | active / archived |
| source_inbox_id | BIGINT | 来源，可空（手工创建为 NULL） |
| updated_at | DATETIME | 最近一次修改时间，可空 |

索引：`(status, pinned)`、`(grp, status)`；全文检索 MVP 期用 `LIKE`（单用户数据量小），量上来后可换 Meilisearch / ES。

#### pomodoro（番茄钟记录）

| 字段 | 类型 | 说明 |
|---|---|---|
| task_id | BIGINT | 关联任务，可空 |
| minutes | INT | 专注时长（默认 25） |
| ended_at | DATETIME | 完成时间 |

说明：番茄钟为顶栏常驻芯片 + 点击唤起的全局面板（专注 25 / 短休 5 / 长休 15，环形进度），可关联当前任务，完成数计入驾驶舱「今日番茄」指标；后续在周回顾中按任务聚合专注时长。

#### activity_log（使用流水，时间线页签数据源）

| 字段 | 类型 | 说明 |
|---|---|---|
| log_type | VARCHAR(16) | inbox / task / praise / memo / pomo / plan |
| content | VARCHAR(500) | 流水描述（完成任务时写入鼓励语） |
| created_at | DATETIME | 发生时间 |

说明：时间线页签按日分组展示全部操作流水；任务完成时从鼓励语库随机取一条写入 praise 类型记录并即时弹出，强化正反馈。索引：`(created_at)`。

#### knowledge_note（知识条目，同步 Obsidian）

| 字段 | 类型 | 说明 |
|---|---|---|
| title | VARCHAR(200) | 笔记标题 |
| vault_path | VARCHAR(255) | 写入 Vault 的相对路径（00-Inbox/xxx.md） |
| sync_status | VARCHAR(16) | pending / synced / failed |
| source_inbox_id | BIGINT | 来源 |

#### daily_plan（每日计划）

| 字段 | 类型 | 说明 |
|---|---|---|
| plan_date | DATE | 日期，唯一 |
| theme | VARCHAR(200) | 今日主题（**已弃用**：2026-09-20 起无任何写入方；列保留是因为 `daily_plan` 表仍被 `daily_plan_item` 的外键依赖） |
| completion_rate | DECIMAL(4,3) | 当日完成率，日结后写入 |

唯一索引：`(plan_date)`

#### daily_plan_item（计划项，AI 建议 + 人工调整）

| 字段 | 类型 | 说明 |
|---|---|---|
| plan_id | BIGINT | 关联 daily_plan |
| task_id | BIGINT | 关联 task |
| sort_order | INT | 展示顺序 |
| ai_reason | VARCHAR(255) | 建议理由（如"已逾期 2 天"），手工添加为 NULL |
| is_ai_suggested | TINYINT | 是否 AI 建议 |

索引：`(plan_id, sort_order)`

#### wechat_msg_log（微信消息流水）

| 字段 | 类型 | 说明 |
|---|---|---|
| msg_id | VARCHAR(64) | 企业微信 msgId，唯一（幂等键） |
| msg_type | VARCHAR(16) | text / voice / event |
| content | TEXT | 消息体 |
| handle_status | VARCHAR(16) | received / handled / failed |
| inbox_item_id | BIGINT | 生成的收集条目，可空 |

唯一索引：`(msg_id)`

#### ai_job（AI 调用流水）

| 字段 | 类型 | 说明 |
|---|---|---|
| job_type | VARCHAR(32) | inbox_classify / daily_summary |
| ref_id | VARCHAR(64) | 关联对象（如 inbox id 列表） |
| status | VARCHAR(16) | running / success / failed |
| prompt_tokens | INT | 成本观测 |
| completion_tokens | INT | 成本观测 |
| duration_ms | INT | 耗时 |
| error_msg | VARCHAR(500) | 失败原因 |

---

## 5. 关键流程

### 5.1 录入 → 整理 → 确认（每日核心闭环）

1. 任意入口录入 → `inbox_item(status=pending)`，微信侧回复"已收录"。
2. 触发整理（手动/16:30 定时/微信指令）→ 创建 `ai_job`，批量送 LLM 分类 → 回写 `ai_category/ai_confidence/ai_payload`，`status=processed`。
3. 用户在"整理确认页"逐条确认/修改 → 生成 `task`/`schedule_event`/`memo`/`knowledge_note` → `inbox_item.status=archived`。
4. knowledge 类异步写入 Vault `00-Inbox/`，更新 `sync_status`。
5. 整理中失败条目 `status=failed`，可一键重试；原始内容永不丢失。

### 5.2 每日早晨流程

1. 打开驾驶舱 → 系统取今日 `schedule_event` + 规则引擎计算 Top3 建议任务 → 写入 `daily_plan`/`daily_plan_item`。
2. 用户确认/调整顺序。（本步原先还有「设定今日主题」，2026-09-20 随该功能下线删除。）
3. 日结时勾选完成情况 → 回写 `completion_rate`。

---

## 6. API 概要（REST，前缀 /api/v1）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /inbox | Web 录入 |
| GET | /inbox?status= | 收集箱列表 |
| POST | /inbox/classify | 触发 AI 整理（异步，返回 jobId） |
| POST | /inbox/{id}/confirm | 确认整理结果，生成实体 |
| POST | /inbox/{id}/reclassify | 人工改分类后重新生成 |
| DELETE | /inbox/{id} | 软删除 |
| GET/POST/PATCH | /tasks, /tasks/{id} | 任务 CRUD + 状态流转 |
| GET/POST/PATCH | /events, /events/{id} | 日程 CRUD |
| GET | /dashboard/today | 驾驶舱今日聚合视图 |
| POST | /plan/generate | 生成当日计划（规则引擎） |
| PATCH | /plan/{date} | 调整计划项、设定主题 |
| POST | /plan/{date}/close | 日结 |
| POST | /wecom/callback | 企业微信回调（验签+幂等+指令路由） |

---

## 7. 验收指标（MVP 出口标准）

| 指标 | 目标 |
|---|---|
| 单次记录耗时（打开到提交） | ≤ 10 秒 |
| 每日整理确认耗时 | ≤ 15 分钟 |
| 收集箱日清空率 | ≥ 90% |
| AI 分类准确率（人工不改分类的比例） | ≥ 80% |
| 微信消息落库成功率 | ≥ 99.5%（幂等无重复） |

---

## 8. 两周排期建议

| 时间 | 内容 |
|---|---|
| 第 1-2 天 | 建库建表、工程骨架（Spring Boot + Vue）、Web 录入与列表 |
| 第 3-5 天 | 任务/日程 CRUD、状态机、驾驶舱静态聚合 |
| 第 6-8 天 | 企业微信回调、幂等、指令路由、语音 ASR |
| 第 9-11 天 | AI 整理引擎（分类 prompt + 确认页 + Vault 写入） |
| 第 12-13 天 | 规则版优先级建议、日结、备份脚本 |
| 第 14 天 | 自测 + 验收指标走查 + 上线自用 |
