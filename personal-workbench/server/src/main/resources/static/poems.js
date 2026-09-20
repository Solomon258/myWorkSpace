/* =============================================================================
   两侧留白区的竖排诗词（2026-09-19）
   用户要求：「左右两侧是空白的，希望竖着展示一些好的诗词……每次刷新都会展示新的。
   展示要好看美观」+「可选中某一段复制，也可以一键复制」+「两边都用同一首」
   +「不要小学课本里的诗词，至少初中、高中及以上」。

   为什么单独一个文件、不改 app.js：
     app.js 是一次拉 11 个接口 + 重绘 11 个视图的主链路（见技能 §2.1 第 1 条），
     诗词是纯前端装饰、不取任何数据，塞进去只会让 refreshAll 那条链路更难读。
     独立文件也顺带避开了静态检查里「app.js 里的 $() 引用必须存在」那批断言。

   为什么不挂 data-action：
     check_frontend_render.js 会遍历 index.html / app.js 里所有 data-action 取值，
     要求 handleAction 里有对应分支。本模块用自己的 data-pr-* 属性 + 局部委托，
     不参与那套检查，也不污染 handleAction。

   竖排三个必须记住的事实（都是实测出来的，别改回去）：
     ① writing-mode:vertical-rl 下 block 方向是「从右往左」，所以 <br> = 另起一列；
     ② line-height 就是「列间距」，是控制版心松紧的唯一旋钮；
     ③ 印章内部必须显式写回 horizontal-tb，否则 Chromium 会把章面两个字渲染成
        「横躺并排」（1 倍图看不出来，放大 6 倍才现形）。
   ============================================================================= */
(function () {
  "use strict";

  /* ------------------------------ 诗库 ------------------------------
     入选标准：部编版初中（七上~九下）及以上 + 毛泽东诗词。
     小学课本篇目已按用户要求整体剔除（静夜思 / 春晓 / 登鹳雀楼 / 江雪 / 相思 /
     望庐山瀑布 / 早发白帝城 / 题西林壁 / 竹石 / 石灰吟 / 山居秋暝）。
     v = 句组，一个元素 = 竖排里的一列。 */
  const POEMS = [
    /* ---- 初中 · 部编版 7~9 年级 ---- */
    { t: "观沧海", d: "汉", a: "曹操", v: [
      "东临碣石，以观沧海。", "水何澹澹，山岛竦峙。", "树木丛生，百草丰茂。", "秋风萧瑟，洪波涌起。",
      "日月之行，若出其中；", "星汉灿烂，若出其里。", "幸甚至哉，歌以咏志。"] },
    { t: "次北固山下", d: "唐", a: "王湾", v: [
      "客路青山外，行舟绿水前。", "潮平两岸阔，风正一帆悬。", "海日生残夜，江春入旧年。", "乡书何处达？归雁洛阳边。"] },
    { t: "天净沙·秋思", d: "元", a: "马致远", v: [
      "枯藤老树昏鸦，小桥流水人家，古道西风瘦马。", "夕阳西下，断肠人在天涯。"] },
    { t: "望岳", d: "唐", a: "杜甫", v: [
      "岱宗夫如何？齐鲁青未了。", "造化钟神秀，阴阳割昏晓。", "荡胸生曾云，决眦入归鸟。", "会当凌绝顶，一览众山小。"] },
    { t: "登飞来峰", d: "宋", a: "王安石", v: [
      "飞来山上千寻塔，闻说鸡鸣见日升。", "不畏浮云遮望眼，自缘身在最高层。"] },
    { t: "游山西村", d: "宋", a: "陆游", v: [
      "莫笑农家腊酒浑，丰年留客足鸡豚。", "山重水复疑无路，柳暗花明又一村。",
      "箫鼓追随春社近，衣冠简朴古风存。", "从今若许闲乘月，拄杖无时夜叩门。"] },
    { t: "己亥杂诗", d: "清", a: "龚自珍", v: [
      "浩荡离愁白日斜，吟鞭东指即天涯。", "落红不是无情物，化作春泥更护花。"] },
    { t: "黄鹤楼", d: "唐", a: "崔颢", v: [
      "昔人已乘黄鹤去，此地空余黄鹤楼。", "黄鹤一去不复返，白云千载空悠悠。",
      "晴川历历汉阳树，芳草萋萋鹦鹉洲。", "日暮乡关何处是？烟波江上使人愁。"] },
    { t: "使至塞上", d: "唐", a: "王维", v: [
      "单车欲问边，属国过居延。", "征蓬出汉塞，归雁入胡天。", "大漠孤烟直，长河落日圆。", "萧关逢候骑，都护在燕然。"] },
    { t: "春望", d: "唐", a: "杜甫", v: [
      "国破山河在，城春草木深。", "感时花溅泪，恨别鸟惊心。", "烽火连三月，家书抵万金。", "白头搔更短，浑欲不胜簪。"] },
    { t: "雁门太守行", d: "唐", a: "李贺", v: [
      "黑云压城城欲摧，甲光向日金鳞开。", "角声满天秋色里，塞上燕脂凝夜紫。",
      "半卷红旗临易水，霜重鼓寒声不起。", "报君黄金台上意，提携玉龙为君死。"] },
    { t: "赤壁", d: "唐", a: "杜牧", v: [
      "折戟沉沙铁未销，自将磨洗认前朝。", "东风不与周郎便，铜雀春深锁二乔。"] },
    { t: "饮酒·其五", d: "晋", a: "陶渊明", v: [
      "结庐在人境，而无车马喧。", "问君何能尔？心远地自偏。", "采菊东篱下，悠然见南山。",
      "山气日夕佳，飞鸟相与还。", "此中有真意，欲辨已忘言。"] },
    { t: "渔家傲", d: "宋", a: "李清照", v: [
      "天接云涛连晓雾，星河欲转千帆舞。仿佛梦魂归帝所。", "闻天语，殷勤问我归何处。",
      "我报路长嗟日暮，学诗谩有惊人句。九万里风鹏正举。", "风休住，蓬舟吹取三山去！"] },
    { t: "酬乐天扬州初逢席上见赠", d: "唐", a: "刘禹锡", v: [
      "巴山楚水凄凉地，二十三年弃置身。", "怀旧空吟闻笛赋，到乡翻似烂柯人。",
      "沉舟侧畔千帆过，病树前头万木春。", "今日听君歌一曲，暂凭杯酒长精神。"] },
    { t: "水调歌头·明月几时有", d: "宋", a: "苏轼", v: [
      "明月几时有？把酒问青天。不知天上宫阙，今夕是何年。",
      "我欲乘风归去，又恐琼楼玉宇，高处不胜寒。起舞弄清影，何似在人间。",
      "转朱阁，低绮户，照无眠。不应有恨，何事长向别时圆？",
      "人有悲欢离合，月有阴晴圆缺，此事古难全。但愿人长久，千里共婵娟。"] },
    { t: "破阵子·为陈同甫赋壮词以寄之", d: "宋", a: "辛弃疾", v: [
      "醉里挑灯看剑，梦回吹角连营。", "八百里分麾下炙，五十弦翻塞外声。沙场秋点兵。",
      "马作的卢飞快，弓如霹雳弦惊。", "了却君王天下事，赢得生前身后名。可怜白发生！"] },
    { t: "江城子·密州出猎", d: "宋", a: "苏轼", v: [
      "老夫聊发少年狂，左牵黄，右擎苍。锦帽貂裘，千骑卷平冈。",
      "为报倾城随太守，亲射虎，看孙郎。",
      "酒酣胸胆尚开张。鬓微霜，又何妨！持节云中，何日遣冯唐？",
      "会挽雕弓如满月，西北望，射天狼。"] },
    { t: "满江红·小住京华", d: "清", a: "秋瑾", v: [
      "小住京华，早又是中秋佳节。为篱下黄花开遍，秋容如拭。",
      "四面歌残终破楚，八年风味徒思浙。苦将侬强派作蛾眉，殊未屑！",
      "身不得，男儿列。心却比，男儿烈。算平生肝胆，因人常热。",
      "俗子胸襟谁识我？英雄末路当磨折。莽红尘何处觅知音？青衫湿！"] },
    { t: "过零丁洋", d: "宋", a: "文天祥", v: [
      "辛苦遭逢起一经，干戈寥落四周星。", "山河破碎风飘絮，身世浮沉雨打萍。",
      "惶恐滩头说惶恐，零丁洋里叹零丁。", "人生自古谁无死？留取丹心照汗青。"] },
    { t: "山坡羊·潼关怀古", d: "元", a: "张养浩", v: [
      "峰峦如聚，波涛如怒，山河表里潼关路。", "望西都，意踌躇。",
      "伤心秦汉经行处，宫阙万间都做了土。", "兴，百姓苦；亡，百姓苦。"] },
    { t: "南乡子·登京口北固亭有怀", d: "宋", a: "辛弃疾", v: [
      "何处望神州？满眼风光北固楼。", "千古兴亡多少事？悠悠。不尽长江滚滚流。",
      "年少万兜鍪，坐断东南战未休。", "天下英雄谁敌手？曹刘。生子当如孙仲谋。"] },
    /* ---- 高中 · 必修 / 选择性必修 ---- */
    { t: "短歌行", d: "汉", a: "曹操", v: [
      "对酒当歌，人生几何！譬如朝露，去日苦多。慨当以慷，忧思难忘。何以解忧？唯有杜康。",
      "青青子衿，悠悠我心。但为君故，沉吟至今。呦呦鹿鸣，食野之苹。我有嘉宾，鼓瑟吹笙。",
      "明明如月，何时可掇？忧从中来，不可断绝。越陌度阡，枉用相存。契阔谈䜩，心念旧恩。",
      "月明星稀，乌鹊南飞。绕树三匝，何枝可依？山不厌高，海不厌深。周公吐哺，天下归心。"] },
    { t: "归园田居·其一", d: "晋", a: "陶渊明", v: [
      "少无适俗韵，性本爱丘山。误落尘网中，一去三十年。",
      "羁鸟恋旧林，池鱼思故渊。开荒南野际，守拙归园田。",
      "方宅十余亩，草屋八九间。榆柳荫后檐，桃李罗堂前。",
      "暧暧远人村，依依墟里烟。狗吠深巷中，鸡鸣桑树颠。",
      "户庭无尘杂，虚室有余闲。久在樊笼里，复得返自然。"] },
    { t: "登高", d: "唐", a: "杜甫", v: [
      "风急天高猿啸哀，渚清沙白鸟飞回。", "无边落木萧萧下，不尽长江滚滚来。",
      "万里悲秋常作客，百年多病独登台。", "艰难苦恨繁霜鬓，潦倒新停浊酒杯。"] },
    { t: "念奴娇·赤壁怀古", d: "宋", a: "苏轼", v: [
      "大江东去，浪淘尽，千古风流人物。故垒西边，人道是，三国周郎赤壁。",
      "乱石穿空，惊涛拍岸，卷起千堆雪。江山如画，一时多少豪杰。",
      "遥想公瑾当年，小乔初嫁了，雄姿英发。羽扇纶巾，谈笑间，樯橹灰飞烟灭。",
      "故国神游，多情应笑我，早生华发。人生如梦，一尊还酹江月。"] },
    { t: "永遇乐·京口北固亭怀古", d: "宋", a: "辛弃疾", v: [
      "千古江山，英雄无觅，孙仲谋处。", "舞榭歌台，风流总被，雨打风吹去。",
      "斜阳草树，寻常巷陌，人道寄奴曾住。", "想当年，金戈铁马，气吞万里如虎。",
      "元嘉草草，封狼居胥，赢得仓皇北顾。", "四十三年，望中犹记，烽火扬州路。",
      "可堪回首，佛狸祠下，一片神鸦社鼓。", "凭谁问：廉颇老矣，尚能饭否？"] },
    { t: "声声慢", d: "宋", a: "李清照", v: [
      "寻寻觅觅，冷冷清清，凄凄惨惨戚戚。", "乍暖还寒时候，最难将息。",
      "三杯两盏淡酒，怎敌他、晚来风急！", "雁过也，正伤心，却是旧时相识。",
      "满地黄花堆积，憔悴损，如今有谁堪摘？", "守着窗儿，独自怎生得黑！",
      "梧桐更兼细雨，到黄昏、点点滴滴。", "这次第，怎一个愁字了得！"] },
    { t: "虞美人", d: "南唐", a: "李煜", v: [
      "春花秋月何时了？往事知多少。", "小楼昨夜又东风，故国不堪回首月明中。",
      "雕栏玉砌应犹在，只是朱颜改。", "问君能有几多愁？恰似一江春水向东流。"] },
    { t: "鹊桥仙", d: "宋", a: "秦观", v: [
      "纤云弄巧，飞星传恨，银汉迢迢暗度。", "金风玉露一相逢，便胜却人间无数。",
      "柔情似水，佳期如梦，忍顾鹊桥归路。", "两情若是久长时，又岂在朝朝暮暮。"] },
    { t: "蜀相", d: "唐", a: "杜甫", v: [
      "丞相祠堂何处寻？锦官城外柏森森。", "映阶碧草自春色，隔叶黄鹂空好音。",
      "三顾频烦天下计，两朝开济老臣心。", "出师未捷身先死，长使英雄泪满襟。"] },
    { t: "锦瑟", d: "唐", a: "李商隐", v: [
      "锦瑟无端五十弦，一弦一柱思华年。", "庄生晓梦迷蝴蝶，望帝春心托杜鹃。",
      "沧海月明珠有泪，蓝田日暖玉生烟。", "此情可待成追忆？只是当时已惘然。"] },
    /* ---- 毛泽东（《沁园春·长沙》为高中必修上篇目） ---- */
    { t: "沁园春·长沙", d: "一九二五年", a: "毛泽东", v: [
      "独立寒秋，湘江北去，橘子洲头。", "看万山红遍，层林尽染；漫江碧透，百舸争流。",
      "鹰击长空，鱼翔浅底，万类霜天竞自由。", "怅寥廓，问苍茫大地，谁主沉浮？",
      "携来百侣曾游。忆往昔峥嵘岁月稠。", "恰同学少年，风华正茂；书生意气，挥斥方遒。",
      "指点江山，激扬文字，粪土当年万户侯。", "曾记否，到中流击水，浪遏飞舟？"] },
    { t: "沁园春·雪", d: "一九三六年", a: "毛泽东", v: [
      "北国风光，千里冰封，万里雪飘。", "望长城内外，惟余莽莽；大河上下，顿失滔滔。",
      "山舞银蛇，原驰蜡象，欲与天公试比高。", "须晴日，看红装素裹，分外妖娆。",
      "江山如此多娇，引无数英雄竞折腰。", "惜秦皇汉武，略输文采；唐宗宋祖，稍逊风骚。",
      "一代天骄，成吉思汗，只识弯弓射大雕。", "俱往矣，数风流人物，还看今朝。"] },
    { t: "忆秦娥·娄山关", d: "一九三五年", a: "毛泽东", v: [
      "西风烈，长空雁叫霜晨月。", "霜晨月，马蹄声碎，喇叭声咽。",
      "雄关漫道真如铁，而今迈步从头越。", "从头越，苍山如海，残阳如血。"] },
    { t: "七律·长征", d: "一九三五年", a: "毛泽东", v: [
      "红军不怕远征难，万水千山只等闲。", "五岭逶迤腾细浪，乌蒙磅礴走泥丸。",
      "金沙水拍云崖暖，大渡桥横铁索寒。", "更喜岷山千里雪，三军过后尽开颜。"] },
    { t: "卜算子·咏梅", d: "一九六一年", a: "毛泽东", v: [
      "风雨送春归，飞雪迎春到。", "已是悬崖百丈冰，犹有花枝俏。",
      "俏也不争春，只把春来报。", "待到山花烂漫时，她在丛中笑。"] },
    { t: "清平乐·六盘山", d: "一九三五年", a: "毛泽东", v: [
      "天高云淡，望断南飞雁。", "不到长城非好汉，屈指行程二万。",
      "六盘山上高峰，红旗漫卷西风。", "今日长缨在手，何时缚住苍龙？"] },
    { t: "采桑子·重阳", d: "一九二九年", a: "毛泽东", v: [
      "人生易老天难老，岁岁重阳。", "今又重阳，战地黄花分外香。",
      "一年一度秋风劲，不似春光。", "胜似春光，寥廓江天万里霜。"] },
    { t: "浪淘沙·北戴河", d: "一九五四年", a: "毛泽东", v: [
      "大雨落幽燕，白浪滔天，秦皇岛外打鱼船。", "一片汪洋都不见，知向谁边？",
      "往事越千年，魏武挥鞭，东临碣石有遗篇。", "萧瑟秋风今又是，换了人间。"] },
    { t: "水调歌头·游泳", d: "一九五六年", a: "毛泽东", v: [
      "才饮长沙水，又食武昌鱼。", "万里长江横渡，极目楚天舒。",
      "不管风吹浪打，胜似闲庭信步，今日得宽余。", "子在川上曰：逝者如斯夫！",
      "风樯动，龟蛇静，起宏图。", "一桥飞架南北，天堑变通途。",
      "更立西江石壁，截断巫山云雨，高峡出平湖。", "神女应无恙，当惊世界殊。"] },
    { t: "七律·人民解放军占领南京", d: "一九四九年", a: "毛泽东", v: [
      "钟山风雨起苍黄，百万雄师过大江。", "虎踞龙盘今胜昔，天翻地覆慨而慷。",
      "宜将剩勇追穷寇，不可沽名学霸王。", "天若有情天亦老，人间正道是沧桑。"] },
    { t: "蝶恋花·答李淑一", d: "一九五七年", a: "毛泽东", v: [
      "我失骄杨君失柳，杨柳轻飏直上重霄九。", "问讯吴刚何所有，吴刚捧出桂花酒。",
      "寂寞嫦娥舒广袖，万里长空且为忠魂舞。", "忽报人间曾伏虎，泪飞顿作倾盆雨。"] }
  ];

  /* ------------------------------ 版面常量 ------------------------------
     改这些数之前先看 fit() 的注释：它们与 style.css 里的字号倍数是配对的。
     TITLE_EM / SIGN_EM 必须与 .pr-title / .pr-sign 的 font-size 一致。 */
  const APP_MAX_W   = 1120;   // .app{max-width:1120px}，两侧留白 = (视口宽 - 这个) / 2
  const BASE_FS     = 16;     // 正文基准字号
  const MIN_FS      = 12.5;   // 低于这个字号就不显示（宁可不显示，也不显示看不清的字）
  const LH_FLOOR    = 1.42;   // 列距下限：再压列就贴到一起了
  const LH_CEIL     = 1.90;   // 列距上限：列太少时不要铺得太散
  const TRACK       = 1.05;   // 每字前进量 ≈ 字号 × TRACK（含 letter-spacing 与余量）
  const TITLE_EM    = 1.28;
  const SIGN_EM     = 0.78;
  const SEAL_PX     = 34;     // 印章 26px + 与落款之间的 8px

  /* ============================ 工具 ============================ */
  function esc(s) {
    return String(s).replace(/[&<>]/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c];
    });
  }
  function hash(s) {
    let h = 0;
    for (let i = 0; i < s.length; i++) { h = (h * 31 + s.charCodeAt(i)) | 0; }
    return h;
  }

  /* 闲章：两字。豪放一路给「江山」，其余轮换。 */
  const SEALS_SOFT = ["清赏", "静观", "清欢", "清远"];
  function sealOf(p) {
    if (p.a === "毛泽东") { return "江山"; }
    return SEALS_SOFT[Math.abs(hash(p.t + p.a)) % SEALS_SOFT.length];
  }
  /* 章面在自己的 horizontal-tb 坐标系里排版，用 <br> 把两个字上下叠。
     直接继承外层 vertical-rl 会让 Chromium 把两个字渲染成「横躺并排」。 */
  function sealHTML(txt) {
    return '<span class="pr-seal">' + esc(txt).split("").join("<br>") + "</span>";
  }

  /* ============================ 排版 ============================
     竖排的两个方向各有一把尺子，而且**不对等**，这是最容易算错的地方：

       竖直方向（每列能放多少字） = 字数 × 字号倍数 × font-size × TRACK
       水平方向（这块占多宽）     = Σ 列宽系数 × line-height × font-size

     为什么水平方向要「列宽系数」而不是简单乘列数：`line-height` 是无单位倍数，
     会乘在**元素自己的 font-size** 上。`.pr-title` 是 1.28em，于是诗题那一列
     比正文列宽 1.28 倍（0.78em 的落款列理论上更窄，但印章撑到 1 倍）。
     第一版按「所有列同宽」算，宽度少算了 0.28 列 → 整块从 block-end（左边）溢出
     内容盒 2~6px，被 .pr-card 的 overflow:hidden 裁掉一丝（实测差 6.17px）。

     两个方向还互相牵制：字号变小 → 一列能放更多字 → 折行变少 → 列数变少 → 又允许字号变大。
     所以做固定点迭代（实测 3~4 轮收敛，取 8 轮封顶）。

     返回 null = 这个尺寸下放不下，调用方应当整条轨道不显示
     （而不是硬塞 —— 溢出会被 overflow:hidden 裁掉，是静默丢内容）。 */
  function layout(p, availW, availH) {
    /* em = 每列的「em 高」（字数 × 字号倍数）；wf = 每列的「宽度系数」 */
    const em = [], wf = [];
    em.push(p.t.length * TITLE_EM); wf.push(TITLE_EM);
    for (let i = 0; i < p.v.length; i++) { em.push(p.v[i].length); wf.push(1); }
    em.push((p.d + " · " + p.a).length * SIGN_EM); wf.push(1);
    const sealIdx = em.length - 1;

    let fs = BASE_FS, lh = LH_CEIL, cols = em.length;
    for (let pass = 0; pass < 8; pass++) {
      /* ① 竖直方向：一列放不下就折行成两列；同时累计真实列数与总宽度系数 */
      let n = 0, wsum = 0;
      for (let i = 0; i < em.length; i++) {
        const u = em[i] + (i === sealIdx ? SEAL_PX / fs : 0);
        const k = Math.max(1, Math.ceil(u * fs * TRACK / availH));
        n += k;
        wsum += k * wf[i];
      }
      cols = n;
      /* ② 水平方向：总宽 = wsum × line-height × font-size，反解出列距与字号 */
      let l = availW / wsum / fs;
      if (l > LH_CEIL) { l = LH_CEIL; }
      if (l < LH_FLOOR) { l = LH_FLOOR; fs = availW / wsum / l; }
      lh = l;
    }

    if (!isFinite(fs) || !isFinite(lh) || fs < MIN_FS) { return null; }
    return { fs: Math.min(fs, BASE_FS), lh: lh, cols: cols };
  }

  /* ============================ 渲染 ============================ */
  function poemHTML(p) {
    const body = p.v.map(function (l) {
      return '<span class="pr-line">' + esc(l) + "</span>";
    }).join("<br>");
    return '<div class="pr-poem">'
      + '<span class="pr-title">' + esc(p.t) + "</span><br>"
      + body + "<br>"
      + '<span class="pr-sign">' + esc(p.d + " · " + p.a) + "</span>"
      + sealHTML(sealOf(p))
      + "</div>";
  }
  function barHTML() {
    return '<div class="pr-bar">'
      + '<button class="pr-btn" type="button" data-pr-copy>复制</button>'
      + '<button class="pr-btn" type="button" data-pr-reroll>换一首</button>'
      + "</div>";
  }
  /* 一键复制的文本：横向排版、可直接粘进聊天框 */
  function plainText(p) {
    return p.t + "\n" + p.d + " · " + p.a + "\n\n" + p.v.join("\n");
  }

  /* ============================ 复制 ============================
     localhost 属于安全上下文，navigator.clipboard 正常可用；
     但用户也可能通过局域网 IP 打开，那里是非安全上下文 → 必须留 execCommand 兜底。 */
  function legacyCopy(text) {
    return new Promise(function (resolve, reject) {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.setAttribute("readonly", "");
      ta.style.position = "fixed";
      ta.style.top = "-1000px";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      let ok = false;
      try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
      document.body.removeChild(ta);
      if (ok) { resolve(); } else { reject(new Error("浏览器不允许自动复制，请手动选中后按 Ctrl+C")); }
    });
  }
  function writeClipboard(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text);
    }
    return legacyCopy(text);
  }
  /* toast 定义在 app.js（顶层函数，全局可见）。这里加 typeof 守卫：
     万一 app.js 没加载成功，诗词也不该跟着整块失效。 */
  function say(msg, ms) {
    if (typeof window.toast === "function") { window.toast(msg, ms); }
  }

  /* ============================ 装配 ============================ */
  let current = null;

  function paint(rail) {
    if (!rail || !current) { return false; }
    const poem = rail.querySelector(".pr-poem");
    if (!poem) { return false; }
    const cs = getComputedStyle(poem);
    const availW = poem.clientWidth
      - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight) - 4;
    const availH = poem.clientHeight
      - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom);
    const box = layout(current, availW, availH);
    /* 量不到尺寸（.app 还藏着）或真的摆不下，都返回 false 让 update() 决定。
       ⚠️ 这里**不能**顺手把轨道藏了：首屏那一刻量到的必然是 0，
       藏了就再也没人把它叫回来（见 style.css 里 visibility 那段说明）。 */
    if (!box) { return false; }
    poem.style.fontSize = box.fs.toFixed(2) + "px";
    poem.style.lineHeight = box.lh.toFixed(3);
    /* 列距（line-height × 字号）交给 CSS：印章要按它夹自己的尺寸，
       否则长词压到 10 列时 26px 的章会比列距宽，越过最左一列被裁角。 */
    poem.style.setProperty("--pr-pitch", (box.lh * box.fs).toFixed(2) + "px");
    rail.dataset.poemCols = String(box.cols);
    return true;
  }

  function render(rail) {
    if (!rail || !current) { return; }
    rail.innerHTML = '<div class="pr-card">' + poemHTML(current) + barHTML() + "</div>";
  }

  /* 换诗**只替换诗句本身**，操作行原地不动。
     为什么在意这一点：整块 innerHTML 重写会连 <button> 一起换掉，
     正在聚焦的那个节点随即消失，键盘用户按完「换一首」焦点会掉回 body
     （要 Tab 一整圈才能回来）。顺带也让 data-pr-* 的委托无需重绑。 */
  function swapPoem(rail) {
    const card = rail.querySelector(".pr-card");
    if (!card) { render(rail); return; }
    const holder = document.createElement("div");
    holder.innerHTML = poemHTML(current);
    const fresh = holder.firstChild;
    const old = card.querySelector(".pr-poem");
    if (old) { card.replaceChild(fresh, old); } else { card.insertBefore(fresh, card.firstChild); }
  }

  const rails = [];

  /* 两侧留白 = (文档可视宽 - 1120px) / 2。
     用它而不是 calc(100% - 1120px)：position:fixed 的百分比对的是初始包含块，
     各家对「要不要减掉滚动条」并不一致，直接算绝对值反而没有歧义。 */
  function gapWidth() {
    const vw = document.documentElement.clientWidth;
    return Math.max(0, (vw - APP_MAX_W) / 2);
  }

  /* 轨道自身的固定开销：左右 padding 10×2 + 卡片边框 1×2 + 诗句 padding 14×2 + 排版余量 4。
     ⚠️ 改 style.css 里这几个值时必须同步改这里。
     为什么要在一个「不依赖渲染」的常量上花这个代价：首屏时 `.app` 还带 hidden，
     这时候任何 clientWidth 都是 0，**不能**拿它当判据（2026-09-19 就是这么卡死过一次，
     线上表现为「强刷后根本看不到诗词」）。用算术算出来就没有这个依赖。 */
  const CHROME_W = 54;

  /* 当前宽度下放得下的诗：按「总宽度系数」筛。
     不这么做的话窄屏只有两个选择：整条轨道消失，或者长词被裁掉半列。用户要的是「总有诗看」——
     窄一点就显示绝句律诗，宽了才轮到长调。 */
  function pickPool(gap) {
    const inner = gap - CHROME_W;
    if (inner <= 0) { return []; }
    const maxWf = inner / (LH_FLOOR * MIN_FS);
    return POEMS.filter(function (p) {
      return TITLE_EM + p.v.length + 1 <= maxWf;
    });
  }

  function update() {
    const gap = gapWidth();
    rails.forEach(function (r) { r.style.width = gap + "px"; });

    const pool = pickPool(gap);
    if (!pool.length) {
      /* 窄到连最短的绝句都摆不下（约 <1380px 视口）就整条不显示 ——
         好过显示一首被 overflow:hidden 裁掉半列的诗。 */
      rails.forEach(function (r) { r.classList.remove("show"); });
      mark("off-narrow");
      return;
    }

    /* 窗口变窄后当前这首可能已经不在池子里了：换一首放得下的，
       而不是把轨道整条撤掉（那看起来像坏了）。 */
    if (!current || pool.indexOf(current) < 0) {
      current = pool[(Math.random() * pool.length) | 0];
      rails.forEach(function (r) { swapPoem(r); });
    }

    let ok = true;
    rails.forEach(function (r) { if (!paint(r)) { ok = false; } });
    rails.forEach(function (r) { r.classList.toggle("show", ok); });
    mark(ok ? "on" : "off-not-laid-out");
  }

  /* 把当前状态挂到 <html data-poem-rail>：纯诊断，无任何副作用。
     以后再遇到「看不到诗词」，看一眼这个属性就知道是「窗口太窄」还是「还没排上版」，
     不用再猜一轮。 */
  function mark(state) {
    document.documentElement.setAttribute("data-poem-rail", state);
  }

  let raf = 0;
  function scheduleUpdate() {
    if (raf) { return; }
    raf = requestAnimationFrame(function () { raf = 0; update(); });
  }

  function onClick(event) {
    const btn = event.target.closest ? event.target.closest("[data-pr-copy],[data-pr-reroll]") : null;
    if (!btn || !current) { return; }
    const rail = btn.closest(".poem-rail");
    if (btn.hasAttribute("data-pr-reroll")) {
      roll();
      return;
    }
    writeClipboard(plainText(current)).then(function () {
      const old = btn.textContent;
      btn.textContent = "已复制";
      btn.classList.add("is-done");
      setTimeout(function () { btn.textContent = old; btn.classList.remove("is-done"); }, 1400);
      say("已复制《" + current.t + "》全文");
    }).catch(function (e) {
      say(e && e.message ? e.message : "复制失败", 3200);
    });
  }

  /* 抽一首 —— 左右两条轨共用同一首（用户明确要求「两边都用同一首」）。
     只从「当前宽度放得下」的池子里抽，窄屏就不会翻出一首摆不下的长调。 */
  function roll() {
    const pool = pickPool(gapWidth());
    if (pool.length) {
      current = pool[(Math.random() * pool.length) | 0];
      rails.forEach(function (r) { swapPoem(r); });
    }
    update();
  }

  function init() {
    /* 唯一一次「有没有这个元素」的判断都走 querySelectorAll，
       不用 $("id") —— 少一个 id 也不会让整页脚本躺下。 */
    const found = document.querySelectorAll(".poem-rail");
    for (let i = 0; i < found.length; i++) { rails.push(found[i]); }
    if (!rails.length) { return; }

    rails.forEach(function (r) { r.addEventListener("click", onClick); });
    /* 尺寸变了（窗口缩放、侧栏收起、滚动条出现）都要重排。
       监听轨道自身的尺寸而不是 window.resize：轨道是被计算尺寸的，
       窗口没变但轨道变了的情况（例如 .app 显示/隐藏）它也能捕获。 */
    if (typeof ResizeObserver === "function") {
      const ro = new ResizeObserver(scheduleUpdate);
      rails.forEach(function (r) { ro.observe(r); });
    }
    window.addEventListener("resize", scheduleUpdate);

    roll();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
