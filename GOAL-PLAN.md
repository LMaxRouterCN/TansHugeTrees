# GOAL-PLAN — TansHugeTrees 大修战役 (LMax Fix V50.x)

> 更新: 2026-09-17 深夜 | 阶段: 正确性战役收官 → 可见性/性能战役开幕
> 旧 GOAL-PLAN (V15时代活化石) 已由 max 移入暂存文件夹归档, 勿删
> 细节判例库: .agent/memory.md (长期记忆 001-110)

## 0. 项目身份
- 仓库: D:\Documents\mcmod\TansHugeTrees (1.20.1 Forge 47.4.10)
- 目标: 修复 Tan's Huge Trees 世界生成管线系列缺陷 (扫描→bin→DQ/executor放置→客户端可见)
- 部署链: gradlew build --offline → build\libs jar → MD5双验 → mods唯一 → (游戏运行中禁部署)
- 测试台: 世界54=草面grove超平坦 / 世界55=雪面grove超平坦(polaris专属试验台)
- commit链: e702ed4(刀J) → 75dc0a9(max护档) → ecb8c93(刀I) → 0558c3f(刀H)

## 1. 已结案 (2026-09-17 全绿收官)
| 案 | 修复 | 终验 |
|---|---|---|
| 冷启动零树 | 刀G 空读自愈钩子 | hook 1495次零失败 |
| 门黑洞890全盲 | 刀I 跨线程探针转DQ | 2843/2843盲区后全落地 |
| REJ崩溃 | 刀J 静态池复活+submit守卫 | fresh JVM生效(退出再进场景待刀K后验) |
| polaris大树 | — | 09-17 21:42 世界55实见生成 |
| 劈树 | 刀F 足迹门 | 两轮实测无劈 |
| K1空缓存疑案 | — | 翻案: 肉眼见树=真写块 |
| bush E2×365 / polaris E2×3605 | — | 树叠树防御/草地设计性拒绝, 均设计内 |

## 2. 活跃案件 (优先级序)
### P0 幽灵方块/放置可见性案 (max 09-17晚定义, 刀C回档失落的旧案)
- 症状三件套: 视距内只放几颗就停(等多久不新增) / 空白chunk有空气墙(撞得到看不到) / 跑出范围回来就好(chunk重载显形)
- 根因假设: 服务端写盘成功但resync仅覆盖玩家小半径, 半径外=客户端持旧chunk+无补发事件=永久静止
- 分叉待裁决: 全放了但只可见几颗(纯resync案) vs 放置流程半途真停(DQ案) — 判据=读log对账placing计数vs视觉棵数
- 线索: max自述此bug之前修过被刀C回档抹掉 → git分支 knife-c-failed-20260912 存刀C全链, 落码前必翻
- 修法候选: A)按被跟踪chunk归属发包(原版正统) B)setBlock flag加同步位(包风暴风险) C)每chunk批量完成发全量chunk包(预判优)
### P1 密度频率审查 (max点名)
- 读TreeLocation选点算法(rarity/min_distance/group_size消费逻辑) → 产出"当前配置理论每100×100格N棵"换算表 → max对表目测
### P2 刀K 跨世界静态泄漏 (方案已备, 等max拍板)
- 清单: region_scan_claims(最致命) / Data.bin_convert_futures / DQ.queue / processed_chunks / TL.cache_*×4+pendingEmptyChunks / TP.cache_locations+cache_functions / core层DeferredBlocks
- 方案: 各类静态resetWorldState()自清私有容器, eventWorldAboutToStart统一调用; PlacementGate.clear()挪AboutToStart
- 禁区: 刀K落地前"退出再进"切世界(fresh JVM重启免疫)
### 顺手债
- Watchdog开关入lmax-debuglog.json (侦察已做, 下轮落码)
- TPS体感量化: 硬指标双零, 嫌疑=resync包风暴/MSPT逼近50/滴灌积压
- 树管线结构背景: 天然后置(chunk可见→5s延迟→region首扫65s→bin→DQ 40ms/tick滴灌→gate→放置)

## 3. 方法论铁律 (判例血泪)
1. 观测者坐标与产出坐标先对账再下"失败"结论(两度翻案同因)
2. "运行了"≠"写入了", 放置需落块计数证据; "玩家看到的植被"未必是你的产出
3. 多出口控制流必须会计闭环(入口=Σ出口+PASS)
4. 静默break/return链=取证盲区制造机
5. 破坏性git操作前GOAL-PLAN与.agent必须先commit入库; 里程碑commit禁止src-only
6. 跨线程读MC chunk层结构不可信, 探测必须在数据所属线程
7. 静态单例生命周期必须对齐实例级短命对象(MinecraftServer)
8. fresh JVM与同JVM重进是不同测试环境, 协议必须区分声明
9. 体感性能问题先量化后动手
10. "以前从不炸"的雷被新功能引爆, 查引信是不是自己接的
11. thenRun回调异常无人观察会被静默吞, 挂钩必须自带try-catch+日志
12. 修复部署前必须审"谁还活着"矩阵(池/事件源生命周期)

## 4. 测试环境备忘
- lmax-debuglog.json 8键遥测当前ON, 迟滞案结案后关回 (日志税: 24.8MB/14min)
- config_world_gen.txt: 75条目; polaris唯一绑minecraft:grove(ground=snow_block精确匹配); want带#=tag匹配(grass_block∈#dirt)
- 21:40-21:53会话log待读(polaris终验书面化+迟滞对账素材)
- 世界54"Y恒27/零方差"=超平坦本相非异常(旧红旗翻案)
## [2026-09-18] 刀L+刀M 落地 (幽灵方块根治 + Watchdog 开关迁移)
- 刀L: Tile.set 主线程统一 flag=2 (原版接管 section 增量同步+光照), placeForced flag4→2, resyncChunk 降级双保险, is_world_gen 成死参数
- 刀M: watchdog_enabled 迁 lmax-debuglog.json (默认 false, 新建 json 模板含该键, 缺键安全 false 不回写), 启动点迁 Core.loadDebugLogConfig (治时序坑), threshold/dump 静态赋值无条件保留 + threshold 加 50ms 兜底
- 落码判例: PowerShell 写 Java 源码必须 UTF8Encoding($false) 无 BOM — [Text.Encoding]::UTF8 带 BOM, javac -encoding UTF-8 报非法字符 \ufeff (前两轮编译失败真凶, 被 daemon 噪音淹没)
- 待验收: 世界55 撞墙测试 (幽灵消失?) + 树影出现? + json 加 "watchdog_enabled": true 测开关效力
- 验收绿后收尸刀: resyncChunk 三调用点 / is_world_gen 死参数 / Tile.set 异步防御分支 / Handcode 死字段 watchdog_enabled / Tile.remove 结构是否同改(侦察结果待判)

## [2026-09-18 01:1x] 幽灵方块案结案 + 刀K数据实锤
- 幽灵案结案判据: fresh JVM 当面冒出一片(增量包实时送达) + 树影正常(光照增量治愈, lc直写时代无影) + 撞墙免验(当面出现必撞得到)
- 刀L生效: Tile.set主线程flag=2 + placeForced flag2 + resyncChunk双保险期, 全部按图施工无回归
- 刀M生效: Watchdog键控启动(threshold=100ms从主config正确传入), max测试中暂不关
- 刀K数据实锤: 同JVM旧世界55先进→新世界E2=0(55期间E2=2170)+DQ挂等region唤醒597次→零树; fresh JVM同jar同config正常→排除jar/config回归
- 污染机理: region_scan_claims为静态跨世界状态, dimension key不含存档身份(记忆104), 新世界region被判已扫→E2出口关死
- 待max拍板: 刀K方向B(世界卸载钩子清空静态状态, 需先做静态池全面审计, 与静态池生命周期案并案) vs 方向A(claims键加存档身份, 治标)
- 待max拍板: 收尸刀(幽灵案遗产): resyncChunk三调用点物理删除 / is_world_gen死参数(set+remove) / Tile.set异步防御分支 / Handcode watchdog_enabled残留(主config死键+字段声明L231+默认赋值L449) / remove邻居条件简化
- 遗留观察: 55旧世界跑图零树未定案(region幂等vs身后冒树, max确认充分测试+小地图回看过, 但接受不深究)

## [2026-09-18 02:3x] 刀K手术单·终稿(静态池审计完成, 待max审批后动刀)
- 案情: 同JVM旧世界→新世界零树, 击杀链三段接力:
  1. processed_chunks(EventCenter L148, key无dimension): 同坐标chunk add=false→start整链短路
  2. region_scan_claims TRUE残留(TreeLocation L49): putIfAbsent命中→扫描跳过→新世界盘上无bin
  3. Data.bin_convert_futures跨世界投毒(TreePlacer L1283): future读盘路径L1337执行时求值+key无存档身份→新世界命中旧世界解析结果=旧种子树记录灌入新世界(最毒)
- 清理时机判决: eventWorldAboutToStart L85后L87路径切换前(旧任务已死透=menu间隙人类时间尺度; 理论外straggler写旧路径=不污染新存档, 注释记录)
- 手术内容(四处):
  A. TreeLocation.clearWorldState(): 清region_scan_claims/pendingEmptyChunks/cache_biome/cache_write_tree_location/cache_write_place/cache_other_region
  B. TreePlacer.clearWorldState(): 清DeferredQueue.queue/Data.clear()接线(孤儿方法转正)/LeafLitterGeneration.cache_locations/Function.cache_functions/PlacementGate.clear()(复用, Started L103保留不动)
  C. DeferredBlocks.clear()新增(core层, 现有API只有add/take/size)
  D. EventCenter: AboutToStart补四行调用+processed_chunks.clear()
- 设计原则: EventCenter不直接摸外部类私有字段, 走各类聚合入口(PlacementGate先例推广); PlacementGate.clear的Started调用保留(冗余无害, 不动刀F遗产)
- 不清理: ConfigDynamic等config缓存(每JVM语义, 正确保留); DetailedDetection.memoryCache(已废除L1409); overlay原子字段(自复位)
- 收尸刀清单+1: region_scans(EventCenter L193)死字段, 全项目仅声明零引用
- 验收协议(术后): 同JVM先进旧世界跑图→退出存档→新建世界→树应生成; 复跑fresh JVM回归; Watchdog盯stall

## [2026-09-18 02:4x] 夜班收官·CacheManager对账
- CacheManager.clear (DataLogic/DataText/DataShort/DataInt四池) 与刀K的11池零重叠: 前者=config/字典层通用KV, 后者=生成链专属池; AboutToStart同钩子各清各层互补
- 历史呼应: V16跨存档字典污染修复(path_world_mod切换L87-89+restart L92调CacheManager.clear)正是本钩子前哨战; 刀K=把战线推到最后一块未清扫阵地(生成数据池)
- 手术单不去重, 原样成立; region_scans死刑确认(RS-REFS=1仅声明行)
- 夜班交付: ad87724(README+fork段)/00ca85c(README重写+版本号1.8.0)/349de96(仓库整理)/ced0714(刀K手术单); max醒后三决定: 手术单审批/push/收尸刀排期

## [2026-09-18 03:0x] 收尸刀手术单v1 (七项尸检完成, 与刀K并案待批)
- #1 resyncChunk 死刑: 调用点5个(修正, 非3): EC L229/L237/L254 + TP L182/L235 + 定义L267; 注释提及4处(GameUtils L513-514/Handcode L214/TreeLocation L649/TP L2137)改写措辞保留历史
- #2 is_world_gen set侧: 死参数确认(主线程行为无差异), set签名瘦身
- #3 is_world_gen remove侧: 半死, L570分支(if false→neighborChanged L572)语义待定; 术前看remove调用方传参分布, 恒定后展开; #7并入此项
- #4 watchdog_enabled双字段真相: Core.L84=真身(lmax-debuglog.json刀M控制) / Handcode.L231=尸体(apply无赋值行, 纯死物)+主config模板键L449; 删Handcode侧零引用(WE-TOTAL=8交叉验证), 用户旧config死键无害不清理
- #5 Tile.set异步防御分支: 探针取偏(Tile.test方法体L332-451), set本体L482-538手术时现场取; 骨架已定位(B节签名+L512-515注释)
- #6 region_scans: 死刑已确认(RS-REFS=1), 字段+注释删除
- 前置验证红线: 删resyncChunk前全项目setBlockState调用点扫描, 确认所有落块路径flag=2; 漏一个裸写0路径=幽灵方块回归, 不满足不删
- 部署建议: 与刀K同次部署同轮验收(手术单两份摆一案, max一次批)

## [2026-09-18 03:2x] 收尸刀手术单v2·终稿 (三待字清零, 批完即动刀)
- 终验三判决:
  a) Watchdog三件套: enabled=尸体(刀M断线) / threshold_ms+dump_all=活人(主config→Handcode apply→Watchdog真链路, L568-572); #4只删enabled一具(L231+L449), 另两个保留——README正在引用它们
  b) Tile.set九调用点: 8×false+1×true(TreePlacer L2185=worldgen直写遗言); 方法体零读取is_world_gen(线程三分支不看), 参数死透
  c) #5手术形态修正: 异步分支不删改转投——L518-532改写为无条件DeferredBlocks.add(getChunkNow探测+静默直写删除), 异步上下文一律进缓存等主线程冲刷; 幽灵方块从理论可能变物理不可能, resyncChunk可删
- 终稿手术清单:
  S1 (#1+#5耦合): GameUtils异步分支改转投 + resyncChunk五调用点(EC L229/L237/L254+TP L182/L235)+定义L267删 + 注释4处改写(GameUtils L513/Handcode L214/TreeLocation L649/TP L2137)
  S2 (#2): Tile.set签名删boolean+9调用点删末参
  S3 (#3+#7): Tile.remove签名删boolean+L570分支展开(neighborChanged无条件)+L568转发同步删参+2调用点(LeafLitter L56/LivingMechanics L515)删末参
  S4 (#4): Handcode L231字段+L449模板键删(threshold/dump_all活体不动)
  S5 (#6): EventCenter L193 region_scans字段+注释删
- 改动面: ~13文件; 编译验证必跑; 部署与刀K同批(一次部署一轮验收)
- 顺带观察(不立案): Core.currentServer(EventCenter L340每tick刷新=自愈, Watchdog L454读它跨世界无害)
## [2026-09-18 23:xx] 刀K落地 c92e5b0（同JVM世界切换零树·静态池清场）
- 四处: DeferredBlocks.clear(core原语补全) / TreeLocation.clearWorldState(六池: region_scan_claims+cache_write_tree_location+cache_write_place+cache_other_region+cache_biome+pendingEmptyChunks) / TreePlacer.clearWorldState聚合入口(DQ.queue+Data+LeafLitter.cache_locations+Function.cache_functions+DeferredBlocks+PlacementGate复用) / EventCenter AboutToStart接线(processed_chunks.clear+两聚合, executor复活之后路径切换之前)
- 设计原则: EventCenter不直接摸外部私有字段, 走各类聚合入口(PlacementGate先例推广); 不清: config缓存(每JVM语义)/DetailedDetection.memoryCache(已废除)
- 时机判决: AboutToStart=menu间隙旧任务死透; 理论外straggler写旧路径不污染新存档(注释记录)
- 4文件44行, BUILD绿

## [2026-09-18 23:xx] 刀N落地 65f1c80（收尸刀S1-S5·落块全通道主线程收敛·幽灵方块物理不可能）
- 施工中发现术前未预见的永动机坑: Tile.set无条件转投+异步侧直接place消费=take→set→add回原缓存死循环(方块永不落地); 根治=两异步消费点(TreePlacer.start空数据分支/EventCenter A3重载else)改DeferredQueue.addForced转投, 主线程processTick消费(就绪检查+setBlock(2)), 环物理不存在
- S1: Tile.set坍缩二分支(主线程setBlock(2)/异步无条件DeferredBlocks.add; getChunkNow探测+lc.setBlockState裸直写=幽灵理论源, 删); resyncChunk五调用点(EC×3+TP×2)+定义退役; flushPendingBlocks孤儿收尸
- S2: Tile.set签名删is_world_gen死参+9调用点(8×false+1×true); S3: Tile.remove删参+neighborChanged无条件化+2调用点; S4: Handcode watchdog_enabled尸体字段+模板键删(threshold/dump_all活体保留); S5: EC region_scans死字段删
- 施工判例: ①锚点+固定偏移死于原作者空行风格, throw在Save前的断点设计=零脏写重跑幂等, 全改锚点+扫描定位; ②javac中文locale错误行滤error:漏报, 双locale过滤(error:|错误|.java:N); ③naive brace计数对string字面量假阳性(fromText解析{}), javac裁决为准; ④EC else扫描误收内层if收尾→悬空闭括号, 编译拦截后修复
- 12文件62+/117-, BUILD绿; 陈旧注释2处随盘面票校正(DeferredBlocks冲刷闭环措辞/EC resubmitPlacement头注释)
- 待验收(max手动): 同JVM旧世界跑图→退存档→新建世界→树应生成; fresh JVM回归; 幽灵撞墙免验(物理不可能); Watchdog盯stall
