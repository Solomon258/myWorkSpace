const state={status:null,dashboard:null,tasks:[],inbox:[],classify:[],events:[],pendingEvents:[],memos:[],activity:[],settings:null,backups:[],trash:[],memoGrp:"all",memoArchived:false,memoQuery:"",taskQuery:"",eventQuery:"",taskSearchOpen:false,eventSearchOpen:false,memoSearchOpen:false,tab:"dashboard",timelineCollapsed:{},moveOpen:null,taskFilter:"all",dragTaskId:null,
  // 周视图（2026-09-13，见 docs/日程周视图设计.md）。visibleWeeks 是**有序**的周起始日（周一）列表，
  // 顺序 = 页面从上到下（未来在上、过去在下），默认 [下周一, 本周一, 上周一]。
  // weekBase = 当前「本周」的周一，只由后端下发的 dashboard.date 决定，用来判断要不要整组复位。
  // expandedWeek = 当前**唯一展开**的那一条周（手风琴：任何时刻只有一个展开）。
  // 存 state 而不是只挂 DOM class：refreshAll 每次都重建周条，只挂 DOM 的话状态会被抹掉
  // （时间线折叠踩过同一个坑）。用「哪个展开」而不是「哪些折叠」，是因为后者能表达出
  // 「全展开」「全折叠」这些不该存在的状态。
  weekBase:"",visibleWeeks:[],expandedWeek:"",
  // 页内检索的防抖计时器（任务 / 日程 / 备忘各一个）。见 debounceInPage。
  inPageTimers:{},
  // 全局搜索的独立状态。**不参与 refreshAll** —— 那条链路一次拉 11 个接口并全量重绘 11 个视图，
  // 挂上去就等于每敲一个字符把整个工作台重画一遍（现有 #memoSearch 就是这个毛病）。
  search:{q:"",open:false,scope:"all",typeFilter:"",previewOpen:true,groups:[],activeIndex:-1,loading:false,error:"",totalCount:0,elapsedMs:0,truncated:false,seq:0,timer:null,resultQuery:"",
    semanticState:"off",expandedTerms:[],semanticCount:0},
  knowledge:{notes:[],index:null,indexState:"idle",indexError:"",chat:[],asking:false},
  pomo:{mode:"work",remain:25*60,running:false,timer:null,endAt:0,config:{work:25,shortBreak:5,longBreak:15,auto:false},today:{count:0,minutes:0},audio:null,flashTimer:null,baseTitle:document.title}};
const $=id=>document.getElementById(id);
// 转义必须覆盖引号。esc 的输出**大量用在属性上下文**（`value="…"` / `href="…"` / `data-name="…"`），
// 而「textContent → innerHTML」那种写法只转义 & < >，引号原样输出：
// 标题里带一个 `"`（如「复盘"双十一"」）就会提前关掉属性，后面的文字变成野属性
// （`value="复盘"双十一""`），输入框里只剩「复盘」；用户点保存，标题就被悄悄截短了——
// 不报错、也不难复现，属于最难发现的一类数据损坏。href 同理（内容里粘的链接带引号会把标签撕开）。
function esc(value){return String(value==null?"":value)
  .replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")
  .replace(/"/g,"&quot;").replace(/'/g,"&#39;")}
function toast(message,ms){const el=$("toast");el.textContent=message;el.classList.add("show");clearTimeout(toast.timer);
  // 长文案（如路径校验失败的详细原因）需要更长阅读时间：基础 2.2s，超出 24 字后每字 +60ms，上限 8s
  const duration=ms||Math.min(8000,2200+Math.max(0,String(message).length-24)*60);
  toast.timer=setTimeout(()=>el.classList.remove("show"),duration)}
function greeting(){const hour=new Date().getHours();return hour<11?"早上好":hour<14?"中午好":hour<18?"下午好":"晚上好"}
function eventTypeName(type){return {meeting:"会议",deep_block:"深度块",other:"其他"}[type]||type}
function statusName(status){return {todo:"待办",doing:"进行中",done:"已完成",canceled:"已取消"}[status]||status}
function inboxStatusName(status){return {pending:"待整理",processed:"待确认",failed:"整理失败",archived:"已归档"}[status]||status}
function categoryName(category){return {task:"任务",schedule:"日程",memo:"备忘",knowledge:"知识"}[category]||category}
function categoryOptions(){const options=["task","schedule","memo"];if(state.settings&&state.settings.obsidianVaultPath)options.push("knowledge");return options}
function nextStatus(status){return status==="todo"?"doing":status==="doing"?"done":"todo"}
// 任务页三泳道：泳道 key 就是任务的 status。泳道名跟着看板上的标题走，别在提示文案里各处硬编码中文。
function laneName(lane){return {todo:"待办",doing:"进行中",done:"已完成"}[lane]||lane}
// 拖动的本质是「改状态」，而后端是**严格状态机**（todo→doing→done，「开始」这一步不能跳）。
// 所以「待办直接拖到已完成」不能只发一次请求，要按合法路径逐级走：每一级各写一条时间线记录，
// 那是真实发生过的流转，不是噪音。返回 null = 这条路径不合法（已取消只有「恢复→待办」一条出路），
// 返回 [] = 已经在那条泳道里了。两者必须分开：一个是「不能这么走」，一个是「不用走」。
function taskDragSteps(fromStatus,toStatus){
  if(fromStatus===toStatus)return [];
  if(fromStatus==="canceled")return toStatus==="todo"?["todo"]:null;
  if(toStatus==="todo")return ["todo"];
  if(toStatus==="doing")return fromStatus==="todo"?["doing"]:["todo","doing"];
  return fromStatus==="doing"?["done"]:["doing","done"];
}
function formatDate(value){return value?value.slice(5):"无截止"}
// 回收站里的 type 是后端约定的实体名，和用户看到的页面名不是一回事（event ↔ 日程）。
// inbox 对应现在的「收录」页签 —— 恢复提示里会写「去「收录」看看」，名字必须和导航一致，
// 否则用户会去找一个已经不存在的「收集箱」。
function trashTypeName(type){return {inbox:"收录",task:"任务",event:"日程",memo:"备忘",knowledge:"知识"}[type]||type}
// 「移至」：任务 / 日程 / 备忘三个菜单之间搬运。目标只有另外两个，所以按来源直接推出来 ——
// 不写死三份列表：漏一份的表现是某个菜单里只列出两个目标，不报错，只能靠肉眼发现。
function menuName(type){return {task:"任务",event:"日程",memo:"备忘"}[type]||type}
function moveTargets(from){return from==="task"?["event","memo"]:from==="event"?["task","memo"]:["task","event"]}
// 菜单的开合状态存在 state 里，而不是只挂在 DOM 的 class 上：refreshAll 每次都重绘卡片，
// 状态只在 DOM 里的话下一次刷新就没了（时间线折叠踩过同一个坑，这里不重复）。
// 按钮样式必须**跟着所在那一行**走，不能在这里写死：任务 / 日程卡片那一行是实心按钮
// （编辑 / 顺延 / 取消 / 删除），备忘卡片那一行整排是 ghost（置顶 / 归档 / 编辑 / 删除）。
// 原来写死 ghost 的后果是「移至」是任务行里唯一一个无边框、灰字、还矮 4px 的按钮，
// 和左右邻居明显不是一套。所以 ghost 由调用方按所在行传进来：第三参数 true = 跟 ghost 排。
function moveMenu(from,id,ghost){
  const open=state.moveOpen===from+"-"+id;
  const targets=moveTargets(from);
  const options=targets.map(type=>`<button class="move-opt" data-action="move-to" data-from="${from}" data-to="${type}" data-id="${id}">移到「${menuName(type)}」</button>`).join("");
  return `<span class="move-wrap"><button class="btn sm${ghost?" ghost":""}" data-action="move" data-from="${from}" data-id="${id}" aria-expanded="${open?"true":"false"}" title="把这条记录移到「${targets.map(menuName).join("」或「")}」">移至</button><span class="move-menu${open?" open":""}" id="move-menu-${from}-${id}">${options}</span></span>`;
}
function closeMoveMenu(){
  if(!state.moveOpen&&!document.querySelector(".move-menu.open"))return;
  state.moveOpen=null;
  document.querySelectorAll(".move-menu.open").forEach(menu=>menu.classList.remove("open"));
}
async function refreshAll(){
  const memoParams={archived:state.memoArchived};if(state.memoGrp!=="all")memoParams.grp=state.memoGrp;if(state.memoQuery)memoParams.q=state.memoQuery;
  // 任务检索走后端 keyword（命中 标题 / 描述 / 备注），所以必须在**取数**这一步带上，
  // 不能在渲染阶段过滤：任务页只加载前 100 条，本地过滤会把「第 100 条之外的那个匹配项」
  // 静默漏掉——用户搜了、没结果，会以为那条任务不存在。
  const taskParams={size:100};if(state.taskQuery)taskParams.keyword=state.taskQuery;
  // 日程页是周视图：一次取回「当前可见的所有周」那一段区间（from/to），
  // 而不是按天循环调 7 次（那会让一次刷新多出 6 个请求）。
  // 区间要先知道「本周」才算得出来，而「今天」只能由后端 dashboard.date 定义一次，
  // 所以这里先 syncVisibleWeeks() 定一次；首次进入时 dashboard 还没回来，
  // 用本机日期兜底（前端与后端同机同时区），拿到 dashboard 后再校一次，跨零点会整组复位。
  // 检索态让出区间：命中跨全部日期，带上 from/to 会变成「只在这一段里搜」。
  syncVisibleWeeks();
  const results=await Promise.all([WorkbenchApi.dashboard(),WorkbenchApi.events(eventParams()),WorkbenchApi.tasks(taskParams),WorkbenchApi.inbox("pending"),WorkbenchApi.inbox("processed"),WorkbenchApi.memos(memoParams),WorkbenchApi.activity(500),WorkbenchApi.settings(),WorkbenchApi.backups(),WorkbenchApi.knowledgeNotes(),WorkbenchApi.pendingEvents(),WorkbenchApi.trash(),WorkbenchApi.inbox("failed")]);
  state.dashboard=results[0];
  // dashboard.date 可能与首帧兜底算出的「本周」不同（首次进入、或跨了零点）：那时上面那条
  // events 请求的区间已经过期，按新周补取一次，否则界面上会缺一整周的数据。
  const weeksChanged=syncVisibleWeeks();
  state.events=weeksChanged?(await WorkbenchApi.events(eventParams()))||[]:(results[1]||[]);state.tasks=results[2];state.classify=results[4];state.memos=results[5];state.activity=results[6]||[];state.settings=results[7];state.backups=results[8]||[];state.knowledge.notes=results[9]||[];state.pendingEvents=results[10]||[];state.trash=results[11]||[];
  // 「待整理」区同时装 pending 与 failed：整理失败的条目状态既不是 pending 也不是 processed，
  // 两个列表都捞不到它 —— 条目会彻底从界面上消失。这是「静默丢数据」的典型形态。
  state.inbox=(results[3]||[]).concat(results[12]||[]);
  renderDashboard();renderTasks();renderInbox();renderClassify();renderSchedule();renderPendingEvents();renderMemos();renderTimeline();renderPomoTasks();renderSettings();renderKnowledge();renderTrash();
}
function setTab(tab){
  // 切页签时收起「移至」菜单：它挂在卡片上，页签切换不重绘卡片，不收就会留在页面上。
  closeMoveMenu();
  // 同理收起全局搜索面板：它是覆盖在内容之上的浮层，切了页签还挡着就看不到新页面了。
  // 关键词保留在输入框里，回来再点一下就是原来的结果。
  closeSearchPanel();
  state.tab=tab;document.querySelectorAll(".tabs button").forEach(button=>button.classList.toggle("active",button.dataset.tab===tab));
  document.querySelectorAll(".view").forEach(view=>view.classList.toggle("active",view.id==="view-"+tab));
  const gear=$("gearButton");if(gear)gear.classList.toggle("active",tab==="settings");
  // 设置页要显示 Vault 是否真的生效，所以也要拿到知识库索引
  if((tab==="knowledge"||tab==="settings")&&(state.knowledge.indexState==="idle"||state.knowledge.indexState==="error"))loadKnowledgeIndex();
  else if(tab==="settings")renderVaultStatus();
}
function renderDashboard(){
  const data=state.dashboard,m=data.metrics;
  $("metricOpen").textContent=m.taskOpen;$("metricDone").textContent=m.taskDone+" / "+m.taskTotal;$("metricOverdue").textContent=m.overdue;$("metricToday").textContent=data.todayTasks.length;
  $("metricInbox").textContent=m.inboxPending;$("metricPomo").textContent=m.pomoToday;
  if(document.activeElement!==$("themeInput"))$("themeInput").value=data.theme||"";
  $("brief").textContent=data.brief;
  $("topTasks").innerHTML=data.topTasks.length?data.topTasks.map((task,index)=>`<div class="list-item top-card drill-row" data-action="drill-task" data-id="${task.id}" title="到任务列表查看这条"><span class="top-index">${index+1}</span><div class="grow"><div class="task-title ${task.overdue?"overdue-text":""}">${esc(task.title)} <span class="pill ${task.priority==="P0"?"red":task.priority==="P1"?"amber":""}">${task.priority}</span></div><div class="reason">${esc(task.reason)}</div></div><button class="btn sm" data-action="status" data-id="${task.id}" data-status="${nextStatus(task.status)}">${task.status==="doing"?"完成":"开始"}</button></div>`).join(""):"<div class=\"empty\">暂无待推进任务</div>";
  $("todayTasks").innerHTML=data.todayTasks.length?data.todayTasks.map(task=>`<div class="list-item drill-row" data-action="drill-task" data-id="${task.id}" title="到任务列表查看这条"><div class="grow"><div class="task-title">${esc(task.title)}</div><div class="task-meta">${task.priority} · ${statusName(task.status)} · 今天截止</div></div></div>`).join(""):"<div class=\"empty\">今天没有到期任务</div>";
  $("metricEvents").textContent=m.eventToday;
  $("dashEvents").innerHTML=data.events.length?data.events.map(e=>`<div class="list-item drill-row" data-action="drill-event" data-id="${e.id}" data-date="${e.date||""}" title="到日程页查看这条"><div class="grow"><div class="task-title">${e.start?esc(e.start)+" ":""}${esc(e.title)} <span class="pill ${e.type==="deep_block"?"blue":e.type==="meeting"?"green":""}">${eventTypeName(e.type)}</span></div>${e.end?`<div class="task-meta">${e.start||""} - ${e.end}</div>`:""}</div></div>`).join(""):"<div class=\"empty\">今日暂无日程</div>";
}
// 任务页一次只取 100 条（后端 TaskService 的硬上限，见 safeSize）。超出时**必须说出来**：
// 之前是静默截断——用户任务攒到 100 条以上，老任务凭空消失，界面上没有任何异常。
// 真实总数来自驾驶舱 metrics.taskTotal（`COUNT(*) WHERE deleted=0`），所以能算出「还有多少条没显示」。
function hiddenTaskCount(){const m=state.dashboard&&state.dashboard.metrics;return Math.max(0,(m?m.taskTotal:0)-state.tasks.length)}
function taskTruncationNote(){
  // 检索态下上面那条减法**不成立**：metrics.taskTotal 是全库条数，与命中数不是一回事，
  // 拿它减命中数会报出「另有 117 条更早的任务没有显示」这种凭空捏造的数字。
  // 检索态能确定的只有「命中数顶到 100 条上限了」，就照实说，并给出下一步（收窄关键词）。
  if(state.taskQuery){
    return state.tasks.length>=100?"<div class=\"empty\">匹配「"+esc(state.taskQuery)+"」的任务已达 100 条上限，只显示前 100 条；换个更具体的关键词能看得更准。</div>":"";
  }
  const hidden=hiddenTaskCount();
  return hidden?"<div class=\"empty\">任务较多：上面只列出前 100 条（排序为 进行中 → 待办 → 已完成，再按优先级与截止时间），另有 "+hidden+" 条更早的任务没有显示。</div>":"";
}
// 驾驶舱下钻（2026-09-12）：指标卡点一下 → 对应页签；列表条目点一下 → 跳到它所在的页并高亮。
// 筛选口径必须与后端算指标时一致：是否逾期读后端的 task.overdue，「今天」用 todayString()
// （后端按配置时区定义的那一天），不要用浏览器时钟另算一套。
function taskFilterName(filter){return {open:"进行中与待办",done:"已完成 / 已取消",overdue:"逾期任务",today:"今天截止"}[filter]||filter}
function taskVisible(task){
  const filter=state.taskFilter||"all";
  if(filter==="open")return task.status==="todo"||task.status==="doing";
  if(filter==="done")return task.status==="done"||task.status==="canceled";
  if(filter==="overdue")return !!task.overdue;
  if(filter==="today")return task.due===todayString();
  return true;
}
// 高亮是一次性动画，不写进 state：用户做完别的操作再刷新时，不希望它还亮着。
function highlightRow(row){
  if(!row)return;
  row.classList.add("hl");
  row.scrollIntoView({block:"center",behavior:"smooth"});
  setTimeout(()=>row.classList.remove("hl"),2600);
}
// 清检索条件时必须**同时**清掉 state 与输入框里的文字。只清 state 的话，列表回到了全部，
// 但输入框里还写着关键词 —— 用户会以为搜索没生效（本项目最忌讳的「看着像坏了」）。
// 下钻前的清场与「一键取消」按钮都走这两个函数，别各写一份。
function clearTaskQuery(){state.taskQuery="";const el=$("taskSearch");if(el)el.value=""}
function clearEventQuery(){state.eventQuery="";const el=$("eventSearch");if(el)el.value=""}
function clearMemoQuery(){state.memoQuery="";const el=$("memoSearch");if(el)el.value=""}
// ===== 搜索行（2026-09-12 改版）=====
// 收起态只留「＋新建X」+ 一个放大镜方按钮；点放大镜后输入框接管这一行、新建压窄留在左边。
// 展开/收起是**纯视图状态**，所以按 ID 绑定、不走 data-action（同 bindComposer 的理由：
// 检查脚本那条「每个 data-action 都有处理分支」盯的是会改数据 / 跳转的动作）。
// 三条硬规则，每一条都对应一类真实故障：
// ① 同一行同一时刻只能有一个展开态（开搜索就收新建表单，开新建表单就收搜索）。
//    不这么做的话 .composer 被压窄成一百来像素而里面的表单还开着，grid 会被挤成一条细缝。
// ② 收起**不等于**退出搜索：关键词留着，靠按钮上的小圆点 + 页面上那条 .filter-note 说明
//    「列表还是筛过的」。列表被筛过却看不出为什么 = 静默过滤，是本项目最忌讳的一类失效。
// ③ 备忘页的「显示已归档」收起后没有入口（它管的是常驻列表，不只是搜索），
//    勾选生效时补一个「含归档」标签兜住，点一下就关。
function searchRowIds(tab){
  if(tab==="event")return {row:"eventSearchRow",input:"eventSearch",btn:"eventSearchBtn",body:"composerBodyEvent",toggle:"composerToggleEvent",name:"日程"};
  if(tab==="memo")return {row:"memoSearchRow",input:"memoSearch",btn:"memoSearchBtn",body:"composerBodyMemo",toggle:"composerToggleMemo",name:"备忘"};
  return {row:"taskSearchRow",input:"taskSearch",btn:"taskSearchBtn",body:"composerBodyTask",toggle:"composerToggleTask",name:"任务"};
}
function searchQuery(tab){return tab==="event"?state.eventQuery:tab==="memo"?state.memoQuery:state.taskQuery}
function searchOpenFlag(tab){return tab==="event"?!!state.eventSearchOpen:tab==="memo"?!!state.memoSearchOpen:!!state.taskSearchOpen}
function setSearchOpenFlag(tab,open){if(tab==="event")state.eventSearchOpen=open;else if(tab==="memo")state.memoSearchOpen=open;else state.taskSearchOpen=open}
// 只同步 DOM：不改 state、不取数。所以三个 render 的末尾都能安全地调它，
// 保证「state 一变、这一行跟着变」，不会出现两处各写一套显隐逻辑然后漂掉。
function applySearchRow(tab){
  const ids=searchRowIds(tab);
  const open=searchOpenFlag(tab),query=searchQuery(tab);
  const row=$(ids.row),input=$(ids.input),btn=$(ids.btn);
  if(row)row.classList.toggle("searching",open);
  if(input)input.classList.toggle("hidden",!open);
  if(btn){
    btn.setAttribute("aria-expanded",open?"true":"false");
    btn.setAttribute("aria-label",open?"收起搜索":"搜索"+ids.name);
    // 小圆点只在「收起但关键词还在」时亮。展开时不用它：输入框里的字本身就是提示。
    btn.classList.toggle("busy",!open&&!!query);
  }
  if(tab==="memo"){
    const label=$("memoArchivedLabel");if(label)label.classList.toggle("hidden",!open);
    // 勾选框藏在 .hidden 里时它的 checked 不会自己跟着 state 走，展开时补一次同步：
    // 否则「含归档」标签关掉之后重新展开，勾选框还画着对勾（显示与状态不符）。
    const box=$("memoShowArchived");if(box&&box.checked!==!!state.memoArchived)box.checked=!!state.memoArchived;
    const chip=$("memoArchivedChip");if(chip)chip.classList.toggle("hidden",!(state.memoArchived&&!open));
  }
}
function setSearchOpen(tab,open){
  const ids=searchRowIds(tab);
  // 规则①：开搜索就把新建表单收起来。只是「收起」，关键词不丢（规则②）。
  if(open){
    const body=$(ids.body),toggle=$(ids.toggle);
    if(body&&body.classList.contains("open")){
      body.classList.remove("open");
      if(toggle){toggle.classList.remove("open");toggle.setAttribute("aria-expanded","false")}
    }
  }
  setSearchOpenFlag(tab,open);
  applySearchRow(tab);
  if(open){const input=$(ids.input);if(input&&input.focus)input.focus()}
}
// ===== 页内检索：防抖 + 只重渲染自己那一块（2026-09-12）=====
// 这三个输入框原来是 `input → refreshAll()`：**每敲一个字符拉 11 个接口、重绘 11 个视图**。
// 而检索命中的只是自己那一页的数据（任务 `?keyword=` / 日程 `?q=` / 备忘 `?q=`），
// 其余 10 个视图一个字都不会变。所以收敛成「防抖 200ms + 只取自己要的那一份」。
// 注意 state 字段的语义完全不变（taskQuery / eventQuery / memoQuery），
// 下一次 refreshAll 用的是同一批字段 —— 两套取数口径必须保持一致，否则
// 「搜索后再点别处」会看到两种不同的列表。
const IN_PAGE_SEARCH_DEBOUNCE_MS=200;
function debounceInPage(key,run){
  const timers=state.inPageTimers;
  if(timers[key])clearTimeout(timers[key]);
  timers[key]=setTimeout(function(){timers[key]=null;run()},IN_PAGE_SEARCH_DEBOUNCE_MS);
}
async function reloadTasks(){
  const params={size:100};
  if(state.taskQuery)params.keyword=state.taskQuery;
  state.tasks=(await WorkbenchApi.tasks(params))||[];
  renderTasks();
}
async function reloadMemos(){
  const params={archived:state.memoArchived};
  if(state.memoGrp!=="all")params.grp=state.memoGrp;
  if(state.memoQuery)params.q=state.memoQuery;
  state.memos=(await WorkbenchApi.memos(params))||[];
  renderMemos();
}
function drillMetric(kind){
  if(kind==="inbox"){setTab("inbox");return}
  if(kind==="pomo"){$("pomoMask").classList.add("show");return}
  state.taskFilter=kind;
  setTab("tasks");renderTasks();
  // 筛出来是空的要当场说明，否则用户会以为任务丢了（不是「没显示」，而是「没有」）
  if(!state.tasks.some(taskVisible))toast("当前没有「"+taskFilterName(kind)+"」的任务",3600);
}
function drillTask(id){
  // 下钻到某一条时必须先清掉筛选：那一条很可能正被上一次的筛选挡着，否则就是「点了没反应」。
  state.taskFilter="all";
  setTab("tasks");renderTasks();
  // 选择器必须限定在任务页之内。驾驶舱的 Top3 条目也带 data-task-id 且排在 DOM 更前面，
  // 用 document.querySelector('.list-item[data-task-id]') 时命中的会是**隐藏的驾驶舱那一行**：
  // 高亮与 scrollIntoView 都作用在 display:none 的元素上，界面上什么都没发生 ——
  // 又是一次「点了没反应」，而且控制台一个字都不报。
  const row=document.querySelector('#view-tasks .task-card[data-task-id="'+id+'"]');
  if(row)highlightRow(row);
  else toast("这条任务不在已加载的列表里（任务页一次最多显示 100 条）",4800);
}
async function drillEvent(id,date){
  // 和任务页下钻同一条约定：先清掉检索条件再跳过去。否则要定位的那一条很可能正被
  // 上一次的检索挡在结果之外，用户看到的是「点了没反应」。
  clearEventQuery();
  setTab("schedule");
  // 日程页是周视图：先把这条日程落在的那一周弄进可见列表，再渲染、滚动、高亮。
  // 顺序不能反 —— 那一周的周条不进 DOM，下面那个选择器就什么都找不到。
  // date 为空只可能是待定日程（event_date 为 NULL）：它不属于任何一周，只在「待定时间」区露出，
  // 不要拿今天去凑一个周出来。
  if(date){
    const week=weekStartOf(date);
    // ensureWeek 只改 state（目标周进列表 + 设为当前展开的那条）；渲染要自己做，
    // 否则会出现「周条还是收起的、按钮也没跟过去」—— 高亮落在一条收起的横杠里等于没高亮。
    if(ensureWeek(week))await loadEvents();
    else renderSchedule();
    scrollToWeek(week);
  }
  const row=document.querySelector('#eventWeeks .event-card[data-event-id="'+id+'"]')
    ||document.querySelector('#pendingEventList .list-item[data-event-id="'+id+'"]');
  if(row)highlightRow(row);
  else toast("这条日程不在当前显示的周里，可用周头右上角的 ↑ / ↓ 翻到它那一周",5600);
}
function renderTasks(){
  const filter=state.taskFilter||"all";
  const query=state.taskQuery||"";
  // 检索走后端（见 refreshAll），state.tasks 里已经是命中结果，这里只再叠加「筛选」这一层。
  const visible=state.tasks.filter(taskVisible);
  const narrowed=filter!=="all"||!!query;
  // 泳道顺序 = 状态机顺序：待办 → 进行中 → 已完成。
  // 「已取消」不单开一条泳道（用户只要三条），它归在「已完成」里，但卡片带「已取消」样式区分开——
  // 比直接把它藏掉好得多：藏掉的话那条任务在界面上连入口都没有了（第七类失效）。
  const todo=visible.filter(task=>task.status==="todo");
  const doing=visible.filter(task=>task.status==="doing");
  const done=visible.filter(task=>task.status==="done"||task.status==="canceled");
  // 条数角标写在泳道标题上，让「颜色 = 泳道」一眼可读；0 条时留空，由 CSS :empty 藏掉空胶囊。
  const counts={todo:$("taskCountTodo"),doing:$("taskCountDoing"),done:$("taskCountDone")};
  if(counts.todo)counts.todo.textContent=todo.length?todo.length+" 条":"";
  if(counts.doing)counts.doing.textContent=doing.length?doing.length+" 条":"";
  if(counts.done)counts.done.textContent=done.length?done.length+" 条":"";
  // 筛选 / 检索状态必须写在页面上、并且能一键取消：静默过滤会让用户以为任务凭空少了一半。
  // 两句话必须分别说清「筛的是什么」与「搜的是什么」，只说其中一句会让另一句变成隐形条件
  // （用户改了口径却看不出为什么条数没变）。
  const filterNote=$("taskFilterNote");
  if(filterNote){
    const scope=filter==="all"?"":"只显示「"+taskFilterName(filter)+"」";
    const searching=query?"搜索「"+esc(query)+"」":"";
    let text="";
    if(scope&&searching)text="当前"+scope+"，并在其中"+searching+"，共 "+visible.length+" 条。";
    else if(searching)text="当前在全部任务里"+searching+"，共 "+visible.length+" 条。";
    else if(scope)text="当前"+scope+"，共 "+visible.length+" 条。";
    // 一键取消要一次清干净（筛选 + 检索）：只清一半的话，用户点了按钮条数却没变回去，
    // 看起来就是「这个按钮坏了」。按钮文案跟着说清它会清掉几样东西。
    const clearLabel=scope&&searching?"清除筛选与搜索":searching?"清除搜索":"显示全部任务";
    filterNote.classList.toggle("hidden",!narrowed);
    filterNote.innerHTML=narrowed?("<span>"+text+"</span><button class=\"btn sm ghost\" data-action=\"clear-task-filter\">"+clearLabel+"</button>"):"";
  }
  // 空泳道的文案分两种：没筛选时教怎么把卡片弄进来（空泳道不写清楚，看起来就像坏了）；
  // 筛选 / 检索时别再说「从上面新建一条」——用户此刻看的是筛出来的结果，不是空列表。
  const why=query?"搜索":"筛选";
  const empty=narrowed
    ?{todo:"没有符合当前"+why+"的待办任务。",doing:"没有符合当前"+why+"的进行中任务。",done:"没有符合当前"+why+"的任务。"}
    :{todo:"没有待办任务。在上面「新建任务」里加一条，或把卡片从别的泳道拖过来。",
      doing:"没有进行中的任务。把「待办」里的卡片拖到这里，就表示这件事开始做了。",
      done:"还没有已完成的任务。做完的卡片会落到这里。"};
  $("taskLaneTodo").innerHTML=todo.length?todo.map(taskCard).join(""):"<div class=\"empty\">"+empty.todo+"</div>";
  $("taskLaneDoing").innerHTML=doing.length?doing.map(taskCard).join(""):"<div class=\"empty\">"+empty.doing+"</div>";
  $("taskLaneDone").innerHTML=done.length?done.map(taskCard).join(""):"<div class=\"empty\">"+empty.done+"</div>";
  // 截断说明挂在整个看板下方：它讲的是「列表不完整（只加载了前 100 条）」，不是某条泳道为空，
  // 塞进泳道里会被读成「这一列的任务丢了」。
  const boardNote=$("taskBoardNote");
  if(boardNote)boardNote.innerHTML=taskTruncationNote();
  // 搜索行的显隐 / 小圆点跟着 state 一起同步：收起态也要能看出「还在检索」。
  applySearchRow("task");
}
function taskCard(task){
  const ended=task.status==="done"||task.status==="canceled";
  const canceled=task.status==="canceled";
  // 优先级不放标题后面，而是钉在卡片右上角（.task-prio）：卡片只有约 350px 宽，
  // 中文标题一长就会把「P2」挤到自己独占一行，看起来像一段多出来的文字（截过图才看出来的）。
  // 留在标题后的只有「给标题加注释」的语义标签：逾期 / 阻塞 / 深度 / 顺延。
  const prio=task.priority==="P0"?"red":task.priority==="P1"?"amber":"";
  const flags=[task.overdue?'<span class="pill red">逾期</span>':"",task.blocking?'<span class="pill amber">阻塞</span>':"",task.deep?'<span class="pill blue">深度</span>':"",task.postponed>=3?'<span class="pill red">顺延≥3次</span>':""].join("");
  // 卡片自上而下三层：状态圆点 + 标题/元信息 → 操作按钮 → 内联编辑表单。
  // 编辑表单放**最底下**，与备忘卡片一致：点「编辑」不会把上面的按钮顶来顶去。
  // draggable 默认 false、由 mousedown 动态打开（见文件末尾 taskBoard 的绑定）：
  // 整卡常驻 draggable=true 会让编辑表单里的输入框没法用鼠标选中文本，Chrome / Firefox 都会。
  return `<div class="task-card${canceled?" is-canceled":""}" draggable="false" data-task-id="${task.id}" data-status="${task.status}"><div class="tc-top"><button class="status-button ${task.status}" data-action="status" data-id="${task.id}" data-status="${nextStatus(task.status)}" title="切换任务状态：待办 → 进行中 → 已完成 → 待办">${task.status==="done"?"✓":task.status==="doing"?"…":""}</button><div class="grow"><div class="task-title${canceled?" strike":""}">${esc(task.title)} ${flags}</div><div class="task-meta">${statusName(task.status)} · 截止 ${formatDate(task.due)}${task.overdue?` · <span class="overdue-text">逾期 ${task.overdueDays} 天</span>`:""}${task.postponed?` · 已顺延 ${task.postponed} 次`:""}</div>${task.note?`<div class="task-meta">备注：${esc(task.note)}</div>`:""}</div><span class="pill task-prio${prio?" "+prio:""}">${task.priority}</span></div><div class="actions tc-acts"><button class="btn sm" data-action="edit" data-id="${task.id}">编辑</button>${!ended?`<button class="btn sm" data-action="postpone" data-id="${task.id}">顺延</button>`:""}<button class="btn sm" data-action="status" data-id="${task.id}" data-status="${task.status==="canceled"||task.status==="done"?"todo":"canceled"}">${ended?"恢复":"取消"}</button>${moveMenu("task",task.id)}<button class="btn sm danger" data-action="delete" data-id="${task.id}">删除</button></div><div class="edit-panel me-form" id="edit-${task.id}"><div class="me-head">编辑任务</div><label class="me-field"><span class="me-label">标题</span><input id="edit-title-${task.id}" value="${esc(task.title)}" maxlength="200"></label><div class="me-row"><label class="me-field"><span class="me-label">优先级</span><select id="edit-priority-${task.id}">${["P0","P1","P2","P3"].map(p=>`<option ${p===task.priority?"selected":""}>${p}</option>`).join("")}</select></label><label class="me-field"><span class="me-label">截止日期</span><input type="date" id="edit-due-${task.id}" value="${task.due||""}"></label></div><label class="me-field"><span class="me-label">备注</span><input id="edit-note-${task.id}" value="${esc(task.note||"")}" placeholder="备注" maxlength="1000"></label><div class="checks me-checks"><label><input type="checkbox" id="edit-deep-${task.id}" ${task.deep?"checked":""}> 深度工作</label><label><input type="checkbox" id="edit-blocking-${task.id}" ${task.blocking?"checked":""}> 阻塞他人</label></div><div class="me-actions"><button class="btn sm" data-action="cancel" data-id="${task.id}">取消</button><button class="btn sm primary" data-action="save" data-id="${task.id}">保存</button></div></div></div>`;
}
// 拖动落点 = 把卡片换到目标泳道对应的状态。
// 这里**不能只发一次 status 请求**：后端是严格状态机（todo→doing→done，「开始」这一步跳不过去），
// 「待办直接拖到已完成」会被 3001 拒掉。所以按 taskDragSteps 给出的合法路径逐级走；
// 中途失败时任务会停在中间那一级，必须把它当前真实的位置说出来，否则用户看到卡片落在
// 「进行中」会以为自己拖错了（比没提示更糟）。
async function dropTaskToLane(taskId,lane){
  const task=state.tasks.find(item=>String(item.id)===String(taskId));
  if(!task)return;
  const steps=taskDragSteps(task.status,lane);
  if(steps===null){toast("「已取消」的任务只能拖回「待办」：先拖到待办泳道（或点卡片上的「恢复」），再从那里拖到别的泳道。",7000);return}
  if(!steps.length){toast("这条任务已经在「"+laneName(lane)+"」里了");return}
  try{
    for(const status of steps)await WorkbenchApi.changeTaskStatus(task.id,status);
  }catch(error){
    await refreshAll();
    const now=state.tasks.find(item=>String(item.id)===String(taskId));
    throw new Error("没能移到「"+laneName(lane)+"」："+error.message+"（任务现在停在「"+(now?statusName(now.status):"未知")+"」）");
  }
  await refreshAll();
  toast(steps.length>1?"已移到「"+laneName(lane)+"」；跨级拖动按状态机逐级流转，时间线里会留下 "+steps.length+" 条状态记录。":"已移到「"+laneName(lane)+"」");
}
// 拖到哪条泳道就点亮哪条：整条泳道描边 + 底色，比只高亮一条细边框更容易看清落点。
// 传 null 取消全部高亮（拖到泳道之间的缝隙、或拖拽结束时）。
function setLaneDragOver(active){
  document.querySelectorAll("#taskBoard .lane").forEach(lane=>lane.classList.toggle("drag-over",lane===active));
}
// 「收录」页把原来的「收集箱」和「整理确认」合成了一页，所以角标要同时算上待整理 + 待确认。
function renderInbox(){
  const pending=state.inbox,classify=state.classify,total=pending.length+classify.length;
  $("inboxBadge").textContent=total||"";$("inboxBadge").style.display=total?"inline-block":"none";
  const pendingCount=$("pendingInboxCount");if(pendingCount)pendingCount.textContent=pending.length?"（"+pending.length+" 条）":"";
  // 收录即整理，所以「待整理」平时是空的；空着就整块收起来，不给版面添噪音。
  // 但它必须存在：整理失败的条目状态既不是 pending 也不是 processed，没有这块就无处可去。
  const pendingCard=$("pendingCard");if(pendingCard)pendingCard.style.display=pending.length?"":"none";
  $("inboxList").innerHTML=pending.length?pending.map(item=>`<div class="list-item" data-inbox-id="${item.id}" data-inbox-status="${item.status}"><div class="grow"><div class="task-title">${esc(item.raw)}</div><div class="task-meta">${inboxStatusName(item.status)} · ${item.source==="wecom"?"微信":"Web"} · ${item.createdAt}</div></div><div class="actions"><button class="btn sm" data-action="reclassify-inbox" data-id="${item.id}">重新整理</button><button class="btn sm danger" data-action="delete-inbox" data-id="${item.id}">删除</button></div></div>`).join(""):"<div class=\"empty\">没有待整理的条目。</div>";
}
// 各分类真正会落库的字段：task 用【日期+优先级】，schedule 用【日期+开始时间】，
// memo / knowledge 只用标题。这里按需求 US-2.3「修改分类后字段表单联动切换」做显隐，
// 避免出现「填了却不生效」的静默丢弃字段。
function classifyFieldVisible(category,field){
  if(field==="due")return category==="task"||category==="schedule";
  if(field==="priority")return category==="task";
  if(field==="start")return category==="schedule";
  return true;
}
// 逐卡片算网格列数：被隐藏的字段不占列，否则「确认生成」会被挤到错误的列或被拉伸变形。
function classifyGridTemplate(category){
  const cols=["1fr","1.5fr"];
  if(classifyFieldVisible(category,"due"))cols.push("1fr");
  if(classifyFieldVisible(category,"priority"))cols.push(".8fr");
  if(classifyFieldVisible(category,"start"))cols.push(".9fr");
  cols.push("auto");
  return cols.join(" ");
}
function syncClassifyFields(id,category){
  ["due","priority","start"].forEach(field=>{
    const el=$("confirm-"+field+"-"+id);
    if(el)el.classList.toggle("hidden",!classifyFieldVisible(category,field));
  });
  const categorySelect=$("confirm-category-"+id);
  if(categorySelect&&categorySelect.parentElement)categorySelect.parentElement.style.gridTemplateColumns=classifyGridTemplate(category);
}
// 「会进入待定区」的提示要跟着日期输入实时收敛：用户一旦填了日期，这条提示就过期了。
function syncPendingHint(id){
  const hint=$("confirm-pendinghint-"+id);if(!hint)return;
  const due=$("confirm-due-"+id);
  hint.classList.toggle("hidden",!!(due&&due.value));
}
// 与后端 InboxService.confirmHighConfidence 的筛选条件保持一致：
// 置信度 >= 0.70，且知识类必须已配置 Vault（否则后端会跳过，前端不能虚报数量）。
function batchConfirmableCount(){
  const vaultReady=!!(state.settings&&state.settings.obsidianVaultPath);
  return state.classify.filter(item=>{
    const ai=item.ai;if(!ai||typeof ai.confidence!=="number")return false;
    if(ai.confidence<0.70)return false;
    if(ai.category==="knowledge"&&!vaultReady)return false;
    return true;
  }).length;
}
// 待确认卡里的日期默认值：整理器抽出日期就用抽出的，没抽出来就**默认今天**。
// 注意这是「前端预填」而不是后端兜底 —— 用户把日期清空仍然是「时间待定」这个真实状态
// （US-4.2），后端不会把它悄悄改回今天。把默认值放在前端，是项目里既定的做法
// （日程页的日期框也是预填正在查看的那天），后端一旦隐式兜底，「清空 = 待定」就没有入口了。
function confirmDueValue(ai){
  if(!classifyFieldVisible(ai.category,"due"))return "";
  return (ai.payload&&ai.payload.due)||todayString();
}
function renderClassify(){
  const countEl=$("classifyCount");if(countEl)countEl.textContent=state.classify.length?"（"+state.classify.length+" 条待确认）":"";
  // 按钮上直接写明会生成几条，避免用户点完才发现「一个都没生成」。
  const batchButton=$("confirmHighConfidenceButton");
  if(batchButton){const count=batchConfirmableCount();batchButton.textContent=count?"一键确认高置信度（"+count+"）":"一键确认高置信度";batchButton.disabled=!count;batchButton.title=count?"按 AI 建议直接生成这 "+count+" 条；低于 70% 的会保留待你逐条确认":"当前没有置信度 ≥ 70% 的条目，请逐条核对后点「确认生成」"}
  $("classifyList").innerHTML=state.classify.length?state.classify.map(item=>{
    const ai=item.ai||{category:"task",confidence:0,payload:{}};
    const due=confirmDueValue(ai);
    return `<div class="list-item classify-card" data-inbox-id="${item.id}" data-inbox-status="processed"><div class="grow"><div class="task-title">${esc(item.raw)} <span class="pill ${ai.needsConfirm?"amber":"green"}">${Math.round(ai.confidence*100)}%${ai.needsConfirm?" · 待人工确认":""}</span></div><div class="task-meta">建议整理为：${categoryName(ai.category)}${ai.payload&&ai.payload.due?" · "+ai.payload.due:""}${ai.payload&&ai.payload.start?" · "+ai.payload.start:""}</div>${ai.category==="schedule"&&ai.payload&&ai.payload.repeatWeeks>1?`<div class="task-meta">识别到「每周」：确认后会一次生成 ${ai.payload.repeatWeeks} 期（连续 ${ai.payload.repeatWeeks} 周），每一期都是独立日程，之后可以单独修改。</div>`:""}${ai.category==="schedule"?`<div class="task-meta${due?" hidden":""}" id="confirm-pendinghint-${item.id}">没选日期：确认后会先进入日程页的「待定时间」区，之后补上日期即可。</div>`:""}${ai.needsConfirm?'<div class="task-meta">置信度低于 70%，只表示不参与「一键批量确认」；核对下面的分类和字段后可点「确认生成」，沿用 AI 建议的类别也可以。</div>':""}<div class="classify-form" style="grid-template-columns:${classifyGridTemplate(ai.category)}"><select id="confirm-category-${item.id}">${categoryOptions().map(c=>`<option value="${c}" ${c===ai.category?"selected":""}>${categoryName(c)}</option>`).join("")}</select><input id="confirm-title-${item.id}" value="${esc(ai.payload&&ai.payload.title||item.raw)}" maxlength="200"><input type="date" id="confirm-due-${item.id}" class="${classifyFieldVisible(ai.category,"due")?"":"hidden"}" value="${due}"><select id="confirm-priority-${item.id}" class="${classifyFieldVisible(ai.category,"priority")?"":"hidden"}">${["P0","P1","P2","P3"].map(p=>`<option ${p===(ai.payload&&ai.payload.priority||"P2")?"selected":""}>${p}</option>`).join("")}</select><input id="confirm-start-${item.id}" class="${classifyFieldVisible(ai.category,"start")?"":"hidden"}" value="${ai.payload&&ai.payload.start||""}" maxlength="5" placeholder="开始 HH:mm"><input type="hidden" id="confirm-eventtype-${item.id}" value="${ai.payload&&ai.payload.eventType?ai.payload.eventType:"meeting"}"><button class="btn primary" data-action="confirm-inbox" data-id="${item.id}">确认生成</button></div></div><div class="actions"><button class="btn ghost sm" data-action="reclassify-inbox" data-id="${item.id}" title="用当前整理规则重新分类，并刷新建议标题与字段">重新整理</button></div></div>`;
  }).join(""):"<div class=\"empty\">没有待确认的条目。在上面输入框写一句话点「收录」，它会自动整理好放在这里。</div>";
}
// 日程卡片在「按日时间线」和「待定时间」两处复用，只有时间描述与补充提示不同。
function eventCard(e,options){
  const pending=!!(options&&options.pending);
  const when=pending?"时间待定，还没排进具体哪天":(e.date||"");
  return `<div class="list-item" data-event-id="${e.id}"><div class="grow"><div class="task-title">${e.start?esc(e.start)+" ":""}${esc(e.title)} <span class="pill ${e.type==="deep_block"?"blue":e.type==="meeting"?"green":""}">${eventTypeName(e.type)}</span></div><div class="task-meta">${when}${e.end?" · "+(e.start||"")+" - "+e.end:""}</div><div class="edit-panel me-form" id="event-edit-${e.id}"><div class="me-head">编辑日程</div><label class="me-field"><span class="me-label">标题</span><input id="event-edit-title-${e.id}" value="${esc(e.title)}" maxlength="200"></label><div class="me-row"><label class="me-field"><span class="me-label">类型</span><select id="event-edit-type-${e.id}">${["meeting","deep_block","other"].map(t=>`<option value="${t}" ${t===e.type?"selected":""}>${eventTypeName(t)}</option>`).join("")}</select></label><label class="me-field"><span class="me-label">日期</span><input type="date" id="event-edit-date-${e.id}" value="${e.date||""}"></label></div><div class="me-row"><label class="me-field"><span class="me-label">开始</span><input type="time" id="event-edit-start-${e.id}" value="${e.start||""}"></label><label class="me-field"><span class="me-label">结束</span><input type="time" id="event-edit-end-${e.id}" value="${e.end||""}"></label></div>${pending?'<p class="me-note">填上日期并保存，它就会从待定区排进那一天。</p>':''}<div class="me-actions"><button class="btn sm" data-action="cancel-event" data-id="${e.id}">取消</button><button class="btn sm primary" data-action="save-event" data-id="${e.id}">保存</button></div></div></div><div class="actions"><button class="btn sm" data-action="edit-event" data-id="${e.id}">编辑</button>${moveMenu("event",e.id)}<button class="btn sm danger" data-action="delete-event" data-id="${e.id}">删除</button></div></div>`;
}
// dayLabel 随按日视图一起移除了 —— 它只服务于「X月X日的日程」那个按日标题。
// 周视图里日期由周头（周区间）和每行的 .wr-date 表达，不再需要「今天 / 昨天 / 明天」这种单日措辞。
// ===== 周视图（2026-09-13，见 docs/日程周视图设计.md）=====
// 「本周」只由后端 dashboard.date 定义（见 todayString）。首次刷新时 dashboard 还没回来，
// 先用本机日期兜底算一次 —— 前端与后端跑在同一台机器、同一时区，这个兜底只作用于首帧；
// 拿到 dashboard.date 后再校一次，一旦发现「本周」变了（首次进入、跨零点、改了时区配置）
// 就把整组复位成默认三条。
// 返回「是否发生了复位」：调用方据此判断上面那次 events 请求的区间有没有过期。
function syncVisibleWeeks(){
  const thisWeek=weekStartOf(todayString());
  if(state.weekBase===thisWeek&&state.visibleWeeks.length)return false;
  state.weekBase=thisWeek;
  // 默认三条，顺序 = 页面从上到下：**下一周在上、本周居中、上一周在下**（用户 2026-09-13 定死）。
  const next=addDays(thisWeek,7),prev=addDays(thisWeek,-7);
  state.visibleWeeks=[next,thisWeek,prev];
  // **手风琴**：永远只有一个周展开（用户 2026-09-13 第二次明确）。所以这里记的是
  // 「哪个周展开」这一个值，而不是「哪些周折叠」——后者能表达出「全展开」「全折叠」这些
  // 不该存在的状态。默认展开本周，上下两条收起成一行。
  state.expandedWeek=thisWeek;
  return true;
}
// 取数区间 = 当前可见的所有周覆盖到的那一段。YYYY-MM-DD 是定长零填充格式，
// 字符串比较等价于日期比较，所以直接比大小、不转 Date。
function visibleRange(){
  const weeks=state.visibleWeeks.length?state.visibleWeeks:[weekStartOf(todayString())];
  let min=weeks[0],max=weeks[0];
  weeks.forEach(week=>{if(week<min)min=week;if(week>max)max=week});
  return {from:min,to:addDays(max,6)};
}
// 日程页的取数参数：检索态只带 q（命中跨全部日期），否则带整段区间。
// 一次取回所有可见周，而不是按天循环调 7 次接口。
function eventParams(){
  if(state.eventQuery)return {q:state.eventQuery};
  const range=visibleRange();
  return {from:range.from,to:range.to};
}
// 「某一天是不是正显示在页面上」—— 新建日程后据此决定要不要提示「它不在当前显示的周里」。
function isDateVisible(day){return state.visibleWeeks.indexOf(weekStartOf(day))>=0}
// 把某一周放进可见列表（驾驶舱下钻 / 全局搜索定位用）。列表始终保持**时间倒序**
// （未来在上、过去在下），所以是插入后整体重排，而不是追加到末尾。返回是否新增。
function ensureWeek(weekStart){
  // 目标周设成当前展开的那一条：下钻 / 全局搜索落点就是「要去看那一周里的那一条」，
  // 展开了才看得到卡片（手风琴下别的周都是收起的一条横杠）。
  state.expandedWeek=weekStart;
  if(state.visibleWeeks.indexOf(weekStart)>=0)return false;
  state.visibleWeeks.push(weekStart);
  state.visibleWeeks.sort((a,b)=>a<b?1:a>b?-1:0);
  return true;
}
function scrollToWeek(weekStart){
  const block=document.querySelector('.week-block[data-week="'+weekStart+'"]');
  if(block&&block.scrollIntoView)block.scrollIntoView({block:"start",behavior:"smooth"});
}
// ↑ / ↓ 的语义是「往列表外侧再要一周」：列表是时间倒序的，所以「更远的未来」= 最小日期减 7 天，
// 「更远的过去」= 最大日期加 7 天。
// 三种情形必须都给出反馈，否则就是「按了箭头什么都没发生」：
//   ① 目标周还不在列表里 → 追加 + 重取数 + 展开它；
//   ② 目标周已经在列表里（用户之前翻到过）→ **不重复追加**，直接展开它并滚过去；
//   ③ 无论如何都要滚过去（新周条可能在视口之外）。
async function growWeeks(direction){
  const weeks=state.visibleWeeks;
  if(!weeks.length)return;
  const oldest=weeks.reduce((min,week)=>(week<min?week:min),weeks[0]);
  const newest=weeks.reduce((max,week)=>(week>max?week:max),weeks[0]);
  const next=direction>0?addDays(newest,7):addDays(oldest,-7);
  state.expandedWeek=next;
  if(weeks.indexOf(next)<0){
    weeks.push(next);
    weeks.sort((a,b)=>a<b?1:a>b?-1:0);
    await loadEvents();
  }else{
    renderSchedule();
  }
  scrollToWeek(next);
}
// 某一天的日程，**没有开始时间的沉到最前**（用户确认的形态）。
// 后端排序把 start_time IS NULL 放在最后，所以这里必须自己分桶，不能直接用返回顺序。
function eventsOfDay(day){
  const list=state.events.filter(event=>event.date===day);
  const timed=list.filter(event=>event.start).sort((a,b)=>a.start<b.start?-1:a.start>b.start?1:0);
  return list.filter(event=>!event.start).concat(timed);
}
// 周视图里的卡片：等宽、只承载「时间 + 标题 + 类型色」，点一下展开该行下方的编辑面板。
// **不放操作按钮** —— 卡片只有约 130px 宽，塞进去必然折行（宽度测算见设计文档 §3）。
// data-event-id 是下钻 / 全局搜索定位的锚点，不能省。
function eventWeekCard(event){
  const unscheduled=!event.start;
  const kind=event.type==="meeting"?"meeting":event.type==="deep_block"?"deep":"other";
  const time=unscheduled?"未排时段":esc(event.start)+(event.end?" – "+esc(event.end):"");
  // 「每周」重复的期数标记。卡片只有 136px 宽，多一个字都会把标题挤掉，
  // 所以标记上只写「每周」两个字，完整解释交给 title 提示。
  const repeat=event.repeatTotal>1?`<span class="ec-repeat" title="每周重复 · 共 ${event.repeatTotal} 期（每一期都能单独改）">每周</span>`:"";
  const repeatTip=event.repeatTotal>1?`（每周重复 · 共 ${event.repeatTotal} 期）`:"";
  return `<button class="event-card ec-${unscheduled?"unscheduled":kind}" type="button" data-action="edit-event" data-id="${event.id}" data-event-id="${event.id}" title="${esc(event.title)}（${eventTypeName(event.type)}）${repeatTip}—— 点一下展开编辑"><span class="ec-time">${time}${repeat}</span><span class="ec-title">${esc(event.title)}</span></button>`;
}
// 编辑面板与操作按钮渲染在**所属行下方**（跨整行宽），而不是塞进卡片里：
// 卡片只有百余像素，`.me-row` 默认两列（类型 + 日期）在那里会被压成半截。
// 结构沿用三处视图统一的 .edit-panel.me-form，样式作用域仍是 .edit-panel（见 style.css 的约定）。
function eventEditPanel(event){
  const typeOptions=["meeting","deep_block","other"].map(type=>`<option value="${type}" ${type===event.type?"selected":""}>${eventTypeName(type)}</option>`).join("");
  // 重复日程的说明：用户最需要知道的是「改这里只影响这一期」——
  // 这正是当初把「每周 X」物化成 4 条**独立**记录（而不是共享一条）的原因。
  const repeatNote=event.repeatTotal>1?`<p class="me-note">这是「每周」重复日程（共 ${event.repeatTotal} 期）。改这里<strong>只影响这一期</strong>，其余几期不受影响。</p>`:"";
  return `<div class="edit-panel me-form" id="event-edit-${event.id}"><div class="me-head">编辑日程<span class="me-hint">${esc(event.date||"时间待定")}</span></div><label class="me-field"><span class="me-label">标题</span><input id="event-edit-title-${event.id}" value="${esc(event.title)}" maxlength="200"></label><div class="me-row"><label class="me-field"><span class="me-label">类型</span><select id="event-edit-type-${event.id}">${typeOptions}</select></label><label class="me-field"><span class="me-label">日期</span><input type="date" id="event-edit-date-${event.id}" value="${event.date||""}"></label></div><div class="me-row"><label class="me-field"><span class="me-label">开始</span><input type="time" id="event-edit-start-${event.id}" value="${event.start||""}"></label><label class="me-field"><span class="me-label">结束</span><input type="time" id="event-edit-end-${event.id}" value="${event.end||""}"></label></div>${repeatNote}<p class="me-note">清空日期会把它放回「待定时间」区。</p><div class="me-actions"><button class="btn sm" data-action="cancel-event" data-id="${event.id}">取消</button>${moveMenu("event",event.id)}<button class="btn sm danger" data-action="delete-event" data-id="${event.id}">删除</button><button class="btn sm primary" data-action="save-event" data-id="${event.id}">保存</button></div></div>`;
}
// 一个周条 = 可点折叠的周头 + 严格七行。
// 折叠靠 CSS（.week-block.collapsed .week-body 隐藏），DOM 不动 —— 这样 toggle-week 只切一个 class
// 就能完成折叠，不必重绘，也不会把滚动位置弹回顶部。
// 折叠态由 .wh-hint 写出「N 项日程 · 点这一行展开」：只剩一个光秃秃的日期区间，用户会以为那周是空的。
function weekBlock(start,thisWeek){
  // 手风琴：只有 state.expandedWeek 那一条是展开的，其余一律收起（用户 2026-09-13 第二次明确）。
  const expanded=start===state.expandedWeek;
  const collapsed=!expanded;
  const isCurrent=start===thisWeek;
  const today=todayString();
  let total=0;
  const rows=[0,1,2,3,4,5,6].map(offset=>{
    const day=addDays(start,offset);
    const list=eventsOfDay(day);
    total+=list.length;
    const isToday=day===today;
    const cards=list.length?list.map(eventWeekCard).join(""):'<span class="wr-empty">这一天还没有日程</span>';
    const edits=list.length?'<div class="wr-edits">'+list.map(eventEditPanel).join("")+"</div>":"";
    return `<div class="week-row${isToday?" is-today":""}" data-day="${day}"><div class="wr-day"><span class="wr-weekday">${weekdayName(day)}</span><span class="wr-date">${monthDayText(day)}${isToday?" · 今天":""}</span></div><div class="wr-cards">${cards}</div></div>${edits}`;
  }).join("");
  // 「回到本周 / ↑ / ↓」挂在**当前展开的那一条**上，不是只挂本周（用户 2026-09-13 第二次修正）。
  // 切到别的周时这三个按钮要跟着过去，所以 expand-week 走的是「搬 DOM 节点」而不是整块重绘 ——
  // 只切 class 的话按钮会留在原来那一周，看起来像「按钮不见了」。
  // ⚠️ 这三个按钮不能放进 .wh-toggle 里面：那是个 <button>，而 HTML 不允许按钮嵌套按钮 ——
  // 嵌了浏览器会静默把内层拆到外面，折叠热区与按钮的位置就全乱了。
  // title 必须写清目标是哪一周：单看 ↑ / ↓ 分不清方向（列表是「未来在上」）。
  const actions=expanded?`<span class="wh-actions"><button class="btn sm wh-current" type="button" data-action="goto-current-week" title="回到本周：把上下的周条复位成默认的「下一周 / 本周 / 上一周」">回到本周</button><button class="wh-nav" type="button" data-action="add-future-week" title="再往上看一周：${weekRangeText(addDays(start,7))}">↑</button><button class="wh-nav" type="button" data-action="add-past-week" title="再往下看一周：${weekRangeText(addDays(start,-7))}">↓</button></span>`:"";
  return `<section class="week-block${collapsed?" collapsed":""}${isCurrent?" is-current":""}" data-week="${start}"><div class="week-head"><button class="wh-toggle" type="button" data-action="expand-week" data-week="${start}" aria-expanded="${collapsed?"false":"true"}"><span class="wh-caret" aria-hidden="true">▾</span><span class="wh-range">${weekRangeText(start)}</span><span class="wh-tag">${weekRelativeName(start,thisWeek)}</span><span class="wh-meta">第 ${isoWeekNumber(start)} 周 · ${total} 项</span><span class="wh-hint">${total} 项日程 · 点这一行展开</span></button>${actions}</div><div class="week-body">${rows}</div></section>`;
}
function renderWeeks(){
  const thisWeek=state.weekBase||weekStartOf(todayString());
  return state.visibleWeeks.map(start=>weekBlock(start,thisWeek)).join("");
}
function renderSchedule(){
  const query=state.eventQuery||"";
  const searching=!!query;
  // 按日视图那套（日期选择器 / 前一天 / 后一天 / 回到今天）已整体移除，
  // 换成周视图。理由与取舍见 docs/日程周视图设计.md。
  // 检索状态必须写在页面上并能一键退出，理由同任务页：静默过滤会让用户以为日程少了。
  const note=$("eventSearchNote");
  if(note){
    note.classList.toggle("hidden",!searching);
    // 用模板字符串而不是字符串拼接：拼接写法里 data-action 的引号要转义，静态检查就扫不到这个
    // action（「每个 data-action 都有处理分支」那条断言会漏掉它），写法也和文件里其它模板不一致。
    // （注释里也别原样写出那个属性，检查脚本会把它当成一个真的 action 扫走。）
    note.innerHTML=searching?`<span>正在全部日期中搜索「${esc(query)}」（含「待定时间」），共 ${state.events.length} 条。</span><button class="btn sm ghost" data-action="clear-event-search">返回周视图</button>`:"";
  }
  // 放在这个位置而不是函数末尾：检索分支下面有一个提前 return，放末尾会漏掉它，
  // 结果是「搜索展开着，放大镜却还是放大镜图标」。
  applySearchRow("event");
  // 周视图与检索结果互斥：检索命中跨**全部日期**，套进七行网格会散成十几个只有一条卡的周条，
  // 反而看不出结果。周视图那一整块收起来，检索结果用平铺列表。
  const weeks=$("eventWeeks"),results=$("eventResults");
  if(weeks)weeks.classList.toggle("hidden",searching);
  if(results)results.classList.toggle("hidden",!searching);
  if(searching){
    // 空结果要把「怎么回去」写出来，否则用户会以为日程页坏了（此刻周视图被藏起来了）。
    if(results)results.innerHTML=state.events.length?state.events.map(e=>eventCard(e)).join(""):`<div class="empty">没有匹配「${esc(query)}」的日程。换个关键词，或点上面的「返回周视图」。</div>`;
    return;
  }
  if(weeks)weeks.innerHTML=renderWeeks();
}
function renderPendingEvents(){
  const list=state.pendingEvents;
  const badge=$("pendingCount");if(badge)badge.textContent=list.length?"（"+list.length+" 项待补全）":"";
  $("pendingEventList").innerHTML=list.length?list.map(e=>eventCard(e,{pending:true})).join(""):"<div class=\"empty\">没有待定日程。整理时抽不出日期的日程会先落到这里。</div>";
}
function memoTime(value){
  const m=String(value||"").match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}:\d{2})/);
  if(!m)return String(value||"").slice(0,16);
  // 「今天」的年份只认后端下发的 dashboard.date，前端不另算一套（否则时区差会串天）。
  const thisYear=String((state.dashboard&&state.dashboard.date)||"").slice(0,4);
  const day=(thisYear&&m[1]===thisYear)?Number(m[2])+"月"+Number(m[3])+"日":m[1]+"-"+m[2]+"-"+m[3];
  return day+" "+m[4];
}
function renderMemos(){
  const view=$("view-memos");view.className="view memo-theme-"+state.memoGrp+(state.tab==="memos"?" active":"");
  document.querySelectorAll(".memo-choice").forEach(b=>b.classList.toggle("active",b.dataset.grp===state.memoGrp));
  const list=state.memos;
  $("memoList").innerHTML=list.length?list.map(m=>{
    const tags=(m.tags||[]).map(t=>`<span class="pill">${esc(t)}</span>`).join("");
    // 备忘的 title 是后端从 content 裁出来的（MemoService.shortTitle，裁 24 字不拼省略号），
    // 所以 24 字以内的备忘标题与正文**完全相等**，两层都渲染就是同一句话显示两遍。
    // 只有用户手动改过标题（两者不同）时才需要正文这一层。
    // 另：正文现在是可空的（只有标题的「速查信息」也能存），所以正文为空时必须整层不渲染——
    // 否则会留下一个空的 .memo-body 占位，卡片底部凭空多出一段空白。
    const contentText=String(m.content||"").trim();
    const showBody=!!contentText&&contentText!==String(m.title||"").trim();
    return `<div class="memo-card cat-${m.grp} ${m.pinned?"pinned":""} ${m.status==="archived"?"archived":""}" data-memo-id="${m.id}"><div class="memo-kicker"><span class="memo-dot"></span><span>${m.grp==="work"?"工作":"生活"}</span><span class="memo-sep">·</span><span>${esc(memoTime(m.updatedAt||m.createdAt))}</span>${m.status==="archived"?'<span class="memo-arch">已归档</span>':""}</div><div class="memo-title">${m.pinned?'<span class="memo-pin">置顶</span>':""}${esc(m.title)}</div>${showBody?`<div class="memo-body">${esc(m.content)}</div>`:""}${m.url?`<div class="memo-link"><a href="${esc(m.url)}" target="_blank" rel="noopener">${esc(m.url)}</a></div>`:""}<div class="memo-foot"><div class="memo-tags">${tags}</div><div class="actions memo-acts"><button class="btn sm ghost" data-action="pin-memo" data-id="${m.id}">${m.pinned?"取消置顶":"置顶"}</button><button class="btn sm ghost" data-action="archive-memo" data-id="${m.id}">${m.status==="archived"?"恢复":"归档"}</button><button class="btn sm ghost" data-action="edit-memo" data-id="${m.id}">编辑</button>${moveMenu("memo",m.id,true)}<button class="btn sm ghost danger" data-action="delete-memo" data-id="${m.id}">删除</button></div></div><div class="edit-panel me-form memo-edit" id="memo-edit-${m.id}"><div class="me-head">编辑备忘<span class="me-hint">标题和正文至少写一项</span></div><label class="me-field"><span class="me-label">标题</span><input id="memo-edit-title-${m.id}" value="${esc(m.title)}" maxlength="200" placeholder="标题"></label><label class="me-field"><span class="me-label">正文</span><textarea id="memo-edit-content-${m.id}" maxlength="4000" rows="3">${esc(m.content)}</textarea></label><div class="me-row me-narrow"><label class="me-field"><span class="me-label">标签</span><input id="memo-edit-tags-${m.id}" value="${esc((m.tags||[]).join(","))}" placeholder="逗号分隔，留空自动归类" maxlength="500"></label><label class="me-field"><span class="me-label">空间</span><select id="memo-edit-grp-${m.id}"><option value="work" ${m.grp==="work"?"selected":""}>工作</option><option value="life" ${m.grp==="life"?"selected":""}>生活</option></select></label></div><div class="me-actions"><button class="btn sm" data-action="cancel-memo" data-id="${m.id}">取消</button><button class="btn sm primary" data-action="save-memo" data-id="${m.id}">保存</button></div></div></div>`;
  }).join(""):"<div class=\"empty\">当前空间没有备忘。随手记一条链接、班车或家里要买的东西。</div>";
  // 备忘的检索状态也要写在页面上：2026-09-12 起输入框会被收起，没有这条就变成
  // 「列表是筛过的但看不出为什么」—— 这一条是这次改版**必须补**的，以前输入框常驻所以不需要。
  const note=$("memoSearchNote");
  if(note){
    const q=state.memoQuery||"";
    note.classList.toggle("hidden",!q);
    note.innerHTML=q?`<span>正在当前空间里搜索「${esc(q)}」，共 ${list.length} 条${state.memoArchived?"（含已归档）":""}。</span><button class="btn sm ghost" data-action="clear-memo-search">清除搜索</button>`:"";
  }
  applySearchRow("memo");
}
function setMemoGrp(grp){state.memoGrp=grp;refreshAll().catch(error=>toast(error.message))}
function timelineTypeName(type){return {inbox:"收录",task:"事件",praise:"完成",memo:"备忘",pomo:"番茄",plan:"计划"}[type]||"记录"}
const TL_COLORS={inbox:"#378ADD",task:"#534AB7",praise:"#639922",memo:"#BA7517","memo:work":"#6C9D81","memo:life":"#D98267",pomo:"#0F6E56",plan:"#888780"};
function dayString(date){return date.getFullYear()+"-"+String(date.getMonth()+1).padStart(2,"0")+"-"+String(date.getDate()).padStart(2,"0")}
function localDay(offset){const d=new Date();d.setDate(d.getDate()+offset);return dayString(d)}
// 不能用 new Date("2026-09-11")：它按 UTC 解析，东八区会退回前一天。
function parseDay(value){const parts=String(value||"").split("-");if(parts.length!==3)return null;const y=parseInt(parts[0],10),m=parseInt(parts[1],10),d=parseInt(parts[2],10);if(!y||!m||!d)return null;const date=new Date(y,m-1,d);return isNaN(date.getTime())?null:date}
// 「今天」以后端下发的 dashboard.date 为准（它按用户配置时区算），本地时钟只做兜底。
function todayString(){return (state.dashboard&&state.dashboard.date)||localDay(0)}
// ===== 周视图的日期工具（2026-09-13，见 docs/日程周视图设计.md）=====
// 一律基于 parseDay 的**本地时区**解析，绝不写 new Date("YYYY-MM-DD")——那是按 UTC 解析的，
// 东八区会退回前一天，「日期差一天」的经典 bug 就是这么来的。
function addDays(day,offset){const base=parseDay(day)||parseDay(todayString())||new Date();base.setDate(base.getDate()+offset);return dayString(base)}
// 周一为一周之首（用户要的就是「周一到周日」七行）。getDay() 里周日是 0，
// 先 (getDay()+6)%7 归成「周一 = 0」，再往前退这么多天。
function weekStartOf(day){const base=parseDay(day)||parseDay(todayString())||new Date();const offset=(base.getDay()+6)%7;return dayString(new Date(base.getFullYear(),base.getMonth(),base.getDate()-offset))}
// ISO 8601 周号：先把这一周挪到它的**周四**，再和「本年第一个周四」比周数。
// 必须先算周四——ISO 规定「包含周四的那一周才属于新的一年」，拿 1 月 1 日直接算会在跨年那几周错位，
// 而错位是静默的（只是显示的第 N 周不对，不会报错）。
function isoWeekNumber(day){
  const base=parseDay(day);if(!base)return "";
  const thursday=new Date(base.getFullYear(),base.getMonth(),base.getDate()-((base.getDay()+6)%7)+3);
  const jan4=new Date(thursday.getFullYear(),0,4);
  const firstThursday=new Date(jan4.getFullYear(),jan4.getMonth(),jan4.getDate()-((jan4.getDay()+6)%7)+3);
  return 1+Math.round((thursday-firstThursday)/604800000);
}
function weekdayName(day){const base=parseDay(day);if(!base)return "";return ["周一","周二","周三","周四","周五","周六","周日"][(base.getDay()+6)%7]}
function monthDayText(day){const base=parseDay(day);return base?String(base.getMonth()+1).padStart(2,"0")+"."+String(base.getDate()).padStart(2,"0"):day}
// 「2026年9月7日 – 9月13日」：结束时只写月日，省掉重复的年份（同一周不可能跨年）。
function weekRangeText(start){
  const a=parseDay(start),b=parseDay(addDays(start,6));
  if(!a||!b)return start+" – "+addDays(start,6);
  return a.getFullYear()+"年"+(a.getMonth()+1)+"月"+a.getDate()+"日 – "+(b.getMonth()+1)+"月"+b.getDate()+"日";
}
// 相对「本周」的说法：默认三条是「下一周 / 本周 / 上一周」，用户继续往外翻的用「N 周后 / N 周前」——
// 只给一个日期区间的话，用户得自己数才知道离现在多远。
function weekRelativeName(start,thisWeek){
  const a=parseDay(start),b=parseDay(thisWeek);
  if(!a||!b)return "";
  const diff=Math.round((a-b)/604800000);
  if(diff===0)return "本周";
  if(diff===1)return "下一周";
  if(diff===-1)return "上一周";
  return diff>0?diff+" 周后":(-diff)+" 周前";
}
// 时间线一次最多取 500 条（后端 MAX_LIMIT，产品设计里就是这么定的：「上限 500 条，超出截断」）。
// 但计数文案不能只说「（500 条记录）」——那读起来像「一共有 500 条」，实际是「只加载了 500 条」。
// 达到上限时必须补一句，否则用户会以为更早的流水不存在。
function activityCountText(items){
  if(!items.length)return "";
  const capped=items.length>=500;
  return "（"+items.length+" 条记录"+(capped?"，已达 500 条上限，更早的没有加载":"")+"）";
}
function renderTimeline(){
  const items=state.activity;
  $("logCount").textContent=activityCountText(items);
  if(!items.length){$("timelineList").innerHTML="<article class=\"card\"><div class=\"empty\">还没有流水记录，去收录一条或完成一个任务吧</div></article>";return}
  const groups={},order=[];
  items.forEach(item=>{const day=item.day||"未知日期";if(!groups[day]){groups[day]=[];order.push(day)}groups[day].push(item)});
  // 「今天」取后端下发的 dashboard.date（todayString），不要用 new Date() 另算一套：
  // 用户配置的时区和浏览器时区可以不一致，两套算法会让同一天在不同页面上贴不同的标签。
  // localDay(-1) 只用来算「昨天」这一个标签，其余一律以 todayString() 为基准。
  const today=todayString(),yesterday=localDay(-1);
  $("timelineList").innerHTML=order.map(day=>{
    const label=day===today?"今天":day===yesterday?"昨天":day;
    // 日期头是折叠开关（用户要求「点日期折叠这一天的内容」）。
    // 折叠状态存进 state.timelineCollapsed，而不是只挂在 DOM 的 class 上：
    // renderTimeline 每次 refreshAll 都会重绘（做完任何操作都会刷新），状态只在 DOM 里的话
    // 下一次刷新就被抹掉，用户看到的是「点了又自己弹开」。这也正是本项目反复出现的
    // 「状态没地方存 → 功能看起来失灵」那一类问题。
    // 条数（N 条）必须常显：折叠后如果只剩一个光秃秃的日期，用户会以为这天的记录丢了。
    const collapsed=state.timelineCollapsed[day]===true;
    const rows=groups[day].map((item,index)=>{
      const side=index%2===0?"left":"right";
      const colorKey=item.type==="memo"&&item.category?"memo:"+item.category:item.type;
      const color=TL_COLORS[colorKey]||TL_COLORS[item.type]||"#888780";
      const tname=item.type==="memo"?(item.category==="work"?"备忘·工作":item.category==="life"?"备忘·生活":"备忘"):timelineTypeName(item.type);
      const time=(item.createdAt||"").slice(11,16);
      return `<div class="tl-item ${side}${item.type==="praise"?" praise":""}" data-log-id="${item.id}"><span class="tl-node" style="color:${color};background:${color}"></span><div class="tl-card"><button class="tl-del" data-action="delete-activity" data-id="${item.id}" title="永久删除这条记录（无法恢复）" aria-label="永久删除这条记录，无法恢复"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"></path><path d="M10 11v6M14 11v6"></path><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"></path></svg></button><span class="tl-time">${time} · ${tname}</span><span class="tl-text">${esc(item.content)}</span></div></div>`;
    }).join("");
    return `<div class="tl-group${collapsed?" collapsed":""}" data-day="${esc(day)}"><button type="button" class="tl-day" data-action="toggle-day" data-day="${esc(day)}" aria-expanded="${collapsed?"false":"true"}" title="点击${collapsed?"展开":"折叠"}这一天的记录"><span class="tl-label">${esc(label)}</span><span class="tl-count">${groups[day].length} 条</span><span class="tl-caret" aria-hidden="true">▾</span></button><div class="tl-rail">${rows}</div></div>`;
  }).join("");
}
// 知识库索引的文案由「状态」决定，而不是「index 是不是空」。
// 旧实现拿 index 为空当作「正在读取」，于是 index.html 里写死的「正在读取 Vault 索引…」
// 每次强刷都会先闪一遍，而且只要后端慢就一直停在那句话上 —— 用户看不到任何进展与出路。
// 现在四态显式化：idle 还没请求 / loading 请求中 / ready 有数据 / error 失败（给重试入口）。
function kbIndexHtml(){
  const k=state.knowledge,index=k.index;
  let base="";
  if(index){
    base=index.configured
      ?("已索引 Vault「"+esc(index.vaultName)+"」· "+index.fileCount+" 篇笔记 · "+index.chunkCount+" 个片段"+(index.llmEnabled?"":" · 未启用 AI，用摘录模式"))
      :"未配置 Vault —— 请点右上角 ⚙ → Obsidian 填写库路径";
  }else if(k.indexState==="loading"){
    base="正在读取 Vault 索引…";
  }
  // 读失败时必须给出可点的出路：停在「正在读取…」上等于让用户干等一个永远不会来的结果。
  if(k.indexState==="error"){
    const err='<span style="color:#a32d2d">索引读取失败：'+esc(k.indexError||"")+'</span> <button class="btn sm" data-action="retry-knowledge-index">重试</button>';
    return base?base+" ｜ "+err:err;
  }
  return base;
}
function renderKnowledge(){
  const notes=state.knowledge.notes;
  $("kbNoteCount").textContent=notes.length?"（"+notes.length+" 条）":"";
  $("kbIndexInfo").innerHTML=kbIndexHtml();
  $("kbNoteList").innerHTML=notes.length?notes.map(n=>{
    const syncPill=n.syncStatus==="synced"?'<span class="pill green">已同步</span>':n.syncStatus==="failed"?'<span class="pill red">同步失败</span>':'<span class="pill amber">待同步</span>';
    const tags=(n.tags||[]).map(t=>`<span class="pill">${esc(t)}</span>`).join("");
    return `<div class="list-item"><div class="grow"><div class="task-title">${esc(n.title)} ${syncPill}</div><div class="task-meta">${(n.createdAt||"").slice(0,16)}${n.vaultPath?" · "+esc(n.vaultPath):""}</div><div class="task-meta">${esc(n.content||"")}</div>${tags?`<div class="memo-tags">${tags}</div>`:""}</div><div class="actions">${n.obsidianUrl?`<a class="btn sm" href="${esc(n.obsidianUrl)}">原文 ↗</a>`:""}${n.syncStatus!=="synced"?`<button class="btn sm" data-action="sync-knowledge" data-id="${n.id}">重试同步</button>`:""}<button class="btn sm danger" data-action="delete-knowledge" data-id="${n.id}">删除</button></div></div>`;
  }).join(""):'<div class="empty">还没有知识笔记。在「整理确认」把有价值的条目归类为「知识」，就会写入 Vault。</div>';
  paintKbChat();
}
function renderTrash(){
  const items=state.trash;
  $("trashBadge").textContent=items.length||"";$("trashBadge").style.display=items.length?"inline-block":"none";
  $("trashCount").textContent=items.length?"（共 "+items.length+" 条）":"";
  // 剩余天数由后端按保留期算（30 天是后端规则，前端不自己推），这里只负责措辞：
  // daysLeft 为 0 时不能说「还剩 0 天可恢复」——那读起来像是还能用，实际是今天之内。
  $("trashList").innerHTML=items.length?items.map(item=>`<div class="list-item" data-trash-type="${item.type}" data-trash-id="${item.id}"><div class="grow"><div class="task-title">${esc(item.title==null||item.title===""?"（无标题）":item.title)} <span class="pill">${trashTypeName(item.type)}</span></div><div class="task-meta">${item.detail?esc(item.detail)+" · ":""}${(item.deletedAt||"").slice(0,16)} 删除 · ${item.daysLeft>0?"还剩 "+item.daysLeft+" 天可恢复":"今天之后不能再恢复"}</div></div><div class="actions"><button class="btn sm primary" data-action="restore-trash" data-type="${item.type}" data-id="${item.id}">恢复</button></div></div>`).join(""):"<div class=\"empty\">回收站是空的。删除的任务、日程、备忘、知识记录和收集箱条目会先到这里，30 天内可以恢复。</div>";
}
function paintKbChat(){
  const box=$("kbChatBox");
  if(!state.knowledge.chat.length){
    // 一片空白什么都不说，用户只会以为「坏了」。索引没就绪时给出各自的下一步，
    // 尤其失败态要指向那个「重试」按钮 —— 否则用户唯一的出路是刷新整页。
    const hint=state.knowledge.indexState==="loading"?"正在读取 Vault 索引，稍后就能提问。"
      :state.knowledge.indexState==="error"?"索引读取失败：先点标题旁的「重试」，成功后即可提问。"
      :"进入本页会自动读取 Vault 索引。";
    box.innerHTML='<div class="msg ai"><div class="bubble"><span class="muted">'+hint+'</span></div></div>';
    return;
  }
  box.innerHTML=state.knowledge.chat.map(m=>`<div class="msg ${m.role}"><div class="bubble">${m.html}</div></div>`).join("");
  box.scrollTop=box.scrollHeight;
}
async function loadKnowledgeIndex(){
  if(state.knowledge.indexState==="loading")return;   // 键盘/连点都别并发打同一个慢接口
  state.knowledge.indexState="loading";state.knowledge.indexError="";
  renderKnowledge();renderVaultStatus();
  try{
    const index=await WorkbenchApi.knowledgeIndex();
    state.knowledge.index=index;state.knowledge.indexState="ready";
    if(!state.knowledge.chat.length){
      state.knowledge.chat.push({role:"ai",html:index.configured?("你好，我已索引你的 Obsidian 知识库「"+esc(index.vaultName)+"」（"+index.fileCount+" 篇笔记 / "+index.chunkCount+" 个片段）。问我任何问题，答案会附出处。"):"知识库问答需要先配置 Obsidian Vault：点右上角 ⚙ → Obsidian，填写你的库路径后回来刷新。"});
    }
    renderKnowledge();
    renderVaultStatus();
  }catch(error){
    state.knowledge.indexState="error";state.knowledge.indexError=error.message;
    renderKnowledge();renderVaultStatus();
  }
}
async function askKnowledge(){
  const input=$("kbChatInput"),question=input.value.trim();
  if(!question||state.knowledge.asking)return;
  if(!state.knowledge.index){
    toast(state.knowledge.indexState==="loading"?"索引还在加载，请稍候":"索引还没读取成功，请点标题旁的「重试」",3200);
    return;
  }
  if(state.knowledge.index&&!state.knowledge.index.configured){toast("请先配置 Obsidian Vault（⚙ → Obsidian）",3200);return}
  input.value="";state.knowledge.asking=true;$("kbChatSend").disabled=true;
  state.knowledge.chat.push({role:"user",html:esc(question)},{role:"ai",html:'<span class="muted">正在检索笔记…</span>'});
  paintKbChat();
  try{
    const result=await WorkbenchApi.knowledgeAsk(question);
    state.knowledge.chat.pop();
    let html=esc(result.answer);
    if(result.citations&&result.citations.length){
      html+=result.citations.map((c,i)=>`<span class="cite"><span class="src">${i===0?"出处":"相关"}：${esc(c.file)} § ${esc(c.heading)}</span><br>${esc(c.snippet)}<br><a href="${esc(c.obsidianUrl)}">obsidian:// 打开原文 ↗</a></span>`).join("");
    }
    if(result.capturedToInbox){html+='<span class="cite"><span class="src">已收录</span><br>到「收录」页确认后，这个问题会作为新知识写入 Vault。</span>';state.knowledge.indexState="idle";refreshAll().catch(()=>{})}
    if(result.answered&&!result.llmUsed)html+='<div class="kb-mode muted">摘录模式（配置 AI 后由模型组织答案）</div>';
    state.knowledge.chat.push({role:"ai",html});
  }catch(error){
    state.knowledge.chat.pop();
    state.knowledge.chat.push({role:"ai",html:"检索失败："+esc(error.message)});
  }finally{
    state.knowledge.asking=false;$("kbChatSend").disabled=false;paintKbChat();
  }
}
async function saveTheme(){
  const theme=$("themeInput").value.trim();if(!theme){toast("请输入今日主题");return}
  await WorkbenchApi.saveTheme(theme);await refreshAll();toast("今日主题已设定");
}
function pomoModeMinutes(mode){return mode==="work"?state.pomo.config.work:mode==="short"?state.pomo.config.shortBreak:state.pomo.config.longBreak}
const POMO_RING=414.7,POMO_MLABEL={work:"FOCUS",short:"BREAK",long:"REST"},POMO_BLABEL={work:"专注",short:"短休",long:"长休"};
function renderPomoClock(){
  const pomo=state.pomo,total=pomoModeMinutes(pomo.mode)*60,remain=Math.max(0,pomo.remain);
  const m=String(Math.floor(remain/60)).padStart(2,"0"),s=String(remain%60).padStart(2,"0");
  $("pomoTime").textContent=m+":"+s;$("pomoChipTime").textContent=m+":"+s;
  $("pomoModeLabel").textContent=POMO_MLABEL[pomo.mode];
  $("ringFg").style.strokeDashoffset=(POMO_RING*(1-(total?remain/total:0))).toFixed(1);
  $("pomoChip").classList.toggle("running",pomo.running);
  $("pomoBtn").textContent=pomo.running?"暂停":(remain<total?"继续":(pomo.mode==="work"?"开始专注":"开始休息"));
}
function renderPomoToday(){$("pomoTodayText").textContent="今日已完成 "+state.pomo.today.count+" 个番茄 · 专注 "+state.pomo.today.minutes+" 分钟"}
function renderPomoTasks(){
  const select=$("pomoTask");const current=select.value;
  const open=state.tasks.filter(task=>task.status==="todo"||task.status==="doing");
  select.innerHTML="<option value=\"\">自由专注（不关联任务）</option>"+open.map(task=>`<option value="${task.id}">${esc(task.title)}</option>`).join("");
  if(current&&open.some(task=>String(task.id)===current))select.value=current;
}
function switchPomoMode(mode){
  const pomo=state.pomo;clearInterval(pomo.timer);pomo.timer=null;pomo.running=false;
  pomo.mode=mode;pomo.remain=pomoModeMinutes(mode)*60;
  document.querySelectorAll(".pomo-panel .modes button").forEach(button=>{
    const m=button.dataset.m;button.classList.toggle("active",m===mode);
    button.textContent=POMO_BLABEL[m]+" "+pomoModeMinutes(m);
  });
  renderPomoClock();
}
function pomoTick(){
  const pomo=state.pomo;pomo.remain=Math.max(0,Math.round((pomo.endAt-Date.now())/1000));renderPomoClock();
  if(pomo.remain<=0){clearInterval(pomo.timer);pomo.timer=null;pomo.running=false;renderPomoClock();pomoFinish()}
}
function pomoBeep(){
  try{
    const Ctx=window.AudioContext||window.webkitAudioContext;if(!Ctx)return;
    const ctx=state.pomo.audio||(state.pomo.audio=new Ctx());
    [0,0.28,0.56].forEach((offset,index)=>{
      const osc=ctx.createOscillator(),gain=ctx.createGain();osc.connect(gain);gain.connect(ctx.destination);
      osc.frequency.value=index===2?1047:784;gain.gain.setValueAtTime(0.001,ctx.currentTime+offset);
      gain.gain.exponentialRampToValueAtTime(0.22,ctx.currentTime+offset+0.04);
      gain.gain.exponentialRampToValueAtTime(0.001,ctx.currentTime+offset+0.24);
      osc.start(ctx.currentTime+offset);osc.stop(ctx.currentTime+offset+0.26);
    });
  }catch(error){/* 无音频环境时静默 */}
}
function flashTitle(text){
  const original=state.pomo.baseTitle;let count=0;clearInterval(state.pomo.flashTimer);
  state.pomo.flashTimer=setInterval(()=>{document.title=count%2===0?text:original;count++;if(count>11){clearInterval(state.pomo.flashTimer);document.title=original}},700);
}
function pomoFinish(){
  const pomo=state.pomo;pomoBeep();
  if(pomo.mode==="work"){
    flashTitle("🍅 番茄完成！");
    const minutes=pomo.config.work;const taskId=$("pomoTask").value?Number($("pomoTask").value):null;
    WorkbenchApi.completePomo({taskId:taskId,minutes:minutes}).then(today=>{
      pomo.today=today;renderPomoToday();toast("番茄完成，已记录 "+minutes+" 分钟");
      const next=today.count%4===0?"long":"short";switchPomoMode(next);
      if(pomo.config.auto)pomoStart();
      refreshAll().catch(()=>{});
    }).catch(error=>{toast(error.message);switchPomoMode("short");if(pomo.config.auto)pomoStart()});
  }else{
    flashTitle("☕ 休息结束");toast("休息结束，准备下一个番茄");switchPomoMode("work");
    if(pomo.config.auto)pomoStart();
  }
}
function pomoStart(){
  const pomo=state.pomo;
  if(pomo.running){clearInterval(pomo.timer);pomo.timer=null;pomo.running=false;renderPomoClock();return}
  pomo.running=true;pomo.endAt=Date.now()+pomo.remain*1000;
  pomo.timer=setInterval(pomoTick,500);renderPomoClock();
}
async function initPomo(){
  try{
    const results=await Promise.all([WorkbenchApi.pomoConfig(),WorkbenchApi.pomoToday()]);
    state.pomo.config=results[0];state.pomo.today=results[1];
    $("pomoCfgWork").value=state.pomo.config.work;$("pomoCfgShort").value=state.pomo.config.shortBreak;$("pomoCfgLong").value=state.pomo.config.longBreak;$("pomoCfgAuto").checked=state.pomo.config.auto;
    switchPomoMode("work");renderPomoToday();
  }catch(error){toast(error.message)}
}
async function savePomoConfig(){
  const data={work:Number($("pomoCfgWork").value)||25,shortBreak:Number($("pomoCfgShort").value)||5,longBreak:Number($("pomoCfgLong").value)||15,auto:$("pomoCfgAuto").checked};
  state.pomo.config=await WorkbenchApi.savePomoConfig(data);
  $("pomoCfgWork").value=state.pomo.config.work;$("pomoCfgShort").value=state.pomo.config.shortBreak;$("pomoCfgLong").value=state.pomo.config.longBreak;
  if(!state.pomo.running)switchPomoMode(state.pomo.mode);
  toast("番茄配置已保存");
}
function setIfIdle(id,value){const el=$(id);if(document.activeElement!==el)el.value=value==null?"":value}
function fmtSize(bytes){return bytes>=1048576?(bytes/1048576).toFixed(1)+" MB":Math.max(1,Math.round(bytes/1024))+" KB"}
function renderSettings(){
  const s=state.settings;if(!s)return;
  setIfIdle("setUsername",s.username);
  setIfIdle("setAiBaseUrl",s.aiBaseUrl);setIfIdle("setAiModel",s.aiModel);setIfIdle("setVaultPath",s.obsidianVaultPath);
  $("setAiEnabled").checked=!!s.aiEnabled;
  $("aiKeyHint").textContent=s.aiApiKeySet?("已保存 API Key："+s.aiApiKeyMasked+"（重新输入可替换，留空保持不变）"):"未配置 API Key 时始终使用本地规则整理。";
  setIfIdle("setPomoWork",state.pomo.config.work);setIfIdle("setPomoShort",state.pomo.config.shortBreak);setIfIdle("setPomoLong",state.pomo.config.longBreak);
  $("backupList").innerHTML=state.backups.length?state.backups.map(b=>`<div class="list-item"><div class="grow"><div class="task-title">${esc(b.name)}</div><div class="task-meta">${fmtSize(b.sizeBytes)} · ${(b.modifiedAt||"").replace("T"," ").slice(0,16)}</div></div><button class="btn sm" data-action="restore-backup" data-name="${esc(b.name)}">恢复此备份</button></div>`).join(""):"<div class=\"empty\">还没有备份。点「立即备份」创建第一份。</div>";
  renderVaultStatus();
}
function renderVaultStatus(){
  // 直接在设置页显示 Vault 是否真的生效，避免「填了路径却不知道有没有生效」
  const el=$("vaultStatus");if(!el)return;
  const path=state.settings&&state.settings.obsidianVaultPath;
  const index=state.knowledge.index;
  if(!path){el.innerHTML='<span style="color:#a96c1d">未配置 Vault 路径：知识分类与知识库问答不可用。</span>';return}
  if(!index){
    if(state.knowledge.indexState==="loading"){el.textContent="正在检测 Vault 状态…";return}
    if(state.knowledge.indexState==="error"){
      el.innerHTML='<span style="color:#a32d2d">Vault 状态读取失败：'+esc(state.knowledge.indexError||"")+'</span><br>到「知识库」页点标题旁的「重试」可重新读取。';
      return;
    }
    el.textContent="尚未读取 Vault 状态。";return
  }
  if(index.configured){
    el.innerHTML='<span style="color:#39745b">已生效：Vault「'+esc(index.vaultName)+'」· '+index.fileCount+' 篇笔记 / '+index.chunkCount+' 个片段</span>';
  }else{
    el.innerHTML='<span style="color:#a32d2d">路径已保存但应用读不到：'+esc(path)
      +'<br>若工作台运行在 Docker 容器内，Windows 盘符路径不可见，需挂载后填写容器内路径。</span>';
  }
}
async function saveUsername(){const username=$("setUsername").value.trim();if(!username){toast("请输入用户名");return}await WorkbenchApi.saveProfile({username:username});await refreshAll();toast("用户名已更新")}
async function savePassword(){
  const current=$("setCurrentPassword").value,next=$("setNewPassword").value;
  if(!current||!next){toast("请输入当前密码和新密码");return}
  await WorkbenchApi.saveProfile({currentPassword:current,newPassword:next});
  $("setCurrentPassword").value="";$("setNewPassword").value="";toast("密码已修改");
}
async function savePomoSetting(){
  const data={work:Number($("setPomoWork").value)||25,shortBreak:Number($("setPomoShort").value)||5,longBreak:Number($("setPomoLong").value)||15,auto:state.pomo.config.auto};
  state.pomo.config=await WorkbenchApi.savePomoConfig(data);
  $("pomoCfgWork").value=state.pomo.config.work;$("pomoCfgShort").value=state.pomo.config.shortBreak;$("pomoCfgLong").value=state.pomo.config.longBreak;
  if(!state.pomo.running)switchPomoMode(state.pomo.mode);
  toast("番茄时长已保存");
}
async function saveAiSetting(){
  await WorkbenchApi.saveAi({enabled:$("setAiEnabled").checked,baseUrl:$("setAiBaseUrl").value.trim(),model:$("setAiModel").value.trim(),apiKey:$("setAiKey").value.trim()});
  $("setAiKey").value="";await refreshAll();toast("AI 配置已保存");
}
async function saveVaultSetting(){
  try{
    await WorkbenchApi.saveObsidian($("setVaultPath").value.trim());
  }catch(error){toast(error.message);return}
  // 配置变更后让知识库索引重新读取 Vault 状态：否则 state.knowledge.index.configured
  // 仍是首次访问时的旧值（false），发消息会误报「请先配置 Obsidian Vault」。
  // 换库时旧快照整个作废（它描述的是另一个 Vault），连 index 一起清掉，别让用户看到上一个库的笔记数。
  // 换库时旧快照整个作废（它描述的是另一个 Vault），连 index 一起清掉，别让用户看到上一个库的笔记数。
  state.knowledge.index=null;state.knowledge.indexState="idle";
  await refreshAll();
  if(state.tab==="knowledge"||state.tab==="settings") await loadKnowledgeIndex();
  toast("Vault 路径已保存");
}
async function doBackupNow(){const result=await WorkbenchApi.backupNow();await refreshAll();toast("备份完成："+result.file+"（"+fmtSize(result.sizeBytes)+"）")}
async function doClearDemo(){
  if(!confirm("确定清空所有示例数据？此操作只删除标记为示例的记录。"))return;
  if(!confirm("再次确认：示例任务、日程、备忘、收录记录与相关流水将被删除，无法撤销。"))return;
  const result=await WorkbenchApi.clearDemo();await refreshAll();toast("已清空 "+result.total+" 条示例数据");
}
async function doShutdown(){
  if(!confirm("确定关闭工作台？关闭后需重新双击启动器才能使用。"))return;
  try{await WorkbenchApi.shutdown()}catch(error){/* 连接中断属正常 */}
  setTimeout(()=>{document.body.innerHTML="<div style=\"padding:60px 24px;font-family:sans-serif;text-align:center;color:#33415c\"><h2>工作台已关闭</h2><p>可以关闭此页面。重新使用请双击「启动工作台」。</p></div>"},900);
}
async function createMemo(){
  const content=$("memoContent").value.trim();
  const title=$("memoTitle").value.trim();
  // 背书 MemoCreateRequest：正文不再是必填，title / content **至少写一项**即可。
  // 两者都空才会被后端拒（400/1002「备忘的标题和正文不能同时为空，至少写一项」），
  // 前端先拦一次只是为了不白跑一趟接口，措辞与后端保持一致。
  if(!content&&!title){toast("标题和正文至少写一项");$("memoTitle").focus();return}
  // title / content 只能传 null（不能传空串）：空串会让后端把「留空」当成「显式写了空标题」，
  // 于是显式标题的回退逻辑（取正文前 24 字）被跳过，备忘会存成没有标题的一条。
  await WorkbenchApi.createMemo({title:title||null,content:content||null,tags:$("memoTags").value.trim()||null,grp:$("memoGrp").value});
  $("memoTitle").value="";$("memoContent").value="";$("memoTags").value="";await refreshAll();toast("备忘已保存");
}
// 日期默认预填**今天**，不跟着正在查看的那一周走。
// 「日期为空」仍然只剩一种含义：还没定下哪一天 → 落到「待定时间」区。
// 用户翻到别的周还新建时，createEvent 会明确提示「它不在当前显示的周里」，
// 而不是让这条日程悄悄落在看不见的地方。
function defaultEventDate(){return todayString()}
// 切周 / 追加周只重取日程，不重拉整页（任务、备忘、流水等与周无关，没必要跟着刷新）。
// 检索态必须把 q 带上、并让出区间：否则翻一次周，检索结果会被这一段的日程顶掉，
// 看上去像「搜索被重置了」。
async function loadEvents(){state.events=(await WorkbenchApi.events(eventParams()))||[];renderSchedule()}
async function createEvent(){
  const title=$("eventTitle").value.trim();if(!title){toast("请输入日程标题");return}
  const date=$("eventDate").value||null;
  const weeks=Number(($("eventRepeat")||{}).value)||1;
  const result=await WorkbenchApi.createEvent({title:title,type:$("eventType").value,date:date,start:$("eventStart").value||null,end:$("eventEnd").value||null,repeatWeeks:weeks});
  $("eventTitle").value="";$("eventStart").value="";$("eventEnd").value="";$("eventDate").value=defaultEventDate();
  // 重复选项每次用完都要回到「不重复」：留着上一次的「连续 4 周」，下一条日程会被静默地
  // 也生成 4 期，而用户以为自己只是加了一件事。
  if($("eventRepeat"))$("eventRepeat").value="1";
  await refreshAll();
  if(result.warnings&&result.warnings.length)toast("已加入，但注意："+result.warnings.join("；"),5200);
  else if(!date)toast("已加入「待定时间」；在卡片里补上日期即可排进那一天",5200);
  else if(!isDateVisible(date))toast("已加入 "+date+"；它不在当前显示的周里，可用周头右上角的 ↑ / ↓ 翻到那一周",6200);
  else if(weeks>1)toast("已按「每周」加入 "+weeks+" 期（到 "+addDays(date,7*(weeks-1))+" 为止）；每一期都能单独改",6600);
  else toast("日程已加入");
}
// 「收录」= 收录 + 整理合并成一步：先把原文写进收录箱，紧接着跑一遍整理。
// 用户不用再单独点一次「开始整理」，页面直接显示分类建议供核对。
async function createInbox(){
  const raw=$("inboxInput").value.trim();if(!raw){toast("请输入内容");return}
  const created=await WorkbenchApi.createInbox(raw);
  $("inboxInput").value="";$("inboxInput").focus();
  await WorkbenchApi.classifyInbox();
  await refreshAll();
  // 用接口返回的 id 精确找回刚收录的这一条，而不是猜「列表第一条就是刚存的」：
  // 自动整理失败时它会落到「待整理」，必须说清楚，不能笼统报一句「已收录」让用户以为一切正常。
  const mine=state.classify.filter(item=>String(item.id)===String(created.id))[0];
  if(mine&&mine.ai)toast("已收录并整理为「"+categoryName(mine.ai.category)+"」，核对后点「确认生成」",4600);
  else if(state.inbox.some(item=>String(item.id)===String(created.id)))toast("已收录，但自动整理没成功，可在下面「待整理」里点「重新整理」",5600);
  else toast("已收录");
}
async function confirmHighConfidence(){
  const targets=batchConfirmableCount();
  if(!targets){toast("没有可批量确认的条目：剩余条目的置信度都低于 70%，请逐条核对后点「确认生成」",5200);return}
  if(!confirm("将按整理建议直接生成 "+targets+" 条（置信度 ≥ 70%）。\n低于 70% 的条目会保留下来，需要你逐条确认。\n\n继续？"))return;
  const results=await WorkbenchApi.confirmHighConfidence();await refreshAll();
  const created=(results||[]).length;
  // created 与 targets 理论上一致（筛选条件前后端对齐）。万一不一致，只报事实、不猜原因，
  // 免得把「后端跳过了未配置 Vault 的知识类」这类猜测当成结论塞给用户。
  if(!created)toast("这次没有条目被生成，可能已被处理过；请查看下方列表",5200);
  else if(created<targets)toast("已生成 "+created+" 条，另有 "+(targets-created)+" 条未生成；请查看列表并逐条确认",6400);
  else toast("已生成 "+created+" 条，剩余 "+state.classify.length+" 条需要逐条确认",5200);
}
async function confirmInbox(id){
  const category=$("confirm-category-"+id).value;
  // eventType 沿用 AI 抽取结果（deep_block / other 不该被硬编码成会议），字段缺失时兜底 meeting
  const eventType=($("confirm-eventtype-"+id)||{}).value||"meeting";
  await WorkbenchApi.confirmInbox(id,{category:category,title:$("confirm-title-"+id).value.trim(),due:$("confirm-due-"+id).value||null,priority:$("confirm-priority-"+id).value,start:$("confirm-start-"+id).value.trim()||null,end:null,eventType:eventType});
  await refreshAll();toast("已确认并生成「"+categoryName(category)+"」");
}
async function reclassifyInbox(id){
  await WorkbenchApi.reclassifyInbox(id);
  await refreshAll();toast("已重新整理，请再核对分类和字段");
}
async function createTask(){
  const title=$("taskTitle").value.trim();if(!title){toast("请输入任务标题");return}
  await WorkbenchApi.createTask({title:title,priority:$("taskPriority").value,due:$("taskDue").value||null,deep:$("taskDeep").checked,blocking:$("taskBlocking").checked,note:$("taskNote").value.trim()||null});
  $("taskTitle").value="";$("taskNote").value="";$("taskDeep").checked=false;$("taskBlocking").checked=false;await refreshAll();toast("任务已创建");
}
async function handleAction(button){
  const id=button.dataset.id,action=button.dataset.action;
  if(action==="toggle-day"){
    // 时间线按日折叠。这里**只切状态和当前分组的 class，不整页重绘**：
    // 走 refreshAll 会把滚动位置弹回顶部，长列表里折叠一下内容就"跳走"了。
    const day=button.dataset.day,group=button.closest(".tl-group");
    const collapsed=!state.timelineCollapsed[day];
    if(collapsed)state.timelineCollapsed[day]=true;else delete state.timelineCollapsed[day];
    if(group)group.classList.toggle("collapsed",collapsed);
    button.setAttribute("aria-expanded",collapsed?"false":"true");
    button.title="点击"+(collapsed?"展开":"折叠")+"这一天的记录";
    return
  }
  // ---- 「移至」与驾驶舱下钻 ----
  if(action==="move"){
    // 和「时间线折叠」同样的理由：只切状态与 class，不整页重绘 ——
    // refreshAll 会把滚动位置弹回顶部，用户刚点开菜单页面就跳走了。
    const key=button.dataset.from+"-"+id;
    const next=state.moveOpen===key?null:key;
    state.moveOpen=next;
    document.querySelectorAll(".move-menu.open").forEach(menu=>menu.classList.remove("open"));
    const menu=$("move-menu-"+key);
    if(next&&menu)menu.classList.add("open");
    button.setAttribute("aria-expanded",next?"true":"false");
    return
  }
  if(action==="move-to"){
    // 移动 = 目标菜单新建 + 源记录进回收站，后端在一个事务里完成。
    // toast 必须说清三件事：去哪儿了、原记录还能找回来（回收站 30 天）、哪些字段没跟过去。
    const result=await WorkbenchApi.transfer({fromType:button.dataset.from,id:Number(id),toType:button.dataset.to});
    state.moveOpen=null;
    await refreshAll();
    let message="「"+result.title+"」已移到「"+result.toLabel+"」；原记录在回收站里，30 天内可恢复。";
    if(result.warnings&&result.warnings.length)message+=" 注意："+result.warnings.join(" ");
    toast(message,9000);
    return
  }
  if(action==="drill"){drillMetric(button.dataset.drill);return}
  if(action==="drill-task"){drillTask(id);return}
  if(action==="drill-event"){await drillEvent(id,button.dataset.date);return}
  // 一次清干净（筛选 + 检索）。只清一半的话用户点了按钮条数却没变回去，看起来就是这个按钮坏了。
  // 检索是服务端行为，所以清掉之后必须重新取数；只有筛选时本地重绘即可。
  if(action==="clear-task-filter"){
    const hadQuery=!!state.taskQuery;
    state.taskFilter="all";clearTaskQuery();
    if(hadQuery){await refreshAll();return}
    renderTasks();return
  }
  if(action==="clear-event-search"){clearEventQuery();await refreshAll();return}
  // 「点周头 = 把这一周设为当前展开的那一周」（手风琴：任何时刻只有一个展开）。
  // 全程只动 DOM、不重绘：切换旧/新两条的 collapsed class，再把这一个 `.wh-actions` 节点
  // 从旧的那条搬到新的那条。重绘会把滚动位置弹回顶部，长列表里点一下就「跳走」。
  if(action==="expand-week"){
    const week=button.dataset.week;
    if(state.expandedWeek===week)return;   // 点已经展开的那条：幂等，不做任何事
    const previous=state.expandedWeek;
    state.expandedWeek=week;
    [previous,week].forEach(target=>{
      const block=document.querySelector('.week-block[data-week="'+target+'"]');
      if(!block)return;
      const collapsed=target!==week;
      block.classList.toggle("collapsed",collapsed);
      const toggle=block.querySelector(".wh-toggle");
      if(toggle)toggle.setAttribute("aria-expanded",collapsed?"false":"true");
    });
    // 按钮**跟着展开的那条走**，而且必须是搬 DOM 节点：只切 class 的话按钮会留在原来那一周，
    // 用户看到的是「切过去之后按钮不见了」（新需求：按钮不再固定挂在本周）。
    const actions=document.querySelector(".wh-actions");
    const head=document.querySelector('.week-block[data-week="'+week+'"] .week-head');
    if(actions&&head)head.appendChild(actions);
    return
  }
  // 「回到本周」= 复位成默认三条（下一周 / 本周 / 上一周）并把本周收回来。
  // 用户从很远的周回到现在也只有这一个出口，所以不能只滚动、不重置。
  if(action==="goto-current-week"){
    // 复位成与首次进入完全一致的形态。这里刻意**复用 syncVisibleWeeks 的同一套规则**
    // （把 weekBase 清空逼它重新初始化），而不是在这儿再抄一遍「哪几条要折叠」——
    // 抄一遍就意味着以后改默认形态要改两处，漏一处就会出现两种不同的「回到本周」。
    state.weekBase="";
    syncVisibleWeeks();
    await loadEvents();
    scrollToWeek(state.weekBase);
    return
  }
  if(action==="add-future-week"){await growWeeks(1);return}
  if(action==="add-past-week"){await growWeeks(-1);return}
  // 备忘的「清除搜索」只清关键词：备忘页没有驾驶舱下钻进来的筛选，没有第二样东西要清。
  if(action==="clear-memo-search"){clearMemoQuery();await refreshAll();return}
  if(action==="edit"){$("edit-"+id).classList.toggle("open");return}
  if(action==="cancel"){$("edit-"+id).classList.remove("open");return}
  if(action==="save"){await WorkbenchApi.updateTask(id,{title:$("edit-title-"+id).value.trim(),priority:$("edit-priority-"+id).value,due:$("edit-due-"+id).value||"",note:$("edit-note-"+id).value.trim(),deep:$("edit-deep-"+id).checked,blocking:$("edit-blocking-"+id).checked});await refreshAll();toast("任务已更新");return}
  if(action==="status"){await WorkbenchApi.changeTaskStatus(id,button.dataset.status);await refreshAll();toast("任务已改为「"+statusName(button.dataset.status)+"」");return}
  if(action==="postpone"){const task=await WorkbenchApi.postponeTask(id);await refreshAll();toast("已顺延到 "+formatDate(task.due)+"，第 "+task.postponed+" 次");return}
  if(action==="confirm-inbox"){await confirmInbox(id);return}
  if(action==="reclassify-inbox"){await reclassifyInbox(id);return}
  if(action==="delete-inbox"){await WorkbenchApi.deleteInbox(id);await refreshAll();toast("已移入回收站，30 天内可恢复");return}
  if(action==="delete-event"){await WorkbenchApi.deleteEvent(id);await refreshAll();toast("已移入回收站，30 天内可恢复");return}
  if(action==="edit-event"){$("event-edit-"+id).classList.toggle("open");return}
  if(action==="cancel-event"){$("event-edit-"+id).classList.remove("open");return}
  if(action==="save-event"){
    const wasPending=state.pendingEvents.some(e=>String(e.id)===String(id));
    const date=$("event-edit-date-"+id).value||"";
    const result=await WorkbenchApi.updateEvent(id,{title:$("event-edit-title-"+id).value.trim(),type:$("event-edit-type-"+id).value,date:date,start:$("event-edit-start-"+id).value||"",end:$("event-edit-end-"+id).value||""});
    await refreshAll();
    if(result.warnings&&result.warnings.length)toast("已保存，但注意："+result.warnings.join("；"),5200);
    else if(!date)toast("已放回「待定时间」区，补上日期后就会排进那一天");
    else if(wasPending)toast("已排进 "+date);
    else toast("日程已更新");return
  }
  if(action==="pin-memo"){await WorkbenchApi.pinMemo(id);await refreshAll();toast("已更新置顶");return}
  if(action==="archive-memo"){await WorkbenchApi.archiveMemo(id);await refreshAll();toast("已更新归档状态");return}
  if(action==="edit-memo"){$("memo-edit-"+id).classList.toggle("open");return}
  if(action==="cancel-memo"){$("memo-edit-"+id).classList.remove("open");return}
  if(action==="save-memo"){await WorkbenchApi.updateMemo(id,{title:$("memo-edit-title-"+id).value.trim(),content:$("memo-edit-content-"+id).value.trim(),tags:$("memo-edit-tags-"+id).value.trim(),grp:$("memo-edit-grp-"+id).value});await refreshAll();toast("备忘已更新");return}
  if(action==="delete-memo"){await WorkbenchApi.deleteMemo(id);await refreshAll();toast("已移入回收站，30 天内可恢复");return}
  if(action==="retry-knowledge-index"){await loadKnowledgeIndex();return}
  if(action==="sync-knowledge"){await WorkbenchApi.syncKnowledge(id);state.knowledge.indexState="idle";await refreshAll();toast("已重新同步到 Vault");return}
  if(action==="delete-knowledge"){await WorkbenchApi.deleteKnowledge(id);await refreshAll();toast("已移入回收站，30 天内可恢复（Vault 里的 .md 文件一直保留）");return}
  if(action==="delete-activity"){
    // 全应用**唯一不可逆**的删除：按 R6 规则，时间线是操作流水不是正式实体，走物理删除、不进回收站。
    // 所以这里是唯一保留二次确认的删除动作——其余删除都只是移入回收站（30 天内可恢复），
    // 按设计规范 §8.2 放宽为免确认。触发点仍是卡片角上一个小图标，且触屏下常显，
    // 越是不起眼的入口越需要这层确认。
    if(!confirm("确认删除这条时间线记录？\n\n时间线是操作流水，按设计走物理删除、不经过回收站，删了无法恢复——这一点和任务、备忘、日程的删除不一样，那些都能在回收站里找回来。"))return;
    await WorkbenchApi.deleteActivity(id);await refreshAll();toast("这条时间线记录已永久删除（回收站里没有）");return
  }
  if(action==="restore-trash"){
    // 恢复就是「撤销」本身，不需要二次确认：误点最多是记录回到列表里，再删一次即可。
    const result=await WorkbenchApi.restoreTrash(button.dataset.type,id);
    await refreshAll();
    // 「收录」页只列「待确认 / 待整理」两种状态，而回收站里也可能躺着**已归档**的收录记录。
    // 那种记录恢复后不会出现在任何列表里 —— 若照搬「去「收录」看看」，用户找不着会以为恢复失败。
    const gone=!state.classify.some(item=>String(item.id)===String(id))&&!state.inbox.some(item=>String(item.id)===String(id));
    toast(result.type==="inbox"&&gone
      ?"已恢复「"+result.title+"」；这条收录记录此前已归档，不会出现在收录列表里"
      :"已恢复「"+result.title+"」，去「"+trashTypeName(result.type)+"」看看",5600);return
  }
  if(action==="restore-backup"){
    const name=button.dataset.name;
    if(!confirm("确定用备份「"+name+"」恢复？\n当前数据会先自动备份为 pre-restore 快照，然后整个库将回滚到该备份。"))return;
    const result=await WorkbenchApi.restoreBackup(name);await refreshAll();
    toast("已恢复「"+name+"」，当前数据快照保存在 "+result.preRestoreBackup,4200);return
  }
  if(action==="delete"){await WorkbenchApi.deleteTask(id);await refreshAll();toast("已移入回收站，30 天内可恢复");return}
  // 兜底：action 没匹配上，说明按钮和分发逻辑对不上了（改名只改了一边）。
  // 静默什么都不发生是最糟的结果——用户会反复点，也不知道该反馈什么现象。
  // 这条分支由检查脚本的「每个 data-action 都有处理分支」守着，正常不该触发。
  toast("这个按钮暂时没有对应的操作（action="+action+"），请反馈这个按钮");
}
async function initialize(){
  try{state.status=await WorkbenchApi.authStatus();if(!state.status.initialized||!state.status.loggedIn){location.replace("/setup.html");return}$("welcome").textContent=state.status.username;$("greeting").textContent=greeting()+"，"+state.status.username;$("app").classList.remove("hidden");applyPageBackground();await refreshAll();$("eventDate").value=defaultEventDate();await initPomo()}catch(error){toast(error.message)}
}
// 页面背景（2026-09-13）。**先探一次图，成功了再挂 class**，而不是直接挂：
// 图缺失时若已经挂上 class，body 会先去加载那张图、失败后才退回兜底色，
// 用户会看到一次闪白（白 → 浅蓝灰）。先探再挂就没有这一帧。
// 探测失败**保持静默**：背景是装饰，不该因为一张图弹一个错误提示出来。
function applyPageBackground(){
  const img=new Image();
  img.onload=()=>document.body.classList.add("has-page-bg");
  img.src="/assets/backgrounds/workbench.jpg";
}
// ===== 全局搜索（跨 收录 / 任务 / 日程 / 备忘 / 时间线 + 回收站）=====
// 设计见 docs/全文搜索功能设计.md。四条红线，每一条都对应一类真实故障：
// ① **绝不调 refreshAll**。它一次拉 11 个接口并全量重绘 11 个视图，挂上去就等于
//    每敲一个字符把整个工作台重画一遍（现有 #memoSearch 正是这个毛病）。搜索走独立状态。
// ② **高亮必须先转义再包标签**，且只走 highlightMatch() 这一个出口：
//    分两处自己拼 innerHTML，迟早有一处漏掉转义（搜 <img src=x onerror=...> 会真的执行）。
// ③ **乱序响应要丢弃**。自增 seq，回来时对不上就扔掉 —— 否则「先打的字后回来」会让
//    界面上的结果与输入框里的关键词不是一回事，而用户看不出哪里不对。
// ④ **回收站命中只能跳回收站页**。任务 / 日程 / 备忘页查的都是 deleted=0，
//    跳过去根本找不到那一条，用户看到的就是「点了没反应」。
const SEARCH_LABELS={task:"任务",event:"日程",memo:"备忘",inbox:"收录",timeline:"时间线"};
// 沿用项目既有的「单字方块」图标风格（设置页的 账/番/险/库、备忘空间的 工/生 都是这样）
const SEARCH_ICONS={task:"任",event:"程",memo:"忘",inbox:"收",timeline:"线"};
const SEARCH_DEBOUNCE_MS=200;
// 检索词按长度降序：前端按这个顺序占位，长词先占，短词不会把长词切碎
// （后端 matchedTerms 已经排好了，这里再排一次是为了不依赖调用顺序）。
function searchNeedles(terms){
  const seen={},list=[];
  (terms||[]).forEach(term=>{
    const value=String(term==null?"":term).trim().toLowerCase();
    if(!value||seen[value])return;
    seen[value]=true;list.push(value);
  });
  return list.sort((left,right)=>right.length-left.length);
}
/**
 * 把命中的词包成 <mark>。**这是全站唯一允许把检索词拼进 HTML 的地方。**
 *
 * <p>做法是「先按命中区间切片，再对每一片各自 esc」，而不是「先 esc 整段、再替换」。
 * 后者有个隐蔽的坑：文本里的 {@code <} 会被 esc 成 {@code &lt;}，
 * 此时搜 {@code lt} 就会命中实体中间的 {@code lt}，把 {@code &lt;} 切成
 * {@code &<mark>lt</mark>;} —— 页面上直接显示出「&lt;」这五个字符。</p>
 */
function highlightMatch(text,terms){
  const raw=String(text==null?"":text);
  const needles=searchNeedles(terms);
  if(!needles.length||!raw)return esc(raw);
  const hit=new Array(raw.length);
  const lower=raw.toLowerCase();
  needles.forEach(needle=>{
    let from=0;
    for(;;){
      const at=lower.indexOf(needle,from);
      if(at<0)return;
      for(let i=at;i<at+needle.length;i++)hit[i]=true;
      from=at+needle.length;
    }
  });
  let html="",start=0;
  for(let i=1;i<=raw.length;i++){
    if(i<raw.length&&hit[i]===hit[i-1])continue;
    const chunk=raw.slice(start,i);
    html+=hit[start]?'<mark class="gs-mark">'+esc(chunk)+'</mark>':esc(chunk);
    start=i;
  }
  return html;
}
// 类型 chips 用**前端过滤**而不是把所有类型都重新请求一遍：
// 结果本来就是全量下发的（每组已被后端 limit 截断），本地过滤不会漏掉任何一条，
// 而且 chips 上的数量始终是完整的，切起来零延迟。
function visibleSearchGroups(){
  const search=state.search;
  if(!search.typeFilter)return search.groups||[];
  return (search.groups||[]).filter(group=>group.type===search.typeFilter);
}
function searchFlatItems(){
  const items=[];
  visibleSearchGroups().forEach(group=>(group.items||[]).forEach(item=>items.push(item)));
  return items;
}
function positionSearchPanel(){
  const panel=$("searchPanel"),header=document.querySelector("header.top");
  if(!panel||!header||typeof header.getBoundingClientRect!=="function")return;
  // 面板顶在顶栏下方。顶栏高度会随窄屏折行变化，所以每次展开都重新量，不写死像素值。
  panel.style.top=(header.offsetTop+header.offsetHeight+8)+"px";
}
function openSearchPanel(){
  const search=state.search;
  positionSearchPanel();
  if(search.open){return}
  search.open=true;
  const panel=$("searchPanel");
  if(panel)panel.classList.remove("hidden");
  renderSearchPanel();
}
// 收起面板**不丢关键词**：用户点开别的地方再回来，输入框里的字和结果都还在。
function closeSearchPanel(){
  state.search.open=false;
  const panel=$("searchPanel");
  if(panel)panel.classList.add("hidden");
}
function focusGlobalSearch(){
  const input=$("globalSearchInput");
  openSearchPanel();
  if(input&&input.focus)input.focus();
}
function scheduleGlobalSearch(){
  const search=state.search;
  if(search.timer)clearTimeout(search.timer);
  search.timer=setTimeout(function(){
    search.timer=null;
    runGlobalSearch().catch(error=>toast(error.message));
  },SEARCH_DEBOUNCE_MS);
}
/** 把一次搜索响应落进 state。第二段（联想补位）也走这里，免得两处各写一份赋值漂掉。 */
function applySearchData(search,data,keepActiveIndex){
  const previous=search.activeIndex;
  search.groups=(data&&data.groups)||[];
  search.totalCount=(data&&data.totalCount)||0;
  search.elapsedMs=(data&&data.elapsedMs)||0;
  search.truncated=!!(data&&data.truncated);
  const count=searchFlatItems().length;
  search.activeIndex=count?(keepActiveIndex?Math.min(Math.max(previous,0),count-1):0):-1;
  search.semanticCount=countSemanticHits();
}
/** 统计这批结果里有多少条是「联想命中」——汇总行要如实说清它们是怎么来的。 */
function countSemanticHits(){
  let count=0;
  (state.search.groups||[]).forEach(group=>(group.items||[]).forEach(item=>{
    if(item.matchKind==="semantic")count++;
  }));
  return count;
}
async function runGlobalSearch(){
  const search=state.search;
  const query=String(search.q||"").trim();
  const seq=++search.seq;
  search.semanticState="off";search.expandedTerms=[];search.semanticCount=0;
  if(!query){
    search.groups=[];search.activeIndex=-1;search.loading=false;search.error="";search.totalCount=0;
    search.resultQuery="";
    renderSearchPanel();
    return;
  }
  search.loading=true;search.error="";
  renderSearchPanel();
  try{
    const data=await WorkbenchApi.search({q:query,scope:search.scope});
    if(seq!==search.seq)return;
    applySearchData(search,data,false);
    // 记下「这批结果对应的是哪个关键词」。回车打开时要拿它跟输入框比对 ——
    // 用户敲完一个词立刻按回车时，列表里可能还是上一次的结果，
    // 那时候打开就等于跳到一条他根本没在看的记录上（而且不会报错）。
    search.resultQuery=query;
  }catch(error){
    if(seq!==search.seq)return;
    search.groups=[];search.activeIndex=-1;search.error=error.message;
    search.resultQuery="";
  }
  if(seq!==search.seq)return;
  search.loading=false;
  renderSearchPanel();
  // 第一段到此为止：用户此刻已经能看、能选、能回车。
  // 第二段（语义联想）异步跑，绝不挡在上面 —— 调模型要一秒上下，
  // 同步等它就等于把「输入即搜」变成「输入等一秒」。
  expandSearchAsync(seq,query).catch(function(){});
}
/**
 * 第二段：拿联想词再补一次搜索。
 *
 * <p>失败**完全静默**：关键字结果已经在屏幕上了，为一次联想失败弹错，
 * 只会让用户以为搜索坏了。空词表同样静默 —— 那表示没配 AI 或这次没联想出东西，
 * 都不是用户需要知道的事。</p>
 */
async function expandSearchAsync(seq,query){
  const search=state.search;
  let expanded=null;
  try{
    expanded=await WorkbenchApi.expandSearch(query);
  }catch(error){
    if(seq!==search.seq)return;
    search.semanticState="failed";
    renderSearchPanel();
    return;
  }
  if(seq!==search.seq)return;      // 用户已经换了词，这批联想词作废
  const terms=(expanded&&expanded.terms)||[];
  if(!expanded||!expanded.enabled||!terms.length){
    search.semanticState="off";
    renderSearchPanel();
    return;
  }
  search.expandedTerms=terms;
  search.semanticState="pending";
  renderSearchPanel();
  let data=null;
  try{
    data=await WorkbenchApi.search({q:query,scope:search.scope,expand:terms.join(",")});
  }catch(error){
    if(seq!==search.seq)return;
    search.semanticState="failed";
    renderSearchPanel();
    return;
  }
  if(seq!==search.seq)return;
  // 补位时保留用户已经选中的那一条：联想结果是在原结果上追加，
  // 选中项被重置回第一条的话，用户刚移过去的位置就白移了。
  applySearchData(search,data,true);
  search.resultQuery=query;
  search.semanticState="ready";
  renderSearchPanel();
}
function searchMetaText(item){
  const meta=item.meta||{};
  const parts=[];
  if(item.deleted)parts.push("在回收站");
  if(item.entity==="task"){
    parts.push(meta.priority);
    parts.push(statusName(meta.status));
    parts.push(meta.due?"截止 "+meta.due:"无截止");
  }else if(item.entity==="event"){
    parts.push(eventTypeName(meta.type));
    parts.push(meta.date?((meta.start?meta.start+" ":"")+meta.date):"时间待定");
  }else if(item.entity==="memo"){
    parts.push(meta.grp==="life"?"生活":"工作");
    if(meta.archived)parts.push("已归档");
  }else if(item.entity==="inbox"){
    parts.push(inboxStatusName(meta.status));
  }else if(item.entity==="timeline"){
    parts.push(timelineTypeName(meta.logType));
  }
  // 匹配来源必须写出来：语义联想的结果如果不说「为什么它会出现」，
  // 用户会看到一堆没搜过的词，然后不再信任搜索结果。
  parts.push(item.matchKind==="exact"?"精确命中":item.matchKind==="semantic"?"联想命中":"分词命中");
  return parts.filter(part=>part!==undefined&&part!==null&&part!=="").join(" · ");
}
function searchRowHtml(item,index){
  const active=index===state.search.activeIndex;
  const label=SEARCH_LABELS[item.entity]||item.entity;
  const icon=SEARCH_ICONS[item.entity]||"记";
  const meta=searchMetaText(item);
  return '<div class="gs-row'+(active?" active":"")+(item.deleted?" is-deleted":"")+'" data-search-index="'+index+'">'
    +'<span class="gs-ico gs-ico-'+esc(item.entity)+'">'+esc(icon)+'</span>'
    +'<span class="gs-row-main"><span class="gs-row-title">'+highlightMatch(item.title,item.matchedTerms)+'</span>'
    +(meta?'<span class="gs-row-meta">'+esc(meta)+'</span>':"")
    +'</span><span class="gs-pill gs-pill-'+esc(item.entity)+'">'+esc(label)+'</span></div>';
}
function previewMetaRows(item){
  const meta=item.meta||{};
  const rows=[];
  if(item.entity==="task"){
    rows.push(["状态",statusName(meta.status)]);
    rows.push(["优先级",meta.priority]);
    rows.push(["截止日期",meta.due||"没有截止日期"]);
    if(meta.deep)rows.push(["深度工作","是"]);
    if(meta.blocking)rows.push(["阻塞他人","是"]);
  }else if(item.entity==="event"){
    rows.push(["类型",eventTypeName(meta.type)]);
    rows.push(["时间",meta.date?((meta.start?meta.start+" - "+(meta.end||"")+" ":"")+meta.date).trim():"待定（还没排进具体哪天）"]);
  }else if(item.entity==="memo"){
    rows.push(["空间",meta.grp==="life"?"生活":"工作"]);
    if(meta.tags)rows.push(["标签",meta.tags]);
    if(meta.archived)rows.push(["状态","已归档（列表里默认不显示）"]);
  }else if(item.entity==="inbox"){
    rows.push(["状态",inboxStatusName(meta.status)]);
    rows.push(["来源",meta.source==="wecom"?"微信":"Web"]);
  }else if(item.entity==="timeline"){
    rows.push(["类型",timelineTypeName(meta.logType)]);
  }
  if(item.deleted)rows.push(["位置","回收站（30 天内可恢复）"]);
  rows.push(["最近更新",String(item.updatedAt||"").slice(0,16)]);
  return rows;
}
function renderSearchPreview(item){
  const search=state.search;
  const panel=$("searchPanel");
  if(panel)panel.classList.toggle("preview-off",!search.previewOpen);
  const el=$("searchPreview");
  if(!el)return;
  if(!search.previewOpen){el.innerHTML="";return}
  if(!item){el.innerHTML='<div class="gs-empty">选中一条结果，这里会显示完整内容。</div>';return}
  const label=SEARCH_LABELS[item.entity]||item.entity;
  const fieldText=item.matchField==="title"?" · 位于标题":item.matchField==="tags"?" · 位于标签":item.matchField==="raw"?" · 位于收录原文":item.matchField==="log"?" · 位于流水内容":" · 位于正文";
  const kindText=item.matchKind==="exact"?"精确命中「"+esc(String(search.q||"").trim())+"」":item.matchKind==="semantic"?"联想命中（与输入的关键词意思相近）":"按分词命中";
  let html='<div class="gs-pv-head"><span class="gs-pill gs-pill-'+esc(item.entity)+'">'+esc(label)+'</span>';
  if(item.deleted)html+='<span class="gs-pill gs-pill-trash">在回收站</span>';
  html+='</div>';
  html+='<div class="gs-pv-title">'+highlightMatch(item.title,item.matchedTerms)+'</div>';
  html+='<div class="gs-pv-match">'+kindText+fieldText+'</div>';
  if(item.snippet)html+='<div class="gs-pv-snippet">'+highlightMatch(item.snippet,item.matchedTerms)+'</div>';
  html+='<div class="gs-pv-meta">'+previewMetaRows(item).map(row=>'<div class="gs-pv-row"><span>'+esc(row[0])+'</span><span>'+esc(row[1])+'</span></div>').join("")+'</div>';
  html+='<div class="gs-pv-acts"><button class="btn primary" data-search-open="1">'+(item.deleted?"到回收站恢复它":"打开并定位到这一条")+'</button></div>';
  el.innerHTML=html;
}
function renderSearchPanel(){
  const search=state.search;
  if(!search.open)return;
  const query=String(search.q||"").trim();
  const summary=$("searchSummary");
  if(summary){
    if(!query)summary.innerHTML='<span class="gs-muted">跨任务、日程、备忘、收录与时间线一起检索；选中后回车即可跳到那一条。</span>';
    else if(search.error)summary.innerHTML='<span class="gs-error">'+esc(search.error)+'</span>';
    else if(search.loading&&!search.groups.length)summary.innerHTML='<span class="gs-muted">正在搜索…</span>';
    else{
      // 联想是异步补位的，所以这一行要能表达三种时刻：正在联想 / 联想到了什么 / 联想不可用。
      // 不说的话，用户要么以为「结果就这么多了」，要么反过来怀疑多出来的那条是哪来的。
      let semantic="";
      if(search.semanticState==="pending")semantic='<span class="gs-warn">正在联想相近说法…</span>';
      else if(search.semanticCount>0)semantic='<span class="gs-warn">含 '+search.semanticCount+' 条与「'+esc(query)+'」语义相近</span>';
      else if(search.semanticState==="ready"&&search.expandedTerms.length)semantic='<span class="gs-muted">已联想：'+esc(search.expandedTerms.join(" / "))+'</span>';
      summary.innerHTML='<span class="gs-count">'+search.totalCount+' 条结果</span><span class="gs-muted">「'+esc(query)+'」· 用时 '+search.elapsedMs+'ms</span>'+semantic+(search.truncated?'<span class="gs-warn">每组只显示前几条</span>':"");
    }
  }
  const chips=$("searchChips");
  if(chips){
    const showChips=!!query&&!search.error&&search.groups.length>0;
    chips.classList.toggle("hidden",!showChips);
    if(showChips){
      let chipHtml='<button class="gs-chip'+(search.typeFilter?"":" active")+'" data-search-type="">全部 '+search.totalCount+'</button>';
      search.groups.forEach(group=>{
        chipHtml+='<button class="gs-chip'+(search.typeFilter===group.type?" active":"")+'" data-search-type="'+esc(group.type)+'">'+esc(group.label)+' '+group.total+'</button>';
      });
      chips.innerHTML=chipHtml;
    }else chips.innerHTML="";
  }
  const listEl=$("searchList");
  if(listEl){
    const groups=visibleSearchGroups();
    if(!query){
      // 空态不留白：直接教怎么用，比一个空面板有用
      listEl.innerHTML='<div class="gs-empty">试试搜「限流」找出相关的任务与备忘，或者搜「复盘」翻出上周的日程。<br>结果按 任务 → 日程 → 备忘 → 收录 → 时间线 → 回收站 分组，回车直接跳到那一条。</div>';
    }else if(search.error){
      listEl.innerHTML='<div class="gs-empty">搜索暂时不可用。<br><button class="btn sm" data-search-retry="1">重试</button></div>';
    }else if(!groups.length){
      listEl.innerHTML='<div class="gs-empty">'+(search.loading?"正在搜索…":"没有找到「"+esc(query)+"」。")+(search.loading?"":"<br><button class=\"btn sm\" data-search-collect=\"1\">把「"+esc(query)+"」收录到收集箱</button>")+'</div>';
    }else{
      let cursor=-1;
      let listHtml="";
      groups.forEach(group=>{
        listHtml+='<div class="gs-group"><div class="gs-group-head">'+esc(group.label)+' · '+group.total+'</div>';
        (group.items||[]).forEach(item=>{cursor++;listHtml+=searchRowHtml(item,cursor)});
        const rest=group.total-(group.items||[]).length;
        // 截断必须说出来，否则用户会以为「就这几条」
        if(rest>0)listHtml+='<div class="gs-more">该分组还有 '+rest+' 条未显示，输入更具体的关键词能看得更准。</div>';
        listHtml+='</div>';
      });
      listEl.innerHTML=listHtml;
    }
  }
  renderSearchPreview(searchFlatItems()[search.activeIndex]||null);
}
function setSearchActive(index){
  const search=state.search;
  search.activeIndex=index;
  const listEl=$("searchList");
  // 重绘会把列表滚动位置弹回顶部，长结果里按一下方向键就「跳走」了，所以先存后还原。
  const scrollTop=listEl?listEl.scrollTop:0;
  renderSearchPanel();
  if(listEl)listEl.scrollTop=scrollTop;
  const row=document.querySelector('#searchList .gs-row[data-search-index="'+index+'"]');
  if(row&&row.scrollIntoView)row.scrollIntoView({block:"nearest"});
}
function moveSearchSelection(delta){
  const items=searchFlatItems();
  if(!items.length)return;
  const search=state.search;
  let next=(search.activeIndex<0?0:search.activeIndex+delta);
  // 到底 / 到顶不回绕：回绕会让「一直按 ↓」在两个端点之间来回跳，反而找不着位置
  if(next<0)next=0;
  if(next>items.length-1)next=items.length-1;
  setSearchActive(next);
}
/**
 * 打开一条搜索结果并**定位到那一条**。
 *
 * <p>所有分支共用一个入口（按后端给的 {@code target.tab} 分发），不要再在渲染结果行时
 * 各自内联跳转逻辑 —— 那样最容易漏掉「先切日期再渲染」这类顺序要求。</p>
 */
async function openSearchResult(item){
  if(!item)return;
  const target=item.target||{};
  closeSearchPanel();
  if(target.tab==="trash"){
    // 已删数据的落点只有回收站。跳任务 / 日程 / 备忘页是找不到它的（那些页面查 deleted=0）。
    setTab("trash");
    highlightRow(document.querySelector('#trashList .list-item[data-trash-type="'+item.entity+'"][data-trash-id="'+item.id+'"]'));
    return;
  }
  if(target.tab==="tasks"){
    // 清筛选 + 清页内检索：目标那一条很可能正被它们挡在列表之外，挡着就不在 DOM 里。
    const reload=state.taskFilter!=="all"||!!state.taskQuery;
    state.taskFilter="all";clearTaskQuery();
    setTab("tasks");
    if(reload)await refreshAll();else renderTasks();
    const row=document.querySelector('#view-tasks .task-card[data-task-id="'+item.id+'"]');
    if(row)highlightRow(row);
    else toast("这条任务不在已加载的前 100 条里，可在任务页用搜索框按标题找它",5600);
    return;
  }
  if(target.tab==="schedule"){
    const reload=!!state.eventQuery;
    clearEventQuery();
    setTab("schedule");
    if(target.date){
      // 顺序不能反：日程页是周视图，先把目标那一周弄进可见列表并展开，再等渲染完，
      // 卡片才会出现在 DOM 里（ensureWeek 只改 state，不负责渲染）。
      const week=weekStartOf(target.date);
      const reloaded=ensureWeek(week)||reload;
      if(reloaded)await loadEvents();
      else renderSchedule();
      scrollToWeek(week);
    }else if(reload){
      // 待定日程（event_date 为 NULL）**不补今天**：它不属于任何一周，只在「待定时间」区露出。
      await loadEvents();
    }
    const row=document.querySelector('#eventWeeks .event-card[data-event-id="'+item.id+'"]')
      ||document.querySelector('#pendingEventList .list-item[data-event-id="'+item.id+'"]');
    if(row)highlightRow(row);
    else toast("这条日程不在当前显示的周里，可用周头右上角的 ↑ / ↓ 翻到它那一周",5600);
    return;
  }
  if(target.tab==="memos"){
    const grp=target.grp==="life"?"life":"work";
    const archived=!!(item.meta&&item.meta.archived);
    // 已归档的备忘在列表里默认不显示：不替用户把开关打开，高亮必然落空。
    const reload=state.memoGrp!==grp||!!state.memoQuery||(archived&&!state.memoArchived);
    state.memoGrp=grp;clearMemoQuery();
    if(archived)state.memoArchived=true;
    setTab("memos");
    if(reload)await refreshAll();else renderMemos();
    const box=$("memoShowArchived");if(box)box.checked=!!state.memoArchived;
    const row=document.querySelector('#memoList .memo-card[data-memo-id="'+item.id+'"]');
    if(row)highlightRow(row);
    else toast("这条备忘不在当前空间的列表里，可到备忘页切到「"+(grp==="life"?"生活":"工作")+"」空间查看",5600);
    return;
  }
  if(target.tab==="inbox"){
    // 「收录」页把待整理 + 待确认合并在同一页，两种状态的条目在不同容器里，都要找一遍。
    setTab("inbox");
    const row=document.querySelector('#inboxList .list-item[data-inbox-id="'+item.id+'"]')
      ||document.querySelector('#classifyList .list-item[data-inbox-id="'+item.id+'"]');
    if(row)highlightRow(row);
    else toast("这条收录记录不在待整理或待确认里，可能已经归档",5600);
    return;
  }
  if(target.tab==="timeline"){
    // 时间线按日分组且可折叠。那天如果是折叠的，高亮目标在 display:none 的容器里 —— 先展开。
    const day=target.day||"";
    if(day)state.timelineCollapsed[day]=false;
    setTab("timeline");
    renderTimeline();
    const row=document.querySelector('#timelineList .tl-item[data-log-id="'+item.id+'"]');
    if(row)highlightRow(row);
    else toast("这条记录不在最近加载的 500 条时间线里",5600);
    return;
  }
  toast("这条结果暂时没有可跳转的位置，请反馈这条记录");
}
// 搜不到东西时，把关键词本身收录下来 —— 「没搜到」往往正说明这是个值得记下来的新东西。
// 这里只写收集箱、不自动整理：按钮文案承诺的是「收录到收集箱」，多走一步就是承诺之外的行为。
async function collectSearchQuery(){
  const query=String(state.search.q||"").trim();
  if(!query)return;
  await WorkbenchApi.createInbox(query);
  closeSearchPanel();
  await refreshAll();
  toast("已把「"+query+"」收录到待整理，到「收录」页点「重新整理」即可");
}
document.querySelector(".tabs").addEventListener("click",event=>{const button=event.target.closest("button[data-tab]");if(button)setTab(button.dataset.tab)});
$("gearButton").addEventListener("click",()=>setTab("settings"));
// 委托到任何带 data-action 的元素，而不只是 button：驾驶舱的指标卡是 button，
// 但 Top3 / 今天截止 / 今日日程这些「整行可点」的条目是 div。
// closest 从最内层往上找，所以条目里的小按钮（如「开始」）命中的仍是它自己。
document.body.addEventListener("click",event=>{
  const target=event.target.closest("[data-action]");
  if(target){handleAction(target).catch(error=>toast(error.message));return}
  // 点了页面别处：收起「移至」菜单，别让它挂在卡片上。
  closeMoveMenu();
});
// ===== 任务页三泳道：卡片左右拖到别的泳道改状态 =====
// 用原生 HTML5 拖放而不是引入拖拽库：本页没有构建步骤，原生拖放也不会和页面滚动打架
// （触屏上 HTML5 拖放不会触发，那条路由卡片左侧圆点的循环切换兜住，看板下方有说明）。
// draggable 由 mousedown **动态**打开而不是在模板里常驻 true：整卡常驻可拖会让编辑表单里的
// 输入框没法用鼠标选中文本（Chrome / Firefox 都是），而「选不中文字」是没法靠提示补救的。
const taskBoard=$("taskBoard");
if(taskBoard){
  taskBoard.addEventListener("mousedown",event=>{
    // 先把上一次可能残留的 draggable 全部收掉：这个标记是按「上一次按在哪儿」设的，
    // 不清就会让某张卡片一直处于可拖状态，从它的输入框里也能拖走整张卡。
    taskBoard.querySelectorAll('.task-card[draggable="true"]').forEach(card=>card.setAttribute("draggable","false"));
    const card=event.target.closest(".task-card");
    if(!card)return;
    // 从按钮 / 输入框 / 已展开的编辑表单上按下时不启用拖拽：用户此刻想点它、或者想选文字。
    if(event.target.closest("input,textarea,select,button,a,label,.edit-panel"))return;
    card.setAttribute("draggable","true");
  });
  taskBoard.addEventListener("dragstart",event=>{
    const card=event.target.closest(".task-card");
    if(!card||event.target.closest("input,textarea,select,button,a,label,.edit-panel")){event.preventDefault();return}
    state.dragTaskId=card.dataset.taskId;
    card.classList.add("dragging");
    // dataTransfer 是 drop 时的**正规**来源；state.dragTaskId 只是同页内的兜底，
    // 因为个别浏览器在 drop 阶段读 getData 会拿到空串。
    if(event.dataTransfer){event.dataTransfer.effectAllowed="move";event.dataTransfer.setData("text/plain",card.dataset.taskId)}
  });
  taskBoard.addEventListener("dragend",()=>{
    state.dragTaskId=null;
    taskBoard.querySelectorAll(".task-card.dragging").forEach(card=>card.classList.remove("dragging"));
    setLaneDragOver(null);
  });
  taskBoard.addEventListener("dragover",event=>{
    const lane=event.target.closest(".lane");
    // 落在泳道之间的缝隙上时把高亮撤掉：高亮还亮着会让人以为「放这儿也算」。
    setLaneDragOver(lane);
    if(!lane)return;
    // 必须 preventDefault：不写的话浏览器认为这里不接受拖放，drop 事件压根不会触发，
    // 表现就是「拖过去松手什么也没发生」，控制台同样不报错。
    event.preventDefault();
    if(event.dataTransfer)event.dataTransfer.dropEffect="move";
  });
  taskBoard.addEventListener("drop",event=>{
    const lane=event.target.closest(".lane");
    if(!lane)return;
    event.preventDefault();
    const id=state.dragTaskId||(event.dataTransfer?event.dataTransfer.getData("text/plain"):"");
    state.dragTaskId=null;
    taskBoard.querySelectorAll(".task-card.dragging").forEach(card=>card.classList.remove("dragging"));
    setLaneDragOver(null);
    if(!id)return;
    // 用 toast 报错而不是让 Promise 悬着：跨级拖动可能连打两次接口，
    // 中途失败的原因（3001 非法流转、网络断了）必须让用户看见。
    dropTaskToLane(id,lane.dataset.lane).catch(error=>toast(error.message));
  });
}
$("createTaskButton").addEventListener("click",()=>createTask().catch(error=>toast(error.message)));
$("taskTitle").addEventListener("keydown",event=>{if(event.key==="Enter")createTask().catch(error=>toast(error.message))});
// 检索边走边刷（和备忘的搜索框一致）：任务页只加载前 100 条，本地过滤会漏掉第 100 条之外的匹配项。
// 防抖 + 只重取任务这一份数据，不再顺手把整个工作台重绘一遍。
$("taskSearch").addEventListener("input",()=>{state.taskQuery=$("taskSearch").value.trim();debounceInPage("task",function(){reloadTasks().catch(error=>toast(error.message))})});
$("inboxAddButton").addEventListener("click",()=>createInbox().catch(error=>toast(error.message)));
$("inboxInput").addEventListener("keydown",event=>{if(event.key==="Enter")createInbox().catch(error=>toast(error.message))});
$("confirmHighConfidenceButton").addEventListener("click",()=>confirmHighConfidence().catch(error=>toast(error.message)));
// 委托在容器上：renderClassify 会整体替换 innerHTML，逐条绑定会随重渲染失效
$("classifyList").addEventListener("change",event=>{
  const id=event.target&&event.target.id;
  if(id&&id.indexOf("confirm-category-")===0){const pid=id.substring("confirm-category-".length);syncClassifyFields(pid,event.target.value);syncPendingHint(pid)}
  if(id&&id.indexOf("confirm-due-")===0)syncPendingHint(id.substring("confirm-due-".length));
});
$("eventAddButton").addEventListener("click",()=>createEvent().catch(error=>toast(error.message)));
$("eventTitle").addEventListener("keydown",event=>{if(event.key==="Enter")createEvent().catch(error=>toast(error.message))});
// 按日视图的「前一天 / 后一天 / 日期选择器 / 回到今天」已随视图整体移除。
// ⚠️ 这几行绑定必须一起删：元素不在 HTML 里时 $() 返回 null，对 null 调 addEventListener
// 会在**脚本顶层**抛 TypeError，整页连 initialize 一起死 —— 而且控制台报的是个
// 「Cannot read properties of null」，看不出和日程页有关。
// 日程检索跨全部日期（含待定区），所以复用 loadEvents —— 它本来就是「按当前检索态取日程」
// 的唯一入口，refreshAll 里调的也是它。
$("eventSearch").addEventListener("input",()=>{state.eventQuery=$("eventSearch").value.trim();debounceInPage("event",function(){loadEvents().catch(error=>toast(error.message))})});
$("memoAddButton").addEventListener("click",()=>createMemo().catch(error=>toast(error.message)));
// 正文从单行 input 改成了多行 textarea（因为现在标题与正文分开录）：回车必须是换行，
// 保存改用 Ctrl / Cmd + Enter —— 页面上有一行文字提示，否则用户按回车没反应会以为坏了。
// 标题框里的回车用来把焦点交给正文，形成「标题 → 正文 → 保存」的顺手顺序。
$("memoTitle").addEventListener("keydown",event=>{if(event.key==="Enter"){event.preventDefault();$("memoContent").focus()}});
$("memoContent").addEventListener("keydown",event=>{if(event.key==="Enter"&&(event.ctrlKey||event.metaKey)){event.preventDefault();createMemo().catch(error=>toast(error.message))}});
$("memoSearch").addEventListener("input",()=>{state.memoQuery=$("memoSearch").value.trim();debounceInPage("memo",function(){reloadMemos().catch(error=>toast(error.message))})});
$("memoShowArchived").addEventListener("change",()=>{state.memoArchived=$("memoShowArchived").checked;refreshAll().catch(error=>toast(error.message))});
document.querySelector(".memo-switch").addEventListener("click",event=>{const button=event.target.closest(".memo-choice");if(button)setMemoGrp(button.dataset.grp)});
$("themeSaveButton").addEventListener("click",()=>saveTheme().catch(error=>toast(error.message)));
$("themeInput").addEventListener("keydown",event=>{if(event.key==="Enter")saveTheme().catch(error=>toast(error.message))});
$("pomoChip").addEventListener("click",()=>{$("pomoMask").classList.add("show")});
$("pomoMask").addEventListener("click",event=>{if(event.target===$("pomoMask"))$("pomoMask").classList.remove("show")});
document.querySelector(".pomo-panel .modes").addEventListener("click",event=>{const button=event.target.closest("button[data-m]");if(button)switchPomoMode(button.dataset.m)});
$("pomoBtn").addEventListener("click",pomoStart);
$("pomoResetBtn").addEventListener("click",()=>switchPomoMode(state.pomo.mode));
$("pomoSetToggle").addEventListener("click",()=>{const panel=$("pomoSet");const show=panel.style.display==="none";panel.style.display=show?"block":"none";if(show){$("pomoCfgWork").value=state.pomo.config.work;$("pomoCfgShort").value=state.pomo.config.shortBreak;$("pomoCfgLong").value=state.pomo.config.longBreak;$("pomoCfgAuto").checked=state.pomo.config.auto}});
$("pomoCfgSave").addEventListener("click",()=>savePomoConfig().catch(error=>toast(error.message)));
$("saveUsernameButton").addEventListener("click",()=>saveUsername().catch(error=>toast(error.message)));
$("savePasswordButton").addEventListener("click",()=>savePassword().catch(error=>toast(error.message)));
$("savePomoButton").addEventListener("click",()=>savePomoSetting().catch(error=>toast(error.message)));
$("saveAiButton").addEventListener("click",()=>saveAiSetting().catch(error=>toast(error.message)));
$("saveVaultButton").addEventListener("click",()=>saveVaultSetting().catch(error=>toast(error.message)));
$("backupNowButton").addEventListener("click",()=>doBackupNow().catch(error=>toast(error.message)));
$("clearDemoButton").addEventListener("click",()=>doClearDemo().catch(error=>toast(error.message)));
$("shutdownButton").addEventListener("click",()=>doShutdown().catch(error=>toast(error.message)));
$("logoutButton").addEventListener("click",async()=>{try{await WorkbenchApi.logout()}finally{location.replace("/setup.html")}});
$("kbChatSend").addEventListener("click",()=>askKnowledge().catch(error=>toast(error.message)));
$("kbChatInput").addEventListener("keydown",event=>{if(event.key==="Enter")askKnowledge().catch(error=>toast(error.message))});
// ===== 全局搜索的接线 =====
// 输入即搜（防抖 200ms）。这里**只打 /api/v1/search 一个接口**：
// 现有 #memoSearch 是 input → refreshAll，每敲一个字符会拉 11 个接口并重绘 11 个视图。
$("globalSearchInput").addEventListener("input",function(){
  state.search.q=$("globalSearchInput").value;
  openSearchPanel();
  scheduleGlobalSearch();
});
$("globalSearchInput").addEventListener("focus",function(){openSearchPanel()});
$("globalSearchInput").addEventListener("keydown",function(event){
  const search=state.search;
  if(event.key==="ArrowDown"){event.preventDefault();moveSearchSelection(1);return}
  if(event.key==="ArrowUp"){event.preventDefault();moveSearchSelection(-1);return}
  if(event.key==="Enter"){
    event.preventDefault();
    const pending=String(search.q||"").trim();
    // 结果还没跟上输入时不许打开：此刻列表里是**上一次**的结果，
    // 回车会跳到一条用户根本没在看的记录上，而且界面看起来完全正常。
    if(search.loading||pending!==search.resultQuery){
      if(pending)toast("正在搜索「"+pending+"」，结果出来后再按回车");
      return;
    }
    const items=searchFlatItems();
    if(items.length)openSearchResult(items[Math.max(0,search.activeIndex)]).catch(error=>toast(error.message));
    return;
  }
  if(event.key==="Escape"){
    event.preventDefault();
    // 有内容先清内容（想「重搜」时最常按的就是 Esc），已经空了才收起面板
    if($("globalSearchInput").value){
      $("globalSearchInput").value="";state.search.q="";runGlobalSearch().catch(error=>toast(error.message));
    }else closeSearchPanel();
    return;
  }
  if(event.key==="Tab"){
    // 双向切换预览。这里会拦住 Tab 的默认行为 —— 出口是 Esc：
    // 面板一收起 Tab 立刻恢复正常，不会把键盘用户困在输入框里
    // （「必须给出口」和「Tab 要能切回来」之间的取法）。
    event.preventDefault();
    search.previewOpen=!search.previewOpen;
    renderSearchPanel();
  }
});
$("searchPanel").addEventListener("click",function(event){
  const chip=event.target.closest("[data-search-type]");
  if(chip){
    state.search.typeFilter=chip.dataset.searchType||"";
    state.search.activeIndex=searchFlatItems().length?0:-1;
    renderSearchPanel();
    return;
  }
  if(event.target.closest("[data-search-open]")){
    const items=searchFlatItems();
    openSearchResult(items[Math.max(0,state.search.activeIndex)]).catch(error=>toast(error.message));
    return;
  }
  if(event.target.closest("[data-search-retry]")){runGlobalSearch().catch(error=>toast(error.message));return}
  if(event.target.closest("[data-search-collect]")){collectSearchQuery().catch(error=>toast(error.message));return}
  const row=event.target.closest("[data-search-index]");
  if(row)setSearchActive(Number(row.dataset.searchIndex));
});
// 双击结果行 = 直接打开（单击是「选中并预览」，双击是「就是它了」）
$("searchPanel").addEventListener("dblclick",function(event){
  const row=event.target.closest("[data-search-index]");
  if(row)openSearchResult(searchFlatItems()[Number(row.dataset.searchIndex)]).catch(error=>toast(error.message));
});
// Ctrl / Cmd + K：从任意页签聚焦到搜索框。输入框本身常驻可见，
// 这个快捷键省掉的只是「用鼠标点一下」这一步。
document.addEventListener("keydown",function(event){
  if((event.ctrlKey||event.metaKey)&&(event.key==="k"||event.key==="K")){
    event.preventDefault();focusGlobalSearch();
  }
});
// 点面板外面收起。面板内部与输入框不算「外面」，否则点一下结果面板就没了。
document.addEventListener("click",function(event){
  if(!state.search.open)return;
  // 这里必须用**事件路径**（composedPath）而不是 event.target.closest("#searchPanel")：
  // 面板里的按钮会同步重绘自己所在的容器（切一下类型 chip 就整体替换 #searchChips 的 innerHTML），
  // 事件继续冒泡到这里时 target 已经脱离文档树，closest() 找不到面板祖先，
  // 于是「点一下筛选 chip」被误判成「点了外面」，刚打开的面板自己关掉 —— 不报错，只是莫名其妙地关了。
  // composedPath 在事件派发的那一刻就定好了，不受后续 DOM 变动影响。
  const path=typeof event.composedPath==="function"?event.composedPath():[];
  for(let i=0;i<path.length;i++){
    const node=path[i];
    if(node&&node.id&&(node.id==="searchPanel"||node.id==="globalSearchInput"))return;
  }
  if(event.target&&event.target.closest&&(event.target.closest("#searchPanel")||event.target.closest("#globalSearchInput")))return;
  closeSearchPanel();
});
if(window.addEventListener)window.addEventListener("resize",function(){if(state.search.open)positionSearchPanel()});
// 三个「新增」区（任务 / 日程 / 备忘）折叠成 SaaS 风格的「＋ 新建」入口条：默认收起，点开才展开表单。
// 直接按 ID 绑定而不是走 data-action —— 展开/收起是纯视图状态，不进 handleAction 的 if 链，
// 也不会被检查脚本「每个 data-action 都有处理分支」扫到（那条盯的是会改数据 / 跳转的动作）。
// 2026-09-12：新建表单现在和搜索同行，所以多一个 tab 参数 —— 展开表单时要**互斥**地收起搜索，
// 否则 .composer 被压窄成一百来像素而表单还开着，grid 会被挤成一条细缝（不报错，只是难看又难查）。
function bindComposer(tab,toggleId,bodyId){
  const toggle=$(toggleId),body=$(bodyId);
  if(!toggle||!body)return;
  toggle.addEventListener("click",()=>{
    const open=!body.classList.contains("open");
    if(open)setSearchOpen(tab,false);
    body.classList.toggle("open",open);
    toggle.classList.toggle("open",open);
    toggle.setAttribute("aria-expanded",open?"true":"false");
    // 展开后聚焦第一个文本控件，省去一次点击；checkbox 不聚焦（它会抢焦点且看不见）。
    if(open){const first=body.querySelector("input:not([type=checkbox]),textarea");if(first)first.focus()}
  });
}
bindComposer("task","composerToggleTask","composerBodyTask");
bindComposer("event","composerToggleEvent","composerBodyEvent");
bindComposer("memo","composerToggleMemo","composerBodyMemo");
// 三个放大镜按钮：点它是**切换**（展开 ↔ 收起），收起时关键词不丢 ——
// 「还在检索」这件事由按钮上的小圆点和页面上那条提示条负责说，不靠用户记性。
function bindSearchToggle(tab,btnId){
  const btn=$(btnId);
  if(!btn)return;
  btn.addEventListener("click",()=>setSearchOpen(tab,!searchOpenFlag(tab)));
}
bindSearchToggle("task","taskSearchBtn");
bindSearchToggle("event","eventSearchBtn");
bindSearchToggle("memo","memoSearchBtn");
// Esc 收起搜索框，给键盘用户留一个出口（不然只能靠 Tab 摸到那个按钮）。
// 只收起**不清空**：清空是页面上「清除搜索」按钮的事，混在一起会让人按一次 Esc 就丢了关键词。
document.addEventListener("keydown",event=>{
  if(event.key!=="Escape")return;
  ["task","event","memo"].forEach(tab=>{if(searchOpenFlag(tab))setSearchOpen(tab,false)});
});
// 「含归档」标签：它存在的理由就是「显示已归档」跟着搜索框一起被收起来了，
// 而那个开关管的是常驻列表 —— 不补这个出口，用户勾上之后就再也找不到怎么关掉。
$("memoArchivedChip").addEventListener("click",()=>{state.memoArchived=false;refreshAll().catch(error=>toast(error.message))});
initialize();
