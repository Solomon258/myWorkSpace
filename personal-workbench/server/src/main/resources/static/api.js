(function(global){
  async function request(path, options){
    const opts=options||{};
    const headers=Object.assign({},opts.headers||{});
    // rawBody：上传 multipart 时**绝不能**手写 Content-Type —— boundary 由浏览器生成，
    // 写死成 "multipart/form-data" 就不带 boundary，后端解析必然失败（400）。
    // 保留这个开关而不是全局放开，是为了让既有的 11 个 JSON 接口行为一个字都不变。
    if(!opts.rawBody)headers["Content-Type"]="application/json";
    const config=Object.assign({},opts,{headers:headers});
    delete config.rawBody;
    let response;
    try{response=await fetch(path,config)}catch(error){throw new Error("暂时无法连接工作台，请确认程序正在运行")}
    // 只有本项目契约（ApiResponse）的响应才带 code。带不出 code 时说明请求**压根没进业务代码**：
    // 路径写错、被网关截胡、响应体为空。此时必须把状态码和路径说出来——否则用户只看到
    // 「操作失败」或「无法识别的响应」，会把「路径写错了」这种永久性错误当成临时故障反复重试。
    // 抽成函数是因为「响应体是 JSON 但没有 code」和「响应体不是 JSON」是同一故障的两种皮，
    // 措辞必须一致，否则同一个 404 会给出两种说法。
    const transportFailure=()=>new Error("请求失败（HTTP "+response.status+"）："+path
      +(response.status===404?" 不存在，请核对路径或确认后端已就绪":""));
    let body;
    try{body=await response.json()}catch(error){
      throw response.ok?new Error("工作台返回了无法识别的响应，请稍后重试"):transportFailure()
    }
    if(response.status===401||body.code===2002){location.replace("/setup.html");throw new Error("请先登录")}
    if(response.status===409||body.code===2001){location.replace("/setup.html");throw new Error("请先完成首次配置")}
    if(!response.ok||body.code!==0){
      if(typeof body.code!=="number")throw transportFailure();
      throw new Error(body.message||"操作失败");
    }
    return body.data;
  }
  global.WorkbenchApi={
    request:request,
    authStatus:function(){return request("/api/v1/auth/status")},
    logout:function(){return request("/api/v1/auth/logout",{method:"POST"})},
    dashboard:function(){return request("/api/v1/dashboard/today")},
    tasks:function(params){const q=new URLSearchParams(params||{});return request("/api/v1/tasks"+(q.toString()?"?"+q.toString():""))},
    // 「已完成」页按天汇总（2026-09-20）。**必须走后端**：任务页一次只加载 100 条，
    // 今天完成的条目可能排在更后面，前端自己分组会把「今天完成 N 项」静默少算。
    doneSummary:function(){return request("/api/v1/tasks/done-summary")},
    createTask:function(data){return request("/api/v1/tasks",{method:"POST",body:JSON.stringify(data)})},
    updateTask:function(id,data){return request("/api/v1/tasks/"+id,{method:"PATCH",body:JSON.stringify(data)})},
    changeTaskStatus:function(id,status){return request("/api/v1/tasks/"+id+"/status",{method:"POST",body:JSON.stringify({status:status})})},
    postponeTask:function(id){return request("/api/v1/tasks/"+id+"/postpone",{method:"POST"})},
    // 只改工作 / 生活分组：卡片上的分组徽标是一键切换，走 updateTask 会把标题 / 优先级 /
    // 日期 / 备注全部回传一遍，并发场景下会把别处刚做的修改覆盖掉（丢失更新）。
    // 前端不使用（2026-09-17 起）：任务页的分组功能整体移除，卡片上的分组徽标与编辑表单里的
    // 分组下拉都没了。**后端 POST /tasks/{id}/group 保留不动** —— 任务的 grp 字段还在、
    // 后端仍在按关键词自动判定，只是前端不再有改它的入口。将来若要恢复分组，从这里接回去。
    // ⚠️「前端不使用」这五个字必须留在**定义那一行**上：check_frontend_render.js 是逐行扫的
    //    （`if (line.includes("前端不使用"))`），写在上一行的注释里它扫不到，会误报成死接口。
    changeTaskGroup:function(id,grp){return request("/api/v1/tasks/"+id+"/group",{method:"POST",body:JSON.stringify({grp:grp})})}, // 前端不使用
    deleteTask:function(id){return request("/api/v1/tasks/"+id,{method:"DELETE"})},
    // 把一条记录从任务 / 日程 / 收藏中的一个菜单搬到另一个（后端一个事务里完成：
    // 目标菜单新建 + 源记录进回收站 + 记一条流水）。
    transfer:function(data){return request("/api/v1/transfers",{method:"POST",body:JSON.stringify(data)})},
    inbox:function(status){return request("/api/v1/inbox"+(status?"?status="+encodeURIComponent(status):""))},
    // attachmentIds：先在 POST /api/v1/attachments 上传拿到 id，收录时一次性绑定
    // （留空 = 没有附件，与加这个参数之前的行为一致）。
    createInbox:function(raw,attachmentIds){return request("/api/v1/inbox",{method:"POST",body:JSON.stringify({raw:raw,attachmentIds:attachmentIds||[]})})},
    classifyInbox:function(){return request("/api/v1/inbox/classify",{method:"POST"})},
    classifyJob:function(jobId){return request("/api/v1/inbox/jobs/"+jobId)}, // 前端不使用：整理是同步完成的，前端不需要轮询；此接口保留给后端审计与排障
    reclassifyInbox:function(id){return request("/api/v1/inbox/"+id+"/reclassify",{method:"POST"})},
    confirmInbox:function(id,data){return request("/api/v1/inbox/"+id+"/confirm",{method:"POST",body:JSON.stringify(data)})},
    // 一次确认多条：图片解析一张图常出 2–3 条，逐条点「确认生成」时列表每次重排、很容易点错。
    // items = [{inboxId, confirm:{category,title,due,priority,start,end,eventType}}]
    confirmInboxItems:function(items){return request("/api/v1/inbox/confirm-items",{method:"POST",body:JSON.stringify({items:items})})},
    confirmHighConfidence:function(){return request("/api/v1/inbox/confirm-high-confidence",{method:"POST"})},
    // 图片解析：立刻返回（解析要 3–10 秒，同步等会把整页卡住），进度靠 parseStatus 轮询。
    parseImages:function(attachmentIds,source){return request("/api/v1/inbox/parse-images",{method:"POST",body:JSON.stringify({attachmentIds:attachmentIds,source:source||"web"})})},
    parseStatus:function(){return request("/api/v1/inbox/parse-status")},
    deleteInbox:function(id){return request("/api/v1/inbox/"+id,{method:"DELETE"})},
    // 日程列表有三个形态，收成一个 params 对象（与 tasks / favorites 的写法一致）：
    //   {q}       → 全文检索，命中**全部日期**（含待定区）
    //   {from,to} → 区间查询，日程页周视图一次取回所有可见周（from/to 必须成对）
    //   {date} / {} → 单日；省略 date 或传空串 = 今天
    // 后端按 q > from/to > date 的优先级处理，规则写在 EventController.list 上。
    // 早先这里刻意用了 (date, q) 两个位置参数，理由是「只差一个参数就换形态，调用处难看懂」；
    // 2026-09-13 加了区间查询后形态变成三种，位置参数反而更难读（events(null,"客户") 是什么意思？），
    // 于是改成与另外两个列表接口同构的对象入参。
    events:function(params){const q=new URLSearchParams(params||{});return request("/api/v1/events"+(q.toString()?"?"+q.toString():""))},
    pendingEvents:function(){return request("/api/v1/events/pending")},
    createEvent:function(data){return request("/api/v1/events",{method:"POST",body:JSON.stringify(data)})},
    updateEvent:function(id,data){return request("/api/v1/events/"+id,{method:"PATCH",body:JSON.stringify(data)})},
    deleteEvent:function(id){return request("/api/v1/events/"+id,{method:"DELETE"})},
    favorites:function(params){const q=new URLSearchParams(params||{});return request("/api/v1/favorites"+(q.toString()?"?"+q.toString():""))},
    createFavorite:function(data){return request("/api/v1/favorites",{method:"POST",body:JSON.stringify(data)})},
    updateFavorite:function(id,data){return request("/api/v1/favorites/"+id,{method:"PATCH",body:JSON.stringify(data)})},
    pinFavorite:function(id){return request("/api/v1/favorites/"+id+"/pin",{method:"POST"})},
    archiveFavorite:function(id){return request("/api/v1/favorites/"+id+"/archive",{method:"POST"})},
    deleteFavorite:function(id){return request("/api/v1/favorites/"+id,{method:"DELETE"})},
    pomoConfig:function(){return request("/api/v1/pomodoros/config")},
    savePomoConfig:function(data){return request("/api/v1/pomodoros/config",{method:"PUT",body:JSON.stringify(data)})},
    pomoToday:function(){return request("/api/v1/pomodoros/today")},
    completePomo:function(data){return request("/api/v1/pomodoros",{method:"POST",body:JSON.stringify(data)})},
    activity:function(limit){return request("/api/v1/activity?limit="+encodeURIComponent(limit||500))},
    deleteActivity:function(id){return request("/api/v1/activity/"+id,{method:"DELETE"})},
    settings:function(){return request("/api/v1/settings")},
    saveProfile:function(data){return request("/api/v1/settings/profile",{method:"PUT",body:JSON.stringify(data)})},
    saveAi:function(data){return request("/api/v1/settings/ai",{method:"PUT",body:JSON.stringify(data)})},
    saveObsidian:function(vaultPath){return request("/api/v1/settings/obsidian",{method:"PUT",body:JSON.stringify({vaultPath:vaultPath})})},
    // 得到登录 Cookie：收藏文章时后端带着它去抓取，已购文章才拿得到全文。
    // 后端只回「配没配」，不回传明文。
    saveDedaoCookie:function(cookie){return request("/api/v1/settings/dedao-cookie",{method:"PUT",body:JSON.stringify({cookie:cookie})})},
    backupNow:function(){return request("/api/v1/system/backup",{method:"POST",body:JSON.stringify({})})},
    backups:function(){return request("/api/v1/system/backups")},
    restoreBackup:function(fileName){return request("/api/v1/system/restore",{method:"POST",body:JSON.stringify({fileName:fileName,confirm:true})})},
    clearDemo:function(){return request("/api/v1/system/demo/clear",{method:"POST",body:JSON.stringify({confirm:true})})},
    shutdown:function(){return request("/api/v1/system/shutdown",{method:"POST"})},
    knowledgeNotes:function(){return request("/api/v1/knowledge/notes")},
    syncKnowledge:function(id){return request("/api/v1/knowledge/notes/"+id+"/sync",{method:"POST"})},
    deleteKnowledge:function(id){return request("/api/v1/knowledge/notes/"+id,{method:"DELETE"})},
    knowledgeIndex:function(){return request("/api/v1/knowledge/index")},
    knowledgeAsk:function(question){return request("/api/v1/knowledge/ask",{method:"POST",body:JSON.stringify({question:question})})},
    // 收藏文章：把得到的分享链接（或一整段分享文案）交给后端解析、按课程归档到 Vault、并录入知识库。
    collectArticle:function(content){return request("/api/v1/collect/article",{method:"POST",body:JSON.stringify({content:content})})},
    // 存量回填：把 Vault 里已有的笔记导入知识库。幂等，重复调用不会产生重复条目。
    importVault:function(dir){return request("/api/v1/knowledge/import",{method:"POST",body:JSON.stringify({dir:dir})})},
    // 全局搜索（跨 收录 / 任务 / 日程 / 收藏 / 时间线 + 回收站）。
    // 这是**独立通道**：一次输入只打这一个接口，绝不触发 refreshAll 那 11 个请求。
    // options 用来透传 AbortController 的 signal（取消上一次未完成的搜索）。
    search:function(params,options){const q=new URLSearchParams(params||{});return request("/api/v1/search"+(q.toString()?"?"+q.toString():""),options)},
    // 语义联想（独立接口，供「两段式渲染」用）：关键字结果先渲染，这个词回来再补位。
    // 后端在未配置 AI / 超时 / 模型返回垃圾时都会返回空数组且 code=0，前端不需要为它写错误分支。
    expandSearch:function(q){return request("/api/v1/search/expand",{method:"POST",body:JSON.stringify({q:q})})},
    // 回收站（US-1.5）。type 是后端约定的实体名：inbox / task / event / favorite / knowledge。
    trash:function(){return request("/api/v1/trash")},
    restoreTrash:function(type,id){return request("/api/v1/trash/"+encodeURIComponent(type)+"/"+id+"/restore",{method:"POST"})},
    // 彻底删除：没有 /restore 那样的动词后缀，DELETE 到记录本身。它删的是原始数据，
    // 不是「从回收站里挪走」，所以调用方必须先二次确认（app.js 的 delete-trash 分支）。
    purgeTrash:function(type,id){return request("/api/v1/trash/"+encodeURIComponent(type)+"/"+id,{method:"DELETE"})},
    clearTrash:function(){return request("/api/v1/trash",{method:"DELETE"})},
    // 上传单个附件。多选时前端**循环调用**：每张图各自有进度、可单独重试，
    // 一张失败不拖垮其余几张（后端也只为单文件设计）。
    // owner 可省略（先上传、提交表单时再绑定），传了就当场归属。
    uploadAttachment:function(file,owner){
      const form=new FormData();
      form.append("file",file);
      if(owner&&owner.ownerType){
        form.append("ownerType",owner.ownerType);
        form.append("ownerId",String(owner.ownerId));
      }
      return request("/api/v1/attachments",{method:"POST",body:form,rawBody:true});
    },
    // 移除缩略图时删掉刚上传的附件（软删，30 天内可恢复）。
    deleteAttachment:function(id){return request("/api/v1/attachments/"+id,{method:"DELETE"})},
    // 诗词品读（全屏展示用）：后端拿「诗本身」去调 AI，同一首第二次进会命中服务端缓存。
    // 只传纯文本，诗库仍在前端 poems.js 里 —— 后端不认识任何一首诗。
    reflectPoem:function(poem){return request("/api/v1/poems/reflect",{method:"POST",body:JSON.stringify(poem)})}
  };
})(window);
