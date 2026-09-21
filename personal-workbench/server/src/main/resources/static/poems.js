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
     入选标准（2026-09-20 用户重定，旧的「初中及以上」标准作废）：
     按「来源教科书」配比，小学篇目一律不收 ——
       高中 · 人教统编版必修 + 选择性必修                  24 首 = 40%
       初中 · 部编版 7~9 年级                              6 首 = 10%
       大学中文系 · 古代文学作品选 / 文学史经典（中学课本外） 30 首 = 50%
     用户原话：「诗词的选取都太普通了，几乎都是初中教科书的内容……至少要求是
     高中教科书的内容，可以占比 40%，初中教科书的占比 10%，大学中文系学的诗词占比 50%」。
     三类分别是 g=senior / junior / college，只作统计与自检用，不参与渲染。
     v = 句组，一个元素 = 竖排里的一列（一列放不下时由 layout() 自动折列）。
     配比与去重由 output/poetry-pool/check_poems.js 守着，增删篇目请同步跑它。 */
  const POEMS = [
    /* ================= 初中 · 部编版 7~9 年级（6 首 · 10%） ================= */
    { g: "junior", t: "观沧海", d: "汉", a: "曹操", v: [
      "东临碣石，以观沧海。", "水何澹澹，山岛竦峙。", "树木丛生，百草丰茂。", "秋风萧瑟，洪波涌起。",
      "日月之行，若出其中；", "星汉灿烂，若出其里。", "幸甚至哉，歌以咏志。"] },
    { g: "junior", t: "望岳", d: "唐", a: "杜甫", v: [
      "岱宗夫如何？齐鲁青未了。", "造化钟神秀，阴阳割昏晓。", "荡胸生曾云，决眦入归鸟。", "会当凌绝顶，一览众山小。"] },
    { g: "junior", t: "登飞来峰", d: "宋", a: "王安石", v: [
      "飞来山上千寻塔，闻说鸡鸣见日升。", "不畏浮云遮望眼，自缘身在最高层。"] },
    { g: "junior", t: "己亥杂诗", d: "清", a: "龚自珍", v: [
      "浩荡离愁白日斜，吟鞭东指即天涯。", "落红不是无情物，化作春泥更护花。"] },
    { g: "junior", t: "天净沙·秋思", d: "元", a: "马致远", v: [
      "枯藤老树昏鸦，小桥流水人家，古道西风瘦马。", "夕阳西下，断肠人在天涯。"] },
    { g: "junior", t: "山坡羊·潼关怀古", d: "元", a: "张养浩", v: [
      "峰峦如聚，波涛如怒，山河表里潼关路。", "望西都，意踌躇。",
      "伤心秦汉经行处，宫阙万间都做了土。", "兴，百姓苦；亡，百姓苦。"] },

    /* ================= 高中 · 必修 + 选择性必修（24 首 · 40%） ================= */
    /* -- 必修上册 -- */
    { g: "senior", t: "沁园春·长沙", d: "一九二五年", a: "毛泽东", v: [
      "独立寒秋，湘江北去，橘子洲头。", "看万山红遍，层林尽染；漫江碧透，百舸争流。",
      "鹰击长空，鱼翔浅底，万类霜天竞自由。", "怅寥廓，问苍茫大地，谁主沉浮？",
      "携来百侣曾游。忆往昔峥嵘岁月稠。", "恰同学少年，风华正茂；书生意气，挥斥方遒。",
      "指点江山，激扬文字，粪土当年万户侯。", "曾记否，到中流击水，浪遏飞舟？"] },
    { g: "senior", t: "短歌行", d: "汉", a: "曹操", v: [
      "对酒当歌，人生几何！譬如朝露，去日苦多。慨当以慷，忧思难忘。何以解忧？唯有杜康。",
      "青青子衿，悠悠我心。但为君故，沉吟至今。呦呦鹿鸣，食野之苹。我有嘉宾，鼓瑟吹笙。",
      "明明如月，何时可掇？忧从中来，不可断绝。越陌度阡，枉用相存。契阔谈䜩，心念旧恩。",
      "月明星稀，乌鹊南飞。绕树三匝，何枝可依？山不厌高，海不厌深。周公吐哺，天下归心。"] },
    { g: "senior", t: "归园田居·其一", d: "晋", a: "陶渊明", v: [
      "少无适俗韵，性本爱丘山。误落尘网中，一去三十年。",
      "羁鸟恋旧林，池鱼思故渊。开荒南野际，守拙归园田。",
      "方宅十余亩，草屋八九间。榆柳荫后檐，桃李罗堂前。",
      "暧暧远人村，依依墟里烟。狗吠深巷中，鸡鸣桑树颠。",
      "户庭无尘杂，虚室有余闲。久在樊笼里，复得返自然。"] },
    { g: "senior", t: "梦游天姥吟留别", d: "唐", a: "李白", v: [
      "海客谈瀛洲，烟涛微茫信难求；越人语天姥，云霞明灭或可睹。",
      "天姥连天向天横，势拔五岳掩赤城。天台四万八千丈，对此欲倒东南倾。",
      "我欲因之梦吴越，一夜飞度镜湖月。湖月照我影，送我至剡溪。",
      "谢公宿处今尚在，渌水荡漾清猿啼。脚著谢公屐，身登青云梯。",
      "半壁见海日，空中闻天鸡。千岩万壑路不定，迷花倚石忽已暝。",
      "熊咆龙吟殷岩泉，栗深林兮惊层巅。云青青兮欲雨，水澹澹兮生烟。",
      "列缺霹雳，丘峦崩摧。洞天石扉，訇然中开。",
      "青冥浩荡不见底，日月照耀金银台。霓为衣兮风为马，云之君兮纷纷而来下。",
      "虎鼓瑟兮鸾回车，仙之人兮列如麻。忽魂悸以魄动，恍惊起而长嗟。",
      "惟觉时之枕席，失向来之烟霞。世间行乐亦如此，古来万事东流水。",
      "别君去兮何时还？且放白鹿青崖间，须行即骑访名山。安能摧眉折腰事权贵，使我不得开心颜！"] },
    { g: "senior", t: "登高", d: "唐", a: "杜甫", v: [
      "风急天高猿啸哀，渚清沙白鸟飞回。", "无边落木萧萧下，不尽长江滚滚来。",
      "万里悲秋常作客，百年多病独登台。", "艰难苦恨繁霜鬓，潦倒新停浊酒杯。"] },
    { g: "senior", t: "念奴娇·赤壁怀古", d: "宋", a: "苏轼", v: [
      "大江东去，浪淘尽，千古风流人物。故垒西边，人道是，三国周郎赤壁。",
      "乱石穿空，惊涛拍岸，卷起千堆雪。江山如画，一时多少豪杰。",
      "遥想公瑾当年，小乔初嫁了，雄姿英发。羽扇纶巾，谈笑间，樯橹灰飞烟灭。",
      "故国神游，多情应笑我，早生华发。人生如梦，一尊还酹江月。"] },
    { g: "senior", t: "永遇乐·京口北固亭怀古", d: "宋", a: "辛弃疾", v: [
      "千古江山，英雄无觅，孙仲谋处。", "舞榭歌台，风流总被，雨打风吹去。",
      "斜阳草树，寻常巷陌，人道寄奴曾住。", "想当年，金戈铁马，气吞万里如虎。",
      "元嘉草草，封狼居胥，赢得仓皇北顾。", "四十三年，望中犹记，烽火扬州路。",
      "可堪回首，佛狸祠下，一片神鸦社鼓。", "凭谁问：廉颇老矣，尚能饭否？"] },
    { g: "senior", t: "声声慢", d: "宋", a: "李清照", v: [
      "寻寻觅觅，冷冷清清，凄凄惨惨戚戚。", "乍暖还寒时候，最难将息。",
      "三杯两盏淡酒，怎敌他、晚来风急！", "雁过也，正伤心，却是旧时相识。",
      "满地黄花堆积，憔悴损，如今有谁堪摘？", "守着窗儿，独自怎生得黑！",
      "梧桐更兼细雨，到黄昏、点点滴滴。", "这次第，怎一个愁字了得！"] },
    { g: "senior", t: "虞美人", d: "南唐", a: "李煜", v: [
      "春花秋月何时了？往事知多少。", "小楼昨夜又东风，故国不堪回首月明中。",
      "雕栏玉砌应犹在，只是朱颜改。", "问君能有几多愁？恰似一江春水向东流。"] },
    { g: "senior", t: "鹊桥仙", d: "宋", a: "秦观", v: [
      "纤云弄巧，飞星传恨，银汉迢迢暗度。", "金风玉露一相逢，便胜却人间无数。",
      "柔情似水，佳期如梦，忍顾鹊桥归路。", "两情若是久长时，又岂在朝朝暮暮。"] },
    /* -- 必修下册（古诗词诵读） -- */
    { g: "senior", t: "静女", d: "先秦", a: "《诗经·邶风》", v: [
      "静女其姝，俟我于城隅。爱而不见，搔首踟蹰。",
      "静女其娈，贻我彤管。彤管有炜，说怿女美。",
      "自牧归荑，洵美且异。匪女之为美，美人之贻。"] },
    { g: "senior", t: "无衣", d: "先秦", a: "《诗经·秦风》", v: [
      "岂曰无衣？与子同袍。王于兴师，修我戈矛。与子同仇！",
      "岂曰无衣？与子同泽。王于兴师，修我矛戟。与子偕作！",
      "岂曰无衣？与子同裳。王于兴师，修我甲兵。与子偕行！"] },
    { g: "senior", t: "涉江采芙蓉", d: "汉", a: "《古诗十九首》", v: [
      "涉江采芙蓉，兰泽多芳草。采之欲遗谁？所思在远道。",
      "还顾望旧乡，长路漫浩浩。同心而离居，忧伤以终老。"] },
    /* -- 选择性必修上册 -- */
    { g: "senior", t: "春江花月夜", d: "唐", a: "张若虚", v: [
      "春江潮水连海平，海上明月共潮生。滟滟随波千万里，何处春江无月明！",
      "江流宛转绕芳甸，月照花林皆似霰。空里流霜不觉飞，汀上白沙看不见。",
      "江天一色无纤尘，皎皎空中孤月轮。江畔何人初见月？江月何年初照人？",
      "人生代代无穷已，江月年年望相似。不知江月待何人，但见长江送流水。",
      "白云一片去悠悠，青枫浦上不胜愁。谁家今夜扁舟子？何处相思明月楼？",
      "可怜楼上月徘徊，应照离人妆镜台。玉户帘中卷不去，捣衣砧上拂还来。",
      "此时相望不相闻，愿逐月华流照君。鸿雁长飞光不度，鱼龙潜跃水成文。",
      "昨夜闲潭梦落花，可怜春半不还家。江水流春去欲尽，江潭落月复西斜。",
      "斜月沉沉藏海雾，碣石潇湘无限路。不知乘月几人归，落月摇情满江树。"] },
    { g: "senior", t: "将进酒", d: "唐", a: "李白", v: [
      "君不见，黄河之水天上来，奔流到海不复回。君不见，高堂明镜悲白发，朝如青丝暮成雪。",
      "人生得意须尽欢，莫使金樽空对月。天生我材必有用，千金散尽还复来。",
      "烹羊宰牛且为乐，会须一饮三百杯。岑夫子，丹丘生，将进酒，杯莫停。",
      "与君歌一曲，请君为我倾耳听。钟鼓馔玉不足贵，但愿长醉不复醒。",
      "古来圣贤皆寂寞，惟有饮者留其名。陈王昔时宴平乐，斗酒十千恣欢谑。",
      "主人何为言少钱，径须沽取对君酌。五花马，千金裘，呼儿将出换美酒，与尔同销万古愁。"] },
    { g: "senior", t: "江城子·乙卯正月二十日夜记梦", d: "宋", a: "苏轼", v: [
      "十年生死两茫茫，不思量，自难忘。千里孤坟，无处话凄凉。",
      "纵使相逢应不识，尘满面，鬓如霜。",
      "夜来幽梦忽还乡，小轩窗，正梳妆。相顾无言，惟有泪千行。",
      "料得年年肠断处，明月夜，短松冈。"] },
    /* -- 选择性必修中册 -- */
    { g: "senior", t: "燕歌行", d: "唐", a: "高适", v: [
      "汉家烟尘在东北，汉将辞家破残贼。", "男儿本自重横行，天子非常赐颜色。",
      "摐金伐鼓下榆关，旌旆逶迤碣石间。", "校尉羽书飞瀚海，单于猎火照狼山。",
      "山川萧条极边土，胡骑凭陵杂风雨。", "战士军前半死生，美人帐下犹歌舞。",
      "大漠穷秋塞草腓，孤城落日斗兵稀。", "身当恩遇恒轻敌，力尽关山未解围。",
      "铁衣远戍辛勤久，玉箸应啼别离后。", "少妇城南欲断肠，征人蓟北空回首。",
      "边庭飘飖那可度，绝域苍茫更何有。", "杀气三时作阵云，寒声一夜传刁斗。",
      "相看白刃血纷纷，死节从来岂顾勋。", "君不见沙场征战苦，至今犹忆李将军。"] },
    { g: "senior", t: "李凭箜篌引", d: "唐", a: "李贺", v: [
      "吴丝蜀桐张高秋，空山凝云颓不流。", "江娥啼竹素女愁，李凭中国弹箜篌。",
      "昆山玉碎凤凰叫，芙蓉泣露香兰笑。", "十二门前融冷光，二十三丝动紫皇。",
      "女娲炼石补天处，石破天惊逗秋雨。", "梦入神山教神妪，老鱼跳波瘦蛟舞。",
      "吴质不眠倚桂树，露脚斜飞湿寒兔。"] },
    { g: "senior", t: "锦瑟", d: "唐", a: "李商隐", v: [
      "锦瑟无端五十弦，一弦一柱思华年。", "庄生晓梦迷蝴蝶，望帝春心托杜鹃。",
      "沧海月明珠有泪，蓝田日暖玉生烟。", "此情可待成追忆？只是当时已惘然。"] },
    { g: "senior", t: "书愤", d: "宋", a: "陆游", v: [
      "早岁那知世事艰，中原北望气如山。", "楼船夜雪瓜洲渡，铁马秋风大散关。",
      "塞上长城空自许，镜中衰鬓已先斑。", "出师一表真名世，千载谁堪伯仲间。"] },
    /* -- 选择性必修下册 -- */
    { g: "senior", t: "氓", d: "先秦", a: "《诗经·卫风》", v: [
      "氓之蚩蚩，抱布贸丝。匪来贸丝，来即我谋。送子涉淇，至于顿丘。匪我愆期，子无良媒。将子无怒，秋以为期。",
      "乘彼垝垣，以望复关。不见复关，泣涕涟涟。既见复关，载笑载言。尔卜尔筮，体无咎言。以尔车来，以我贿迁。",
      "桑之未落，其叶沃若。于嗟鸠兮，无食桑葚！于嗟女兮，无与士耽！士之耽兮，犹可说也。女之耽兮，不可说也。",
      "桑之落矣，其黄而陨。自我徂尔，三岁食贫。淇水汤汤，渐车帷裳。女也不爽，士贰其行。士也罔极，二三其德。",
      "三岁为妇，靡室劳矣。夙兴夜寐，靡有朝矣。言既遂矣，至于暴矣。兄弟不知，咥其笑矣。静言思之，躬自悼矣。",
      "及尔偕老，老使我怨。淇则有岸，隰则有泮。总角之宴，言笑晏晏。信誓旦旦，不思其反。反是不思，亦已焉哉！"] },
    { g: "senior", t: "蜀相", d: "唐", a: "杜甫", v: [
      "丞相祠堂何处寻？锦官城外柏森森。", "映阶碧草自春色，隔叶黄鹂空好音。",
      "三顾频烦天下计，两朝开济老臣心。", "出师未捷身先死，长使英雄泪满襟。"] },
    { g: "senior", t: "望海潮·东南形胜", d: "宋", a: "柳永", v: [
      "东南形胜，三吴都会，钱塘自古繁华。", "烟柳画桥，风帘翠幕，参差十万人家。",
      "云树绕堤沙。怒涛卷霜雪，天堑无涯。", "市列珠玑，户盈罗绮，竞豪奢。",
      "重湖叠巘清嘉。有三秋桂子，十里荷花。", "羌管弄晴，菱歌泛夜，嬉嬉钓叟莲娃。",
      "千骑拥高牙。乘醉听箫鼓，吟赏烟霞。", "异日图将好景，归去凤池夸。"] },
    { g: "senior", t: "扬州慢·淮左名都", d: "宋", a: "姜夔", v: [
      "淮左名都，竹西佳处，解鞍少驻初程。", "过春风十里，尽荠麦青青。",
      "自胡马窥江去后，废池乔木，犹厌言兵。", "渐黄昏，清角吹寒，都在空城。",
      "杜郎俊赏，算而今重到须惊。", "纵豆蔻词工，青楼梦好，难赋深情。",
      "二十四桥仍在，波心荡，冷月无声。", "念桥边红药，年年知为谁生？"] },

    /* ============ 大学中文系 · 古代文学作品选 / 文学史经典（30 首 · 50%） ============ */
    /* -- 先秦两汉 -- */
    { g: "college", t: "黍离", d: "先秦", a: "《诗经·王风》", v: [
      "彼黍离离，彼稷之苗。行迈靡靡，中心摇摇。知我者，谓我心忧；不知我者，谓我何求。悠悠苍天，此何人哉？",
      "彼黍离离，彼稷之穗。行迈靡靡，中心如醉。知我者，谓我心忧；不知我者，谓我何求。悠悠苍天，此何人哉？",
      "彼黍离离，彼稷之实。行迈靡靡，中心如噎。知我者，谓我心忧；不知我者，谓我何求。悠悠苍天，此何人哉？"] },
    { g: "college", t: "湘夫人", d: "战国", a: "屈原", v: [
      "帝子降兮北渚，目眇眇兮愁予。袅袅兮秋风，洞庭波兮木叶下。",
      "登白薠兮骋望，与佳期兮夕张。鸟何萃兮蘋中，罾何为兮木上？",
      "沅有芷兮澧有兰，思公子兮未敢言。荒忽兮远望，观流水兮潺湲。"] },
    { g: "college", t: "行行重行行", d: "汉", a: "《古诗十九首》", v: [
      "行行重行行，与君生别离。相去万余里，各在天一涯。",
      "道路阻且长，会面安可知？胡马依北风，越鸟巢南枝。",
      "相去日已远，衣带日已缓。浮云蔽白日，游子不顾反。",
      "思君令人老，岁月忽已晚。弃捐勿复道，努力加餐饭。"] },
    { g: "college", t: "西北有高楼", d: "汉", a: "《古诗十九首》", v: [
      "西北有高楼，上与浮云齐。交疏结绮窗，阿阁三重阶。",
      "上有弦歌声，音响一何悲！谁能为此曲？无乃杞梁妻。",
      "清商随风发，中曲正徘徊。一弹再三叹，慷慨有余哀。",
      "不惜歌者苦，但伤知音稀。愿为双鸿鹄，奋翅起高飞。"] },
    { g: "college", t: "上邪", d: "汉", a: "乐府民歌", v: [
      "上邪！我欲与君相知，长命无绝衰。",
      "山无陵，江水为竭，冬雷震震，夏雨雪，天地合，乃敢与君绝！"] },
    { g: "college", t: "饮马长城窟行", d: "汉", a: "乐府民歌", v: [
      "青青河畔草，绵绵思远道。远道不可思，宿昔梦见之。",
      "梦见在我傍，忽觉在他乡。他乡各异县，展转不相见。",
      "枯桑知天风，海水知天寒。入门各自媚，谁肯相为言！",
      "客从远方来，遗我双鲤鱼。呼儿烹鲤鱼，中有尺素书。",
      "长跪读素书，书中竟何如？上言加餐饭，下言长相忆。"] },
    /* -- 魏晋南北朝 -- */
    { g: "college", t: "白马篇", d: "魏", a: "曹植", v: [
      "白马饰金羁，连翩西北驰。借问谁家子？幽并游侠儿。",
      "少小去乡邑，扬声沙漠垂。宿昔秉良弓，楛矢何参差。",
      "控弦破左的，右发摧月支。仰手接飞猱，俯身散马蹄。",
      "狡捷过猴猿，勇剽若豹螭。边城多警急，虏骑数迁移。",
      "羽檄从北来，厉马登高堤。长驱蹈匈奴，左顾陵鲜卑。",
      "弃身锋刃端，性命安可怀？父母且不顾，何言子与妻！",
      "名编壮士籍，不得中顾私。捐躯赴国难，视死忽如归！"] },
    { g: "college", t: "咏怀·其一", d: "魏", a: "阮籍", v: [
      "夜中不能寐，起坐弹鸣琴。薄帷鉴明月，清风吹我襟。",
      "孤鸿号外野，翔鸟鸣北林。徘徊将何见？忧思独伤心。"] },
    { g: "college", t: "晚登三山还望京邑", d: "南朝", a: "谢朓", v: [
      "灞涘望长安，河阳视京县。白日丽飞甍，参差皆可见。",
      "余霞散成绮，澄江静如练。喧鸟覆春洲，杂英满芳甸。",
      "去矣方滞淫，怀哉罢欢宴。佳期怅何许，泪下如流霰。",
      "有情知望乡，谁能鬒不变？"] },
    { g: "college", t: "拟行路难·其六", d: "南朝", a: "鲍照", v: [
      "对案不能食，拔剑击柱长叹息。", "丈夫生世会几时？安能蹀躞垂羽翼！",
      "弃置罢官去，还家自休息。朝出与亲辞，暮还在亲侧。",
      "弄儿床前戏，看妇机中织。自古圣贤尽贫贱，何况我辈孤且直！"] },
    { g: "college", t: "西洲曲", d: "南朝", a: "乐府民歌", v: [
      "忆梅下西洲，折梅寄江北。单衫杏子红，双鬓鸦雏色。",
      "西洲在何处？两桨桥头渡。日暮伯劳飞，风吹乌臼树。",
      "采莲南塘秋，莲花过人头。低头弄莲子，莲子清如水。",
      "置莲怀袖中，莲心彻底红。忆郎郎不至，仰首望飞鸿。"] },
    /* -- 唐五代 -- */
    { g: "college", t: "月下独酌·其一", d: "唐", a: "李白", v: [
      "花间一壶酒，独酌无相亲。举杯邀明月，对影成三人。",
      "月既不解饮，影徒随我身。暂伴月将影，行乐须及春。",
      "我歌月徘徊，我舞影零乱。醒时同交欢，醉后各分散。",
      "永结无情游，相期邈云汉。"] },
    { g: "college", t: "秋兴八首·其一", d: "唐", a: "杜甫", v: [
      "玉露凋伤枫树林，巫山巫峡气萧森。", "江间波浪兼天涌，塞上风云接地阴。",
      "丛菊两开他日泪，孤舟一系故园心。", "寒衣处处催刀尺，白帝城高急暮砧。"] },
    { g: "college", t: "走马川行奉送封大夫出师西征", d: "唐", a: "岑参", v: [
      "君不见走马川行雪海边，平沙莽莽黄入天。",
      "轮台九月风夜吼，一川碎石大如斗，随风满地石乱走。",
      "匈奴草黄马正肥，金山西见烟尘飞，汉家大将西出师。",
      "将军金甲夜不脱，半夜军行戈相拨，风头如刀面如割。",
      "马毛带雪汗气蒸，五花连钱旋作冰，幕中草檄砚水凝。",
      "虏骑闻之应胆慑，料知短兵不敢接，车师西门伫献捷。"] },
    { g: "college", t: "金铜仙人辞汉歌", d: "唐", a: "李贺", v: [
      "茂陵刘郎秋风客，夜闻马嘶晓无迹。", "画栏桂树悬秋香，三十六宫土花碧。",
      "魏官牵车指千里，东关酸风射眸子。", "空将汉月出宫门，忆君清泪如铅水。",
      "衰兰送客咸阳道，天若有情天亦老。", "携盘独出月荒凉，渭城已远波声小。"] },
    { g: "college", t: "无题·昨夜星辰昨夜风", d: "唐", a: "李商隐", v: [
      "昨夜星辰昨夜风，画楼西畔桂堂东。", "身无彩凤双飞翼，心有灵犀一点通。",
      "隔座送钩春酒暖，分曹射覆蜡灯红。", "嗟余听鼓应官去，走马兰台类转蓬。"] },
    { g: "college", t: "菩萨蛮·小山重叠金明灭", d: "唐", a: "温庭筠", v: [
      "小山重叠金明灭，鬓云欲度香腮雪。", "懒起画蛾眉，弄妆梳洗迟。",
      "照花前后镜，花面交相映。", "新帖绣罗襦，双双金鹧鸪。"] },
    { g: "college", t: "浪淘沙令·帘外雨潺潺", d: "南唐", a: "李煜", v: [
      "帘外雨潺潺，春意阑珊。罗衾不耐五更寒。梦里不知身是客，一晌贪欢。",
      "独自莫凭阑，无限江山。别时容易见时难。流水落花春去也，天上人间。"] },
    /* -- 宋 -- */
    { g: "college", t: "雨霖铃·寒蝉凄切", d: "宋", a: "柳永", v: [
      "寒蝉凄切，对长亭晚，骤雨初歇。都门帐饮无绪，留恋处，兰舟催发。",
      "执手相看泪眼，竟无语凝噎。念去去，千里烟波，暮霭沉沉楚天阔。",
      "多情自古伤离别，更那堪，冷落清秋节！今宵酒醒何处？杨柳岸，晓风残月。",
      "此去经年，应是良辰好景虚设。便纵有千种风情，更与何人说？"] },
    { g: "college", t: "临江仙·梦后楼台高锁", d: "宋", a: "晏几道", v: [
      "梦后楼台高锁，酒醒帘幕低垂。去年春恨却来时。落花人独立，微雨燕双飞。",
      "记得小蘋初见，两重心字罗衣。琵琶弦上说相思。当时明月在，曾照彩云归。"] },
    { g: "college", t: "水龙吟·次韵章质夫杨花词", d: "宋", a: "苏轼", v: [
      "似花还似非花，也无人惜从教坠。抛家傍路，思量却是，无情有思。",
      "萦损柔肠，困酣娇眼，欲开还闭。梦随风万里，寻郎去处，又还被、莺呼起。",
      "不恨此花飞尽，恨西园、落红难缀。晓来雨过，遗踪何在？一池萍碎。",
      "春色三分，二分尘土，一分流水。细看来，不是杨花，点点是离人泪。"] },
    { g: "college", t: "武陵春·风住尘香花已尽", d: "宋", a: "李清照", v: [
      "风住尘香花已尽，日晚倦梳头。物是人非事事休，欲语泪先流。",
      "闻说双溪春尚好，也拟泛轻舟。只恐双溪舴艋舟，载不动许多愁。"] },
    { g: "college", t: "水龙吟·登建康赏心亭", d: "宋", a: "辛弃疾", v: [
      "楚天千里清秋，水随天去秋无际。遥岑远目，献愁供恨，玉簪螺髻。",
      "落日楼头，断鸿声里，江南游子。把吴钩看了，栏杆拍遍，无人会，登临意。",
      "休说鲈鱼堪脍，尽西风，季鹰归未？求田问舍，怕应羞见，刘郎才气。",
      "可惜流年，忧愁风雨，树犹如此！倩何人唤取，红巾翠袖，揾英雄泪！"] },
    { g: "college", t: "点绛唇·丁未冬过吴松作", d: "宋", a: "姜夔", v: [
      "燕雁无心，太湖西畔随云去。数峰清苦，商略黄昏雨。",
      "第四桥边，拟共天随住。今何许？凭阑怀古，残柳参差舞。"] },
    /* -- 金元明清 -- */
    { g: "college", t: "摸鱼儿·雁丘词", d: "金", a: "元好问", v: [
      "问世间，情为何物，直教生死相许？天南地北双飞客，老翅几回寒暑。",
      "欢乐趣，离别苦，就中更有痴儿女。君应有语：渺万里层云，千山暮雪，只影向谁去？",
      "横汾路，寂寞当年箫鼓，荒烟依旧平楚。招魂楚些何嗟及，山鬼暗啼风雨。",
      "天也妒，未信与，莺儿燕子俱黄土。千秋万古，为留待骚人，狂歌痛饮，来访雁丘处。"] },
    { g: "college", t: "南吕·一枝花·不伏老（节选）", d: "元", a: "关汉卿", v: [
      "我是个蒸不烂、煮不熟、捶不匾、炒不爆、响当当一粒铜豌豆。",
      "恁子弟每谁教你钻入他锄不断、斫不下、解不开、顿不脱、慢腾腾千层锦套头。",
      "我玩的是梁园月，饮的是东京酒，赏的是洛阳花，攀的是章台柳。",
      "你便是落了我牙、歪了我嘴、瘸了我腿、折了我手，天赐与我这几般儿歹症候，尚兀自不肯休！"] },
    { g: "college", t: "人月圆·山中书事", d: "元", a: "张可久", v: [
      "兴亡千古繁华梦，诗眼倦天涯。孔林乔木，吴宫蔓草，楚庙寒鸦。",
      "数间茅舍，藏书万卷，投老村家。山中何事？松花酿酒，春水煎茶。"] },
    { g: "college", t: "临江仙·滚滚长江东逝水", d: "明", a: "杨慎", v: [
      "滚滚长江东逝水，浪花淘尽英雄。是非成败转头空。青山依旧在，几度夕阳红。",
      "白发渔樵江渚上，惯看秋月春风。一壶浊酒喜相逢。古今多少事，都付笑谈中。"] },
    { g: "college", t: "浣溪沙·谁念西风独自凉", d: "清", a: "纳兰性德", v: [
      "谁念西风独自凉？萧萧黄叶闭疏窗，沉思往事立残阳。",
      "被酒莫惊春睡重，赌书消得泼茶香，当时只道是寻常。"] },
    { g: "college", t: "蝶恋花·阅尽天涯离别苦", d: "清", a: "王国维", v: [
      "阅尽天涯离别苦，不道归来，零落花如许。", "花底相看无一语，绿窗春与天俱暮。",
      "待把相思灯下诉，一缕新欢，旧恨千千缕。", "最是人间留不住，朱颜辞镜花辞树。"] }
  ];

  /* ------------------------------ 版面常量 ------------------------------
     改这些数之前先看 fit() 的注释：它们与 style.css 里的字号倍数是配对的。
     TITLE_EM / SIGN_EM 必须与 .pr-title / .pr-sign 的 font-size 一致。

     2026-09-20 用户反馈「字号偏小、阅读体验欠佳」→ 正文基准从 16 降到 14 是**下限抬高**，
     不是变小：原来窄轨道会被 layout() 压到 MIN_FS 12.5 才显示，现在下限就是 14，
     宁可少放一列也不把字缩小；长诗词照常展示（不再因为放不下而整条撤掉）。 */
  const APP_MAX_W   = 1120;   // .app{max-width:1120px}，两侧留白 = (视口宽 - 这个) / 2
  const BASE_FS     = 14;     // 正文字号（固定）。layout() 只会在放不下时降列距，不降字号
  const LH_FLOOR    = 1.38;   // 列距下限（只是「舒适区」的界线，不用于撤轨道，见 pickPool）
  const LH_HARD_MIN = 0.90;   // 列距硬底线：低于此值汉字会互相叠，属物理不可读 → 才不显示
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

     2026-09-20 起**字号固定 14px、只调列距**（用户要求「字号改 14px、长诗词仍然展示」）：
       ① 先按 14px 算竖直方向要几列；
       ② 水平方向反解列距 l = availW / (wsum × 14)；
       ③ l 落在 [LH_FLOOR, LH_CEIL] 内 → 就用它（最舒服）；
          l > LH_CEIL → 夹到上限（列少时不要铺太散），字号不变；
          l < LH_FLOOR → 说明这一列太多、摆不下。
             **不再撤掉这条轨道**（旧行为会让长调整条消失），而是把列距压到 l
             （允许贴得紧一些），字号仍然保住 14px。压到 LH_HARD_MIN 以下才判
             「真的放不下」—— 那时一列的字会互相叠，属于物理上不可读，只能不显示。

     返回 null 只在**极端窄**（连压紧列距都摆不下）时出现，调用方据此整条隐藏。 */
  function layout(p, availW, availH) {
    /* em = 每列的「em 高」（字数 × 字号倍数）；wf = 每列的「宽度系数」 */
    const em = [], wf = [];
    em.push(p.t.length * TITLE_EM); wf.push(TITLE_EM);
    for (let i = 0; i < p.v.length; i++) { em.push(p.v[i].length); wf.push(1); }
    em.push((p.d + " · " + p.a).length * SIGN_EM); wf.push(1);
    const sealIdx = em.length - 1;

    /* 字号是常量（不参与迭代）：这一版的核心就是「宁可压列距，不压字号」。 */
    const fs = BASE_FS;

    /* 竖直方向：一列放不下就折行成两列；同时累计真实列数与总宽度系数 */
    let cols = 0, wsum = 0;
    for (let i = 0; i < em.length; i++) {
      const u = em[i] + (i === sealIdx ? SEAL_PX / fs : 0);
      const k = Math.max(1, Math.ceil(u * fs * TRACK / availH));
      cols += k;
      wsum += k * wf[i];
    }

    /* 水平方向：总宽 = wsum × line-height × 字号，反解出列距 */
    let lh = availW / wsum / fs;
    if (lh > LH_CEIL) { lh = LH_CEIL; }
    /* 旧行为：l < LH_FLOOR 就返回 null（整条不显示）。现在只在「压紧都放不下」时才放弃。
       LH_HARD_MIN 是实测底线：再低汉字会连成一片，属于不可读，不如不显示。 */
    if (lh < LH_HARD_MIN) { return null; }

    return { fs: fs, lh: lh, cols: cols };
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
  /* 全屏入口（2026-09-20 从顶栏挪到诗词栏右上角）。
     绝对定位挂在 .pr-card 上：它**脱离竖排流**，不占 .pr-poem 的内容盒，
     所以 layout() 的竖直折列 / 水平反解完全不受影响 —— 按钮不会挤压诗词排版。
     右上角是**所有** .poem-rail 共用的位置；左右两条轨道各有一个 id 独立的按钮，
     id 由 railIndex 派生（见 render()），事件仍走 data-pr-fs 委托。 */
  function fsBtnHTML() {
    return '<button class="pr-fs" type="button" data-pr-fs'
      + ' aria-label="全屏展示诗词" title="全屏展示诗词">'
      + '<svg viewBox="0 0 20 20" aria-hidden="true" fill="none" stroke="currentColor"'
      + ' stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">'
      + '<path d="M7.6 2.8H3.4c-.33 0-.6.27-.6.6v4.2"/>'
      + '<path d="M12.4 2.8h4.2c.33 0 .6.27.6.6v4.2"/>'
      + '<path d="M16.6 12.4v4.2c0 .33-.27.6-.6.6h-4.2"/>'
      + '<path d="M7.6 17.2H3.4a.6.6 0 0 1-.6-.6v-4.2"/>'
      + "</svg></button>";
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
    rail.innerHTML = '<div class="pr-card">' + fsBtnHTML() + poemHTML(current) + barHTML() + "</div>";
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

  /* 当前宽度下放得下的诗。
     判据必须与 layout() 的水平方向公式一致 —— 2026-09-20 之前这里算错了：
     旧式子 `inner / (LH_FLOOR × MIN_FS)` 是「字号可缩」时代的产物（字号能压小 → 能塞更多列），
     现在字号锁死 14px，唯一能松的只有列距，所以门槛改由 LH_HARD_MIN 决定。
     用 LH_HARD_MIN 而不是 LH_FLOOR：LH_FLOOR 只是「舒适区」边界，低于它仍会显示（压紧列距），
     拿它筛池子会把本该显示的长调错误地筛掉 —— 正是用户要修的那个毛病。

     宽度系数怎么估：竖直方向折几列只有拿到 availH 才知道，而 pickPool 是纯算术（不量 DOM，
     首屏 .app 还 hidden 时量到的都是 0）。这里取最保守的估法：整诗**至少**占
     「诗题列 + 句组数 + 落款列」这么多列宽，只要这个下限都塞不进，实际一定塞不进。 */
  function pickPool(gap) {
    const inner = gap - CHROME_W;
    if (inner <= 0) { return []; }
    const maxWf = inner / (LH_HARD_MIN * BASE_FS);
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
    const fsTrigger = event.target.closest ? event.target.closest("[data-pr-fs]") : null;
    if (fsTrigger) {
      /* 把触发按钮本身传进去：退出全屏时焦点要还回**这个**按钮（见 fsRestoreFocus）。 */
      fsOpenPanel(fsTrigger);
      return;
    }
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

  /* ========================= 全屏展示（2026-09-20） =========================
     入口是**诗词栏右上角**那个「四角展开」图标（.pr-fs，由 fsBtnHTML() 渲染进 .pr-card，
     事件走 onClick 的 data-pr-fs 委托）。2026-09-20 之前挂在顶栏，后按要求挪到轨道上。
     全屏层是 #poemFullscreen（.pfs），诗词正文 + AI 两段（意境字句 / 与你此刻的生活）。

     三条不肯让步的设计约束：
       ① **显示 / 隐藏只用 .hidden 类**，不用 `hidden` 属性 —— 作者样式会盖掉属性（§6.1 踩过）；
       ② **不能调 native fullscreen API**（requestFullscreen）：它会把 `:fullscreen` 伪类挂到
          我们没法完整预测的祖先上，而本页 .app 里有 position:fixed 的侧栏 / 面板，
          实测会出现面板错位；用户要的只是「一块盖满屏幕的阅读页」，div 就能做到。
       ③ **两段文字一次拿到、一次渲染**：后端保证 imagery / affinity 同生同灭，
          前端不做「先渲染一段、再补一段」——否则失败时半屏是字、半屏是空白，最难解释。

     本地缓存为什么还要加一层：
       服务端缓存命中要一次网络往返（几十 ms），但仍要先建连接、走鉴权。
       本地这份是「秒开」用的：同一首诗第二次点开直接出字，不等 spinner。
       只存成功结果，且带上 prompt 版本号 —— 换 prompt 后旧条目自然失效，不用写清理逻辑。
       localStorage 在隐私模式下可能直接抛异常，所以读写都包 try。 */
  const FS_VER = "poem.reflect.v1.";
  const fsEl = document.getElementById("poemFullscreen");
  const fsInner = document.getElementById("pfsInner");
  let fsOpen = false;
  let fsLastFocus = null;

  function fsReadCache(p) {
    try {
      const raw = window.localStorage.getItem(FS_VER + p.t + "|" + p.a);
      if (!raw) { return null; }
      const obj = JSON.parse(raw);
      if (obj && obj.imagery && obj.affinity) { return obj; }
      return null;
    } catch (e) { return null; }
  }
  function fsWriteCache(p, data) {
    try {
      window.localStorage.setItem(FS_VER + p.t + "|" + p.a,
        JSON.stringify({ imagery: data.imagery, affinity: data.affinity }));
    } catch (e) { /* 配额满 / 隐私模式：缓存是加速项，写不进就算了 */ }
  }

  /* 诗题里可能带书名号或标点，标题保持克制：只用引号包住题目本身。 */
  function fsPoemHTML(p) {
    const lines = p.v.map(function (l) {
      return '<span class="ln">' + esc(l) + "</span>";
    }).join("");
    return '<div class="pfs-poem">'
      + '<div class="pfs-title">' + esc(p.t) + "</div>"
      + '<div class="pfs-meta">' + esc(p.d + " · " + p.a) + "</div>"
      + '<div class="pfs-rule"></div>'
      + '<div class="pfs-body">' + lines + "</div>"
      + "</div>";
  }

  /* 两段正文按空行切段再包 <p>：模型偶尔会在一个字段里写两小段，
     直接塞进一个 <p> 会变成一坨。 */
  function fsParagraphs(text) {
    const parts = String(text).split(/\n+/).map(function (s) { return s.trim(); })
      .filter(function (s) { return s.length > 0; });
    return parts.map(function (s) { return "<p>" + esc(s) + "</p>"; }).join("");
  }

  function fsBlocksHTML(data, note) {
    return '<div class="pfs-ai">'
      + '<div class="pfs-block"><div class="cap">意境与字句'
      + (note ? '<span class="ai">' + esc(note) + "</span>" : "")
      + "</div>" + fsParagraphs(data.imagery) + "</div>"
      + '<div class="pfs-block"><div class="cap">与你此刻的生活'
      + (note ? '<span class="ai">' + esc(note) + "</span>" : "")
      + "</div>" + fsParagraphs(data.affinity) + "</div>"
      + "</div>";
  }

  function fsLoadingHTML() {
    return '<div class="pfs-ai"><div class="pfs-loading">'
      + '<span class="pfs-dots"><i></i><i></i><i></i></span>'
      + "<span>正在读这首《" + esc(current ? current.t : "诗") + "》…</span>"
      + "</div></div>";
  }

  /* 失败态。文案来自后端（未配置 AI / 调用失败各有一句能指导操作的话），
     这里只负责把它显示出来 + 给一个「再试一次」。 */
  function fsErrorHTML(message) {
    return '<div class="pfs-ai"><div class="pfs-err">' + esc(message)
      + '<button type="button" data-pfs-retry>再试一次</button></div></div>';
  }

  function fsFootHTML(withCopy) {
    return '<div class="pfs-foot">'
      + (withCopy ? '<button class="pfs-btn" type="button" data-pfs-copy>复制全文</button>' : "")
      + '<button class="pfs-btn" type="button" data-pfs-close>关闭 (Esc)</button>'
      + "</div>";
  }

  /* 只重画 AI 那一段，不重画诗词 —— 重试时诗词不该闪一下。 */
  function fsPaintAI(html) {
    const old = fsInner.querySelector(".pfs-ai");
    const holder = document.createElement("div");
    holder.innerHTML = html;
    const fresh = holder.firstChild;
    if (old) { fsInner.replaceChild(fresh, old); } else { fsInner.appendChild(fresh); }
    /* 底部的「复制/关闭」在首屏就画好了，AI 段插在它前面 */
    const foot = fsInner.querySelector(".pfs-foot");
    if (foot) { fsInner.appendChild(foot); }
  }

  function fsLoad() {
    const poem = current;
    if (!poem) { return; }
    fsPaintAI(fsLoadingHTML());

    const local = fsReadCache(poem);
    if (local) {
      /* 本地命中：立刻出字，连网络都不走。标注「已为你保存过」。 */
      fsPaintAI(fsBlocksHTML(local, "已保存"));
      return;
    }

    if (!window.WorkbenchApi || typeof window.WorkbenchApi.reflectPoem !== "function") {
      fsPaintAI(fsErrorHTML("页面脚本没有加载完整，刷新一次再试试"));
      return;
    }

    window.WorkbenchApi.reflectPoem({
      title: poem.t,
      dynasty: poem.d,
      author: poem.a,
      body: poem.v.join("\n")
    }).then(function (data) {
      /* 等待期间用户可能关了又开、或换了诗 —— 回来时对不上就别画了，
         否则会把 A 诗的品读贴到 B 诗下面。 */
      if (!fsOpen || current !== poem) { return; }
      fsWriteCache(poem, data);
      fsPaintAI(fsBlocksHTML(data, data.cached ? "已保存" : ""));
    }).catch(function (err) {
      if (!fsOpen || current !== poem) { return; }
      fsPaintAI(fsErrorHTML((err && err.message) ? err.message : "AI 暂时没有回应，稍后再试一次吧"));
    });
  }

  /* 退出时焦点还给谁 —— 这里有个容易写错的细节：
     第二次打开全屏时 activeElement 是**面板自己的关闭按钮**（打开时我们主动 focus 了它），
     把它记成「打开前的落点」再还回去，等于在一个刚被 .hidden 藏起来的节点上 focus()，
     浏览器会默默把焦点丢回 body（`document.contains` 拦不住这种情况，它只判在不在文档里）。
     所以除了「在文档里」，还要额外要求「不在面板内部」；否则一律退回顶栏入口按钮。 */
  /* 全屏入口从顶栏挪到诗词栏右上角之后，「还原焦点」的目标变成**点进来的那个按钮**。
     顶栏按钮已删除，所以不能再回退到它 —— 回退目标改为「打开时记下的触发元素」，
     最后兜底才去找轨道上的当前按钮（换诗后按钮节点不变，所以一定找得到）。 */
  function fsFallbackTrigger() {
    const any = document.querySelector(".pr-fs");
    return any || null;
  }
  function fsRestoreFocus() {
    let back = fsLastFocus;
    const insidePanel = back && fsEl && fsEl.contains(back);
    if (!back || !document.contains(back) || insidePanel ||
        typeof back.focus !== "function" || !fsIsTrigger(back)) {
      back = fsFallbackTrigger();
    }
    if (back && typeof back.focus === "function") { back.focus(); }
  }
  /* 只有「入口按钮」才值得把焦点还回去。用户可能是按 Esc 退出的，此时
     activeElement 早就不是入口 —— 那就一律回到轨道上那个入口按钮。 */
  function fsIsTrigger(el) {
    return !!(el && el.hasAttribute && el.hasAttribute("data-pr-fs"));
  }

  /* trigger：实际被点/被按的那个入口按钮（用来在退出时把焦点还回去）。
     从顶栏/轨道/键盘等任何入口进来都适用。 */
  function fsOpenPanel(trigger) {
    if (!fsEl || !fsInner) { return; }
    /* 极端情况（轨道被删、窄屏尚未抽诗）下 current 可能是空：
       不能让按钮变成「点了没反应」，退一步现抽一首。 */
    if (!current) {
      const pool = pickPool(gapWidth());
      if (pool.length) { current = pool[(Math.random() * pool.length) | 0]; }
    }
    if (!current) { return; }
    fsOpen = true;
    /* 落点优先记「触发它的那个按钮」：键盘 / 程序化触发时 activeElement 可能是 body，
       记 body 就等于退出后把焦点丢回 body（轨道上的按钮白点了）。 */
    fsLastFocus = (fsIsTrigger(trigger) ? trigger : null) || document.activeElement;
    fsInner.innerHTML = fsPoemHTML(current) + fsFootHTML(true);
    fsEl.classList.remove("hidden");
    /* 焦点给关闭按钮：键盘用户一进来按 Enter 就能退，Tab 也有落点。 */
    const closeBtn = document.getElementById("pfsClose");
    if (closeBtn) { closeBtn.focus(); }
    fsLoad();
  }

  function fsClosePanel() {
    if (!fsEl || !fsOpen) { return; }
    fsOpen = false;
    fsEl.classList.add("hidden");
    fsInner.innerHTML = "";
    /* 焦点还给入口按钮，否则会掉回 body（与「换一首」同一个坑）。 */
    fsRestoreFocus();
  }

  function fsOnKey(event) {
    if (!fsOpen) { return; }
    if (event.key === "Escape" || event.key === "Esc") {
      event.preventDefault();
      fsClosePanel();
    }
  }

  function fsOnClick(event) {
    if (!fsOpen) { return; }
    const t = event.target;
    /* 点遮罩空白处也关。判据是「点在 .pfs 本身或 .pfs-inner 的空白上」，
       不引入额外的 backdrop 层（多一层就要多一套 z-index 维护）。 */
    if (t === fsEl) { fsClosePanel(); return; }
    /* ⚠️ #pfsClose 上必须带 data-pfs-close（见 index.html）——
       只靠 id 拿不到它，会变成一枚点了没反应的死按钮。 */
    const btn = t.closest ? t.closest("[data-pfs-close],[data-pfs-retry],[data-pfs-copy]") : null;
    if (!btn) { return; }
    if (btn.hasAttribute("data-pfs-close")) { fsClosePanel(); return; }
    if (btn.hasAttribute("data-pfs-retry")) { fsLoad(); return; }
    if (btn.hasAttribute("data-pfs-copy") && current) {
      writeClipboard(plainText(current)).then(function () {
        const old = btn.textContent;
        btn.textContent = "已复制";
        btn.classList.add("is-done");
        setTimeout(function () { btn.textContent = old; btn.classList.remove("is-done"); }, 1400);
      }).catch(function (e) { say(e && e.message ? e.message : "复制失败", 3200); });
    }
  }

  function initFullscreen() {
    /* ⚠️ 入口不再在这里绑定：按钮是 poems.js 动态渲染进 .pr-card 的，
       走 onClick 的 data-pr-fs 委托（与「复制 / 换一首」同一套机制）。
       这样换诗、重渲染都不会把监听器弄丢。
       这里只留面板自己的监听。 */
    if (fsEl) { fsEl.addEventListener("click", fsOnClick); }
    document.addEventListener("keydown", fsOnKey);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () { init(); initFullscreen(); });
  } else {
    init();
    initFullscreen();
  }
})();