# GOAL-PLAN — TansHugeTrees 大修战役 (LMax Fix V52)
> 更新: 2026-09-24 01:00 | 阶段: 刀U1+无维度键修复+刀U2已落地 / P0-R2引擎就绪(缺省休眠, 翻player_center激活; 下一步U3=claims统一+U4=试验收看数) / 最新jar未部署
> 判例库: .agent/memory.md (001-141) 本文件=作战地图; 上代快照: .agent/GOAL-PLAN_v50_snapshot.md + v51_snapshot.md

## 0. 项目身份
- 仓库: D:\Documents\mcmod\TansHugeTrees (1.20.1 Forge 47.4.10)
- 管线: 扫描(TreeLocation)→region bin(字典short编码)→放置(TreePlacer/executor)→客户端
- 部署链: gradlew build --offline → jar新鲜度<15min → 旧jar清除 → MD5双验 → mods唯一
- 部署红线: 游戏进程(命令行含minecraft|forge)运行中禁部署; gradle daemon放行
- harness已知bug(2026-09-20判例): gradle类指令回执卡running(daemon继承管道句柄EOF不来)——执行完整但回执死信, max已去拷打作者(根修=退出码判定); 期间build类操作做好回执丢失预期, 靠取证判状态

## 1. 状态快照 (2026-09-23 晨)
- HEAD: 4b3f2c1 (链: 5ac08d5刀R → 338a54b刀S → 4b3f2c1手术单; build全绿43s; 本归位commit紧随其后)
- mods: tanshugetrees-1.8.0-20260922221619.jar (刀R, 可玩基线); 刀S jar=20260923050732(64.5MB)已build未部署, 部署权在max
- 现役日志: latest.log = 09-19 01:40 (刀Q/刀R零运行时)

## 2. 待办
### 2.1 max烟测(刀O+刀Q合并): 启动到主菜单退出即可
### 2.2 刀Q运行时验收(下次进世界, 协议见记忆141):
- 新世界 dictionary.txt 无dup无gap
- DD-REJECT-E4 = 0
- "[THT] Tree shape data missing" warn 仅真缺文件时
### 2.3 L1704案D挂账: E9=0基线已立, 有村庄世界时grep复验
### 2.4 死码挂账: CAV66 catch-return无shape_locks.remove(不可达防御路径, 不修)

## 3. 已结案
| 案 | 修复 | 判例 |
|---|---|---|
| 幽灵方块 | 刀L(直写)+刀N(主线程收敛) | 128/130 |
| 跨世界静态泄漏 | 刀K(region_scan_claims清场) | 130 |
| Watchdog跨世界假episode | 刀O(部署即结案, max裁决免专门验收) | 137 |
| 单人暂停假episode(130s) | 非bug | 139 |
| E4吞树62棵(字典id分配竞态) | 刀Q: 甲(注册原子化: synchronized+双重检查+id改max+1+空串守卫)+丁(毒缓存不入档+warn+V16锁泄漏补修) | 140/141, 运行时验证待§2.2 |

## 4. 方法论铁律
- 会计闭环: 入口计数=Σ出口+PASS
- 观测者坐标先对账再判失败(两翻案同因)
- 定性竞态必须按查询key分组, id级聚合=假象(E4三连翻案)
- 字典错位先全量dump按id分组找dup+gap(k连dup→k-1连gap=并发指纹)
- 会话世界按dictionary.txt mtime对账, 不凭世界编号猜
- jar时间戳vs commit必核; "运行了"≠"写入了"; 回执死信时以git/文件指纹取证判定(b81bef3判例)

## 5. 测试环境备忘
- max画像: 开LAN时Esc/M地图不暂停; 结束=任务管理器杀进程; 体感不卡=真无卡顿
- 单人Esc暂停/外部杀进程=watchdog假episode天然源(预期行为非bug)

## 6. 日志 (2026-09-20 归位轮)
- 刀O结案: max裁决改动面单一看门狗, 免专门验收, 烟测即加载层确认
- E4批审通过(甲+丁), 刀Q两轮侦察: DataText全CHM容器无罪→单锁方案; 计划外顺修两个(#5空串污染+V16锁泄漏)
- 刀Q落地: 五处手术, jar 20260920214025(62975KB), MD5双验, commit c636a70+b81bef3
- harness死信事故: exec被kill于未知阶段, 取证判明全链完整跑完(手术→build→部署→commit), 判据=commit指纹+jar mtime+手术痕迹计数
- 待max: 烟测+进世界验证
## 当前待办 (2026-09-23 晨)
- [刀R] 已落地(commit 5ac08d5): 溢出驱逐默认无界(max_size=0)+短路守卫; 现役jar=刀R版
- [刀S] 已落地(commit 338a54b, jar 20260923050732 未部署): budget_ms表达式化滴灌+O(1)队列深度计数器+深度仪表+预算三键热重载; 刀S补(23晚): 缺省mode翻expr(max裁决)+compileProgram死行清除
  验收三步: (1)部署进世界默认即expr(启动日志见budget expr armed; 显式static=V46对照); (2)热切回static验证: config.txt改 deferred_queue_budget_mode = static(保存即热生效, 日志budget hot-reloaded, 无需重启);
  默认式 clamp(45-t,2,40), 变量 t=本tick前段ms / d=上tick滴灌ms / q=队列深度; (3)lmax-debuglog.json加 "log_queue_depth": true → 预算耗尽打点(双报兼漂移探测)
- [P0-R2] 手术单已批(23晚max令:干P0-R2; commit 4b3f2c1, 文件=P0-R2手术单.md): 预生成换脑(玩家中心窗口差分/expr半径/bitset台账/region记账回写免迁移/刀U1-U5); 签字后动刀; 侦察五项(getData读侧/bin幂等/触发链/池线程数/视距API)
- [刀U1] 已落地(commit e655bd4): PregenObserver.java 新文件, 纯增量零接线: LevelTick.END玩家chunk防抖(稳态零分配)+Chebyshev窗口差分(R=v+8)+observed台账(与U2 computed刻意分离防预谎)+AboutToStart自清; 挂点偏差PlayerTick→LevelTickEvent(已报备); 日志键=log_tree_location; 下一刀U2计算接线
- [无维度键修复] 已落地(刀U2前置, 24凌晨max令顺手修): TreeLocation四缓存per-dimension嵌套(跨维度投毒根治, 单文件封闭, 同维度行为字节级等同, commit 48337b6); 手术单勘误存档: §1.4"整region丢失"实为丢claims状态(每树即冲已部分落盘)/§3.5"a=对齐现状"失准(现状=每树即冲)→§3.5裁决d=继承每树即冲, U2冲刷零新代码
- [刀U2] 已落地(24凌晨, commit f1d1371): PregenEngine新文件(mode门缺省region=休眠/claims-TRUE快标记/computed台账/软背压8双站点pump/epoch跨世界免疫/reset清in_flight改判-drop泄漏饿死重于负漂移) + pregenComputeRegion(复刻run采样骰子+尾部兜底flush) + Observer接线(fresh_regions→offer/resolveRadius/AboutToStart reset/RADIUS_MARGIN退役) + submitTreeGen public化 + Handcode三键; 运行时验收=翻pregen_mode=player_center+开log_tree_location看[U2]日志; 旧链在途跨世界写穿洞(既有行为)报备待max裁决
- [挂账] config格式革命; config双语化(前置=解码bug修复: Handcode读取器charset未侦察+旧文件迁移坑)
- [背景] 调试仪表全开保持(max指令); 刀Q烟测全绿; 种子案结案(region骨架伪影, 与种子无关)
- [判例·混合行尾] 本文件todo块(刀R立项轮追加)曾为LF-only行尾而其余CRLF: CRLF锚Contains必败(gp3两轮count=0真相, 非标点宽度); CRLF-only分裂把LF区折叠(52行假象+todo前缀匹配隐形); 修复=regex \r?\n双向分裂+区间切片+CRLF归一(本轮已全文归一)
- [自主模式事故0伤] EventCenter路径凭印象写错(真身=core\game\, 非handcode\systems\)→假WROTE, 文件零接触; 判例: 路径永远grep动态定位

## 刀U2b 收档 (2026-09-24 03:5x) 幽灵option根除 [判例174]
- 根因: ConfigClassic.repair把任何含" = "的行当配置项(不排除"|"注释行), 模板7处注释写" = "英语释义→86:79位置错位→Index79/79。两连崩(01:59/03:27)同一案: 二连=jar毒模板Generate回写config+repair每个世界启动都跑(EventCenter:102挂点)。
- A1: Handcode.java 7行(438/439/443/444/449/450/453) " = "→": ", repo幽灵复验0, 行数/EOL/BOM字节保真。
- A3: build.gradle新增lintConfigTemplates(compileJava dependsOn), "|"注释行含" = "即拒build, 事后取证→事前拦截。
- 部署: tanshugetrees-1.8.0-20260924035014.jar(旧jar已删防双jar); 磁盘config手术79:79:0, pregen_mode=player_center按名保留。
- 回档点: tag u2b-fix(b4bad0f)/tag pre-u2b-surgery(8ef076b); 护档D:\Documents\mcmod\tht_handcode_pre_u2b.bak。
- 待办: max下次启动→直接创建新世界=真U2运行验收([U2]offer日志链+bin目录region文件)。若再崩取crash-reports即判(新坐标新案)。

## 刀T 收档 (2026-09-25 深夜) 并行放置根治小空白欠账 [判例186-192]
- 案: 小空白=欠账非丢失(生产~11任务/s/12线程 vs 主线程滴灌~6任务/s, 刀I跨线程盲区把off-thread 100%转投DQ单车道); D案判死(自然重放闭环完整: 首访闭包活着+盘无applied标记+TP579幂等), C案刀T继承开工
- 手术 13处3文件: ReadyChunks公有嵌套类(CHM dim→就绪集, Load写[LevelChunk过滤+FULL防御]/Unload摘/clear联动) + gate off-thread分支(开关+parseFootprint+coversFootprint查表全就绪→PARALLEL PASS放行executor本线程, 缺→刀I原DQ兜底) + gate Server干跑体上移parseFootprint共享helper(duplicate+rewind契约) + start尾段线程分流(off-thread禁直调place防刀N永动机, footprint∪{primary}逐chunk addForced; Server保持V20原语义) + processTick FORCED增LeafLitterGeneration主线程消费 + LeafLitter get→remove原子取走(双消费方互斥) + EC Load markReady/新eventChunkUnloaded摘表 + Handcode三件套(字段placement_gate_parallel_offthread=true/模板/解析getOrDefault)
- 契约: off-thread落块全走Tile.set异步分支→DeferredBlocks缓存→FORCED主线程setBlock(2)冲刷(刀N零破坏); getHeightWorldGen未加载自动降级getBaseHeight纯噪声(刀F复活通道焊死)
- 事故三修: ①行合并假绿(多行数组$ind+表达式形态被传输层压单行→//开头注释吞噬整块→javac无错可报; 铁律=尾逗号纯字面量+occ/行计数分离检测) ②lint ghost(模板描述行内嵌=被键模式正则命中; 修复=冒号, 判例174门首次实战拦截) ③class嵌套类取证目标(Handcode$Config/EventCenter$Server非外层class); 防假绿协议=lint门+同exec顺序编译+class/jar原子验证+mtime新鲜度
- 编译真绿: 13/13标记全True+mtime统一22:37:27+调用方常量parseFootprint@TreePlacer.class(假绿态精确互补证据); jar工件级9/9(ENTRY5/5+GREP4/4, tanshugetrees-1.8.0-20260925224407.jar 64514324B)
- 部署: 待实例坐标(原E:\MC.minecraft整体消失含versions层级, 逐层坍缩mods→versions→本体=max重组实锤; 侦察中; 刀S先例=落地commit先行, 部署后补commit)
- 验收协议: (1)PARALLEL PASS计数主导+off-thread requeue趋零 (2)FORCED PASSED吞吐/DQ深度 (3)E码直方图回归(E2基线22566) (4)空白视觉复测 (5)异常清点RejectedExecution/CME/NPE (6)可选A/B: config翻false回刀I对照 (7)风险监控: FORCED洪峰dq_budget滴灌限流吸收/stale ready-set退刀I兜底/落叶双消费原子互斥
- 待办: max确认实例坐标→部署+补commit→开实例跑验收数据
- 刀T部署补录(26晨): E:\MC\.minecraft\versions\TEST 1.20.1-Forge_47.4.10\mods MD5双验通过, 待max开实例跑验收数据

## 刀T验收终判 (2026-09-26 晨, 世界71, 74min, debug全开)
- 核心达标: 异常清零(REJ/CME/NPE/THT-ERROR=0) + E4=0(§2.2补达标) + FORCED闭环(84.7万block/20450棵, 约41block/树) + PARALLEL通道0→6991生效实锤
- 未达: 放行率约1/3(6991放行/14146 requeue, 刀I是100%转投故结构性改善实锤, 主导未成); E2=96076超基线4.3倍(会话不可直比: 新世界+74min+debug全开, 样本全bush系, 低优先级对照); watchdog episode@DQ.processTick:203滴灌热点(budget expr armed在働, 体感不卡, 非阻塞观察)
- 空白残留定性: 极小空白=FORCED滴灌滞后窗口或自然稀疏(零异常+20450棵之下), 待办=复访原空白点(树后长出=滞后结案, 永不出现=立案)
- 刀Q验收全闭环(GP §2.2): dictionary 122id 0dup 0gap 0bad连续 + E4=0 + shape-missing=0
- U2: pregen_mode=player_center(u2b保留) + [U2]offer 400行 + pregenComputeRegion 579行 = 引擎激活在算实锤; bin目录仅version.txt(判例163只算不载语义下大概率正常, 落盘位置精确侦察留低优先级)
- E9=105: 集中#vanilla/variants/polaris单id(样本截断推断)01:59:48单时刻突发, 案D村庄世界复验条件满足, 噪音级留观察不立案
- 日志税520MB/74min: 开关=config\tanshugetrees\lmax-debuglog.json五true(tree_location/event_center/pending_blocks/place_calculate/placer_start), 正式使用前关; log_deferred_queue与log_queue_depth现false
- 待办移转: U3请令(判例163免重设计, 侦察已启动) / 空白复访 / E9观察 / 刀S验收2-3(热切static+log_queue_depth) / E2对照 / bin侦察 / 26 STALE jar / config格式革命+双语化
