// ⚠️ 任务页的「工作 / 生活分组」已于 2026-09-17 整体移除（用户要求「移除分组相关的功能或入口」）：
// 切换器、卡片上的分组徽标、编辑表单里的分组下拉、取数层带的 grp 参数、以及 state.taskGrp 全都不在了。
// 任务页现在一律展示全部任务。**后端的 grp 字段与自动判定保持不动**：
// 它仍由 TaskService.resolveGroupFor 按标题关键词写入，只是前端不再读取、不再作为视图口径 ——
// 这样既没有破坏性迁移（不动表结构），也不要再往取数层加回 grp 参数
// （加了就会变成「默认只显示一半任务」的静默过滤，那个坑 2026-09-14 刚踩过）。
const state={status:null,dashboard:null,tasks:[],inbox:[],classify:[],events:[],pendingEvents:[],favorites:[],activity:[],settings:null,backups:[],trash:[],favoriteGrp:"all",favoriteArchived:false,favoriteQuery:"",taskQuery:"",eventQuery:"",taskSearchOpen:false,eventSearchOpen:false,favoriteSearchOpen:false,tab:"dashboard",timelineCollapsed:{},moveOpen:null,taskFilter:"all",
  // 任务页的排序口径（2026-09-20 用户要求「任务菜单的所有任务应该提供两个排序，按截止时间（默认）、
  // 按最后修改时间」）。"due" = 按截止时间（默认，与改动前**完全一致**的顺序）；
  // "updated" = 按最后修改时间倒序。
  // ⚠️ 它**参与取数**（refreshAll / reloadTasks 会带 sort=updated），不是在渲染层本地重排 ——
  //    任务页一次只加载 100 条，本地重排只能在这 100 条里做，而「按最后修改时间」要看的
  //    恰恰是最近改过的那一批，它们完全可能不在「按截止时间排出来的前 100 条」里。
  //    这是「接口写好没接上 / 过滤写成本地」那一族失效，只不过换成排序而已。
  taskSort:"due",
  // 「已完成」页的按天汇总（2026-09-20，用户要求「用户能一眼看清今天完成了哪些内容」）。
  // 形状见 TaskDoneSummaryVO：{date, today:{...}, days:[{date,count,p0Count,...}]}。
  // ⚠️ **null = 汇总不可用**（接口失败），此时 renderDoneLane 退回改动前的一整段倒序列表：
  //    宁可没有分组标题，也不能拿「已加载的 100 条」自己数出一个会少算的数字。
  //    汇总必须由后端算（全表分组）—— 今天的完成项可能排在 100 条之外。
  doneSummary:null,
  dragTaskId:null,
  // 周视图（2026-09-13，见 docs/日程周视图设计.md）。visibleWeeks 是**有序**的周起始日（周一）列表，
  // 顺序 = 页面从上到下（未来在上、过去在下），默认 [下周一, 本周一, 上周一]。
  // weekBase = 当前「本周」的周一，只由后端下发的 dashboard.date 决定，用来判断要不要整组复位。
  // expandedWeek = 当前**唯一展开**的那一条周（手风琴：任何时刻只有一个展开）。
  // 存 state 而不是只挂 DOM class：refreshAll 每次都重建周条，只挂 DOM 的话状态会被抹掉
  // （时间线折叠踩过同一个坑）。用「哪个展开」而不是「哪些折叠」，是因为后者能表达出
  // 「全展开」「全折叠」这些不该存在的状态。
  weekBase:"",visibleWeeks:[],expandedWeek:"",
  // 页内检索的防抖计时器（任务 / 日程 / 收藏各一个）。见 debounceInPage。
  inPageTimers:{},
  // 「默认带入当日」的字段各自上一次被自动写进去的值（见 defaultOf / applyAutoDefault）。
  // 空 = 还没写过；等于它 = 仍是默认值（可以被 AI 填表覆盖、可以随「今天」刷新）。
  autoDefaults:{},
  // 全局搜索的独立状态。**不参与 refreshAll** —— 那条链路一次拉 11 个接口并全量重绘 11 个视图，
  // 挂上去就等于每敲一个字符把整个工作台重画一遍（现有 #favoriteSearch 就是这个毛病）。
  search:{q:"",open:false,scope:"all",typeFilter:"",previewOpen:true,groups:[],activeIndex:-1,loading:false,error:"",totalCount:0,elapsedMs:0,truncated:false,seq:0,timer:null,resultQuery:"",
    semanticState:"off",expandedTerms:[],semanticCount:0},
  knowledge:{notes:[],index:null,indexState:"idle",indexError:"",chat:[],asking:false},
  // 收藏文章的进行中标记：只用于防重复提交，不参与 refreshAll。
  collect:{collecting:false,importing:false},
  pomo:{mode:"work",remain:25*60,running:false,timer:null,endAt:0,config:{work:25,shortBreak:5,longBreak:15,auto:false},today:{count:0,minutes:0},audio:null,flashTimer:null,baseTitle:document.title},
  // 图片区（2026-09-16）：四个入口各一份。元素形如
  // {localId,kind:"image"|"file",status:"uploading"|"done"|"failed",attachmentId,name,sizeText,url,error}
  // 只放 state、**不参与 refreshAll** —— 上传是独立通道，重绘整页会把缩略图弄丢。
  media:{inbox:[],task:[],event:[],favorite:[]}};
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
function categoryName(category){return {task:"任务",schedule:"日程",favorite:"收藏",knowledge:"知识"}[category]||category}
function categoryOptions(){const options=["task","schedule","favorite"];if(state.settings&&state.settings.obsidianVaultPath)options.push("knowledge");return options}
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
function trashTypeName(type){return {inbox:"收录",task:"任务",event:"日程",favorite:"收藏",knowledge:"知识"}[type]||type}
// 「移至」：任务 / 日程 / 收藏三个菜单之间搬运。目标只有另外两个，所以按来源直接推出来 ——
// 不写死三份列表：漏一份的表现是某个菜单里只列出两个目标，不报错，只能靠肉眼发现。
function menuName(type){return {task:"任务",event:"日程",favorite:"收藏"}[type]||type}
function moveTargets(from){return from==="task"?["event","favorite"]:from==="event"?["task","favorite"]:["task","event"]}
// 菜单的开合状态存在 state 里，而不是只挂在 DOM 的 class 上：refreshAll 每次都重绘卡片，
// 状态只在 DOM 里的话下一次刷新就没了（时间线折叠踩过同一个坑，这里不重复）。
// 按钮样式必须**跟着所在那一行**走，不能在这里写死：任务 / 日程卡片那一行是实心按钮
// （编辑 / 顺延 / 取消 / 删除），收藏卡片那一行整排是 ghost（置顶 / 归档 / 编辑 / 删除）。
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
  const favoriteParams={archived:state.favoriteArchived};if(state.favoriteGrp!=="all")favoriteParams.grp=state.favoriteGrp;if(state.favoriteQuery)favoriteParams.q=state.favoriteQuery;
  // 任务检索走后端 keyword（命中 标题 / 描述 / 备注），所以必须在**取数**这一步带上，
  // 不能在渲染阶段过滤：任务页只加载前 100 条，本地过滤会把「第 100 条之外的那个匹配项」
  // 静默漏掉——用户搜了、没结果，会以为那条任务不存在。
  // ⚠️ 任务取数**不带 grp**：分组功能已移除，任务页一律展示全部任务（见文件头 state 上方那段说明）。
  // 排序也在这层交给后端（`sort=updated`）：它和 `keyword` 是同一个道理 —— ORDER BY 与 LIMIT 是一体的，
  // 先按截止时间取前 100 条、再在本地按修改时间重排，用户得到的只是「那 100 条里改过的」，
  // 而不是他要的「最近改过的那批」。默认档（due）不传参，保持 URL 干净、与改动前逐字一致。
  const taskParams={size:100};if(state.taskQuery)taskParams.keyword=state.taskQuery;if(taskSortMode()==="updated")taskParams.sort="updated";
  // 日程页是周视图：一次取回「当前可见的所有周」那一段区间（from/to），
  // 而不是按天循环调 7 次（那会让一次刷新多出 6 个请求）。
  // 区间要先知道「本周」才算得出来，而「今天」只能由后端 dashboard.date 定义一次，
  // 所以这里先 syncVisibleWeeks() 定一次；首次进入时 dashboard 还没回来，
  // 用本机日期兜底（前端与后端同机同时区），拿到 dashboard 后再校一次，跨零点会整组复位。
  // 检索态让出区间：命中跨全部日期，带上 from/to 会变成「只在这一段里搜」。
  syncVisibleWeeks();
  const results=await Promise.all([WorkbenchApi.dashboard(),WorkbenchApi.events(eventParams()),WorkbenchApi.tasks(taskParams),WorkbenchApi.inbox("pending"),WorkbenchApi.inbox("processed"),WorkbenchApi.favorites(favoriteParams),WorkbenchApi.activity(500),WorkbenchApi.settings(),WorkbenchApi.backups(),WorkbenchApi.knowledgeNotes(),WorkbenchApi.pendingEvents(),WorkbenchApi.trash(),WorkbenchApi.inbox("failed")]);
  state.dashboard=results[0];  // dashboard.date 可能与首帧兜底算出的「本周」不同（首次进入、或跨了零点）：那时上面那条
  // events 请求的区间已经过期，按新周补取一次，否则界面上会缺一整周的数据。
  const weeksChanged=syncVisibleWeeks();
  state.events=weeksChanged?(await WorkbenchApi.events(eventParams()))||[]:(results[1]||[]);state.tasks=results[2];state.classify=results[4];state.favorites=results[5];state.activity=results[6]||[];state.settings=results[7];state.backups=results[8]||[];state.knowledge.notes=results[9]||[];state.pendingEvents=results[10]||[];state.trash=results[11]||[];
  // 「待整理」区同时装 pending 与 failed：整理失败的条目状态既不是 pending 也不是 processed，
  // 两个列表都捞不到它 —— 条目会彻底从界面上消失。这是「静默丢数据」的典型形态。
  state.inbox=(results[3]||[]).concat(results[12]||[]);
  // 「已完成」页的按天汇总（2026-09-20）。**不接受它在 Promise.all 里**，两个理由：
  //   ① 它是「已完成」泳道的辅助信息，失败不该拖垮整页 —— Promise.all 是全有全无，
  //      13 个请求里任何一个失败会让 11 个视图全部保持旧 DOM（本项目已踩过）。
  //   ② 它算的是全表分组，比列表那条重，单独发可以让别的视图先出。
  // 失败时**置 null 而不是保留旧值**：留着上一次的汇总，界面会拿旧数字配对新列表 ——
  // 比没有汇总更糟（用户看不出来，会以为今天真完成了那么多）。
  // 降级形态见 renderDoneLane：没有汇总 = 退回改动前的一整段倒序列表，功能不做假承诺。
  try{state.doneSummary=await WorkbenchApi.doneSummary()}catch(error){state.doneSummary=null}
  renderDashboard();renderTasks();renderInbox();renderClassify();renderSchedule();renderPendingEvents();renderFavorites();renderTimeline();renderPomoTasks();renderSettings();renderKnowledge();renderTrash();
}
// ===== 新增 / 收录生成之后：保证刚建的那一条**立刻**看得见（2026-09-18）=====
//
// 「重取数 + 重绘」**不等于**「用户看得见」。三个列表都带「会收窄结果」的条件，而且它们在页内
// 一直保留着：收藏是空间（state.favoriteGrp，走服务端 ?grp= 过滤）、任务是页内检索
// （state.taskQuery → ?keyword=）与驾驶舱下钻留下的筛选（state.taskFilter）、日程是页内检索
// （state.eventQuery → ?q=）和「周视图只保留可见那几条周」。
// 新记录被关键词自动归到另一侧、或与检索词不匹配时，它**压根不在当前视图里** ——
// 用户的感受是「刚建的东西不见了」，而 F5 会把上面这些条件复位成默认值，于是现象就是
// 「必须按 F5 才显示」；而且后端日志、控制台都不会有任何异常（典型的静默失效）。
//
// 规则（三个模块完全一致，只有「放宽哪些条件」各自不同）：
//   ① 取数之后按 id 反查一次；查得到 = 它本来就在视图里，什么都不做；
//   ② 查不到 = 被视图条件挡住了 → 把条件放宽到「一定能看见它」的档位，再取一次数；
//   ③ 放宽了什么必须说出来（不许静默改视图：用户下次看到的列表口径变了却不知道为什么）。
// 放宽而不是「只提示一句」：用户要的是「立刻看到刚建的那条」，提示一句仍然要他自己去切。
function categoryKind(category){return category==="schedule"?"event":category==="task"?"task":category==="favorite"?"favorite":null}
// 「看得见吗」必须**与各页真正的渲染口径一致**，不能只问「接口返回里有没有这条」——
// 任务页除了服务端检索，还有一层**本地**筛选（驾驶舱下钻留下的 state.taskFilter，
// 由 taskVisible 在渲染时应用），取数命中不等于画得出来。第一版就是漏了这一层，
// 断言当场抓到「筛选=逾期 时新建任务仍然看不见」。
function createdVisible(kind,id){
  if(kind==="favorite")return state.favorites.some(item=>String(item.id)===String(id));
  if(kind==="task"){
    const task=state.tasks.filter(item=>String(item.id)===String(id))[0];
    return !!task&&taskVisible(task);
  }
  if(kind==="event"){
    // 周视图只画「落在可见那几条周里」的日程；待定日程（没有日期）只认待定区。
    const hit=state.events.filter(item=>String(item.id)===String(id))[0];
    if(hit)return !hit.date||isDateVisible(hit.date);
    return state.pendingEvents.some(item=>String(item.id)===String(id));
  }
  return true; // 知识 / 收录条目没有会收窄列表的条件，不需要这一层
}
function relaxViewForCreated(kind,hint){
  const notes=[];
  if(kind==="favorite"){
    if(state.favoriteQuery){clearFavoriteQuery();notes.push("已清掉收藏的页内检索")}
    if(state.favoriteGrp!=="all"){state.favoriteGrp="all";notes.push("空间已切回「全部」")}
  }else if(kind==="task"){
    if(state.taskQuery){clearTaskQuery();notes.push("已清掉任务的页内检索")}
    if((state.taskFilter||"all")!=="all"){state.taskFilter="all";notes.push("已取消驾驶舱带来的筛选")}
  }else if(kind==="event"){
    if(state.eventQuery){clearEventQuery();notes.push("已清掉日程的页内检索")}
    // 周视图只保留「可见的那几条周」：新日程落在别的周里时，光重取数也看不到它。
    // ensureWeek 同时会把那一周设为当前展开的那条，所以卡片是**露出来**的，不是收起的横杠。
    const date=hint&&hint.date;
    if(date&&!isDateVisible(date)){ensureWeek(weekStartOf(date));notes.push("已把 "+date+" 那一周加进列表")}
  }
  return notes
}
/**
 * 取数之后调一次：确认刚建的那一条在当前视图里；不在就把挡住它的条件放宽、重取数。
 *
 * <p>调用顺序**必须是「先 refreshAll() 再调它」**：它判断的是「重取数之后用户看得见吗」，
 * 而不是「后端有没有这条数据」。</p>
 *
 * @returns 要追加到 toast 的一句话（自带前导「；」）；空串 = 它本来就在视图里，没什么可说的。
 *          文案分两种，**绝不谎报**：放宽后看得到 → 说放宽了什么 + 「立刻出现在列表里」；
 *          放宽后仍看不到（例如任务超出一次加载的前 100 条）→ 照实说，并给出下一步。
 */
async function createdVisibilityNote(kind,id,hint){
  if(!kind||id===undefined||id===null)return "";
  if(createdVisible(kind,id))return "";
  const notes=relaxViewForCreated(kind,hint);
  if(!notes.length)return ""; // 没有可放宽的条件：不是视图把它挡住的，别乱动视图
  await refreshAll();
  if(!createdVisible(kind,id)){
    return "；"+notes.join("；")+"，但它仍不在当前列表里（可能超出一次加载的条数上限），可用页内检索按标题找它"
  }
  return "；"+notes.join("；")+"，这样刚建的这条立刻出现在列表里"
}
function setTab(tab){
  // 切页签时收起「移至」菜单：它挂在卡片上，页签切换不重绘卡片，不收就会留在页面上。
  closeMoveMenu();
  // 同理收起全局搜索面板：它是覆盖在内容之上的浮层，切了页签还挡着就看不到新页面了。
  // 关键词保留在输入框里，回来再点一下就是原来的结果。
  closeSearchPanel();
  state.tab=tab;document.querySelectorAll(".tabs button").forEach(button=>button.classList.toggle("active",button.dataset.tab===tab));
  document.querySelectorAll(".view").forEach(view=>view.classList.toggle("active",view.id==="view-"+tab));
  // 齿轮不在 .tabs 里（它在顶栏），active 要单独维护。
  // 回收站那枚图标按钮**在 .tabs 内**（只是排在右边），所以上面那句一把梭已经管到它了 ——
  // 不要在这里再维护一次，两处维护同一件事迟早会漂。
  const gear=$("gearButton");if(gear)gear.classList.toggle("active",tab==="settings");
  // 设置页要显示 Vault 是否真的生效，所以也要拿到知识库索引
  if((tab==="knowledge"||tab==="settings")&&(state.knowledge.indexState==="idle"||state.knowledge.indexState==="error"))loadKnowledgeIndex();
  else if(tab==="settings")renderVaultStatus();
}
function renderDashboard(){
  const data=state.dashboard,m=data.metrics;
  $("metricOpen").textContent=m.taskOpen;$("metricDone").textContent=m.taskDone+" / "+m.taskTotal;$("metricOverdue").textContent=m.overdue;$("metricToday").textContent=data.todayTasks.length;
  $("metricInbox").textContent=m.inboxPending;$("metricPomo").textContent=m.pomoToday;
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
  // 分组那一支已于 2026-09-17 随「移除分组」一起删掉：任务页不再有分组视图，
  // `metrics.taskTotal` 与手里这份 list 重新变成同一口径（都是「全部任务」），下面的减法重新成立。
  // ⚠️ 将来若有人把分组加回来，必须把那条分支连同它的减法守卫一起恢复，
  // 否则会报出「另有 N 条更早的任务没有显示」这种凭空捏造的数字（2026-09-14 踩过）。
  const hidden=hiddenTaskCount();
  if(!hidden)return "";
  // 截断说明必须说清「漏掉的是哪一批」，而这一句**随排序口径变**：按截止时间取的是
  // 「最该先做的那一批」，按最后修改时间取的是「最近动过的那一批」—— 两种情况漏掉的东西
  // 完全不同。共用一句话会说错，而说错比不说更糟：用户会以为自己已经看全了。
  const orderNote=taskSortMode()==="updated"
    ?"当前排序是「按最后修改时间」，取的是最近动过的 100 条"
    :"排序为 进行中 → 待办 → 已完成，再按优先级与截止时间";
  return "<div class=\"empty\">任务较多：上面只列出前 100 条（"+orderNote+"），另有 "+hidden+" 条没有显示；切到另一档排序或按标题搜索都能找到它们。</div>";
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
// 「今天截止」只在这里定义一次（2026-09-20）：「今天截止」筛选、泳道内置顶、卡片高亮三处都用它。
// 口径与后端算 dashboard 指标时一致 —— 「今天」是 todayString()（后端按配置时区下发的那一天）。
function isDueToday(task){return !!task&&!!task.due&&task.due===todayString()}
// 「已结束」= 已完成 / 已取消。它决定要不要参与置顶与高亮：
// 一条今天到期的任务做完之后，不该再在列表最前面抢注意力（进度已经走完）。
function taskEnded(task){const status=task&&task.status;return status==="done"||status==="canceled"}
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
function clearFavoriteQuery(){state.favoriteQuery="";const el=$("favoriteSearch");if(el)el.value=""}
// ===== 搜索行（2026-09-12 改版）=====
// 收起态只留「＋新建X」+ 一个放大镜方按钮；点放大镜后输入框接管这一行、新建压窄留在左边。
// 展开/收起是**纯视图状态**，所以按 ID 绑定、不走 data-action（同 bindComposer 的理由：
// 检查脚本那条「每个 data-action 都有处理分支」盯的是会改数据 / 跳转的动作）。
// 三条硬规则，每一条都对应一类真实故障：
// ① 同一行同一时刻只能有一个展开态（开搜索就收新建表单，开新建表单就收搜索）。
//    不这么做的话 .composer 被压窄成一百来像素而里面的表单还开着，grid 会被挤成一条细缝。
// ② 收起**不等于**退出搜索：关键词留着，靠按钮上的小圆点 + 页面上那条 .filter-note 说明
//    「列表还是筛过的」。列表被筛过却看不出为什么 = 静默过滤，是本项目最忌讳的一类失效。
// ③ 收藏页的「显示已归档」收起后没有入口（它管的是常驻列表，不只是搜索），
//    勾选生效时补一个「含归档」标签兜住，点一下就关。
function searchRowIds(tab){
  if(tab==="event")return {row:"eventSearchRow",input:"eventSearch",btn:"eventSearchBtn",body:"composerBodyEvent",toggle:"composerToggleEvent",name:"日程"};
  if(tab==="favorite")return {row:"favoriteSearchRow",input:"favoriteSearch",btn:"favoriteSearchBtn",body:"composerBodyFavorite",toggle:"composerToggleFavorite",name:"收藏"};
  return {row:"taskSearchRow",input:"taskSearch",btn:"taskSearchBtn",body:"composerBodyTask",toggle:"composerToggleTask",name:"任务"};
}
function searchQuery(tab){return tab==="event"?state.eventQuery:tab==="favorite"?state.favoriteQuery:state.taskQuery}
function searchOpenFlag(tab){return tab==="event"?!!state.eventSearchOpen:tab==="favorite"?!!state.favoriteSearchOpen:!!state.taskSearchOpen}
function setSearchOpenFlag(tab,open){if(tab==="event")state.eventSearchOpen=open;else if(tab==="favorite")state.favoriteSearchOpen=open;else state.taskSearchOpen=open}
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
  if(tab==="favorite"){
    const label=$("favoriteArchivedLabel");if(label)label.classList.toggle("hidden",!open);
    // 勾选框藏在 .hidden 里时它的 checked 不会自己跟着 state 走，展开时补一次同步：
    // 否则「含归档」标签关掉之后重新展开，勾选框还画着对勾（显示与状态不符）。
    const box=$("favoriteShowArchived");if(box&&box.checked!==!!state.favoriteArchived)box.checked=!!state.favoriteArchived;
    const chip=$("favoriteArchivedChip");if(chip)chip.classList.toggle("hidden",!(state.favoriteArchived&&!open));
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
// 而检索命中的只是自己那一页的数据（任务 `?keyword=` / 日程 `?q=` / 收藏 `?q=`），
// 其余 10 个视图一个字都不会变。所以收敛成「防抖 200ms + 只取自己要的那一份」。
// 注意 state 字段的语义完全不变（taskQuery / eventQuery / favoriteQuery），
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
  // 排序与 refreshAll 里那行必须**逐字一致**：这里少传一次，用户切完排序再敲一下搜索框，
  // 列表就会悄悄回到另一种顺序（两套取数口径各写一份，是本项目反复踩到的漂移源头）。
  if(taskSortMode()==="updated")params.sort="updated";
  state.tasks=(await WorkbenchApi.tasks(params))||[];
  renderTasks();
}
async function reloadFavorites(){
  const params={archived:state.favoriteArchived};
  if(state.favoriteGrp!=="all")params.grp=state.favoriteGrp;
  if(state.favoriteQuery)params.q=state.favoriteQuery;
  state.favorites=(await WorkbenchApi.favorites(params))||[];
  renderFavorites();
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
// ===== 任务排序（2026-09-20，用户要求「任务菜单的所有任务应该提供两个排序，
// 按截止时间（默认）、按最后修改时间」）=====
// 两个口径**都只在泳道内部生效**，不跨泳道搬卡片 —— 泳道就是任务状态，搬过去等于改了状态。
// 排序值 state.taskSort 是**取数口径**的一部分（见 refreshAll / reloadTasks），不是渲染层的临时视图，
// 所以下面这些函数只负责「按哪个规则排列已经拿到的这批」，不负责决定取哪批。
function taskSortMode(){return state.taskSort==="updated"?"updated":"due"}
/**
 * 「按截止时间」档 = 与改动前**完全一致**的顺序，额外把当天截止的排到每条泳道的最前面
 * （2026-09-20 用户要求，与排序功能同一天）。三条约束：
 *   ① 只在泳道内挪，不跨泳道搬卡片；
 *   ② 已结束（完成 / 已取消）的不参与：它们不该再抢注意力；
 *   ③ 用**稳定分区**而不是 sort：同一天截止的几条保持后端给的优先级顺序，
 *      不会因为「排序不稳」在两次刷新之间来回跳（列表跳动比顺序不完美更让人怀疑数据）。
 */
function pinToday(list){
  const hot=[],rest=[];
  list.forEach(task=>{(isDueToday(task)&&!taskEnded(task)?hot:rest).push(task)});
  return hot.concat(rest);
}
/**
 * 「按最后修改时间」档：整条泳道按 updated_at 倒序。
 *
 * <p>这一档**刻意不置顶当日截止**：用户选它就是为了看「我最近动过哪几条」，
 * 此时再按另一个维度把卡片拎到最前面，他会以为排序没生效（而界面上没有任何异常）。
 * 卡片上「今天截止」的高亮仍然保留 —— 那是标记，不改变顺序。</p>
 *
 * <p>口径与后端 SQL 保持一致：后端是 {@code COALESCE(updated_at, created_at) DESC, id DESC}，
 * 这里同样用 created_at 兜底（updated_at 在 V1 里可空），再用 id 兜底同一秒的多条，
 * 保证两侧排出来的结果一样、刷新不会来回跳。</p>
 */
function sortByUpdated(list){
  return list.slice().sort((left,right)=>{
    const a=String(left.updatedAt||left.createdAt||"");
    const b=String(right.updatedAt||right.createdAt||"");
    if(a!==b)return a<b?1:-1;              // "yyyy-MM-dd HH:mm:ss" 文本，字典序即时间序
    return Number(right.id||0)-Number(left.id||0);
  });
}
function sortLane(list){return taskSortMode()==="updated"?sortByUpdated(list):pinToday(list)}
/**
 * 排序条的选中态**只由 state 决定**，不在点击时临时切 class：否则任何一次重绘（refreshAll
 * 会重绘 11 个视图）之后，高亮停在哪一档就和真实口径脱钩了 —— 列表按 A 排、按钮亮在 B 上，
 * 而用户唯一的判断依据就是这个高亮。与 applySearchRow 同一套路：只改 DOM、不取数，
 * 所以在 renderTasks 末尾调用它。
 *
 * <p>⚠️ 选择器限定在 {@code #taskSortBar} 之内：裸 {@code .ts-choice} 一旦别处也用了这个类名，
 * {@code querySelectorAll} 会跨控件命中，把别的切换器一起改掉（§7.5.1 收藏页踩过）。</p>
 */
function applyTaskSort(){
  const bar=$("taskSortBar");
  if(!bar)return;
  const mode=taskSortMode();
  bar.querySelectorAll(".ts-choice").forEach(btn=>{
    const active=btn.dataset.sort===mode;
    btn.classList.toggle("active",active);
    btn.setAttribute("aria-pressed",active?"true":"false");
  });
}
/** 幂等：点当前已经生效的那一档，既不重取数也不弹提示（返回 false 由调用方决定要不要提示）。 */
function setTaskSort(sort){
  const next=sort==="updated"?"updated":"due";
  if(next===taskSortMode())return false;
  state.taskSort=next;
  return true;
}
// ===== 「已完成」泳道按天分组（2026-09-20）=====
//
// 用户诉求原话：「在任务列表的『已完成』页面，我希望用户能一眼看清今天完成了哪些内容。」
// 方案（已与用户确认）：**日期分组 + 今天组高亮 + 组头统计**；
// 口径（1a）「今天完成」**只算真正完成的**（task.completed_at 非空），已取消单独标注、不并入主数字；
// 统计（2a）**由后端算**（GET /tasks/done-summary），不在前端按已加载的 100 条自己数；
// 「今天」（3a）沿用后端下发的日期口径（state.doneSummary.date / dashboard.date），**不用 new Date()**；
// 组头粘性（4a）做；相对日期词（5a）「今天 / 昨天 / 更早的绝对日期」；
// 粒度（6a）只对「今天」做高亮分组，更早的仍是一整段倒序列表；
// 空状态（7a）今天一项都没完成时给一行轻提示，而不是让整块消失。
//
// ⚠️ 三条不许破的约束：
//   ① **汇总不可用时退回旧形态**（state.doneSummary === null → 一整段倒序列表）。
//      绝不能拿「已加载的 100 条」自己数 —— 今天的完成项可能排在 100 条之外，
//      数出来的数字会比真实值小，而页面上没有任何迹象说明它算少了。
//   ② **「今天」只有后端一个定义**（doneSummary.date，按用户时区算）。
//      前端 `new Date()` 在跨零点 / 跨时区时和它差一天，会让「今天」这一组在 0 点前后错位。
//   ③ **换分组只改 `.pr-` 之外的 DOM 结构**：这里整条 innerHTML 重建是安全的，
//      因为泳道里没有需要保留焦点的常驻控件（卡片上的按钮每次 refreshAll 本来就重建）。
//      但**焦点会回到 body** —— 与诗词栏那次「只换 `.pr-poem` 节点」的教训同源，
//      区别是泳道本来就没有跨重绘保持焦点的控件，不需要额外处理。
/** 某一天的相对称呼：今天 / 昨天 / 绝对日期（M月D日 周X）。 */
function doneDayLabel(date,weekday){
  if(!date)return "";
  const parts=String(date).split("-");
  if(parts.length!==3)return date;
  const month=Number(parts[1]),day=Number(parts[2]);
  const tail=(month+"月"+day+"日")+(weekday?" "+weekday:"");
  const today=doneTodayString();
  if(date===today)return "今天 · "+tail;
  if(today&&date===shiftDay(today,-1))return "昨天 · "+tail;
  return tail;
}
/** 「今天」的唯一来源：后端汇总里的 date，取不到时退回 dashboard.date，再退回本机日期。 */
function doneTodayString(){
  if(state.doneSummary&&state.doneSummary.date)return state.doneSummary.date;
  if(state.dashboard&&state.dashboard.date)return state.dashboard.date;
  return todayString();
}
/** 把 yyyy-MM-dd 往前 / 往后挪 n 天。 */
function shiftDay(date,delta){
  const parts=String(date).split("-");
  if(parts.length!==3)return "";
  const d=new Date(Number(parts[0]),Number(parts[1])-1,Number(parts[2]));
  d.setDate(d.getDate()+delta);
  const pad=n=>String(n).length<2?"0"+n:String(n);
  return d.getFullYear()+"-"+pad(d.getMonth()+1)+"-"+pad(d.getDate());
}
/** 该日期的星期几（中文），算不出来时返回空串。 */
function weekdayOf(date){
  const parts=String(date||"").split("-");
  if(parts.length!==3)return "";
  const d=new Date(Number(parts[0]),Number(parts[1])-1,Number(parts[2]));
  if(isNaN(d.getTime()))return "";
  return ["周日","周一","周二","周三","周四","周五","周六"][d.getDay()]||"";
}
/** 一组（某天）在汇总里的统计；没有汇总 / 没有这一天时返回 null。 */
function doneDayStat(date){
  const summary=state.doneSummary;
  if(!summary||!summary.days)return null;
  for(let i=0;i<summary.days.length;i++){if(summary.days[i].date===date)return summary.days[i]}
  return null;
}
/**
 * 分组头的统计文案：`完成 5 项 · P0 2 · 深度 1`。
 *
 * <p>统计来自后端汇总（全表口径），**当天的卡片只是被加载出来的那部分** ——
 * 两者可能不一致（列表被截断、或被检索筛过）。不一致时**以汇总为准**，
 * 并在文案里说明「本次加载 N 条」，而不是让两个数字在页面上各说各话。</p>
 */
function doneGroupStat(date,loadedCount){
  const stat=doneDayStat(date);
  if(!stat)return loadedCount?("本次加载 "+loadedCount+" 条"):"";
  const bits=["完成 "+stat.count+" 项"];
  if(stat.p0Count>0)bits.push("P0 "+stat.p0Count);
  if(stat.deepCount>0)bits.push("深度 "+stat.deepCount);
  if(stat.count!==loadedCount&&loadedCount>0)bits.push("本次加载 "+loadedCount+" 条");
  return bits.join(" · ");
}
/**
 * 渲染「已完成」泳道。
 *
 * <p>两种形态：有汇总 → 今天一组（高亮）+ 更早的一段（倒序，每段带日期头）；
 * 没有汇总 → 改动前的一整段倒序列表（降级，不做假承诺）。</p>
 */
function renderDoneLane(done,narrowed,emptyText){
  if(!done.length)return "<div class=\"empty\">"+emptyText+"</div>";
  // 降级：汇总取不到时不做分组，直接退回改动前的形态。宁可没有分组标题，
  // 也不能拿「已加载的这批」自己数出一个会少算的数字（见本段开头的约束 ①）。
  if(!state.doneSummary)return done.map(taskCard).join("");
  const today=doneTodayString();
  const isToday=task=>{
    // 今天这一组：真正完成的看 completedAt；已取消的没有 completedAt（后端置 NULL），
    // 退回 updatedAt —— 这条分支只在「今天取消」的条目上生效，不会把历史完成项捞进来。
    const stamp=task.status==="done"?task.completedAt:(task.completedAt||task.updatedAt);
    return !!stamp&&String(stamp).slice(0,10)===today;
  };
  const todays=done.filter(isToday);
  const earlier=done.filter(task=>!isToday(task));
  const html=[];
  // 今天这一组：无论有没有条目都渲染组头（空状态给一行轻提示）。
  // 「有头无项」比「整块消失」好理解 —— 后者会让人以为分组功能坏了。
  const todayStat=state.doneSummary.today||{};
  html.push("<div class=\"done-day-head is-today\">"
    +"<span class=\"ddh-label\">"+esc(doneDayLabel(today,weekdayOf(today)))+"</span>"
    +"<span class=\"ddh-count\">"+(todayStat.count||0)+" 项</span>"
    +"<span class=\"ddh-stat\">"+esc(doneTodayMeta(todayStat))+"</span>"
    +"</div>");
  if(todays.length){
    html.push("<div class=\"done-day-body\">"+todays.map(taskCard).join("")+"</div>");
  }else{
    html.push("<div class=\"done-day-empty\">今天还没有完成的任务。做完的卡片会落到这里。</div>");
  }
  // 更早的一段：只给一个分组头，不再逐天拆分（改动最小，用户要的是「今天」突出）。
  if(earlier.length){
    html.push("<div class=\"done-day-head is-earlier\">"
      +"<span class=\"ddh-label\">更早完成</span>"
      +"<span class=\"ddh-count\">"+earlier.length+" 项</span>"
      +"<span class=\"ddh-stat\">"+esc(narrowed?"已按当前条件筛选":"按完成时间倒序")+"</span>"
      +"</div>");
    html.push("<div class=\"done-day-body\">"+earlier.map(taskCard).join("")+"</div>");
  }
  // 今天取消的条数单独说一句：它不并入「完成 N 项」（取消不是成果），
  // 但也不能藏掉 —— 藏掉的话那条任务在界面上连痕迹都没有（与「已取消不单开泳道」同一考虑）。
  if(todayStat.canceledCount>0){
    html.push("<div class=\"done-day-canceled\">今天另取消 "+todayStat.canceledCount+" 项</div>");
  }
  return html.join("");
}
/** 今天那一组头部的细分统计：`P0 2 · 深度 1 · 最早 09:48，最晚 14:32`。 */
function doneTodayMeta(stat){
  if(!stat||!stat.count)return "";
  const bits=[];
  if(stat.p0Count>0)bits.push("P0 "+stat.p0Count);
  if(stat.p1Count>0)bits.push("P1 "+stat.p1Count);
  if(stat.deepCount>0)bits.push("深度 "+stat.deepCount);
  if(stat.firstAt&&stat.lastAt)bits.push(stat.firstAt===stat.lastAt?("完成于 "+stat.firstAt):("最早 "+stat.firstAt+"，最晚 "+stat.lastAt));
  if(stat.diffFromPreviousDay>0)bits.push("比昨天多 "+stat.diffFromPreviousDay+" 项");
  else if(stat.diffFromPreviousDay<0)bits.push("比昨天少 "+(-stat.diffFromPreviousDay)+" 项");
  return bits.join(" · ");
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
  // 排序只作用在**每条泳道内部**，不跨泳道搬卡片 —— 泳道就是状态，搬过去等于改了状态。
  const todo=sortLane(visible.filter(task=>task.status==="todo"));
  const doing=sortLane(visible.filter(task=>task.status==="doing"));
  const done=sortLane(visible.filter(task=>task.status==="done"||task.status==="canceled"));
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
    // 「筛选」与「检索」是仅剩的两个**会收窄列表的条件**，两句必须分别说清：
    // 只说其中一句会让另一句变成隐形条件（用户改了口径却看不出为什么条数没变）。
    // 分组已于 2026-09-17 移除，所以这里不再有「在「工作」分组里」那一句，
    // 提示条的显隐也就恢复了原来的规则：**只在筛选 / 检索生效时出现**。
    // （分组还在时它必须常显——默认视图被静默收窄到一半，是当时最危险的一种失效。）
    const scope=filter==="all"?"":"只显示「"+taskFilterName(filter)+"」";
    const searching=query?"搜索「"+esc(query)+"」":"";
    let text="";
    if(scope&&searching)text="当前"+scope+"，并在其中"+searching+"，共 "+visible.length+" 条。";
    else if(searching)text="当前在全部任务里"+searching+"，共 "+visible.length+" 条。";
    else if(scope)text="当前"+scope+"，共 "+visible.length+" 条。";
    // 按钮文案只说它会清掉的东西：筛选与检索它都清，所以两种都命中时要说「与搜索」。
    const clearLabel=scope&&searching?"清除筛选与搜索":searching?"清除搜索":"显示全部任务";
    filterNote.classList.toggle("hidden",!narrowed);
    filterNote.innerHTML=narrowed?("<span>"+text+"</span>"+"<button class=\"btn sm ghost\" data-action=\"clear-task-filter\">"+clearLabel+"</button>"):"";
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
  // 「已完成」泳道按天分组（2026-09-20）：用户要「一眼看清今天完成了哪些内容」。
  // 只有这条泳道分组 —— 待办 / 进行中是「接下来要做什么」，按天分组没有意义。
  $("taskLaneDone").innerHTML=renderDoneLane(done,narrowed,empty.done);
  // 截断说明挂在整个看板下方：它讲的是「列表不完整（只加载了前 100 条）」，不是某条泳道为空，
  // 塞进泳道里会被读成「这一列的任务丢了」。
  const boardNote=$("taskBoardNote");
  if(boardNote)boardNote.innerHTML=taskTruncationNote();
  // 搜索行的显隐 / 小圆点跟着 state 一起同步：收起态也要能看出「还在检索」。
  applySearchRow("task");
  // 排序条的选中态同理：每次重绘后必须回到 state 说的那一档，
  // 否则刷新一次之后「列表按哪种规则排的」就只能靠猜。
  applyTaskSort();
}
function taskCard(task){
  const ended=task.status==="done"||task.status==="canceled";
  const canceled=task.status==="canceled";
  // 优先级不放标题后面，而是钉在卡片右上角（.task-prio）：卡片只有约 350px 宽，
  // 中文标题一长就会把「P2」挤到自己独占一行，看起来像一段多出来的文字（截过图才看出来的）。
  // 留在标题后的只有「给标题加注释」的语义标签：逾期 / 阻塞 / 深度 / 顺延。
  const prio=task.priority==="P0"?"red":task.priority==="P1"?"amber":"";
  // 当日截止（未结束）的卡片额外加钩子 + 徽标，并把元信息里的「截止」写成「今天截止」。
  // 样式分两档（2026-09-20 用户指定）：**P0 → variant-b（左侧朱红条 + 淡红底 + 实心红徽标）；
  // P1 及以下 → variant-d（白底 + 朱红描边 + 柔光）**。
  // ⚠️ 分档靠 `data-prio` 这个属性交给 CSS 选择器，**不要在 JS 里拼 class 名**：
  //    拼出来的类名一改名，CSS 那边就静默不生效（页面上只是「高亮没了」，不报错）。
  const dueToday=isDueToday(task)&&!ended;
  const dueFlag=dueToday?'<span class="pill dtd">今天截止</span>':"";
  const dueMeta=dueToday?'<span class="dtd-day">今天截止 '+formatDate(task.due)+'</span>':"截止 "+formatDate(task.due);
  const flags=[dueFlag,task.overdue?'<span class="pill red">逾期</span>':"",task.blocking?'<span class="pill amber">阻塞</span>':"",task.deep?'<span class="pill blue">深度</span>':"",task.postponed>=3?'<span class="pill red">顺延≥3次</span>':""].join("");
  // 卡片自上而下三层：标题/元信息 → 操作按钮 → 内联编辑表单。
  // 编辑表单放**最底下**，与收藏卡片一致：点「编辑」不会把上面的按钮顶来顶去。
  // draggable 默认 false、由 mousedown 动态打开（见文件末尾 taskBoard 的绑定）：
  // 整卡常驻 draggable=true 会让编辑表单里的输入框没法用鼠标选中文本，Chrome / Firefox 都会。
  // 2026-09-17 做了两处「减法」，都是为了把卡片还给内容本身：
  //   ① **移除左侧的圆形状态按钮**（.status-button，用户明确要求「移除每个任务前面的圆圈按钮」）。
  //      在这之后任务页里的状态流转主要靠**拖动卡片**（跨级拖动会按状态机逐级走，见 dropTaskToLane），
  //      操作区里的「取消 / 恢复」与驾驶舱 Top3 的「开始 / 完成」是不依赖拖动的另外两条路径。
  //      注意：驾驶舱 Top3 仍用 nextStatus()，所以那个函数不能跟着一起删。
  //   ② **移除分组徽标**（工作 / 生活的工作台 → 随「移除分组」一起下线）。
  // 2026-09-17 第三轮（对齐 Ardot「看板任务管理界面」设计稿）加了**状态徽标**：
  //   已完成 → 绿圆白勾；已取消 → 灰圆白叉。它是用户点名要的「已完成泳道的卡片带勾选或对勾标记」。
  //   它与刚删掉的 .status-button 不是一回事：**纯展示、不可点**（点了不会有任何状态流转），
  //   所以不会把「状态靠拖动改」这件事重新变成歧义。
  //   ⚠️ 颜色用 currentColor，由 style.css 的 `#view-tasks .task-check` 按泳道给出 ——
  //     在内联 SVG 里写死色值的话，改泳道配色时徽标会静默停在旧颜色上。
  //   ⚠️ 它是 .tc-top 的第一个子元素，`> .task-prio` 仍是最后一个 —— 有断言盯着后者。
  const check=task.status==="done"
    ?'<span class="task-check" aria-hidden="true"><svg viewBox="0 0 24 24" width="18" height="18"><circle cx="12" cy="12" r="12" fill="currentColor"/><path d="M7 12.3l3.3 3.3L17 8.7" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg></span>'
    :canceled
      ?'<span class="task-check" aria-hidden="true"><svg viewBox="0 0 24 24" width="18" height="18"><circle cx="12" cy="12" r="12" fill="currentColor"/><path d="M8.7 8.7l6.6 6.6M15.3 8.7l-6.6 6.6" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round"/></svg></span>'
      :"";
  return `<div class="task-card${canceled?" is-canceled":""}${dueToday?" is-due-today":""}" draggable="false" data-task-id="${task.id}" data-status="${task.status}" data-prio="${task.priority}"><div class="tc-top">${check}<div class="grow"><div class="task-title${canceled?" strike":""}">${esc(task.title)} ${flags}</div><div class="task-meta">${statusName(task.status)} · ${dueMeta}${task.overdue?` · <span class="overdue-text">逾期 ${task.overdueDays} 天</span>`:""}${task.postponed?` · 已顺延 ${task.postponed} 次`:""}</div>${task.note?`<div class="task-meta task-note">备注：${esc(task.note)}</div>`:""}</div><span class="pill task-prio${prio?" "+prio:""}">${task.priority}</span></div>${attStrip(task.attachments,{owner:"task:"+task.id})}<div class="actions tc-acts"><button class="btn sm" data-action="edit" data-id="${task.id}">编辑</button><button class="btn sm" data-action="duplicate" data-id="${task.id}" title="新建一条同名任务，截止日期改为今天；附件一并带过去，原任务不动">复制</button>${!ended?`<button class="btn sm" data-action="postpone" data-id="${task.id}">顺延</button>`:""}<button class="btn sm" data-action="status" data-id="${task.id}" data-status="${task.status==="canceled"||task.status==="done"?"todo":"canceled"}">${ended?"恢复":"取消"}</button>${moveMenu("task",task.id)}<button class="btn sm danger" data-action="delete" data-id="${task.id}">删除</button></div></div>`;
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
// 垃圾桶图标（2026-09-18）：收录页两处删除入口共用同一份几何 ——
// 路径与 index.html 里 #trashButton（页签栏最右的「回收站」）**逐字一致**，
// 同一个含义在两个地方用两个形状，用户得重新学一遍；改一处就必须改另一处。
// ⚠️ 抽成函数而不是常量，是为了能被 check_frontend_render.js 的 FUNCS 抽出来注入
// （它按 `function name(` 抠源码，const 抠不到 → new Function 里必 ReferenceError）。
function trashIcon(){
  return '<svg viewBox="0 0 16 16" aria-hidden="true">'
    +'<path d="M2.5 4.5h11" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>'
    +'<path d="M6.2 4.5V3.3c0-.5.4-.9.9-.9h1.8c.5 0 .9.4.9.9v1.2" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>'
    +'<path d="M4.1 4.5l.62 8.2c.04.5.46.9.97.9h4.62c.51 0 .93-.4.97-.9l.62-8.2" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>'
    +'<path d="M6.6 7.1v3.8M9.4 7.1v3.8" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>'
    +'</svg>';
}
// 「收录」页把原来的「收集箱」和「整理确认」合成了一页，所以角标要同时算上待整理 + 待确认。
function renderInbox(){
  const pending=state.inbox,classify=state.classify,total=pending.length+classify.length;
  $("inboxBadge").textContent=total||"";$("inboxBadge").style.display=total?"inline-block":"none";
  const pendingCount=$("pendingInboxCount");if(pendingCount)pendingCount.textContent=pending.length?"（"+pending.length+" 条）":"";
  // 收录即整理，所以「待整理」平时是空的；空着就整块收起来，不给版面添噪音。
  // 但它必须存在：整理失败的条目状态既不是 pending 也不是 processed，没有这块就无处可去。
  const pendingCard=$("pendingCard");if(pendingCard)pendingCard.style.display=pending.length?"":"none";
  $("inboxList").innerHTML=pending.length?pending.map(item=>`<div class="list-item qcard" data-inbox-id="${item.id}" data-inbox-status="${item.status}"><div class="qc-head"><div class="task-title">${esc(item.raw)}</div></div><div class="task-meta">${inboxStatusName(item.status)} · ${item.source==="wecom"?"微信":"Web"} · ${item.createdAt}</div>${attStrip(item.attachments,{owner:"inbox:"+item.id,max:4})}<div class="qc-foot"><button class="btn ghost sm" data-action="reclassify-inbox" data-id="${item.id}">重新整理</button><button class="icon-btn danger" data-action="delete-inbox" data-id="${item.id}" aria-label="删除这条收录记录" title="删除这条收录记录（移入回收站，30 天内可恢复）">${trashIcon()}</button></div></div>`).join(""):'<div class="empty">没有待整理的条目。</div>';
}
// 各分类真正会落库的字段：task 用【日期+优先级】，schedule 用【日期+开始时间】，
// favorite / knowledge 只用标题。这里按需求 US-2.3「修改分类后字段表单联动切换」做显隐，
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
  // 「确认生成」已从本行移到卡片底部（.qc-foot），这里不再补 auto 列 ——
  // 补了会多出一条空轨道，把前面几个字段整体压窄。
  return cols.join(" ");
}
function syncClassifyFields(id,category){
  ["due","priority","start"].forEach(field=>{
    const el=$("confirm-"+field+"-"+id);
    if(el){const visible=classifyFieldVisible(category,field);el.classList.toggle("hidden",!visible);const wrap=el.closest(".qc-field");if(wrap)wrap.classList.toggle("hidden",!visible);}
  });
  const categorySelect=$("confirm-category-"+id);
  if(categorySelect){const grid=categorySelect.closest(".classify-form");if(grid)grid.style.gridTemplateColumns=classifyGridTemplate(category);}
}
// 「会进入待定区」的提示要跟着日期输入实时收敛：用户一旦填了日期，这条提示就过期了。
function syncPendingHint(id){
  const hint=$("confirm-pendinghint-"+id);if(!hint)return;
  const due=$("confirm-due-"+id);
  hint.classList.toggle("hidden",!!(due&&due.value));
}
// 与后端 InboxService.confirmHighConfidence 的筛选条件保持一致：
// 置信度 >= 0.70，且知识类必须已配置 Vault（否则后端会跳过，前端不能虚报数量）。
// **图片条目一律排除**：后端按 origin='image' 无条件跳过（截图里往往同时写着日期、
// 负责人、好几件事，0.9 的置信度也不代表读对了，必须用户看过才落库）。
// 少了这一条，按钮上会写「一键确认高置信度（3）」而点下去只生成 0 条 —— 数字骗人。
function batchConfirmableCount(){
  const vaultReady=!!(state.settings&&state.settings.obsidianVaultPath);
  return state.classify.filter(item=>{
    if(item.origin==="image")return false;
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
    // 单列卡片化（2026-09-16）：原来是一条 .list-item 里塞「原文 + 三四行灰色元信息 + 一整行无标签控件」，
    // 现在拆成卡片：原文 + 置信度 → 浅色建议区（字段名 + 控件）→ 说明 → 一个主操作。
    // ⚠️ 类名 task-title / task-meta / classify-form / confirm-pendinghint 刻意保留，
    // openSearchResult 与 check_frontend_render.js 都按它们定位，改名会静默失效。
    // 2026-09-17：卡片上原来的三行常驻说明（「AI 建议整理为…」/ 低置信度那句 /「识别到「每周」」）
    // 一并收进 .qc-head 里那枚问号的气泡，**按卡片条件拼装** —— 高置信度的卡看不到 70% 那段，
    // 非重复的卡看不到「每周」那段。低置信度的视觉信号改由琥珀色 .qc-flag 独立承担（不用再靠一行字）。
    // ⚠️ 气泡里「识别到「每周」：确认后会一次生成 ${ai.payload.repeatWeeks} 期」必须**原样保留**：
    // check_frontend_render.js 第 769 行是对 app.js 做源码匹配的，改写这串字样会让它变红。
    // ⚠️ 仍留在明文里的只有 #confirm-pendinghint-*（随日期输入实时收敛的状态提示）—— 它必须常驻，
    // 藏进问号就等于制造静默失效，且检查脚本按 class="task-meta hidden" 逐字匹配它。
    return `<div class="list-item qcard classify-card" data-inbox-id="${item.id}" data-inbox-status="processed"><div class="qc-head"><div class="task-title">${esc(item.raw)}</div><span class="qc-flag ${ai.needsConfirm?"is-warn":"is-ok"}">${Math.round(ai.confidence*100)}%</span><button class="q" type="button" aria-label="说明：这条 AI 建议怎么用"><span class="qi">?</span><span class="qt r"><span class="qt-title">这条建议怎么用</span><i>下面浅色区是 AI 给出的分类与字段，核对或修改后点「确认生成」</i>${ai.needsConfirm?`<i>这条低于 70%，不参与「一键批量确认」；沿用建议直接确认也可以</i>`:""}${ai.category==="schedule"&&ai.payload&&ai.payload.repeatWeeks>1?`<i>识别到「每周」：确认后会一次生成 ${ai.payload.repeatWeeks} 期独立日程，之后可单独修改</i>`:""}</span></button></div>${attStrip(item.attachments,{owner:"inbox:"+item.id,max:4})}<div class="qc-sugg"><div class="classify-form" style="grid-template-columns:${classifyGridTemplate(ai.category)}"><label class="qc-field"><span class="qc-label">分类</span><select id="confirm-category-${item.id}">${categoryOptions().map(c=>`<option value="${c}" ${c===ai.category?"selected":""}>${categoryName(c)}</option>`).join("")}</select></label><label class="qc-field"><span class="qc-label">标题</span><input id="confirm-title-${item.id}" value="${esc(ai.payload&&ai.payload.title||item.raw)}" maxlength="200"></label><label class="qc-field${classifyFieldVisible(ai.category,"due")?"":" hidden"}"><span class="qc-label">日期</span><input type="date" id="confirm-due-${item.id}" class="${classifyFieldVisible(ai.category,"due")?"":"hidden"}" value="${due}"></label><label class="qc-field${classifyFieldVisible(ai.category,"priority")?"":" hidden"}"><span class="qc-label">优先级</span><select id="confirm-priority-${item.id}" class="${classifyFieldVisible(ai.category,"priority")?"":"hidden"}">${["P0","P1","P2","P3"].map(p=>`<option ${p===(ai.payload&&ai.payload.priority||"P2")?"selected":""}>${p}</option>`).join("")}</select></label><label class="qc-field${classifyFieldVisible(ai.category,"start")?"":" hidden"}"><span class="qc-label">开始时间</span><input id="confirm-start-${item.id}" class="${classifyFieldVisible(ai.category,"start")?"":"hidden"}" value="${ai.payload&&ai.payload.start||""}" maxlength="5" placeholder="开始 HH:mm"></label><input type="hidden" id="confirm-eventtype-${item.id}" value="${ai.payload&&ai.payload.eventType?ai.payload.eventType:"meeting"}"></div></div>${ai.category==="schedule"?`<div class="task-meta${due?" hidden":""}" id="confirm-pendinghint-${item.id}">没选日期：确认后会先进入日程页的「待定时间」区，之后补上日期即可。</div>`:""}<div class="qc-foot"><button class="btn ghost sm" data-action="reclassify-inbox" data-id="${item.id}" title="用当前整理规则重新分类，并刷新建议标题与字段">重新整理</button><button class="icon-btn danger" data-action="delete-inbox" data-id="${item.id}" aria-label="删除这条收录记录" title="删除这条收录记录（移入回收站，30 天内可恢复）">${trashIcon()}</button><button class="btn primary sm qc-main" data-action="confirm-inbox" data-id="${item.id}">确认生成</button></div></div>`;
  }).join(""):'<div class="empty">没有待确认的条目。在上面输入框写一句话点「收录」，它会自动整理好放在这里。</div>';
}
// 日程卡片在「检索结果」和「待定时间」两处复用，只有时间描述与补充提示不同。
// 编辑走全局弹层（openEventEditor）：卡片上只留「编辑 / 移至 / 删除」三个按钮。
function eventCard(e,options){
  const pending=!!(options&&options.pending);
  const when=pending?"时间待定，还没排进具体哪天":(e.date||"");
  return `<div class="list-item" data-event-id="${e.id}"><div class="grow"><div class="task-title">${e.start?esc(e.start)+" ":""}${esc(e.title)} <span class="pill ${e.type==="deep_block"?"blue":e.type==="meeting"?"green":""}">${eventTypeName(e.type)}</span></div><div class="task-meta">${when}${e.end?" · "+(e.start||"")+" - "+e.end:""}</div>${attStrip(e.attachments,{owner:"event:"+e.id,max:4})}</div><div class="actions"><button class="btn sm" data-action="edit-event" data-id="${e.id}">编辑</button>${moveMenu("event",e.id)}<button class="btn sm danger" data-action="delete-event" data-id="${e.id}">删除</button></div></div>`;
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
  // 附件徽标（2026-09-19）：卡片只有 136px 宽，塞不下缩略图，所以只报「有几个」，
  // 缩略图与文件名在点开后的编辑弹层里。
  // ⚠️ 徽标挂在**标题行**而不是时间行，这是量出来的：时间行还要放「每周」，
  // 两个徽标一起会把「15:00 – 16:00」截成「15:00 – ...」，而时间是这一行的第一信息。
  // 完整测算写在 style.css 的 .ec-line 注释里 —— 不要再把它挪回时间行。
  const atts=attList(event.attachments);
  const attBadge=atts.length
    ?`<span class="ec-att" title="${esc(atts.length+" 个附件："+atts.map(attFileName).join("、"))}">${attClipIcon()}${atts.length}</span>`
    :"";
  const attTip=atts.length?` · ${atts.length} 个附件`:"";
  return `<button class="event-card ec-${unscheduled?"unscheduled":kind}" type="button" data-action="edit-event" data-id="${event.id}" data-event-id="${event.id}" title="${esc(event.title)}（${eventTypeName(event.type)}）${repeatTip}${attTip}—— 点一下展开编辑"><span class="ec-time">${time}${repeat}</span><span class="ec-line"><span class="ec-title">${esc(event.title)}</span>${attBadge}</span></button>`;
}
// （2026-09-18）原来这里还有个 eventEditPanel()，把每张日程卡片的编辑 / 移至 / 删除面板
// 渲染成所属行下方跨整行宽的一块（.wr-edits），一周最多铺 7 份空表单，把周视图撑得很高。
// 现在点卡片就是打开全局编辑弹层（openEventEditor），这一层连同 .wr-edits 样式一起删除 ——
// 周视图因此矮了一截，日程也和其他两个视图共用同一套编辑形态。
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
    return `<div class="week-row${isToday?" is-today":""}" data-day="${day}"><div class="wr-day"><span class="wr-weekday">${weekdayName(day)}</span><span class="wr-date">${monthDayText(day)}${isToday?" · 今天":""}</span></div><div class="wr-cards">${cards}</div></div>`;
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
function favoriteTime(value){
  const m=String(value||"").match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}:\d{2})/);
  if(!m)return String(value||"").slice(0,16);
  // 「今天」的年份只认后端下发的 dashboard.date，前端不另算一套（否则时区差会串天）。
  const thisYear=String((state.dashboard&&state.dashboard.date)||"").slice(0,4);
  const day=(thisYear&&m[1]===thisYear)?Number(m[2])+"月"+Number(m[3])+"日":m[1]+"-"+m[2]+"-"+m[3];
  return day+" "+m[4];
}
function renderFavorites(){
  const view=$("view-favorites");view.className="view favorite-theme-"+state.favoriteGrp+(state.tab==="favorites"?" active":"");
  // 空间切换器的选中态跟着 state 走（不能只在点击时切 class：全局搜索等入口会直接改 state.favoriteGrp）。
  // ⚠️ 选择器**必须**带 `.favorite-switch-wrap` 前缀、不能写成裸的 `.favorite-choice`：本项目对共用类的
  // 约定就是各带容器前缀。2026-09-17 之前任务页的分组切换器抄了同一套类名，裸选择器会把它的
  // 三个按钮一起选进来、按 data-grp（任务按钮上不存在）把它们全部清成非选中 ——
  // 筛选与指示器互相矛盾，而且控制台一个字都不报。那个切换器已经移除，但前缀继续留着。
  document.querySelectorAll(".favorite-switch-wrap .favorite-switch .favorite-choice")
    .forEach(b=>b.classList.toggle("active",b.dataset.grp===state.favoriteGrp));
  const list=state.favorites;
  $("favoriteList").innerHTML=list.length?list.map(m=>{
    const tags=(m.tags||[]).map(t=>`<span class="pill">${esc(t)}</span>`).join("");
    // 收藏的 title 是后端从 content 裁出来的（FavoriteService.shortTitle，裁 24 字不拼省略号），
    // 所以 24 字以内的收藏标题与正文**完全相等**，两层都渲染就是同一句话显示两遍。
    // 只有用户手动改过标题（两者不同）时才需要正文这一层。
    // 另：正文现在是可空的（只有标题的「速查信息」也能存），所以正文为空时必须整层不渲染——
    // 否则会留下一个空的 .favorite-body 占位，卡片底部凭空多出一段空白。
    const contentText=String(m.content||"").trim();
    const showBody=!!contentText&&contentText!==String(m.title||"").trim();
    // 附件行放在正文 / 链接之下、操作行之上（2026-09-19）：附件是**内容的一部分**，
    // 跟标签、按钮那些「元信息」不是一类东西，塞到最下面就变成操作区的一部分了。
    // 它由 attStrip 负责，没有附件时返回空串，卡片高度与以前完全一致。
    return `<div class="favorite-card cat-${m.grp} ${m.pinned?"pinned":""} ${m.status==="archived"?"archived":""}" data-favorite-id="${m.id}"><div class="favorite-kicker"><span class="favorite-dot"></span><span>${m.grp==="work"?"工作":"生活"}</span><span class="favorite-sep">·</span><span>${esc(favoriteTime(m.updatedAt||m.createdAt))}</span>${m.status==="archived"?'<span class="favorite-arch">已归档</span>':""}</div><div class="favorite-title">${m.pinned?'<span class="favorite-pin">置顶</span>':""}${esc(m.title)}</div>${showBody?`<div class="favorite-body">${esc(m.content)}</div>`:""}${m.url?`<div class="favorite-link"><a href="${esc(m.url)}" target="_blank" rel="noopener">${esc(m.url)}</a></div>`:""}${attStrip(m.attachments,{owner:"favorite:"+m.id})}<div class="favorite-foot"><div class="favorite-tags">${tags}</div><div class="actions favorite-acts"><button class="btn sm ghost" data-action="pin-favorite" data-id="${m.id}">${m.pinned?"取消置顶":"置顶"}</button><button class="btn sm ghost" data-action="archive-favorite" data-id="${m.id}">${m.status==="archived"?"恢复":"归档"}</button><button class="btn sm ghost" data-action="edit-favorite" data-id="${m.id}">编辑</button>${moveMenu("favorite",m.id,true)}<button class="btn sm ghost danger" data-action="delete-favorite" data-id="${m.id}">删除</button></div></div></div>`;
  }).join(""):"<div class=\"empty\">当前空间没有收藏。随手记一条链接、班车或家里要买的东西。</div>";
  // 收藏的检索状态也要写在页面上：2026-09-12 起输入框会被收起，没有这条就变成
  // 「列表是筛过的但看不出为什么」—— 这一条是这次改版**必须补**的，以前输入框常驻所以不需要。
  const note=$("favoriteSearchNote");
  if(note){
    const q=state.favoriteQuery||"";
    note.classList.toggle("hidden",!q);
    note.innerHTML=q?`<span>正在当前空间里搜索「${esc(q)}」，共 ${list.length} 条${state.favoriteArchived?"（含已归档）":""}。</span><button class="btn sm ghost" data-action="clear-favorite-search">清除搜索</button>`:"";
  }
  applySearchRow("favorite");
}
// 切收藏空间。加幂等守卫：值没变就不必重新取数。
// 守卫还有一个更实际的作用 —— 任何一次误调（比如委托绑错元素、取到 undefined）
// 都会当场变成「一次多余的 refreshAll」，把整页请求数抬高一倍；
// 有守卫时至少「值没变」这一类不会产生副作用，问题更容易在开发阶段暴露。
function setFavoriteGrp(grp){
  if(grp!=="all"&&grp!=="work"&&grp!=="life")return; // 非法值直接忽略，绝不写进 state
  if(state.favoriteGrp===grp)return;
  state.favoriteGrp=grp;
  refreshAll().catch(error=>toast(error.message));
}
// ===== 收藏编辑弹层（2026-09-17）=====
// 原来收藏的编辑面板是卡片内联的 .edit-panel（#favorite-edit-{id}，靠 classList.toggle("open") 展开），
// 宽度被 .favorite-grid 的列宽锁死，正文只有约 285×80px 却要装 4000 字。现在整条链路只剩这一个
// 编辑入口，所以只需要记住「正在编辑哪一条」；保存前一律按 id 从 state.favorites 现取，
// 而不是在打开时缓存整条对象 —— 否则列表刷新之后弹层还拿着旧快照，会把别人的改动覆盖回去。
let favoriteEditingId=null;
// 打开那一刻的原值，用来判断「有没有未保存的改动」。必须有这一层：Esc 和点遮罩都能关掉弹层，
// 少了它就会静默丢掉刚敲的正文 —— 本项目最忌讳的「静默无反应」，在这条链路上等价于「静默丢失」。
let favoriteEditorSnapshot=null;
let favoriteEditorSaving=false;
function favoriteEditorOpen(){return $("favoriteEditorMask").classList.contains("open")}
function favoriteEditorSyncCount(){
  const box=$("favoriteEditContent"),label=$("favoriteEditCount");
  if(box&&label)label.textContent=box.value.length+" / 4000";
}
function favoriteEditorDirty(){
  if(!favoriteEditorSnapshot||!favoriteEditorOpen())return false;
  return $("favoriteEditTitle").value.trim()!==favoriteEditorSnapshot.title
    ||$("favoriteEditContent").value.trim()!==favoriteEditorSnapshot.content
    ||$("favoriteEditTags").value.trim()!==favoriteEditorSnapshot.tags
    ||$("favoriteEditGrp").value!==favoriteEditorSnapshot.grp;
}
function openFavoriteEditor(id){
  // 列表是筛过的（空间 / 检索 / 含归档），找不到就说明这条已经不在当前视图里。
  // 不能静默 return —— 用户点的是自己看得见的按钮，什么都不发生就是「点了没反应」。
  const favorite=(state.favorites||[]).find(m=>String(m.id)===String(id));
  if(!favorite){toast("这条收藏已不在当前列表里，请刷新后再试");return}
  favoriteEditingId=favorite.id;
  $("favoriteEditTitle").value=favorite.title||"";
  $("favoriteEditContent").value=favorite.content||"";
  $("favoriteEditTags").value=(favorite.tags||[]).join(",");
  // 只给 work / life 两档，没有「自动」：自动分组是**创建时**判定一次的规则，
  // 编辑面板里再放一个「自动」等于每次保存都把用户手选的分组重算一遍。
  $("favoriteEditGrp").value=favorite.grp==="life"?"life":"work";
  favoriteEditorSnapshot={title:$("favoriteEditTitle").value.trim(),content:$("favoriteEditContent").value.trim(),
    tags:$("favoriteEditTags").value.trim(),grp:$("favoriteEditGrp").value};
  favoriteEditorSyncCount();
  // 把这条收藏已有的附件灌进附件区（2026-09-19）：不灌的话用户会以为附件丢了，
  // 而列表上明明看得见缩略图。附件列表直接用列表接口下发的那一份，不再多发一个请求。
  mediaSeed("edit-favorite",favorite.attachments);
  $("favoriteEditorMask").classList.add("open");
  document.body.classList.add("favorite-editing");
  // 焦点：正文有内容就接着改正文（绝大多数场景），只写了标题的「速查信息」才落在标题上。
  // 光标推到末尾，省掉一次手工移动。
  const first=$("favoriteEditContent").value.trim()?$("favoriteEditContent"):$("favoriteEditTitle");
  first.focus();
  if(first.setSelectionRange)first.setSelectionRange(first.value.length,first.value.length);
}
function closeFavoriteEditor(){
  if(!favoriteEditorOpen())return;
  $("favoriteEditorMask").classList.remove("open");
  document.body.classList.remove("favorite-editing");
  favoriteEditingId=null;
  favoriteEditorSnapshot=null;
  // 附件区跟着弹层一起清空：留着的话下次打开别的收藏会先闪一眼上一条的附件。
  mediaClear("edit-favorite");
}
// 取消 / 关闭 × / 点遮罩 / Esc 四个出口全部走这里，只有「保存成功」那条路直接调 closeFavoriteEditor ——
// 保证四条出口的确认行为一模一样，不会有哪一条悄悄把正文丢掉。
function requestCloseFavoriteEditor(){
  if(!favoriteEditorOpen())return;
  if(favoriteEditorDirty()&&!confirm("正文有改动还没保存，确定放弃并关闭？"))return;
  closeFavoriteEditor();
}
async function saveFavoriteEditor(){
  if(favoriteEditorSaving)return; // 连点两次「保存」不该发两次请求
  const id=favoriteEditingId;
  if(id===null||id===undefined)return;
  const title=$("favoriteEditTitle").value.trim();
  const content=$("favoriteEditContent").value.trim();
  // 与后端 FavoriteService.requireTitleOrContent 同一条规则；在这里先拦一次只为省掉一轮白等的网络。
  if(!title&&!content){toast("标题和正文至少写一项");$("favoriteEditTitle").focus();return}
  const button=$("favoriteEditorSave");
  favoriteEditorSaving=true;
  if(button){button.disabled=true;button.textContent="保存中…"}
  try{
    // 空串要**原样**发出去，不能转成 null：后端 update 是局部更新（字段为 null = 不动这个字段），
    // 把「被清空的标题」发成 null，会让「清空标题 → 回落到正文前 24 字」这条既有规则失效。
    await WorkbenchApi.updateFavorite(id,{title:title,content:content,
      tags:$("favoriteEditTags").value.trim(),grp:$("favoriteEditGrp").value});
    closeFavoriteEditor();
    await refreshAll();
    toast("收藏已更新");
  }catch(error){
    // 失败时**不关弹层**：关掉就等于把用户刚敲的正文扔了，比报错本身严重得多。
    toast(error.message);
  }finally{
    favoriteEditorSaving=false;
    if(button){button.disabled=false;button.textContent="保存收藏"}
  }
}
// ===== 任务 / 日程编辑弹层（2026-09-18）=====
// 与收藏弹层同构（2026-09-17 落地的那套）：同一份骨架类 .editor-mask / .editor-modal / .mm-*，
// 四个出口（取消 / × / 点遮罩 / Esc）全部走 requestClose*，保存只有一条路 save*，
// 失败不关弹层。变量名保持「一套弹层一套状态」而不是共用，是为了三处能各自独立演进
// （收藏那侧已有 57 条冒烟断言钉着，不去动它的内部结构）。
// 内容全部来自原来的卡片内联 .edit-panel，字段一个没变；变的只是承载形态 ——
// 任务卡片只有约 273~350px 宽、备注原来挤在一个单行 input 里，日程更极端（卡片 136px）。
let taskEditingId=null;
let taskEditorSnapshot=null;
let taskEditorSaving=false;
let eventEditingId=null;
let eventEditorSnapshot=null;
let eventEditorSaving=false;

function taskEditorOpen(){return $("taskEditorMask").classList.contains("open")}
function eventEditorOpen(){return $("eventEditorMask").classList.contains("open")}
function taskEditorDirty(){
  if(!taskEditorOpen()||!taskEditorSnapshot)return false;
  return $("taskEditTitle").value.trim()!==taskEditorSnapshot.title
    ||$("taskEditPriority").value!==taskEditorSnapshot.priority
    ||$("taskEditDue").value!==taskEditorSnapshot.due
    ||$("taskEditNote").value.trim()!==taskEditorSnapshot.note
    ||$("taskEditDeep").checked!==taskEditorSnapshot.deep
    ||$("taskEditBlocking").checked!==taskEditorSnapshot.blocking;
}
function eventEditorDirty(){
  if(!eventEditorOpen()||!eventEditorSnapshot)return false;
  return $("eventEditTitle").value.trim()!==eventEditorSnapshot.title
    ||$("eventEditType").value!==eventEditorSnapshot.type
    ||$("eventEditDate").value!==eventEditorSnapshot.date
    ||$("eventEditStart").value!==eventEditorSnapshot.start
    ||$("eventEditEnd").value!==eventEditorSnapshot.end;
}
function taskEditorSyncCount(){
  const box=$("taskEditNote"),label=$("taskEditCount");
  if(box&&label)label.textContent=box.value.length+" / "+box.maxLength;
}
function openTaskEditor(id){
  // 任务页的取数带 keyword（检索在服务端做），所以列表可能是收窄过的 ——
  // 找不到就明说，不能静默 return：用户点的是自己看得见的那张卡片上的按钮。
  const task=(state.tasks||[]).find(item=>String(item.id)===String(id));
  if(!task){toast("这条任务已不在当前列表里，请刷新后再试");return}
  taskEditingId=task.id;
  $("taskEditTitle").value=task.title||"";
  $("taskEditPriority").value=task.priority||"P2";
  $("taskEditDue").value=task.due||"";
  $("taskEditNote").value=task.note||"";
  $("taskEditDeep").checked=!!task.deep;
  $("taskEditBlocking").checked=!!task.blocking;
  taskEditorSnapshot={title:$("taskEditTitle").value.trim(),priority:$("taskEditPriority").value,
    due:$("taskEditDue").value,note:$("taskEditNote").value.trim(),
    deep:$("taskEditDeep").checked,blocking:$("taskEditBlocking").checked};
  taskEditorSyncCount();
  // 附件区：用列表接口已经下发的那一份，不再多发请求（理由同 openFavoriteEditor）。
  mediaSeed("edit-task",task.attachments);
  // 「移至」菜单的开合是全局单例状态（state.moveOpen）：上一个 id 的菜单要是还开着，
  // 弹层里新渲染的那份会当场是展开态。
  state.moveOpen=null;
  $("taskEditorMask").classList.add("open");
  document.body.classList.add("favorite-editing");
  // 焦点落在标题：任务的主体就是标题。收藏那边相反（落正文），因为收藏常常只有正文没有标题。
  $("taskEditTitle").focus();
  const title=$("taskEditTitle");
  if(title.setSelectionRange)title.setSelectionRange(title.value.length,title.value.length);
}
function closeTaskEditor(){
  if(!taskEditorOpen())return;
  $("taskEditorMask").classList.remove("open");
  document.body.classList.remove("favorite-editing");
  taskEditingId=null;
  taskEditorSnapshot=null;
  mediaClear("edit-task");
}
function requestCloseTaskEditor(){
  if(!taskEditorOpen())return;
  if(taskEditorDirty()&&!confirm("改动还没保存，确定放弃并关闭？"))return;
  closeTaskEditor();
}
async function saveTaskEditor(){
  if(taskEditorSaving)return; // 连点两次「保存」不该发两次请求
  const id=taskEditingId;
  if(id===null||id===undefined)return;
  const title=$("taskEditTitle").value.trim();
  // 后端 TaskUpdateRequest 的 title 是必填，这里先拦一次只为省掉一轮白等的网络。
  if(!title){toast("标题不能为空");$("taskEditTitle").focus();return}
  const button=$("taskEditorSave");
  taskEditorSaving=true;
  if(button){button.disabled=true;button.textContent="保存中…"}
  try{
    await WorkbenchApi.updateTask(id,{title:title,priority:$("taskEditPriority").value,
      due:$("taskEditDue").value||"",note:$("taskEditNote").value.trim(),
      deep:$("taskEditDeep").checked,blocking:$("taskEditBlocking").checked});
    closeTaskEditor();
    await refreshAll();
    toast("任务已更新");
  }catch(error){
    // 失败时**不关弹层**：关掉就等于把用户刚敲的备注扔了，比报错本身严重得多。
    toast(error.message);
  }finally{
    taskEditorSaving=false;
    if(button){button.disabled=false;button.textContent="保存任务"}
  }
}
// 周视图只加载「可见的那几周」，待定日程在另一路数据（pendingEvents）里 ——
// 两处都要找，少找一处就会出现「点了卡片没反应」（那条其实在视图里，只是不在这份数组里）。
function findEventById(id){
  return (state.events||[]).concat(state.pendingEvents||[]).find(item=>String(item.id)===String(id));
}
function openEventEditor(id){
  const event=findEventById(id);
  if(!event){toast("这条日程已不在当前视图里，请刷新后再试");return}
  eventEditingId=event.id;
  // 类型选项按 eventTypeName() 现渲染，与原来内联面板一致 ——
  // 不要在 index.html 里再写死一份中文名，两处一漂下拉里就会出现没人认识的选项。
  $("eventEditType").innerHTML=["meeting","deep_block","other"]
    .map(type=>`<option value="${type}" ${type===event.type?"selected":""}>${eventTypeName(type)}</option>`).join("");
  $("eventEditTitle").value=event.title||"";
  $("eventEditDate").value=event.date||"";
  $("eventEditStart").value=event.start||"";
  $("eventEditEnd").value=event.end||"";
  // 片子的选中态是**上一次打开**留下的 class，回填完必须重画一次，
  // 否则会出现「打开下一条时，选中态还停在上一条的时刻上」（不报错、只有眼睛能看见）。
  paintTimeChips($("eventEditStart"));paintTimeChips($("eventEditEnd"));
  $("eventEditHint").textContent=event.date||"时间待定";
  // 待定日程（date 为空）要告诉用户「补上日期就会排进那一天」——
  // 这句原来挂在内联面板里（eventCard 的 pending 分支），面板删掉后不能就这么丢了。
  $("eventEditPendingNote").classList.toggle("hidden",!!event.date);
  // 重复日程要写清「改这里只影响这一期」—— 这正是当初把「每周 X」物化成独立记录的原因。
  const repeat=$("eventEditRepeatNote");
  if(event.repeatTotal>1){
    repeat.innerHTML=`这是「每周」重复日程（共 ${event.repeatTotal} 期）。改这里<strong>只影响这一期</strong>，其余几期不受影响。`;
    repeat.classList.remove("hidden");
  }else{
    repeat.innerHTML="";
    repeat.classList.add("hidden");
  }
  // 「移至」按当前这一期的 id 渲染：底栏左侧是个空容器，打开时注入（并顺手清掉上次的开合状态）。
  state.moveOpen=null;
  $("eventEditorMoveWrap").innerHTML=moveMenu("event",event.id);
  eventEditorSnapshot={title:$("eventEditTitle").value.trim(),type:$("eventEditType").value,
    date:$("eventEditDate").value,start:$("eventEditStart").value,end:$("eventEditEnd").value};
  // 附件区：待定日程要从 pendingEvents 里找（findEventById 已经找过了），那份数据同样带 attachments。
  mediaSeed("edit-event",event.attachments);
  $("eventEditorMask").classList.add("open");
  document.body.classList.add("favorite-editing");
  $("eventEditTitle").focus();
  const title=$("eventEditTitle");
  if(title.setSelectionRange)title.setSelectionRange(title.value.length,title.value.length);
}
function closeEventEditor(){
  if(!eventEditorOpen())return;
  $("eventEditorMask").classList.remove("open");
  document.body.classList.remove("favorite-editing");
  eventEditingId=null;
  eventEditorSnapshot=null;
  // 菜单状态跟着弹层一起清：留着的话下次打开这条会直接是展开态。
  state.moveOpen=null;
  $("eventEditorMoveWrap").innerHTML="";
  mediaClear("edit-event");
}
function requestCloseEventEditor(){
  if(!eventEditorOpen())return;
  if(eventEditorDirty()&&!confirm("改动还没保存，确定放弃并关闭？"))return;
  closeEventEditor();
}
async function saveEventEditor(){
  if(eventEditorSaving)return;
  const id=eventEditingId;
  if(id===null||id===undefined)return;
  const title=$("eventEditTitle").value.trim();
  if(!title){toast("标题不能为空");$("eventEditTitle").focus();return}
  const wasPending=(state.pendingEvents||[]).some(e=>String(e.id)===String(id));
  const date=$("eventEditDate").value||"";
  const button=$("eventEditorSave");
  eventEditorSaving=true;
  if(button){button.disabled=true;button.textContent="保存中…"}
  try{
    const result=await WorkbenchApi.updateEvent(id,{title:title,type:$("eventEditType").value,
      date:date,start:$("eventEditStart").value||"",end:$("eventEditEnd").value||""});
    closeEventEditor();
    await refreshAll();
    // 三种结果各说各的（沿用原来内联面板的文案）：有字段损失 > 放回待定区 > 排进某一天。
    if(result.warnings&&result.warnings.length)toast("已保存，但注意："+result.warnings.join("；"),5200);
    else if(!date)toast("已放回「待定时间」区，补上日期后就会排进那一天");
    else if(wasPending)toast("已排进 "+date);
    else toast("日程已更新");
  }catch(error){
    toast(error.message);
  }finally{
    eventEditorSaving=false;
    if(button){button.disabled=false;button.textContent="保存日程"}
  }
}
// 弹层里的「删除」：日程走软删（回收站 30 天），所以按设计规范不弹二次确认，
// 但 toast 必须写清去哪儿找回 —— 否则用户不知道还有撤销。
async function deleteEditingEvent(){
  const id=eventEditingId;
  if(id===null||id===undefined)return;
  closeEventEditor();
  await WorkbenchApi.deleteEvent(id);
  await refreshAll();
  toast("已移入回收站，30 天内可恢复");
}
function timelineTypeName(type){return {inbox:"收录",task:"事件",praise:"完成",favorite:"收藏",pomo:"番茄",plan:"计划"}[type]||"记录"}
const TL_COLORS={inbox:"#378ADD",task:"#534AB7",praise:"#639922",favorite:"#BA7517","favorite:work":"#6C9D81","favorite:life":"#D98267",pomo:"#0F6E56",plan:"#888780"};
function dayString(date){return date.getFullYear()+"-"+String(date.getMonth()+1).padStart(2,"0")+"-"+String(date.getDate()).padStart(2,"0")}
function localDay(offset){const d=new Date();d.setDate(d.getDate()+offset);return dayString(d)}
// 不能用 new Date("2026-09-11")：它按 UTC 解析，东八区会退回前一天。
function parseDay(value){const parts=String(value||"").split("-");if(parts.length!==3)return null;const y=parseInt(parts[0],10),m=parseInt(parts[1],10),d=parseInt(parts[2],10);if(!y||!m||!d)return null;const date=new Date(y,m-1,d);return isNaN(date.getTime())?null:date}
// 「今天」以后端下发的 dashboard.date 为准（它按用户配置时区算），本地时钟只做兜底。
function todayString(){return (state.dashboard&&state.dashboard.date)||localDay(0)}

// ===== 「默认带入当日」的那几个日期字段（2026-09-20，任务截止日期先接）=====
// 为什么值得单独写一层：这些字段一旦带了默认值，**「值是否为空」就再也不能代表「用户填过没有」**。
// 直接放个默认值会静默破坏两件事：
//   ① AI「识别填表」的规则是「只填空着的字段」（见 prefillFromParsed）—— 字段永远不空，
//      于是从截图里识别出来的日期会被**悄悄丢掉**，用户看到的是「AI 没识别出来」。
//   ② 页面开着跨过半夜，默认值还是昨天，用户没改就建成了一条「昨天到期」的任务。
// 所以这里把「上一次自动写进去的值」记在 state.autoDefaults 里：等于它的 = 还是默认值（可覆盖、可刷新），
// 不等于它的 = 用户自己填过或**清空过**（清空 = 不要截止日期，算用户的选择）—— 一个字都不动。
// ⚠️ 用户手动选的日期如果恰好等于默认值，会被当成默认值 —— 二者本来就无法区分，且值相同，无实际影响。
//
// 三个入口的语义**不一样，不要合成一个**（2026-09-20 的验证脚本分别抓过这三条）：
//   initAutoDefault    首次进入页面：只在字段还是空的时候写入；已有值（浏览器自动填充）不动。
//   resetAutoDefault   成功创建一条之后：无条件写回当日 —— 这是「表单复位」，与标题/备注清空同一个语义。
//   refreshAutoDefault 展开表单时：只有值仍是我们上次写的那个才刷新（跨零点）——
//                      用 initAutoDefault 会漏掉跨零点刷新，用 resetAutoDefault 会把用户清空的日期又填回来。
// 日程的时间（开始 / 结束）也走同一套：进页面写入、创建成功后复位、切回日程页时刷新。
// ⚠️ 只有**新建表单**这两个字段有默认值，**编辑弹层的 eventEditStart / eventEditEnd 不接**
//    —— 编辑的是已有日程，给它套默认值会把用户真实的时间抹掉。
function defaultOf(field){
  if(field==="taskDue")return todayString();
  if(field==="eventDate")return defaultEventDate();
  if(field==="eventStart")return defaultEventStart();
  if(field==="eventEnd")return clockText(clockMinutes(defaultEventStart())+60);
  return null;
}
function writeAutoDefault(field){
  const el=$(field),next=defaultOf(field);
  if(!el||!next)return;
  el.value=next;state.autoDefaults[field]=next;
  // 快捷片的选中态要跟着默认值一起变，否则刚复位完页面上的高亮还停在上一格。
  paintTimeChips(el);
}
function initAutoDefault(field){
  const el=$(field);
  if(!el||!defaultOf(field))return;
  if(!el.value)writeAutoDefault(field);
}
function resetAutoDefault(field){
  if(defaultOf(field))writeAutoDefault(field);
}
function refreshAutoDefault(field){
  const el=$(field);
  if(!el||!defaultOf(field))return;
  if(el.value===state.autoDefaults[field])writeAutoDefault(field);
}
// 「这个字段当前只是我们的默认值，不算用户填的」—— prefillFromParsed 靠它决定能不能覆盖。
function isAutoDefault(field){
  const el=$(field);return !!el&&!!el.value&&el.value===state.autoDefaults[field]
}
// ===== 时间框的「一刻钟快捷片」（2026-09-20）=====
// 原生 <input type="time"> 选分钟要面对 60 项列表、键盘 ↑↓ 还是 ±1 分钟 —— 想选 09:15 得按 15 下。
// 一天里绝大多数安排都落在整刻上，所以给每个时间框挂一排 00 / 15 / 30 / 45：点一下只改分钟、小时不动。
// 原生框保留（库里已经有非整刻的时间：AI「识别填表」识别出来的、手输的），这里只加捷径、不做替换。
//
// 三条不写成注释就会踩的规则：
//   ① `syncEndToStart` 只在「结束为空」或「结束早于等于开始」时才动结束时间。用户手动设过的
//      合理区间一律不覆盖 —— 「我设了 09:00–12:00，改个开始就被改成 10:00」比不联动更糟。
//   ② 点快捷片 = 用户显式选过 → 值不再等于 state.autoDefaults → AI 识别出的时间不会覆盖它（对的）。
//      但**同步出来的结束时间是我们替用户算的**，必须写回 autoDefaults，
//      否则「识别填表」会以为用户手填过结束时间，把识别结果丢掉。
//   ③ 快捷片的选中态跟着输入框的真实值走：手输 09:20 时四个片子都不亮 —— 那是「非整刻」这个事实的显示。
const TIME_MINUTES=["00","15","30","45"];
const TIME_PAIRS=[["eventStart","eventEnd"],["eventEditStart","eventEditEnd"]];
// 「HH:MM」→ 当天分钟数。非法输入返回 null，**不要静默当成 0**（那会把 09:xx 变成 00:xx）。
function clockMinutes(value){
  const m=/^(\d{1,2}):(\d{2})$/.exec(String(value||""));
  if(!m)return null;
  const h=Number(m[1]),mi=Number(m[2]);
  if(h>23||mi>59)return null;
  return h*60+mi;
}
// 分钟数 → 「HH:MM」。按 24h 回绕，所以「23:45 加一小时」得 00:45 而不是 24:45。
function clockText(total){
  const t=((total%1440)+1440)%1440;
  const h=Math.floor(t/60),mi=t%60;
  return (h<10?"0":"")+h+":"+(mi<10?"0":"")+mi;
}
// 「下一个最近的一刻钟整点」：10:53 → 11:00；10:45 整 → 10:45。
// 读本机时钟而不是 dashboard.date：默认值的**日期**跟后端口径（todayString），
// 但「现在几点」本来就只能读本机时钟 —— 原生时间框也是按本机时钟理解的。
// ⚠️ 23:50 之后「下一个整刻」会跨到次日 00:00，而日期默认是今天 → 会生成一条「今天 00:00」
//    的过去日程，所以封顶在 23:45。
function defaultEventStart(){
  const now=new Date();
  const total=now.getHours()*60+now.getMinutes()+(now.getSeconds()>0?1:0);
  return clockText(Math.min(Math.ceil(total/15)*15,23*60+45));
}
function timePairOf(id){
  const pair=TIME_PAIRS.find(p=>p[0]===id);
  return pair||null;
}
function timeChipsHtml(){
  // 片子上只写「00 / 15 / 30 / 45」（带上冒号会更挤，且这一排的语境就是分钟）；
  // 纯数字按钮的语义靠 aria-label 补上，别让读屏用户只听到「零 零」。
  return TIME_MINUTES.map(m=>`<button type="button" class="tp-chip" data-time-min="${m}" aria-label="把分钟改成 ${m} 分">${m}</button>`).join("");
}
// ⚠️ 必须用 `closest(".tp")` 而不是 `input.parentNode.querySelector(".tp-chips")`。
//    writeAutoDefault 对**每个**自动默认值字段都会调用 paintTimeChips，其中 eventDate 并不是
//    时间框 —— 它的 parentNode 是 #composerBodyEvent，那里头装着两个 .tp-chips，
//    `querySelector` 会取到**第一个**（也就是「开始时间」的那一排），再用日期值 "2026-09-20"
//    去比对分钟 → 把开始时间的选中态整排清掉。这个 bug 不报错、控制台一声不响，
//    只在「创建一条日程之后」露出来（那时 resetAutoDefault 的顺序让 eventDate 排在最后）。
function timeChipsOf(input){
  if(!input||!input.closest)return null;
  const tp=input.closest(".tp");
  return tp?tp.querySelector(".tp-chips"):null;
}
function paintTimeChips(input){
  const chips=timeChipsOf(input);
  if(!chips)return;
  const mi=String(input.value||"").split(":")[1]||"";
  Array.prototype.forEach.call(chips.querySelectorAll(".tp-chip"),function(btn){
    btn.classList.toggle("active",btn.getAttribute("data-time-min")===mi);
  });
}
function applyTimeChip(input,minute){
  if(!input)return;
  const base=clockMinutes(input.value);
  // 输入框为空时按默认开始时间的小时算 —— 否则点一下芯片会得到「00:15」这种莫名其妙的零点时刻。
  const hour=base===null?Math.floor(clockMinutes(defaultEventStart())/60):Math.floor(base/60);
  input.value=clockText(hour*60+Number(minute));
  paintTimeChips(input);
  syncEndToStart(input.id);
}
function syncEndToStart(startId){
  const pair=timePairOf(startId);
  if(!pair)return;
  const start=$(pair[0]),end=$(pair[1]);
  if(!start||!end||!start.value)return;
  const s=clockMinutes(start.value);
  if(s===null)return;
  const e=clockMinutes(end.value);
  if(e!==null&&e>s)return;
  end.value=clockText(s+60);
  if(defaultOf(pair[1]))state.autoDefaults[pair[1]]=end.value;
  paintTimeChips(end);
}
function bindTimeChips(){
  Array.prototype.forEach.call(document.querySelectorAll(".tp"),function(tp){
    const input=tp.querySelector('input[type="time"]');
    const chips=tp.querySelector(".tp-chips");
    if(!input||!chips)return;
    chips.innerHTML=timeChipsHtml();
    chips.addEventListener("click",function(event){
      const btn=event.target&&event.target.closest?event.target.closest("[data-time-min]"):null;
      if(!btn)return;
      applyTimeChip(input,btn.getAttribute("data-time-min"));
      // 焦点还给输入框：连点两个片子不用重新点框，键盘用户也能接着用 ↑↓ 微调。
      input.focus();
    });
    input.addEventListener("input",function(){paintTimeChips(input);syncEndToStart(input.id)});
    input.addEventListener("change",function(){paintTimeChips(input);syncEndToStart(input.id)});
    paintTimeChips(input);
  });
}
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
      const colorKey=item.type==="favorite"&&item.category?"favorite:"+item.category:item.type;
      const color=TL_COLORS[colorKey]||TL_COLORS[item.type]||"#888780";
      const tname=item.type==="favorite"?(item.category==="work"?"收藏·工作":item.category==="life"?"收藏·生活":"收藏"):timelineTypeName(item.type);
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
    return `<div class="list-item"><div class="grow"><div class="task-title">${esc(n.title)} ${syncPill}</div><div class="task-meta">${(n.createdAt||"").slice(0,16)}${n.vaultPath?" · "+esc(n.vaultPath):""}</div><div class="task-meta">${esc(n.content||"")}</div>${tags?`<div class="favorite-tags">${tags}</div>`:""}</div><div class="actions">${n.obsidianUrl?`<a class="btn sm" href="${esc(n.obsidianUrl)}">原文 ↗</a>`:""}${n.syncStatus!=="synced"?`<button class="btn sm" data-action="sync-knowledge" data-id="${n.id}">重试同步</button>`:""}<button class="btn sm danger" data-action="delete-knowledge" data-id="${n.id}">删除</button></div></div>`;
  }).join(""):'<div class="empty">还没有知识笔记。在「整理确认」把有价值的条目归类为「知识」，就会写入 Vault。</div>';
  paintKbChat();
}
function renderTrash(){
  const items=state.trash;
  $("trashBadge").textContent=items.length||"";$("trashBadge").style.display=items.length?"inline-block":"none";
  $("trashCount").textContent=items.length?"（共 "+items.length+" 条）":"";
  // 「清空回收站」是整页唯一的批量不可逆操作，空回收站时把它收起来：
  // 一个点下去什么都没发生的按钮，比没有按钮更让人怀疑自己点错了。
  // 用 .hidden 类而不是 hidden 属性 —— 属性会被作者样式盖掉。
  const clearTrashButton=$("clearTrashButton");
  if(clearTrashButton)clearTrashButton.classList.toggle("hidden",items.length===0);
  // 剩余天数由后端按保留期算（30 天是后端规则，前端不自己推），这里只负责措辞：
  // daysLeft 为 0 时不能说「还剩 0 天可恢复」——那读起来像是还能用，实际是今天之内。
  $("trashList").innerHTML=items.length?items.map(item=>`<div class="list-item" data-trash-type="${item.type}" data-trash-id="${item.id}"><div class="grow"><div class="task-title">${esc(item.title==null||item.title===""?"（无标题）":item.title)} <span class="pill">${trashTypeName(item.type)}</span></div><div class="task-meta">${item.detail?esc(item.detail)+" · ":""}${(item.deletedAt||"").slice(0,16)} 删除 · ${item.daysLeft>0?"还剩 "+item.daysLeft+" 天可恢复":"今天之后不能再恢复"}</div></div><div class="actions"><button class="btn sm primary" data-action="restore-trash" data-type="${item.type}" data-id="${item.id}">恢复</button><button class="btn sm danger" data-action="delete-trash" data-type="${item.type}" data-id="${item.id}">彻底删除</button></div></div>`).join(""):"<div class=\"empty\">回收站是空的。删除的任务、日程、收藏、知识记录和收集箱条目会先到这里，30 天内可以恢复。</div>";
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
// 模型偶尔还是会带 Markdown 痕迹（**加粗**、## 小标题、- 列表），而气泡里是纯文本渲染，
// 不清理就会把星号和井号原样显示给用户。这里只做「去标记、留文字」，不引 Markdown 渲染器。
function kbPlain(text){
  return String(text==null?"":text)
    .replace(/`([^`]+)`/g,"$1")
    .replace(/\*\*(.+?)\*\*/g,"$1")
    .replace(/__(.+?)__/g,"$1")
    .replace(/^\s*#{1,6}\s*/gm,"")
    .replace(/^\s*[-*+]\s+/gm,"· ");
}
// [1][2] 是答案与「参考来源」对上号的唯一线索，渲染成小角标（不改成链接：
// 同名文件在不同目录里很常见，让用户自己点号子反而容易点错，出处统一在下面列）。
function kbCiteRefs(escaped){return escaped.replace(/\[(\d{1,2})\]/g,'<span class="kb-ref">$1</span>')}
// 回答的形态：正文在前（对问题的一段完整归纳），出处以编号列表附在后面。
// 早期版本把两者混在一起平铺，用户看到的就是「一堆文档块」，反馈说「只列出了文档链接」。
function kbAnswerHtml(result){
  const lines=kbPlain(result.answer).split(/\n+/).map(line=>line.trim()).filter(Boolean);
  let html='<div class="kb-answer">'+(lines.length
    ?lines.map(line=>"<p>"+kbCiteRefs(esc(line))+"</p>").join("")
    :'<p class="muted">（模型没有返回内容）</p>')+'</div>';
  const cites=result.citations||[];
  if(cites.length){
    html+='<div class="kb-srcs"><div class="kb-srcs-head">参考来源 '+cites.length+' 处 · 点标题用 Obsidian 打开原文</div>'
      +cites.map((cite,i)=>{
        const n=cite.index==null?i+1:cite.index;
        return '<div class="kb-src"><span class="kb-src-n">'+n+'</span><div class="kb-src-body">'
          +'<a href="'+esc(cite.obsidianUrl)+'">'+esc(cite.file)+' § '+esc(cite.heading)+'</a>'
          +'<span class="kb-src-snip">'+esc(cite.snippet)+'</span></div></div>';
      }).join("")
      +'</div>';
  }
  return html;
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
// 把 Vault 里早就存在的笔记补进知识库。后端保证幂等，所以这个按钮可以放心重复点。
// 结果必须把「扫描 / 新导入 / 已存在」三个数都报出来 —— 只报一个数字会让用户分不清
// 「没扫到」和「全都导过了」。
async function importVault(){
  if(state.collect.importing)return;
  const dir=$("importDirInput").value.trim();
  state.collect.importing=true;
  const button=$("importVaultButton");button.disabled=true;button.textContent="导入中…";
  try{
    const result=await WorkbenchApi.importVault(dir);
    toast("扫描 "+result.scanned+" 篇，新导入 "+result.imported+" 篇，已存在 "+result.skipped+" 篇",4800);
    state.knowledge.indexState="idle";
    await refreshAll();
  }catch(error){
    toast(error.message,4200);
  }finally{
    state.collect.importing=false;button.disabled=false;button.textContent="从 Vault 导入";
  }
}
// 收藏一篇得到文章：解析、归档、录入知识库都在后端完成，这里只负责递文本并如实反馈结果。
// 失败时把后端的原话透出去（后端给的是中文可操作原因，如「没能从这个链接里读出文章信息」），
// 不要吞成统一的「操作失败」——那会让用户不知道该改什么。
async function collectArticle(){
  const input=$("collectInput"),content=input.value.trim();
  if(!content){toast("请先粘贴得到分享链接");return}
  if(state.collect.collecting)return;
  state.collect.collecting=true;
  const button=$("collectButton");button.disabled=true;button.textContent="收藏中…";
  try{
    const result=await WorkbenchApi.collectArticle(content);
    input.value="";
    toast("已收藏「"+result.title+"」→ "+result.collection,4200);
    // 笔记刚写进 Vault，索引快照要作废，否则紧接着搜是搜不到的。
    state.knowledge.indexState="idle";
    await refreshAll();
  }catch(error){
    toast(error.message,4200);
  }finally{
    state.collect.collecting=false;button.disabled=false;button.textContent="收藏";
  }
}
/* ===== 收藏区的拖拽落点（2026-09-20） =====
   场景：用户在微信 PC 端把得到的分享卡片直接拖进工作台，省掉「右键复制链接 → 点输入框 → 粘贴」。

   微信 PC 端拖动一条消息时到底给什么 MIME，没有公开约定，各版本还不一样 ——
   可能是 text/plain（标题+链接的纯文本）、可能是 text/html（带 <a href>），
   也可能是私有类型。所以这里**不押注单一格式**：把能读到的类型全读一遍再统一抽链接，
   读不到时把「实际拿到了哪些类型」回显出来，否则这类失败只能靠猜。 */
const COLLECT_URL_PATTERN=/https?:\/\/[^\s，。；、！？"'（）【】《》]+/g;
function collectDropText(dataTransfer){
  if(!dataTransfer)return{text:"",types:[]};
  const types=Array.prototype.slice.call(dataTransfer.types||[]);
  const parts=[];
  types.forEach(function(type){
    // 拖进来的文件走 Files 分支：收藏文章只认链接，这里明确不处理
    if(type==="Files")return;
    let data="";
    // 个别浏览器在 drop 阶段读某些类型会抛异常，一个类型失败不该拖垮整次拖拽
    try{data=dataTransfer.getData(type)||""}catch(error){data=""}
    if(data)parts.push(data);
  });
  return{text:parts.join("\n"),types:types};
}
// 优先挑得到自己的域名：分享卡片的 HTML 里常混着封面图、logo、统计链，取第一个会取错
function extractCollectUrl(text){
  if(!text)return "";
  const all=String(text).match(COLLECT_URL_PATTERN)||[];
  const hit=all.find(function(url){return /dedao\.cn|igetget\.com/i.test(url)});
  return (hit||all[0]||"").trim();
}
function describeCollectDropFailure(picked){
  if(!picked.text.trim()){
    if(picked.types.indexOf("Files")>=0)return "拖进来的是文件或图片，不是链接。收藏文章只认分享链接，请改用「复制链接 → 粘贴」。";
    return "没能从拖拽里读到内容（拿到的类型："+(picked.types.join(" / ")||"空")+"）。请改用「复制链接 → 粘贴」。";
  }
  const head=picked.text.replace(/\s+/g," ").trim().slice(0,50);
  return "拖进来的内容里没有链接（拿到的类型："+picked.types.join(" / ")+"；开头是「"+head+"…」）。请改用「复制链接 → 粘贴」。";
}
function bindCollectDrop(){
  const card=$("collectCard"),input=$("collectInput");
  if(!card||!input)return;
  card.addEventListener("dragover",function(event){
    // 不 preventDefault，浏览器就认为这里不接受拖放，drop 压根不触发
    // （表现是「拖过去松手没反应」，且控制台一个字都不报）。
    event.preventDefault();
    if(event.dataTransfer)event.dataTransfer.dropEffect="copy";
    card.classList.add("collect-dragover");
  });
  card.addEventListener("dragleave",function(event){
    // 移入子元素同样会触发 dragleave，用 relatedTarget 判断是否真的离开了卡片
    if(card.contains(event.relatedTarget))return;
    card.classList.remove("collect-dragover");
  });
  card.addEventListener("drop",function(event){
    event.preventDefault();
    card.classList.remove("collect-dragover");
    const picked=collectDropText(event.dataTransfer);
    const link=extractCollectUrl(picked.text);
    if(!link){toast(describeCollectDropFailure(picked),5200);return}
    // 填进输入框再走原来那条路：拖入与粘贴共用一份提交逻辑，
    // 提示信息、防重、失败回显都保持一致。
    input.value=link;
    collectArticle().catch(function(error){toast(error.message,4200)});
  });
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
    let html=kbAnswerHtml(result);
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
  // 得到 Cookie 只回传「配没配」（明文不回传，也没必要回传），所以这里只显示状态。
  // 这段文案是用户排查「收藏只能拿试读」时的第一落点，必须带上下一步该做什么。
  const dedaoHint=$("dedaoCookieHint");
  if(dedaoHint)dedaoHint.textContent=s.dedaoCookieSet
    ?"已配置：收藏文章会带上你的登录态。粘贴新的一串可替换，留空保存即清除。"
    :"未配置：收藏文章只能拿到试读部分（约 20%）。";
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
  await WorkbenchApi.saveAi({enabled:$("setAiEnabled").checked,baseUrl:$("setAiBaseUrl").value.trim(),model:$("setAiModel").value.trim(),visionModel:$("setAiVisionModel").value.trim(),visionLocalOnly:$("setAiVisionLocalOnly").checked,apiKey:$("setAiKey").value.trim()});
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
// 保存「得到登录 Cookie」—— 收藏文章取全文的钥匙（不带它，分享页只开放约 20% 正文）。
// 无论成败都要把设置页的状态文案刷出来：用户下一次收藏失败时，
// 第一件要确认的就是「Cookie 到底配上了没有」。
async function saveDedaoSetting(){
  const input=$("setDedaoCookie"),raw=input.value.trim();
  try{
    await WorkbenchApi.saveDedaoCookie(raw);
  }catch(error){toast(error.message,6200);return}
  input.value="";
  await refreshAll();
  toast(raw?"得到 Cookie 已保存，收藏文章会带上你的登录态":"已清除得到 Cookie");
}
async function doBackupNow(){const result=await WorkbenchApi.backupNow();await refreshAll();toast("备份完成："+result.file+"（"+fmtSize(result.sizeBytes)+"）")}
async function doClearDemo(){
  if(!confirm("确定清空所有示例数据？此操作只删除标记为示例的记录。"))return;
  if(!confirm("再次确认：示例任务、日程、收藏、收录记录与相关流水将被删除，无法撤销。"))return;
  const result=await WorkbenchApi.clearDemo();await refreshAll();toast("已清空 "+result.total+" 条示例数据");
}
async function doShutdown(){
  if(!confirm("确定关闭工作台？关闭后需重新双击启动器才能使用。"))return;
  try{await WorkbenchApi.shutdown()}catch(error){/* 连接中断属正常 */}
  setTimeout(()=>{document.body.innerHTML="<div style=\"padding:60px 24px;font-family:sans-serif;text-align:center;color:#33415c\"><h2>工作台已关闭</h2><p>可以关闭此页面。重新使用请双击「启动工作台」。</p></div>"},900);
}
/* ===== 图片区（2026-09-16） =====
   两个入口共用一条底栏：左「＋AI」解析图片、右「附件」带任意文件，提交按钮在最右。
   二者的区别不在位置而在职责 —— 前者会调用模型且**阻塞提交**，后者不解析。
   整个容器是拖拽落点：拖图片走解析，拖其它文件进附件；在输入框里 Ctrl+V 粘截图也行。

   文件一律「先上传拿 id、提交表单时才绑定」：上传阶段就完成了类型 / 大小 / 魔数校验，
   提交时只做归属。上传失败的条目**不阻断提交**，但仍会被移出待绑定列表并在 toast 里说明。 */
const MEDIA_MAX_IMAGES=3;   // 解析图片上限：一张截图常出 2–3 条，3 张已能产生十来个待确认条目
const MEDIA_MAX_FILES=9;    // 附件上限，与后端 @Size(max=9) 逐字对齐
const MEDIA_IMAGE_ACCEPT="image/png,image/jpeg,image/gif,image/webp";

function mediaScopeOf(el){return el.closest?el.closest("[data-media-scope]"):null}
/**
 * 编辑弹层（scope 以 `edit-` 开头）里的附件是**已保存的附件**（kind="att"），
 * 与录入区里待解析的图（kind="image"）走两条不同的呈现 ——
 * 前者要显示 64px 大图 + 删除钮，后者要显示「图1/图2」角标（解析时会按图认领结果）。
 */
function mediaKindFor(scope,kind){return String(scope||"").indexOf("edit-")===0?"att":kind}
/**
 * 编辑弹层里上传附件时带上的归属。
 *
 * <p>弹层里的附件是**上传即绑定**的（不像录入区「先上传、提交表单时才绑定」）：
 * 用户点的是「给这条记录加个文件」，这句话本身就是提交意图，没有第二个提交动作可以等。
 * 代价是附件区的改动立即生效、不受「取消」影响 —— 所以弹层里有一句明说，
 * 不能让它变成一次静默的意外。</p>
 */
function mediaOwnerOf(scope){
  if(scope==="edit-favorite")return favoriteEditingId?{ownerType:"favorite",ownerId:favoriteEditingId}:null;
  if(scope==="edit-task")return taskEditingId?{ownerType:"task",ownerId:taskEditingId}:null;
  if(scope==="edit-event")return eventEditingId?{ownerType:"schedule_event",ownerId:eventEditingId}:null;
  return null;
}
/** 打开弹层时把这条记录已有的附件灌进待管理列表（不灌的话用户会以为附件丢了）。 */
function mediaSeed(scope,atts){
  state.media[scope]=attList(atts).map(a=>({
    localId:"s"+a.id,kind:"att",status:"done",
    name:attFileName(a),
    size:a.byteSize,sizeText:attSizeText(a.byteSize),
    attachmentId:a.id,url:a.url,error:"",
    image:!!a.image,mime:a.mimeType||""
  }));
  mediaRender(scope);
}
function mediaKeyOf(el){const box=mediaScopeOf(el);return box?box.dataset.mediaScope:""}
function mediaBox(scope){return document.querySelector('[data-media-scope="'+scope+'"]')}
function mediaList(scope){if(!state.media[scope])state.media[scope]=[];return state.media[scope]}

/** 提交时要带给后端的附件 id —— 只收**上传成功**的那些。 */
function mediaIds(scope){
  return mediaList(scope).filter(item=>item.status==="done"&&item.attachmentId).map(item=>item.attachmentId);
}
function mediaBusy(scope){return mediaList(scope).some(item=>item.status==="uploading")}

/** 提交按钮：还有文件在上传就置灰。否则用户点下去、图还没传完，
 *  创建出来的记录将不带附件，而界面上完全看不出少了什么。 */
function mediaSyncSubmit(scope){
  const box=mediaBox(scope);if(!box)return;
  const btn=box.querySelector(".btn.primary");if(!btn)return;
  if(!btn.dataset.label)btn.dataset.label=btn.textContent;
  const busy=mediaBusy(scope);
  btn.disabled=busy;
  btn.textContent=busy?"上传中…":btn.dataset.label;
  const cnt=box.querySelector("[data-media-count]");
  // 用 .hidden 类而不是 hidden 属性：.media-add/.media-cnt 自己带 display:grid/inline-flex，
  // 作者样式的优先级高于浏览器给 [hidden] 的 display:none —— 属性写了也**照样显示**。
  // （2026-09-16 截图发现：＋AI 明明设了 hidden，页面上还是露着。）
  if(cnt){const n=mediaList(scope).filter(i=>i.kind!=="image").length;cnt.classList.toggle("hidden",n===0);cnt.textContent=n}
}

function mediaSizeText(bytes){
  if(bytes>=1024*1024)return (bytes/1024/1024).toFixed(1)+" MB";
  if(bytes>=1024)return Math.round(bytes/1024)+" KB";
  return bytes+" B";
}

function mediaRender(scope){
  const box=mediaBox(scope);if(!box)return;
  const strip=box.querySelector("[data-media-strip]");if(!strip)return;
  let imgIndex=0;
  strip.innerHTML=mediaList(scope).map(item=>{
    // 编辑弹层里的附件：64px 一枚，图片显示缩略图、其它文件显示类型色块，删除钮**常显**。
    if(item.kind==="att"){
      const name=item.name||"未命名文件";
      const body=item.image
        ?'<img src="'+esc(item.url||"")+'" alt="'+esc(name)+'">'
        :'<span class="ma-k '+esc(attExtClass(name))+'">'+esc(attExtLabel(name))+'</span>';
      const mark=item.status==="uploading"
        ?'<span class="ms-mask"><span class="media-spin"></span></span>'
        :item.status==="failed"
          ?'<span class="ma-err" title="'+esc(item.error||"上传失败")+'">!</span>'
          :"";
      return '<span class="media-att'+(item.status==="failed"?" is-err":"")+'" title="'+esc(name)+' · '+esc(item.sizeText||"")+'">'
        +body+'<span class="ma-n">'+esc(name)+'</span>'+mark
        +'<button class="ma-x" type="button" data-media-remove="'+esc(item.localId)+'" title="移除附件">&times;</button>'
        +'</span>';
    }
    if(item.kind==="image"){
      imgIndex++;
      const mark=item.status==="uploading"
        ?'<span class="ms-mask"><span class="media-spin"></span></span>'
        :item.status==="failed"
          ?'<span class="ms-err" title="'+esc(item.error||"上传失败")+'">!</span>'
          :'<span class="ms-ok">✓</span>';
      return '<span class="media-shot'+(item.status==="failed"?" is-err":"")+'" title="'+esc(item.name)+'">'
        +'<img src="'+esc(item.url||"")+'" alt="">'
        +'<span class="ms-no">图'+imgIndex+'</span>'+mark
        +'<button class="ms-x" type="button" data-media-remove="'+esc(item.localId)+'" title="移除">&times;</button>'
        +'</span>';
    }
    const ext=(item.name.split(".").pop()||"").toLowerCase();
    const known=["pdf","xls","xlsx","doc","docx","md","json"].indexOf(ext)>=0?ext:"other";
    return '<span class="media-file" title="'+esc(item.name)+'">'
      +'<span class="mf-k '+known+'">'+esc(ext.toUpperCase().slice(0,4)||"FILE")+'</span>'
      +'<span class="mf-n">'+esc(item.name)+'</span>'
      +'<span class="mf-s">'+esc(item.sizeText||"")+'</span>'
      +'<button class="mf-x" type="button" data-media-remove="'+esc(item.localId)+'" title="移除">&times;</button>'
      +'</span>';
  }).join("");
  mediaSyncSubmit(scope);
}

let mediaSeq=0;
async function mediaUpload(scope,file,kind){
  const k=mediaKindFor(scope,kind);
  const item={localId:"m"+(++mediaSeq),kind:k,status:"uploading",name:file.name,
    size:file.size,sizeText:mediaSizeText(file.size),attachmentId:null,url:"",error:"",
    // 编辑弹层的附件条目按 kind="att" 渲染，要自己记住「是不是图片」才能决定画缩略图还是色块。
    image:k==="att"?/^image\//.test(file.type||""):undefined};
  mediaList(scope).push(item);
  mediaRender(scope);
  try{
    // 编辑弹层带了 owner → 上传即绑定到这条记录（见 mediaOwnerOf 的说明）。
    const saved=await WorkbenchApi.uploadAttachment(file,mediaOwnerOf(scope)||undefined);
    item.status="done";item.attachmentId=saved.id;item.url=saved.url;
    if(k==="att")item.image=!!saved.image;
  }catch(error){
    // 失败的单条不影响其余，也不阻断提交：图没传上去，但表单内容不该白填。
    item.status="failed";item.error=error.message;
    toast("「"+file.name+"」上传失败："+error.message+"（可移除后重选，其余不受影响）",5600);
  }
  mediaRender(scope);
}

function mediaPick(scope,kind){
  const picker=document.createElement("input");
  picker.type="file";picker.multiple=true;
  if(kind==="image")picker.accept=MEDIA_IMAGE_ACCEPT;
  picker.addEventListener("change",()=>{
    mediaAdd(scope,Array.prototype.slice.call(picker.files||[]),kind);
  });
  picker.click();
}

function mediaAdd(scope,files,kind){
  const list=mediaList(scope);
  // 编辑弹层里没有「解析图片」这回事：拖进来 / 粘进来的图一律当附件（见 mediaKindFor）。
  kind=mediaKindFor(scope,kind);
  let imageCount=list.filter(i=>i.kind==="image").length;
  let fileCount=list.filter(i=>i.kind!=="image").length;
  files.forEach(file=>{
    if(kind==="image"){
      if(!/^image\//.test(file.type)){toast("「"+file.name+"」不是图片，已跳过",4200);return}
      if(imageCount>=MEDIA_MAX_IMAGES){toast("解析图片最多 "+MEDIA_MAX_IMAGES+" 张，多余的已忽略",4600);return}
      imageCount++;mediaUpload(scope,file,"image");
    }else{
      if(fileCount>=MEDIA_MAX_FILES){toast("附件最多 "+MEDIA_MAX_FILES+" 个，多余的已忽略",4600);return}
      fileCount++;mediaUpload(scope,file,"file");
    }
  });
}

async function mediaRemove(scope,localId){
  const list=mediaList(scope);
  const index=list.findIndex(i=>i.localId===localId);
  if(index<0)return;
  const item=list[index];
  // 已经保存过的附件（编辑弹层里的那些）移除前**必须确认**：它是真实数据，
  // 而且界面上没有「把某一个附件单独恢复回来」的入口 —— 只有在整条记录从回收站恢复时
  // 它才会跟着回来。说清后果再删，别让一次误点变成永久少一个文件。
  // （录入区里刚拖进来的那条不算：它在服务端还没有归属，删掉就是撤销。）
  if(item.kind==="att"&&item.status==="done"&&item.attachmentId){
    const ok=confirm("移除附件「"+(item.name||"未命名文件")+"」？\n\n"
      +"它会从这条记录上消失，而且界面上没有单独恢复它的入口"
      +"（只有把整条记录从回收站恢复时，它才会跟着回来）。");
    if(!ok)return;
  }
  list.splice(index,1);
  mediaRender(scope);
  // 服务端也删掉：留着就是没人认领的孤儿（虽然清理任务会兜底，
  // 但让用户点了「×」之后数据还在，等于骗他）。
  if(item.attachmentId){
    try{
      await WorkbenchApi.deleteAttachment(item.attachmentId);
    }catch(error){
      // 删失败就把条目**放回列表**：界面显示"已移除"而数据还在是最坏的一种不一致 ——
      // 用户以为删掉了，下次刷新它又回来了。
      list.splice(index,0,item);
      mediaRender(scope);
      toast("移除失败："+error.message+"（附件仍在这条记录上）",5600);
      return;
    }
  }
  if(item.kind==="att")toast("已移除「"+(item.name||"附件")+"」");
}

function mediaClear(scope){state.media[scope]=[];mediaRender(scope)}

// ===== 附件展示层（列表里看得见附件，2026-09-19）=====
// 背景：附件能上传、能绑定，但四个列表**一个都不渲染** —— 用户在列表里完全看不到自己传过什么。
// 这一节负责把它们画出来。设计稿见 output/att-design/。
//
// ⚠️ 下面这些函数必须是**纯函数**（只吃参数、不碰 DOM、不读 state）：check_frontend_render.js
// 会把它们抠出来放进 new Function 里单独跑（见那份脚本的 FUNCS）。所以：
//   ① 不引用模块级 const（抠不出去 → ReferenceError），阈值一律写成函数内的字面量；
//   ② 不调用 esc 之外的全局（esc 由脚本显式注入）。
/** 只留真正有 id 的项：后端偶发的 null 元素不该把整张卡片渲染成 "undefined"。 */
function attList(atts){return Array.isArray(atts)?atts.filter(a=>a&&a.id):[]}
function attFileName(a){const name=String((a&&a.name)||"").trim();return name||"未命名文件"}
function attSizeText(bytes){
  const n=Number((bytes==null?0:bytes));
  if(!isFinite(n)||n<0)return "";
  if(n>=1024*1024)return (n/1024/1024).toFixed(1)+" MB";
  if(n>=1024)return Math.round(n/1024)+" KB";
  return n+" B";
}
/** 文件类型色块的类名。配色与录入区的 .mf-k 是同一套语义，同一个后缀不能在两处是两个颜色。 */
function attExtClass(name){
  const ext=(String(name||"").split(".").pop()||"").toLowerCase();
  const known={pdf:"pdf",xls:"xls",xlsx:"xls",doc:"doc",docx:"doc",md:"md",json:"json"};
  return known[ext]||"other";
}
/** 色块里显示的字：取后缀，最多 4 个字符（"XLSX" / "PDF" / "FILE"）。 */
function attExtLabel(name){
  const ext=(String(name||"").split(".").pop()||"").trim();
  if(!ext||ext.length>5||ext.indexOf("/")>=0)return "FILE";
  return ext.toUpperCase().slice(0,4);
}
function attZoomIcon(){
  return '<svg class="att-zoom" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true">'
    +'<circle cx="10.5" cy="10.5" r="6.5"></circle><path d="M15.6 15.6 21 21"></path>'
    +'<path d="M10.5 7.8v5.4M7.8 10.5h5.4"></path></svg>';
}
/** 日程小卡上的回形针徽标图标（与录入区「附件」按钮同一个几何）。 */
function attClipIcon(){
  return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true">'
    +'<path d="M21.4 11.05 12.25 20.2a5 5 0 0 1-7.07-7.07l9.19-9.19a3.5 3.5 0 0 1 4.95 4.95l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"></path></svg>';
}
/**
 * 图片缩略图按钮。owner 是「归属标识」（`favorite:17` / `task:3` / `event:9` / `inbox:4`），
 * **只用来回查这条记录的全部图片**（点开一张后要能左右翻），所以它不是附件 id。
 */
function attShotHtml(a,owner,index){
  const name=attFileName(a);
  return '<button class="att-shot" type="button" data-action="preview-att"'
    +' data-att-owner="'+esc(owner)+'" data-att-index="'+(index||0)+'"'
    +' title="'+esc(name)+' · '+esc(attSizeText(a.byteSize))+' —— 点开看原图">'
    +'<img src="'+esc(a.url)+'" alt="'+esc(name)+'">'+attZoomIcon()+'</button>';
}
/**
 * 文件胶囊。**图片**才走浮层预览，其它文件一律新标签打开原文件 ——
 * 在页面里预览一个 xlsx 只能看到一堆二进制，直接交给系统打开才是用户要的。
 */
function attFileHtml(a){
  const name=attFileName(a);
  return '<a class="att-file" href="'+esc(a.url)+'" target="_blank" rel="noopener"'
    +' title="'+esc(name)+' · '+esc(attSizeText(a.byteSize))+' —— 点开查看 / 下载">'
    +'<span class="af-k '+esc(attExtClass(name))+'">'+esc(attExtLabel(name))+'</span>'
    +'<span class="af-n">'+esc(name)+'</span>'
    +'<span class="af-s">'+esc(attSizeText(a.byteSize))+'</span></a>';
}
/**
 * 附件行：图片一行（最多 max 张，其余收成「+N」）+ 文件一行。
 *
 * <p>没有附件时返回**空串**而不是空容器 —— 卡片高度必须与「从来没有附件」时逐像素一致，
 * 否则整个列表的高度节奏会随附件有无而跳动。</p>
 *
 * @param atts  后端下发的 attachments（可能是 undefined / null，必须当空处理）
 * @param options {owner, max}
 */
function attStrip(atts,options){
  const list=attList(atts);
  if(!list.length)return "";
  const opt=options||{};
  const owner=opt.owner||"";
  const max=opt.max||3;
  const shots=list.filter(a=>a.image);
  const files=list.filter(a=>!a.image);
  let inner="";
  if(shots.length){
    const shown=shots.slice(0,max);
    const rest=shots.length-shown.length;
    inner+='<div class="att-shots">'+shown.map((a,i)=>attShotHtml(a,owner,i)).join("")
      +(rest>0?'<button class="att-more" type="button" data-action="preview-att" data-att-owner="'+esc(owner)+'" data-att-index="'+max+'" title="还有 '+rest+' 张，点开看全部"><span>+'+rest+'<small>张</small></span></button>':"")
      +'</div>';
  }
  if(files.length){
    inner+='<div class="att-files">'+files.map(a=>attFileHtml(a)).join("")+'</div>';
  }
  return '<div class="att-strip">'+inner+'</div>';
}
/**
 * 按归属标识取回这条记录的全部附件。
 *
 * <p>不用渲染时写进 DOM 的索引去查：列表随时可能重绘（一次 refreshAll 就换一批节点），
 * 而 state 里的记录是**当前真实的那一份**。索引只是图片序列里的位置，不是附件身份。</p>
 */
function attachmentsOf(ownerKey){
  const parts=String(ownerKey||"").split(":");
  const kind=parts[0],id=parts[1];
  if(!kind||!id)return [];
  let pool=[];
  if(kind==="favorite")pool=state.favorites||[];
  else if(kind==="task")pool=state.tasks||[];
  else if(kind==="event")pool=(state.events||[]).concat(state.pendingEvents||[]);
  else if(kind==="inbox")pool=(state.inbox||[]).concat(state.classify||[]);
  const hit=pool.filter(record=>record&&String(record.id)===String(id))[0];
  return hit?attList(hit.attachments):[];
}

// ===== 附件预览浮层 =====
// 打开时传的是**这条记录的全部图片**（不是被点的那一张），因为点开一张后最自然的
// 下一步就是看下一张 —— 为此退回列表再点一次是倒退。
let attViewerList=[];
let attViewerIndex=0;
function attViewerShow(index){
  const list=attViewerList;
  if(!list.length)return;
  // 首尾相接：一路点 → 不会在最后一张卡住。卡住时页面上没有任何提示，看起来就像坏了。
  attViewerIndex=((index%list.length)+list.length)%list.length;
  const item=list[attViewerIndex];
  const img=$("avImg");
  img.src=item.url;
  img.alt=attFileName(item);
  const bar=$("avBar");
  if(bar){
    bar.innerHTML='<span class="av-name">'+esc(attFileName(item))+'</span>'
      +'<span class="av-size">'+esc(attSizeText(item.byteSize))+'</span>'
      +(list.length>1?'<span class="av-count">'+(attViewerIndex+1)+' / '+list.length+'</span>':"");
  }
  // 只有一张时藏掉左右箭头：留着可点但点了没变化的箭头，用户会以为翻页坏了。
  const nav=list.length>1;
  const prev=$("avPrev"),next=$("avNext");
  if(prev)prev.classList.toggle("hidden",!nav);
  if(next)next.classList.toggle("hidden",!nav);
}
function openAttViewer(items,index){
  attViewerList=attList(items);
  if(!attViewerList.length)return;
  const viewer=$("attViewer");
  if(!viewer)return;
  viewer.classList.add("open");
  attViewerShow(index||0);
  const closeBtn=$("avClose");
  if(closeBtn&&closeBtn.focus)closeBtn.focus();
}
function closeAttViewer(){
  const viewer=$("attViewer");
  if(!viewer||!viewer.classList.contains("open"))return;
  viewer.classList.remove("open");
  // 清掉 src：留着大图会让浏览器一直占着解码后的位图（一张 4000px 截图就是几十 MB）。
  const img=$("avImg");
  if(img)img.src="";
  attViewerList=[];
}
function bindAttViewer(){
  const viewer=$("attViewer");
  if(!viewer)return;
  const closeBtn=$("avClose"),prev=$("avPrev"),next=$("avNext");
  if(closeBtn)closeBtn.addEventListener("click",()=>closeAttViewer());
  if(prev)prev.addEventListener("click",()=>attViewerShow(attViewerIndex-1));
  if(next)next.addEventListener("click",()=>attViewerShow(attViewerIndex+1));
  // 点遮罩关闭：判 target===viewer 而不是「点在 box 之外」—— 后者会把图片四周的留白也算进去。
  viewer.addEventListener("click",event=>{if(event.target===viewer)closeAttViewer()});
  document.addEventListener("keydown",event=>{
    if(!viewer.classList.contains("open"))return;
    if(event.key==="Escape")closeAttViewer();
    else if(event.key==="ArrowLeft")attViewerShow(attViewerIndex-1);
    else if(event.key==="ArrowRight")attViewerShow(attViewerIndex+1);
  });
}

// ===== 图片多模态解析（阶段二，2026-09-16）=====
// 三个入口里图片的**去向不同**：
//   inbox  → 解析结果进「整理合并」，用户在待确认卡里逐条核对后生成（与文字收录同一套 UI）
//   task/schedule/favorite → 「抽取预填」：把抽到的标题/日期/时间填进表单，用户自己按保存
// 这个差别是刻意的：表单页没有待确认卡，硬塞一个确认页会让流程变成两段。
const PARSE_POLL_MS=1200;
let parsePollTimer=null;

/** 图片解析中 / 失败 / 已完成的计数，进页面时同步一次。进首页即恢复轮询。 */
async function syncParseStatus(){
  let status;
  try{status=await WorkbenchApi.parseStatus()}catch(error){return}
  state.parseStatus=status||{running:0,failed:0,success:0,lastError:null};
  renderParseStatus();
  // 还挂着未完成的解析（比如上次关页面时正在跑）→ 继续轮询。
  // 少了这句，用户关掉页面再回来，界面上就永远是「解析中…」而它其实早跑完了。
  if(state.parseStatus.running>0)startParsePoll();
}
function renderParseStatus(){
  const el=$("parseStatus");if(!el)return;
  const s=state.parseStatus||{};const running=s.running||0;const failed=s.failed||0;
  // 只在「有话说」时出现：正常跑完的状态栏是噪音，用户不需要知道「0 条失败」。
  // ⚠️ 判断必须**三分支**，不能写成 `if(running>0){跑} else {失败}`：
  // 那样 failed===0 时也会渲染成「有 0 张图片没能解析」，自相矛盾。
  // ⚠️ 而且这里**不能整体赋 className** —— 那会把上面 toggle 出来的 `hidden` 冲掉，
  // 于是「0 失败」的状态栏照样显示在页面上（2026-09-16 冒烟脚本抓到的真 bug）。
  if(running<=0&&failed<=0){el.className="parse-status hidden";el.innerHTML="";return}
  if(running>0){
    el.className="parse-status is-running";
    el.innerHTML='<span class="media-spin"></span>正在解析 '+running+' 张图片…（一张约 3–10 秒，按顺序处理）';
  }else{
    el.className="parse-status is-failed";
    el.innerHTML='有 '+failed+' 张图片没能解析：'+esc(s.lastError||"换个格式或更清晰的图再试一次")+'。它们仍留在「待整理」里，可以手动补文字。';
  }
}
function startParsePoll(){
  if(parsePollTimer)return;
  parsePollTimer=setInterval(async()=>{
    let status;
    try{status=await WorkbenchApi.parseStatus()}catch(error){return}
    state.parseStatus=status||state.parseStatus;
    renderParseStatus();
    if(!state.parseStatus||!state.parseStatus.running){
      stopParsePoll();
      await refreshAll();
      const done=(state.parseStatus&&state.parseStatus.success)||0;
      if(done)toast("图片解析完成，共 "+done+" 条待确认，核对后点「确认生成」",5600);
    }
  },PARSE_POLL_MS);
}
function stopParsePoll(){
  if(parsePollTimer){clearInterval(parsePollTimer);parsePollTimer=null}
}

/**
 * 提交图片解析。
 *
 * <p>只收**上传成功**的图片 —— 失败的那些还在条上标着「!」，把它们算进去
 * 会让后端报「附件不存在」，用户以为是解析坏了，其实是上传就没成功。</p>
 *
 * @returns {Promise<boolean>} 是否真的触发了（false 时调用方不要清空输入框）
 */
async function submitMediaParsing(scope,source){
  const images=mediaList(scope).filter(i=>i.kind==="image");
  if(!images.length)return false;
  if(mediaBusy(scope)){toast("图片还在上传，请稍候再试");return false}
  const ids=images.filter(i=>i.status==="done"&&i.attachmentId).map(i=>i.attachmentId);
  if(!ids.length){toast("图片都没有上传成功，无法解析；可移除后重选",5600);return false}
  const failed=images.length-ids.length;
  try{
    await WorkbenchApi.parseImages(ids,source||"web");
  }catch(error){
    // 同步报错（没配视觉模型 / 开了「仅本地」）→ 原文照旧留在输入框，用户去设置页配好就能重试。
    toast(error.message,6400);
    return false;
  }
  if(failed)toast("有 "+failed+" 张没上传成功，本次只解析 "+ids.length+" 张",5600);
  await syncParseStatus();
  return true;
}

// 从解析结果里挑「抽取预填」要用的那一条。
// 优先取类别与当前表单匹配的（在任务页就优先用 task 的抽取结果），
// 没有匹配的才退回第一条 —— 用户传的是一张日程截图但停在任务页，标题也该预填上，
// 只是不替他把类别搬过去。
function pickPrefill(items,category){
  if(!items||!items.length)return null;
  for(let i=0;i<items.length;i++){
    if(items[i].ai&&items[i].ai.category===category)return items[i];
  }
  return items[0];
}

/**
 * 把解析结果预填进表单：只填**空着的**字段。
 *
 * <p>不覆盖用户已经敲进去的内容 —— 他可能先写了标题才想起来补张截图，
 * 预填把它顶掉等于「传张图，我刚写的没了」。</p>
 */
function prefillFromParsed(item,map){
  if(!item||!item.ai)return;
  const payload=item.ai.payload||{};
  const title=(payload.title||"").trim();
  Object.keys(map).forEach(field=>{
    const el=$(map[field].id);if(!el)return;
    const value=map[field].from(payload,title);
    // 「空着的字段才填」这条规则要加上第二种情况：**只有自动默认值的也算法空**。
    // 否则截止日期被默认填上当天之后，从截图里识别出来的日期永远写不进去 ——
    // 用户看到的是「AI 没识别出日期」，而实际上是被默认值挡住了（静默失效）。
    if(value&&(!el.value||isAutoDefault(map[field].id)))el.value=value;
  });
}

function prefillTaskFromImage(item){
  prefillFromParsed(item,{
    title:{id:"taskTitle",from:function(p,t){return t}},
    due:{id:"taskDue",from:function(p){return p.due||""}}
  });
}
function prefillEventFromImage(item){
  prefillFromParsed(item,{
    title:{id:"eventTitle",from:function(p,t){return t}},
    start:{id:"eventStart",from:function(p){return p.start||""}},
    end:{id:"eventEnd",from:function(p){return p.end||""}}
  });
  // 图里只写了开始时间（很常见：「下午 3 点和张总对进度」）时，结束时间还停在默认值上，
  // 很可能反而早于开始 —— 这时按 +1 小时补齐；两个时间都识别出来时 syncEndToStart 会原样放过。
  syncEndToStart("eventStart");
  // 预填改的是 el.value，快捷片的选中态不会自己跟上，这里补一次。
  paintTimeChips($("eventStart"));paintTimeChips($("eventEnd"));
}
function prefillFavoriteFromImage(item){
  prefillFromParsed(item,{
    title:{id:"favoriteTitle",from:function(p,t){return t}},
    // 正文只在完全空的时候补，且用**整图原文**（raw）——那张图里的信息量
    // 往往比一个标题多得多，丢掉等于让用户重新打字。
    content:{id:"favoriteContent",from:function(){return item.raw||""}}
  });
}

function bindMedia(){
  document.addEventListener("click",event=>{
    const extractBtn=event.target.closest?event.target.closest("[data-media-extract]"):null;
    if(extractBtn){
      const scope=extractBtn.dataset.scope;
      const category=extractBtn.dataset.category;
      // 三个表单页共用这一段：抽到的字段各自往自己的表单里填。
      const apply=category==="task"?prefillTaskFromImage
        :category==="schedule"?prefillEventFromImage:prefillFavoriteFromImage;
      extractBtn.disabled=true;
      const label=extractBtn.textContent;
      extractBtn.textContent="识别中…";
      // finally 里恢复按钮：失败时也要能重试，留着「识别中…」的按钮等于把入口锁死了。
      extractFromImages(scope,category,apply)
        .catch(error=>toast(error.message))
        .then(()=>{extractBtn.disabled=false;extractBtn.textContent=label});
      return;
    }
    const parseBtn=event.target.closest?event.target.closest("[data-media-parse]"):null;
    // 「＋AI」只负责选图与上传；**解析在提交时触发**（见 submitMediaParsing），
    // 因为任务/日程/收藏三个入口是「抽取预填」，要先有表单上下文才知道抽到哪去。
    if(parseBtn){mediaPick(mediaKeyOf(parseBtn),"image");return}
    const attachBtn=event.target.closest?event.target.closest("[data-media-attach]"):null;
    if(attachBtn){mediaPick(mediaKeyOf(attachBtn),"file");return}
    const removeBtn=event.target.closest?event.target.closest("[data-media-remove]"):null;
    if(removeBtn){mediaRemove(mediaKeyOf(removeBtn),removeBtn.dataset.mediaRemove);return}
  });

  // 拖拽：整个输入容器都是落点。
  document.querySelectorAll("[data-media-scope]").forEach(box=>{
    box.addEventListener("dragover",event=>{
      // 这个 preventDefault 不能省：默认行为是**打开这个文件**，整页会跳走。
      event.preventDefault();
      box.classList.add("media-dragover");
    });
    box.addEventListener("dragleave",event=>{
      if(box.contains(event.relatedTarget))return;   // 在子元素之间移动不该熄灭高亮
      box.classList.remove("media-dragover");
    });
    box.addEventListener("drop",event=>{
      event.preventDefault();
      box.classList.remove("media-dragover");
      const files=Array.prototype.slice.call(event.dataTransfer&&event.dataTransfer.files||[]);
      if(!files.length)return;
      const scope=box.dataset.mediaScope;
      // 按类型分流：图片走「解析图片」，其它进「附件」—— 用户不用先记住该点哪个按钮。
      const images=files.filter(f=>/^image\//.test(f.type));
      const others=files.filter(f=>!/^image\//.test(f.type));
      if(images.length)mediaAdd(scope,images,"image");
      if(others.length)mediaAdd(scope,others,"file");
    });
  });

  // 在输入区里 Ctrl+V 粘截图：截图党的主要路径。
  document.addEventListener("paste",event=>{
    const box=mediaScopeOf(event.target);
    if(!box)return;
    const items=(event.clipboardData&&event.clipboardData.items)||[];
    const files=[];
    for(let i=0;i<items.length;i++){
      if(items[i].kind==="file"&&/^image\//.test(items[i].type)){
        const file=items[i].getAsFile();
        if(file)files.push(file);
      }
    }
    if(!files.length)return;
    event.preventDefault();
    mediaAdd(box.dataset.mediaScope,files,"image");
  });
}

async function createFavorite(){
  const content=$("favoriteContent").value.trim();
  const title=$("favoriteTitle").value.trim();
  // 背书 FavoriteCreateRequest：正文不再是必填，title / content **至少写一项**即可。
  // 两者都空才会被后端拒（400/1002「收藏的标题和正文不能同时为空，至少写一项」），
  // 前端先拦一次只是为了不白跑一趟接口，措辞与后端保持一致。
  if(!content&&!title){toast("标题和正文至少写一项");$("favoriteTitle").focus();return}
  if(mediaBusy("favorite")){toast("附件还在上传，请稍候再保存");return}
  // title / content 只能传 null（不能传空串）：空串会让后端把「留空」当成「显式写了空标题」，
  // 于是显式标题的回退逻辑（取正文前 24 字）被跳过，收藏会存成没有标题的一条。
  const created=await WorkbenchApi.createFavorite({title:title||null,content:content||null,tags:$("favoriteTags").value.trim()||null,grp:$("favoriteGrp").value,attachmentIds:mediaIds("favorite")});
  $("favoriteTitle").value="";$("favoriteContent").value="";$("favoriteTags").value="";mediaClear("favorite");await refreshAll();
  // 刚存下的这条可能被「空间 / 页内检索」挡在当前视图之外（收藏的 grp 是按内容自动判定的，
  // 判定结果与用户此刻看的空间不一致时，它就不会出现在列表里）—— 那正是「存了却看不到、
  // 要 F5 才显示」的成因。这里按 id 反查一次，必要时放宽视图条件并说明放宽了什么。
  toast("收藏已保存"+await createdVisibilityNote("favorite",created&&created.id));
}
// 日期默认预填**今天**，不跟着正在查看的那一周走。
// 「日期为空」仍然只剩一种含义：还没定下哪一天 → 落到「待定时间」区。
// 用户翻到别的周还新建时，createEvent 会明确提示「它不在当前显示的周里」，
// 而不是让这条日程悄悄落在看不见的地方。
// 日程表单的日期默认值。它与任务的「截止日期默认当日」是**同一套机制**
// （见 defaultOf / initAutoDefault / resetAutoDefault / refreshAutoDefault）：
// 进页面写入、创建成功后复位、展开表单时按「今天」刷新。
// 2026-09-20 之前只有「进页面写一次」—— 页面开着跨过零点，新建的日程默认还落在昨天。
// ⚠️ 保持单行：变异靶子必须单行，本文件是 CRLF，跨行模式永远匹配不上。
function defaultEventDate(){return todayString()}
// 切周 / 追加周只重取日程，不重拉整页（任务、收藏、流水等与周无关，没必要跟着刷新）。
// 检索态必须把 q 带上、并让出区间：否则翻一次周，检索结果会被这一段的日程顶掉，
// 看上去像「搜索被重置了」。
async function loadEvents(){state.events=(await WorkbenchApi.events(eventParams()))||[];renderSchedule()}
async function createEvent(){
  const title=$("eventTitle").value.trim();if(!title){toast("请输入日程标题");return}
  const date=$("eventDate").value||null;
  const weeks=Number(($("eventRepeat")||{}).value)||1;
  if(mediaBusy("event")){toast("附件还在上传，请稍候再加入");return}
  const result=await WorkbenchApi.createEvent({title:title,type:$("eventType").value,date:date,start:$("eventStart").value||null,end:$("eventEnd").value||null,repeatWeeks:weeks,attachmentIds:mediaIds("event")});
  // 时间也一起复位成「下一个整刻 / 再往后一小时」——与日期复位同一语义。
  // 不能只把这几个框清空：清空之后用户又要面对那两个 60 项的分钟列表，
  // 那正是这次改动的起因。
  $("eventTitle").value="";resetAutoDefault("eventStart");resetAutoDefault("eventEnd");resetAutoDefault("eventDate");mediaClear("event");
  // 重复选项每次用完都要回到「不重复」：留着上一次的「连续 4 周」，下一条日程会被静默地
  // 也生成 4 期，而用户以为自己只是加了一件事。
  if($("eventRepeat"))$("eventRepeat").value="1";
  await refreshAll();
  // 日程也有「会收窄结果」的条件：页内检索（命中跨全部日期），以及**周视图只保留当前可见的那几条周**。
  // 被挡住时用户看到的是「加了却哪儿都没有」，所以这里按 id 反查一次，必要时把那一周加进列表、
  // 把检索清掉，并说清做了什么。
  // 顺序：先算放宽结果，再组话术。放宽之后 isDateVisible(date) 一定为真，下面那条
  // 「不在当前显示的周里」就只剩兜底作用（例如接口没返回 event.id、拿不到 id 可查的情形）。
  const note=await createdVisibilityNote("event",result.event&&result.event.id,{date:date});
  if(result.warnings&&result.warnings.length)toast("已加入，但注意："+result.warnings.join("；")+note,5200);
  else if(!date)toast("已加入「待定时间」；在卡片里补上日期即可排进那一天"+note,5200);
  else if(note)toast("已加入 "+date+note);
  else if(!isDateVisible(date))toast("已加入 "+date+"；它不在当前显示的周里，可用周头右上角的 ↑ / ↓ 翻到那一周",6200);
  else if(weeks>1)toast("已按「每周」加入 "+weeks+" 期（到 "+addDays(date,7*(weeks-1))+" 为止）；每一期都能单独改",6600);
  else toast("日程已加入");
}
// 「收录」= 收录 + 整理合并成一步：先把原文写进收录箱，紧接着跑一遍整理。
// 用户不用再单独点一次「开始整理」，页面直接显示分类建议供核对。
//
// 图片走**另一条路**：图片不再随文字写进收录箱，而是交给视觉模型解析，
// 解析出的每一条都变成一个待确认条目（与文字收录共用同一套待确认 UI）。
// 这正是用户最初的抱怨：「传张邮件截图，还没看到解析结果，任务就被建好了」——
// 所以图片必须等解析完、且必须由用户核对，绝不能直接落成任务。
async function createInbox(){
  const raw=$("inboxInput").value.trim();
  const hasImages=mediaList("inbox").some(i=>i.kind==="image");
  if(!raw&&!hasImages){toast("请输入内容或添加图片");return}
  if(mediaBusy("inbox")){toast("附件还在上传，请稍候再收录");return}

  let parsed=false;
  if(hasImages){parsed=await submitMediaParsing("inbox","web")}

  let created=null;
  if(raw){
    created=await WorkbenchApi.createInbox(raw,mediaIds("inbox"));
  }else if(!parsed){
    // 只有图片、且没能触发解析（没配模型 / 仅本地）→ 什么都没发生，输入框保持原样。
    return;
  }
  $("inboxInput").value="";$("inboxInput").focus();mediaClear("inbox");

  if(raw){
    await WorkbenchApi.classifyInbox();
    await refreshAll();
    // 用接口返回的 id 精确找回刚收录的这一条，而不是猜「列表第一条就是刚存的」：
    // 自动整理失败时它会落到「待整理」，必须说清楚，不能笼统报一句「已收录」让用户以为一切正常。
    const mine=state.classify.filter(item=>String(item.id)===String(created.id))[0];
    if(mine&&mine.ai)toast("已收录并整理为「"+categoryName(mine.ai.category)+"」，核对后点「确认生成」",4600);
    else if(state.inbox.some(item=>String(item.id)===String(created.id)))toast("已收录，但自动整理没成功，可在下面「待整理」里点「重新整理」",5600);
    else toast("已收录");
  }else{
    await refreshAll();
  }
}
async function confirmHighConfidence(){
  const targets=batchConfirmableCount();
  if(!targets){toast("没有可批量确认的条目：剩余条目的置信度都低于 70%，请逐条核对后点「确认生成」",5200);return}
  if(!confirm("将按整理建议直接生成 "+targets+" 条（置信度 ≥ 70%）。\n低于 70% 的条目会保留下来，需要你逐条确认。\n\n继续？"))return;
  const results=await WorkbenchApi.confirmHighConfidence();await refreshAll();
  const created=(results||[]).length;
  // 批量生成出来的记录也必须「立刻看得见」。这里用**低层**的 relaxViewForCreated 汇总放宽项，
  // 只做一次 refreshAll、只做一次后验（逐条走 createdVisibilityNote 会重复取数、还会把收尾句
  // 拼好几遍）。放宽规则本身与单条确认共用同一个函数，两条路不会各写一份。
  const relaxed=[];
  (results||[]).forEach(item=>{
    const kind=categoryKind(item&&item.category);
    if(!kind||createdVisible(kind,item.entityId))return;
    relaxViewForCreated(kind).forEach(t=>{if(relaxed.indexOf(t)<0)relaxed.push(t)});
  });
  let suffix="";
  if(relaxed.length){
    await refreshAll();
    const stillHidden=(results||[]).some(item=>{
      const kind=categoryKind(item&&item.category);
      return kind&&!createdVisible(kind,item.entityId);
    });
    suffix="；"+relaxed.join("；")+(stillHidden
      ?"，但其中有的仍不在当前列表里（可能超出一次加载的条数上限），可用页内检索按标题找它"
      :"，这样刚生成的这几条立刻出现在列表里");
  }
  // created 与 targets 理论上一致（筛选条件前后端对齐）。万一不一致，只报事实、不猜原因，
  // 免得把「后端跳过了未配置 Vault 的知识类」这类猜测当成结论塞给用户。
  if(!created)toast("这次没有条目被生成，可能已被处理过；请查看下方列表",5200);
  else if(created<targets)toast("已生成 "+created+" 条，另有 "+(targets-created)+" 条未生成；请查看列表并逐条确认"+suffix,6400);
  else toast("已生成 "+created+" 条，剩余 "+state.classify.length+" 条需要逐条确认"+suffix,5200);
}
async function confirmInbox(id){
  const category=$("confirm-category-"+id).value;
  // eventType 沿用 AI 抽取结果（deep_block / other 不该被硬编码成会议），字段缺失时兜底 meeting
  const eventType=($("confirm-eventtype-"+id)||{}).value||"meeting";
  const due=$("confirm-due-"+id).value||null;
  const result=await WorkbenchApi.confirmInbox(id,{category:category,title:$("confirm-title-"+id).value.trim(),due:due,priority:$("confirm-priority-"+id).value,start:$("confirm-start-"+id).value.trim()||null,end:null,eventType:eventType});
  await refreshAll();
  // 生成出来的记录同样要保证「立刻看得见」——这正是用户报的那条：
  // 「新收录一条收藏后，点收藏菜单看不到，要 F5」。（分类是「收藏」时最容易撞上：
  // 收回来的收藏会被自动归到工作 / 生活某一侧，与用户当前看的空间不一致时就落到视图外。）
  const kind=categoryKind(result&&result.category||category);
  const note=await createdVisibilityNote(kind,result&&result.entityId,{date:due});
  toast("已确认并生成「"+categoryName(category)+"」"+note);
}
async function reclassifyInbox(id){
  await WorkbenchApi.reclassifyInbox(id);
  await refreshAll();toast("已重新整理，请再核对分类和字段");
}

/**
 * 表单页（任务 / 日程 / 收藏）的「从图片识别」。
 *
 * <p>与收录页的区别：这里**不生成任何记录**，只把抽到的字段填进表单，
 * 由用户自己按「保存」。三个表单页都没有待确认卡，硬塞一个确认页会把
 * 「补张截图」变成两段流程；而且用户本来就在填表单，预填后他还能顺手改 ——
 * 比让他先在另一个页面确认一遍再回来重填更省事。</p>
 */
async function extractFromImages(scope,category,apply){
  const images=mediaList(scope).filter(i=>i.kind==="image");
  if(!images.length){toast("请先用左下的 + 添加图片");return}
  // 记下本次要解析的附件 id：一张图可能解析出好几条，按「条数」认领会错，
  // 必须按**来源附件 id** 认领才准（同一张图出的条目都带同一个 sourceAttachmentId）。
  const wanted=images.filter(i=>i.status==="done"&&i.attachmentId).map(i=>i.attachmentId);
  const started=await submitMediaParsing(scope,"web");
  if(!started)return;

  // 等这一批解析完（后端单线程串行，轮询是唯一可靠的方式）。
  const items=await waitForParsedImages(wanted);
  if(!items.length){toast("没能从图片里识别出内容，可以手动填写",5600);return}
  const picked=pickPrefill(items,category);
  apply(picked);
  toast("已按图片预填，核对后点保存（只填了空着的字段，不会覆盖你写好的内容）",6400);
  // 预填完就把**图片**从条上拿掉：它们已经被读过了，留在那里会让用户以为
  // 「保存后图片还会挂到这条记录上」——其实这条表单走的不是同一条路。
  // 只清图片、保留附件：附件是真要随记录一起保存的。
  state.media[scope]=mediaList(scope).filter(i=>i.kind!=="image");
  mediaRender(scope);
}

/**
 * 轮询到这一批解析结束，返回**由这批附件**产生的条目。
 *
 * <p>按 {@code sourceAttachmentId} 认领，不按条数也不按时间戳：一张截图常出 2–3 条，
 * 按「图片张数」切片会少认；按时间戳则会和别的标签页互相干扰。
 * 也正因为认的是附件 id，用户同时开着收录页在解析别的图，也不会被抽进来。</p>
 *
 * <p>结束判定是「running 归零」，超时 60 秒兜底：单线程串行 + 每张最多 60 秒读超时，
 * 3 张理论上最坏 3 分钟 —— 但那种情况下让用户干等也没意义，不如放他手动填。</p>
 */
async function waitForParsedImages(wanted){
  const deadline=Date.now()+60000;
  while(Date.now()<deadline){
    let status;
    try{status=await WorkbenchApi.parseStatus()}catch(error){break}
    state.parseStatus=status||{running:0,failed:0,success:0,lastError:null};
    renderParseStatus();
    if((status.running||0)===0)break;
    await new Promise(resolve=>setTimeout(resolve,PARSE_POLL_MS));
  }
  stopParsePoll();
  try{
    // 待确认列表就是 status='processed' 那一批（与 refreshAll 里第 5 个请求同一个）。
    state.classify=(await WorkbenchApi.inbox("processed"))||state.classify;
  }catch(error){/* 列表刷新失败不影响预填 */}
  const wantedSet={};
  (wanted||[]).forEach(id=>{wantedSet[String(id)]=true});
  return state.classify.filter(item=>item.origin==="image"
    && wantedSet[String(item.sourceAttachmentId)]);
}
async function createTask(){
  const title=$("taskTitle").value.trim();if(!title){toast("请输入任务标题");return}
  // 分组已移除（2026-09-17）：不再传 grp，一律让后端按标题 / 描述的关键词自动判定
  // （TaskCreateRequest.grp 留空即 auto，与收藏页同一套规则）。
  // 以前这里会带着「当前视图是哪一侧」预填 grp，是为了避免「在生活页里新建却被判成工作、
  // 那条任务当场从列表里消失」；现在任务页不再按分组收窄，这个顾虑自然不存在了。
  if(mediaBusy("task")){toast("附件还在上传，请稍候再创建");return}
  const created=await WorkbenchApi.createTask({title:title,priority:$("taskPriority").value,due:$("taskDue").value||null,deep:$("taskDeep").checked,blocking:$("taskBlocking").checked,note:$("taskNote").value.trim()||null,attachmentIds:mediaIds("task")});
  // 截止日期跟着一起复位成「今天」：连续建几条任务时，默认值不该停在上一张卡上。
  // （它是「默认值」不是「上次填的内容」—— 标题/备注清空是同一个道理。）
  $("taskTitle").value="";$("taskNote").value="";$("taskDeep").checked=false;$("taskBlocking").checked=false;resetAutoDefault("taskDue");mediaClear("task");await refreshAll();
  // 任务页的两个收窄条件都在页内保留：驾驶舱下钻带来的筛选、页内检索（走后端 keyword）。
  // 新任务不满足其中任何一个时就不会出现在列表里 —— 与收藏、日程同一条规则：按 id 反查，
  // 被挡住就把条件放宽并说明放宽了什么（见 createdVisibilityNote）。
  toast("任务已创建"+await createdVisibilityNote("task",created&&created.id));
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
    // 搬走之后，如果这条记录的编辑弹层正开着，必须跟着关掉 —— 否则用户面对的是一个
    // 已经进了回收站的记录，再点「保存」只会拿到 1001（请求的内容不存在）。
    // 目前只有日程弹层内部带「移至」，但判断写成按来源类型分发，将来另外两处加进来也不会漏。
    if(button.dataset.from==="event"&&String(eventEditingId)===String(id))closeEventEditor();
    if(button.dataset.from==="task"&&String(taskEditingId)===String(id))closeTaskEditor();
    await refreshAll();
    let message="「"+result.title+"」已移到「"+result.toLabel+"」；原记录在回收站里，30 天内可恢复。";
    // 附件跟着一起搬过去了：**必须说出来**。不说的话，用户下次在原位置找不到附件、
    // 在新记录上又没注意看，只会得出「移动把附件弄丢了」的结论。
    if(result.movedAttachments)message+=" 连同 "+result.movedAttachments+" 个附件一起移过去了。";
    if(result.warnings&&result.warnings.length)message+=" 注意："+result.warnings.join(" ");
    // 目标菜单那边也是「新建记录」，同样可能被那边的视图条件挡住（比如收藏的空间）。
    // 这里**不切页签**，所以不用「立刻出现在列表里」那套措辞：先把条件放宽，
    // 用户点过去时列表就是对的 —— 否则又是一次「刚移过去的东西看不到」。
    if(!createdVisible(result.toType,result.id)){
      const notes=relaxViewForCreated(result.toType);
      if(notes.length){await refreshAll();message+=" 为让它显示，已"+notes.join("；")+"。";}
    }
    toast(message,9000);
    return
  }
  // 附件缩略图 / 「+N」→ 打开预览浮层。
  // 传进去的是**这条记录的全部图片**（按归属标识从 state 回查），不是 DOM 里那几张 ——
  // 列表最多只画 3 张，用户翻到第 4 张时必须能继续翻。
  if(action==="preview-att"){
    const images=attachmentsOf(button.dataset.attOwner).filter(a=>a.image);
    if(!images.length){
      // 不算异常：列表可能是几秒前的快照（附件在别处被删或被转绑走了），刷新后自然一致。
      toast("这张图片已经不在这条记录上了，请刷新后重试");
      return
    }
    openAttViewer(images,Number(button.dataset.attIndex)||0);
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
  // 排序切换（2026-09-20）：它改的是**取数口径**，所以必须重新取数 —— 只本地重排的话，
  // 「按最后修改时间」只能在这一批（按截止时间取回来的前 100 条）里生效，
  // 而用户要看的「最近改过的那批」很可能压根不在这 100 条里（静默做错，界面上看不出来）。
  // 点当前已经生效的那一档是幂等的：不重取数、也不弹提示（弹了反而像是切成功了）。
  if(action==="sort-task"){
    if(!setTaskSort(button.dataset.sort))return;
    await reloadTasks();
    toast(taskSortMode()==="updated"?"已按最后修改时间排序":"已按截止时间排序");
    return
  }
  if(action==="clear-event-search"){clearEventQuery();await refreshAll();return}
  // 卡片上的分组徽标那一支（action==="task-group"）已于 2026-09-17 随分组功能一起删除。
  // 后端 POST /tasks/{id}/group 还在，只是任务页不再有入口 —— 不要再加回这里的分支，
  // 除非先把「改完之后这条还属不属于当前视图」那段说明补回来（那时它是防「卡片凭空消失」的关键）。
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
  // 收藏的「清除搜索」只清关键词：收藏页没有驾驶舱下钻进来的筛选，没有第二样东西要清。
  if(action==="clear-favorite-search"){clearFavoriteQuery();await refreshAll();return}
  // 任务的编辑 2026-09-18 也搬进全局弹层（与收藏一致）：入口只剩 openTaskEditor，
  // 「取消 / 保存」两条分支随之删除 —— 它们的出口在弹层内部，不再经过 handleAction。
  if(action==="edit"){openTaskEditor(id);return}
  if(action==="status"){await WorkbenchApi.changeTaskStatus(id,button.dataset.status);await refreshAll();toast("任务已改为「"+statusName(button.dataset.status)+"」");return}
  if(action==="postpone"){const task=await WorkbenchApi.postponeTask(id);await refreshAll();toast("已顺延到 "+formatDate(task.due)+"，第 "+task.postponed+" 次");return}
  // 复制任务（2026-09-21）。三步都不能省，见下。
  if(action==="duplicate"){
    // ① 防连点。复制出来的卡片和原卡片**同名**，连点两下会建出两条一模一样的任务 ——
    //    用户在列表里分不出哪条是哪条，只能靠时间线去猜。请求期间禁用按钮，
    //    失败路径由 finally 恢复（恢复不了就等于用户再也点不动这个按钮了）。
    if(button.disabled)return;
    button.disabled=true;
    try{
      const copy=await WorkbenchApi.duplicateTask(id);
      await refreshAll();
      // ② 可见性**必须排在重绘之后**：createdVisibilityNote 在需要放宽条件时自己会再调一次
      //    refreshAll，先高亮的话那一次重绘会把 hl 类整个冲掉（DOM 重建），用户看不到高亮。
      const note=await createdVisibilityNote("task",copy.id);
      // ③ 同名 → 不高亮就等于没反馈。选择器限定 #view-tasks：驾驶舱的 Top3 / 今天截止列表里
      //    也有同名条目且 DOM 更靠前，选错了高亮会落在看不见的地方（drillTask 踩过同一个坑）。
      highlightRow(document.querySelector('#view-tasks .task-card[data-task-id="'+copy.id+'"]'));
      const attachCount=copy.attachments?copy.attachments.length:0;
      toast("已复制，新任务在「待办」，截止日期 "+formatDate(copy.due)+(isDueToday(copy)?"（今天）":"")
        +(attachCount?"，附件 "+attachCount+" 个一并带过来了":"")+note);
    }catch(error){
      toast(error.message);
    }finally{
      button.disabled=false;
    }
    return
  }
  if(action==="confirm-inbox"){await confirmInbox(id);return}
  if(action==="reclassify-inbox"){await reclassifyInbox(id);return}
  if(action==="delete-inbox"){await WorkbenchApi.deleteInbox(id);await refreshAll();toast("已移入回收站，30 天内可恢复");return}
  if(action==="delete-event"){await WorkbenchApi.deleteEvent(id);await refreshAll();toast("已移入回收站，30 天内可恢复");return}
  // 日程同理：点卡片开弹层，取消 / 保存 / 删除 / 移至都收进弹层内部。
  if(action==="edit-event"){openEventEditor(id);return}
  if(action==="pin-favorite"){await WorkbenchApi.pinFavorite(id);await refreshAll();toast("已更新置顶");return}
  if(action==="archive-favorite"){await WorkbenchApi.archiveFavorite(id);await refreshAll();toast("已更新归档状态");return}
  // 收藏编辑走全局弹层（2026-09-17）：原来这三行是「就地展开卡片里那个内联面板」，
  // 面板宽度被卡片锁死（正文约 285×80px 却要装 4000 字）。现在只有一个入口 openFavoriteEditor，
  // 取消 / 保存两条分支随之删除 —— 它们的出口在弹层内部，不再经过 handleAction。
  // ⚠️ 保存路径必须只有一条：按钮点击与 Ctrl+Enter 都调到 saveFavoriteEditor()，
  // 两条路各写一份校验，早晚会出现「按钮能存的、快捷键存不了」。
  if(action==="edit-favorite"){openFavoriteEditor(id);return}
  if(action==="delete-favorite"){await WorkbenchApi.deleteFavorite(id);await refreshAll();toast("已移入回收站，30 天内可恢复");return}
  if(action==="retry-knowledge-index"){await loadKnowledgeIndex();return}
  if(action==="sync-knowledge"){await WorkbenchApi.syncKnowledge(id);state.knowledge.indexState="idle";await refreshAll();toast("已重新同步到 Vault");return}
  if(action==="delete-knowledge"){await WorkbenchApi.deleteKnowledge(id);await refreshAll();toast("已移入回收站，30 天内可恢复（Vault 里的 .md 文件一直保留）");return}
  if(action==="delete-activity"){
    // 全应用**唯一不可逆**的删除：按 R6 规则，时间线是操作流水不是正式实体，走物理删除、不进回收站。
    // 所以这里是唯一保留二次确认的删除动作——其余删除都只是移入回收站（30 天内可恢复），
    // 按设计规范 §8.2 放宽为免确认。触发点仍是卡片角上一个小图标，且触屏下常显，
    // 越是不起眼的入口越需要这层确认。
    if(!confirm("确认删除这条时间线记录？\n\n时间线是操作流水，按设计走物理删除、不经过回收站，删了无法恢复——这一点和任务、收藏、日程的删除不一样，那些都能在回收站里找回来。"))return;
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
  // 回收站里的两个不可逆动作（2026-09-18）。顺序不能反：**先确认、再发请求** ——
  // 反过来的话，用户在确认框上点「取消」时那条数据其实已经没了，而界面上什么都不会显示。
  if(action==="delete-trash"){
    const type=button.dataset.type;
    // 标题要在发请求前从当前列表里反查：请求成功后这条就从 state.trash 里消失了，
    // 那时再想拼出「删掉的是哪条」已经晚了。
    const row=state.trash.filter(item=>item.type===type&&String(item.id)===String(id))[0];
    const name=row&&row.title?row.title:"这条记录";
    if(!confirm("确认彻底删除「"+name+"」？\n\n它会从回收站里消失，原始数据一并删除，无法恢复。"))return;
    const result=await WorkbenchApi.purgeTrash(type,id);
    await refreshAll();
    toast("已彻底删除「"+result.title+"」，无法恢复");return
  }
  if(action==="delete-trash-all"){
    // 确认框里报的是「此刻列表里有几条」；真正删掉几条以服务端返回的 count 为准 ——
    // 两次请求之间回收站可能已经变了（另一个标签页恢复了一条），拿列表长度当「删掉了多少」
    // 会报出一个没发生过的数字。
    if(!confirm("确认清空回收站？\n\n当前这 "+state.trash.length+" 条记录会被永久删除，原始数据一并删除，无法恢复。"))return;
    const result=await WorkbenchApi.clearTrash();
    await refreshAll();
    toast("回收站已清空，彻底删除 "+result.count+" 条记录",4200);return
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
  // 两张表单的日期都「默认带入当日」（任务截止 / 日程日期），走同一套：见 defaultOf 那段。
  // 必须在 refreshAll **之后**：todayString() 取的是后端下发的 dashboard.date（按用户配置时区算），
  // dashboard 还没回来时只能退回本地时钟，跨时区/跨零点会填错一天。
  try{state.status=await WorkbenchApi.authStatus();if(!state.status.initialized||!state.status.loggedIn){location.replace("/setup.html");return}$("welcome").textContent=state.status.username;$("greeting").textContent=greeting()+"，"+state.status.username;$("app").classList.remove("hidden");applyPageBackground();await refreshAll();initAutoDefault("taskDue");initAutoDefault("eventDate");initAutoDefault("eventStart");initAutoDefault("eventEnd");await initPomo();await syncParseStatus()}catch(error){toast(error.message)}
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
// ===== 全局搜索（跨 收录 / 任务 / 日程 / 收藏 / 时间线 + 回收站）=====
// 设计见 docs/全文搜索功能设计.md。四条红线，每一条都对应一类真实故障：
// ① **绝不调 refreshAll**。它一次拉 11 个接口并全量重绘 11 个视图，挂上去就等于
//    每敲一个字符把整个工作台重画一遍（现有 #favoriteSearch 正是这个毛病）。搜索走独立状态。
// ② **高亮必须先转义再包标签**，且只走 highlightMatch() 这一个出口：
//    分两处自己拼 innerHTML，迟早有一处漏掉转义（搜 <img src=x onerror=...> 会真的执行）。
// ③ **乱序响应要丢弃**。自增 seq，回来时对不上就扔掉 —— 否则「先打的字后回来」会让
//    界面上的结果与输入框里的关键词不是一回事，而用户看不出哪里不对。
// ④ **回收站命中只能跳回收站页**。任务 / 日程 / 收藏页查的都是 deleted=0，
//    跳过去根本找不到那一条，用户看到的就是「点了没反应」。
const SEARCH_LABELS={task:"任务",event:"日程",favorite:"收藏",inbox:"收录",timeline:"时间线"};
// 沿用项目既有的「单字方块」图标风格（设置页的 账/番/险/库、收藏空间的 工/生 都是这样）
const SEARCH_ICONS={task:"任",event:"程",favorite:"忘",inbox:"收",timeline:"线"};
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
  }else if(item.entity==="favorite"){
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
  }else if(item.entity==="favorite"){
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
    if(!query)summary.innerHTML='<span class="gs-muted">跨任务、日程、收藏、收录与时间线一起检索；选中后回车即可跳到那一条。</span>';
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
      listEl.innerHTML='<div class="gs-empty">试试搜「限流」找出相关的任务与收藏，或者搜「复盘」翻出上周的日程。<br>结果按 任务 → 日程 → 收藏 → 收录 → 时间线 → 回收站 分组，回车直接跳到那一条。</div>';
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
    // 已删数据的落点只有回收站。跳任务 / 日程 / 收藏页是找不到它的（那些页面查 deleted=0）。
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
  if(target.tab==="favorites"){
    const grp=target.grp==="life"?"life":"work";
    const archived=!!(item.meta&&item.meta.archived);
    // 已归档的收藏在列表里默认不显示：不替用户把开关打开，高亮必然落空。
    const reload=state.favoriteGrp!==grp||!!state.favoriteQuery||(archived&&!state.favoriteArchived);
    state.favoriteGrp=grp;clearFavoriteQuery();
    if(archived)state.favoriteArchived=true;
    setTab("favorites");
    if(reload)await refreshAll();else renderFavorites();
    const box=$("favoriteShowArchived");if(box)box.checked=!!state.favoriteArchived;
    const row=document.querySelector('#favoriteList .favorite-card[data-favorite-id="'+item.id+'"]');
    if(row)highlightRow(row);
    else toast("这条收藏不在当前空间的列表里，可到收藏页切到「"+(grp==="life"?"生活":"工作")+"」空间查看",5600);
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
// 页签栏的点击委托。回收站那枚图标按钮**排在页签栏最右边，但仍是 .tabs 的直接子元素**，
// 所以这一条委托 + setTab 里的 active 一把梭就把它一起覆盖了，不需要为它单开绑定。
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
// 用原生 HTML5 拖放而不是引入拖拽库：本页没有构建步骤，原生拖放也不会和页面滚动打架。
// ⚠️ 2026-09-17 起**拖动是任务页里唯一能把任务往前推的方式**：卡片左侧的圆形状态按钮已按用户
// 要求移除，看板下方原来那条「点圆点循环切换」的说明也一并删了。不依赖拖动的状态入口还剩两个，
// 都不在任务页：驾驶舱 Top3 的「开始 / 完成」、以及卡片操作区里的「取消 / 恢复」。
// （触屏上 HTML5 拖放不触发，任务页内因此没有「开始」的入口 —— 需要时去驾驶舱点。）
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
    // 从按钮 / 输入框上按下时不启用拖拽：用户此刻想点它、或者想选文字。
    // （2026-09-18 编辑表单搬进弹层后，卡片里已经没有任何输入类元素，这条排除列表只剩按钮。）
    if(event.target.closest("input,textarea,select,button,a,label"))return;
    card.setAttribute("draggable","true");
  });
  taskBoard.addEventListener("dragstart",event=>{
    const card=event.target.closest(".task-card");
    if(!card||event.target.closest("input,textarea,select,button,a,label")){event.preventDefault();return}
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
// 检索边走边刷（和收藏的搜索框一致）：任务页只加载前 100 条，本地过滤会漏掉第 100 条之外的匹配项。
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
$("favoriteAddButton").addEventListener("click",()=>createFavorite().catch(error=>toast(error.message)));
// 正文从单行 input 改成了多行 textarea（因为现在标题与正文分开录）：回车必须是换行，
// 保存改用 Ctrl / Cmd + Enter —— 页面上有一行文字提示，否则用户按回车没反应会以为坏了。
// 标题框里的回车用来把焦点交给正文，形成「标题 → 正文 → 保存」的顺手顺序。
$("favoriteTitle").addEventListener("keydown",event=>{if(event.key==="Enter"){event.preventDefault();$("favoriteContent").focus()}});
$("favoriteContent").addEventListener("keydown",event=>{if(event.key==="Enter"&&(event.ctrlKey||event.metaKey)){event.preventDefault();createFavorite().catch(error=>toast(error.message))}});
$("favoriteSearch").addEventListener("input",()=>{state.favoriteQuery=$("favoriteSearch").value.trim();debounceInPage("favorite",function(){reloadFavorites().catch(error=>toast(error.message))})});
$("favoriteShowArchived").addEventListener("change",()=>{state.favoriteArchived=$("favoriteShowArchived").checked;refreshAll().catch(error=>toast(error.message))});
// 收藏页的「空间 全部 / 工作 / 生活」切换。
// 以前这里写的是 `.favorite-switch:not(.task-switch)`：任务页那个分组切换器（DOM 里更靠前）
// 同样带着 .favorite-switch 类，querySelector 只取第一个匹配，于是这行会**绑到任务那排按钮上**，
// 收藏页自己的切换器一个监听都没挂。两条后果都真实伤过用户：
//   ① 任务页点分组时收藏的处理器也跟着跑，拿 dataset.grp（任务按钮上是 undefined）
//      调 setFavoriteGrp(undefined)，把 state.favoriteGrp 写成 undefined 并多发一次 refreshAll；
//   ② 收藏页的「空间」点了完全没反应 —— 「结构都在、入口没接上」的典型形态，控制台不报错。
// 2026-09-17 任务页的分组切换器整体移除，页面上只剩这一处 .favorite-switch，碰撞源没有了。
// 这里仍保留 `.favorite-switch-wrap` 前缀（而不是裸的 .favorite-switch）：本项目对共用类的约定是
// **选择器必须带上自己的容器前缀**，将来谁再加一个同类的切换器也不会又悄悄串味。
document.querySelector(".favorite-switch-wrap .favorite-switch").addEventListener("click",event=>{const button=event.target.closest(".favorite-choice");if(button)setFavoriteGrp(button.dataset.grp)});
// 驾驶舱的「今日主题」卡片已于 2026-09-20 整体下线（用户反馈「没有什么用」）：
// 一并移除的是这里的两个顶层监听、renderDashboard 里的回填、saveTheme()、api.js 的
// saveTheme 与后端 POST /dashboard/theme。**别只删 HTML 不留神这里的 themeInput 引用** ——
// 顶层监听撞上不存在的 id 会抛 TypeError，整页脚本连 initialize 一起死
// （静态检查脚本是按美元符号加圆括号的取值写法扫源码的，连注释里的这种写法都算一处引用，
//  所以注释里也别写出那个形态 —— 这里只能绕开描述。）
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
$("saveDedaoCookieButton").addEventListener("click",()=>saveDedaoSetting().catch(error=>toast(error.message)));
$("backupNowButton").addEventListener("click",()=>doBackupNow().catch(error=>toast(error.message)));
$("clearDemoButton").addEventListener("click",()=>doClearDemo().catch(error=>toast(error.message)));
$("shutdownButton").addEventListener("click",()=>doShutdown().catch(error=>toast(error.message)));
$("logoutButton").addEventListener("click",async()=>{try{await WorkbenchApi.logout()}finally{location.replace("/setup.html")}});
$("kbChatSend").addEventListener("click",()=>askKnowledge().catch(error=>toast(error.message)));
$("kbChatInput").addEventListener("keydown",event=>{if(event.key==="Enter")askKnowledge().catch(error=>toast(error.message))});
$("collectButton").addEventListener("click",()=>collectArticle().catch(error=>toast(error.message)));
$("collectInput").addEventListener("keydown",event=>{if(event.key==="Enter")collectArticle().catch(error=>toast(error.message))});
// 收藏区同时是拖拽落点：微信 PC 端里的分享卡片可以直接拖进来（2026-09-20 用户刚需）
bindCollectDrop();
$("importVaultButton").addEventListener("click",()=>importVault().catch(error=>toast(error.message)));
// ===== 全局搜索的接线 =====
// 输入即搜（防抖 200ms）。这里**只打 /api/v1/search 一个接口**：
// 现有 #favoriteSearch 是 input → refreshAll，每敲一个字符会拉 11 个接口并重绘 11 个视图。
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
// 三个「新增」区（任务 / 日程 / 收藏）折叠成 SaaS 风格的「＋ 新建」入口条：默认收起，点开才展开表单。
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
    // 展开时把「默认带入当日」的字段刷新一次：页面从昨天一直开到现在，默认值不该还是昨天
    // （只在它仍是默认值时刷新 —— 用户自己改过、或清空过的日期一个字都不动）。
    if(open){
      if(tab==="task")refreshAutoDefault("taskDue");
      if(tab==="event"){refreshAutoDefault("eventDate");refreshAutoDefault("eventStart");refreshAutoDefault("eventEnd")}
    }
    // 展开后聚焦第一个文本控件，省去一次点击；checkbox 不聚焦（它会抢焦点且看不见）。
    if(open){const first=body.querySelector("input:not([type=checkbox]),textarea");if(first)first.focus()}
  });
}
// 图片区只绑一次：它用事件委托覆盖四个入口（点击 / 拖拽 / 粘贴都在 bindMedia 内部挂）。
bindTimeChips();
bindMedia();
// 附件预览浮层：关闭 / 左右切换 / Esc / ←→ 只在这里绑一次。
// 打开浮层那一下走的是 handleAction 的 preview-att 分支（它在 document 的委托里）。
bindAttViewer();
bindComposer("task","composerToggleTask","composerBodyTask");
bindComposer("event","composerToggleEvent","composerBodyEvent");
bindComposer("favorite","composerToggleFavorite","composerBodyFavorite");
// 三个放大镜按钮：点它是**切换**（展开 ↔ 收起），收起时关键词不丢 ——
// 「还在检索」这件事由按钮上的小圆点和页面上那条提示条负责说，不靠用户记性。
function bindSearchToggle(tab,btnId){
  const btn=$(btnId);
  if(!btn)return;
  btn.addEventListener("click",()=>setSearchOpen(tab,!searchOpenFlag(tab)));
}
bindSearchToggle("task","taskSearchBtn");
bindSearchToggle("event","eventSearchBtn");
bindSearchToggle("favorite","favoriteSearchBtn");
// Esc 收起搜索框，给键盘用户留一个出口（不然只能靠 Tab 摸到那个按钮）。
// 只收起**不清空**：清空是页面上「清除搜索」按钮的事，混在一起会让人按一次 Esc 就丢了关键词。
document.addEventListener("keydown",event=>{
  if(event.key!=="Escape")return;
  ["task","event","favorite"].forEach(tab=>{if(searchOpenFlag(tab))setSearchOpen(tab,false)});
});
// 「含归档」标签：它存在的理由就是「显示已归档」跟着搜索框一起被收起来了，
// 而那个开关管的是常驻列表 —— 不补这个出口，用户勾上之后就再也找不到怎么关掉。
$("favoriteArchivedChip").addEventListener("click",()=>{state.favoriteArchived=false;refreshAll().catch(error=>toast(error.message))});
// ===== 收藏编辑弹层的事件绑定（2026-09-17）=====
// 五个出口集中挂在一处：按钮点击与键盘快捷键最终都落到同两个函数上，
// 这样「点保存」和「按 Ctrl+Enter」不可能因为各写一份而出现行为差异。
$("favoriteEditorCancel").addEventListener("click",requestCloseFavoriteEditor);
$("favoriteEditorClose").addEventListener("click",requestCloseFavoriteEditor);
$("favoriteEditorSave").addEventListener("click",()=>{saveFavoriteEditor().catch(error=>toast(error.message))});
// 点遮罩关闭必须比 event.target 与遮罩本身，不能用 closest 判断「在不在弹层内」：
// 浮层里的按钮常在点击过程中重绘自己所在的容器，事件冒到 document 时 target 已脱离文档树，
// closest 会返回 null —— 于是「点一下弹层内部」被误判成「点了外面」，弹层自己关掉且不报错。
$("favoriteEditorMask").addEventListener("click",event=>{if(event.target===$("favoriteEditorMask"))requestCloseFavoriteEditor()});
$("favoriteEditContent").addEventListener("input",favoriteEditorSyncCount);
// Esc / Ctrl+Enter 只在弹层开着时接管。用**捕获阶段**挂：既有的两条 Esc 监听
// （收搜索框、关问号气泡）都是 document 冒泡阶段的，弹层开着时按 Esc 只应该关弹层 ——
// 用 stopPropagation 把它们挡在后面，而不是去改那两条既有逻辑。
document.addEventListener("keydown",event=>{
  if(!favoriteEditorOpen())return;
  if(event.key==="Escape"){event.stopPropagation();event.preventDefault();requestCloseFavoriteEditor();return}
  if(event.key==="Enter"&&(event.ctrlKey||event.metaKey)){event.preventDefault();saveFavoriteEditor().catch(error=>toast(error.message))}
},true);
// ===== 任务 / 日程编辑弹层的事件绑定（2026-09-18）=====
// 与收藏那组同构，理由也一样：出口集中挂在一处，「点保存」和「按 Ctrl+Enter」不可能各写一份。
$("taskEditorCancel").addEventListener("click",requestCloseTaskEditor);
$("taskEditorClose").addEventListener("click",requestCloseTaskEditor);
$("taskEditorSave").addEventListener("click",()=>{saveTaskEditor().catch(error=>toast(error.message))});
// 点遮罩关闭一律比 event.target 与遮罩本身，不能用 closest 判断「在不在弹层内」——
// 理由见上面收藏那一条（浮层内的按钮常在点击过程中重绘自己的容器）。
$("taskEditorMask").addEventListener("click",event=>{if(event.target===$("taskEditorMask"))requestCloseTaskEditor()});
$("taskEditNote").addEventListener("input",taskEditorSyncCount);
$("eventEditorCancel").addEventListener("click",requestCloseEventEditor);
$("eventEditorClose").addEventListener("click",requestCloseEventEditor);
$("eventEditorSave").addEventListener("click",()=>{saveEventEditor().catch(error=>toast(error.message))});
$("eventEditorDelete").addEventListener("click",()=>{deleteEditingEvent().catch(error=>toast(error.message))});
$("eventEditorMask").addEventListener("click",event=>{if(event.target===$("eventEditorMask"))requestCloseEventEditor()});
// Esc / Ctrl+Enter 也用**捕获阶段**接管，并且只在对应弹层开着时才 stopPropagation：
// 既有那两条 Esc 监听（收页内搜索框、关问号气泡）都挂在冒泡阶段，弹层开着时按 Esc
// 只应该关弹层。收藏那条虽然先注册，但它自带 `if(!favoriteEditorOpen())return`，拦不到这里。
document.addEventListener("keydown",event=>{
  if(!taskEditorOpen()&&!eventEditorOpen())return;
  if(event.key==="Escape"){
    event.stopPropagation();event.preventDefault();
    if(taskEditorOpen())requestCloseTaskEditor();else requestCloseEventEditor();
    return;
  }
  if(event.key==="Enter"&&(event.ctrlKey||event.metaKey)){
    event.preventDefault();
    if(taskEditorOpen())saveTaskEditor().catch(error=>toast(error.message));
    else saveEventEditor().catch(error=>toast(error.message));
  }
},true);
// ===== 问号说明气泡（.q / .qt，2026-09-17）=====
// 说明文案的显隐由 CSS 的 :hover / :focus-visible 负责，这里只管「点击固定」这一条状态：
// 触屏上没有 hover，点击是唯一入口，所以它必须存在；代价是要多一条「点外面关闭」。
// ⚠️ 用**事件委托**而不是逐个绑定：所有视图都会被 refreshAll 整块重绘（innerHTML 替换），
//    逐个绑定的监听器下一次刷新就全丢了 —— 表现是「刚打开还好，刷新一次点了没反应」。
// ⚠️ 「在不在气泡里」判断必须走 composedPath()，理由与上面 #searchPanel 那条完全一样：
//    气泡内部的按钮若同步重绘容器，事件冒泡到这里时 target 已脱离文档树，closest() 返回 null，
//    于是「点气泡本体」被误判成「点了外面」，气泡自己关掉 —— 不报错，只是莫名其妙地关。
//    放在最前面还有一个原因：点气泡本体不关，用户才能选中里面的文字。
document.addEventListener("click",function(event){
  const path=typeof event.composedPath==="function"?event.composedPath():[];
  let insideBubble=false,trigger=null;
  for(let i=0;i<path.length;i++){
    const node=path[i];
    if(!node||!node.classList)continue;
    if(node.classList.contains("qt"))insideBubble=true;
    if(!trigger&&node.classList.contains("q"))trigger=node;
  }
  if(insideBubble)return;
  const pinned=document.querySelectorAll(".q.pin");
  if(trigger){
    const wasPinned=trigger.classList.contains("pin");
    for(let i=0;i<pinned.length;i++)pinned[i].classList.remove("pin");
    if(!wasPinned)trigger.classList.add("pin");
    return;
  }
  for(let i=0;i<pinned.length;i++)pinned[i].classList.remove("pin");
});
// Esc 关闭固定中的气泡。与上面那条「Esc 收起搜索框」分开写：两者互不干扰，
// 混在一条里会让「只想关气泡」的人也把搜索框一起收掉。
// ⚠️ 必须**连焦点一起摘掉**，否则按 Esc 看起来完全没反应：
//    Chrome 的 :focus-visible 启发式是「用户最近一次交互是键盘 → 当前聚焦元素算 focus-visible」，
//    而 Esc 本身就是键盘事件。于是 ESC 摘掉 .pin 之后，`.q:focus-visible>.qt{display:block}`
//    又把气泡显示着 —— 用户按了 Esc 什么都没变。真机冒烟第一轮就是这条红的。
document.addEventListener("keydown",function(event){
  if(event.key!=="Escape")return;
  const pinned=document.querySelectorAll(".q.pin");
  for(let i=0;i<pinned.length;i++)pinned[i].classList.remove("pin");
  const active=document.activeElement;
  if(active&&active.blur&&active.classList&&active.classList.contains("q"))active.blur();
});
initialize();
