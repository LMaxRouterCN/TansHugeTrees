<!-- ID:001 -->
TansHugeTrees项目:MC1.20.1 Forge47.4.10大树生成模组,核心类TreeLocation(位置计算/距离检测)/TreePlacer(树放置)/EventCenter(事件与发包)/Caches(缓存)。修复史V16-V28:字典污染、CallerRunsPolicy死锁、线程池饥饿、biome modifier step错配、testDistance NPE等。GOAL-PLAN文件(GOAL-PLAN-2026年8月6日-只读和追加,禁止覆写.md)只允许追加,max手写警告不可完全信任其中内容
tag: TansHugeTrees, 项目背景
<!-- END:001 -->
<!-- ID:002 -->
PokerAgent后端exec指令在PowerShell 7.7(2026-08-23)已恢复正常,可执行gradlew等命令。注意:exec传参不能带timeout=xxx之类的附加参数,会被当成命令参数(Gradle会当成任务名报错)。grep大文件仍建议先加-c统计。专用文件指令(grep/read/replace/insert)不受影响
tag: 工具bug, exec恢复, PowerShell
<!-- END:002 -->
<!-- ID:003 -->
TansHugeTrees 2026-08-22日志审计根因:①EventCenter.eventChunkLoaded(172行)每chunk裸new Thread,144行的TREE_GEN_EXECUTOR从未被submit,V20修复名存实亡;②scanned_regions只在region扫描完成后add,一个region扫描=1024次getData,2399个chunk事件对4个region重复扫描600×;③CPU饥饿(非死锁):主线程TIMED_WAITING在waitUntilNextTick的park,Lock not held,2400线程抢12核;④superflat下WorldGenStepBeforePlants.place()从不被调用(实证0次),树全靠ChunkEvent.Load路径;⑤TreeLocation/WorldGenStepBeforePlants的THT-DEBUG println无debug_log守卫,54724条;⑥region_locks只有remove无put死代码;⑦processed_chunks contains+add竞态;⑧服务器Stopping后THT线程成僵尸无取消
tag: TansHugeTrees, 根因, 性能, 线程安全
<!-- END:003 -->
<!-- ID:004 -->
2026-08-22 max拍板THT修复路线:先方案A(region原子认领+线程池submit+日志守卫,100-tick延迟保留)后方案B(事件链重构干掉延迟)。测试方法论修正:超平坦选型正确(可控群系分布),注意0树时进世界反而快是反向指标;玩家是进入世界后被10.5s主线程冻结卡退的,不是进不去。
tag: TansHugeTrees, 架构决策, 测试方法论
<!-- END:004 -->
<!-- ID:005 -->
A方案V38已全部落地:TreeLocation region三态原子认领+EventCenter submit线程池+13处println守卫。遗留:scanned_regions死代码+异常路径无兜底。测试观察点:region扫描>20s则DeferredQueue重试耗尽丢树。
tag: TansHugeTrees, A方案, V38, 遗留问题, 测试方法论
<!-- END:005 -->
<!-- ID:006 -->
V38收尾:①try-finally兜底用remove(key,FALSE)原子回滚,正常完成时TRUE不删,异常时FALSE删除让下个chunk重新认领②意外发现region_locks也是死代码(全项目仅声明+remove,从未put)已一并清理③exec的item bug依旧存在,编译需max手动。
tag: TansHugeTrees, V38, exec不可用, 死代码清理, 原子回滚
<!-- END:006 -->
<!-- ID:007 -->
V39诊断：TreePlacer树不生成bug，已排除NPE和高度检查。两个嫌疑根因：(A)PendingBlocks跨chunk竞态——树方块被add进相邻chunk缓存但place()只拉当前chunk，导致方块残留永不被写入；(B)非主线程(TREE_GEN_EXECUTOR)调用ServerLevel.getChunk()+lc.setBlockState()，MC区块系统非线程安全可能静默失败。V39在add()/place()/early-return三处加诊断日志+空数据chunk也调place()的潜在修复。等跑图结果确认。
tag: 根因, 线程安全, 架构决策, V39, 诊断
<!-- END:007 -->
<!-- ID:008 -->
exec和run指令的item变量bug已被max修复，现已恢复正常使用
tag: exec恢复, 工具bug
<!-- END:008 -->
<!-- ID:009 -->
根因分析（V39验证）：跨chunk树方块滞留cache_blocks。完整调用链：ChunkEvent.Load→100tick延迟→TREE_GEN_EXECUTOR(4-16线程)→TreeLocation.start()+TreePlacer.start()。start()内：Data.get()→空则place()(NULL)+DeferredQueue.add()(retries=0)早退；非空则DetailedDetection→placeCalculate→PendingBlocks.add()分散到多chunk缓存→addForced(source,target)为每个邻居投递forced任务→place(当前chunk)。三个问题：①EARLY RETURN(line184)无限重入：每次start()重试都新建retries=0任务永不消亡，占满processTick 32槽位挤压forced任务；②forced任务±4角点检查(line106-121)：玩家飞远角点chunk卸载则检查失败，400次重试耗尽后任务丢弃，方块永久滞留；③placeForced(line1842)无debug日志无法确认是否被调用。日志铁证：16:08:02后无成功place()，16:08:34时cache_chunks恒定38不降。修复方向：1.加日志验证forced任务状态 2.修EARLY RETURN重试带retries传递 3.修±4检查只检查target chunk自己FULL
tag: TansHugeTrees, 根因, 架构决策, 诊断, V39, 遗留问题
<!-- END:009 -->
<!-- ID:010 -->
V40根因定案+修复实施(2026-09-02)：DeferredQueue维度串黑洞=横杠串minecraft-overworld经parse补默认命名空间→getLevel()=null→静默continue吞掉2569任务(零树+跨chunk劈树同根因)。诊断方法论：队列size回落是poller存活铁证；日志缺失不可信、计数器走势可信；静默continue必须配丢弃日志。修复已实施部署：DeferredTask携带ResourceKey<Level>(入队取level.dimension())，processTick直查getLevel(dim_key)，null分支补stderr canary日志，横杠串仅限Data文件路径。jar=20260902155207。注意：修复后retries=0无限重入路径才真正运转(黑洞曾掩盖)，队列持续震荡即此问题暴露。
tag: 根因, V40, TansHugeTrees, 诊断方法论, 修复
<!-- END:010 -->
<!-- ID:011 -->
PokerAgent exec 工具结论（2026-08-25 修订，覆盖此前"中文导致损坏"的错误归因）：旧单行内联 exec 的传输/解析层会间歇性破坏内容——空格丢失（-Path 与变量粘连）、变量名乱码、ParserError。与中文无关（纯 ASCII 同样中招，同批次一成一败是间歇性缺陷指纹）。正解：代码块格式——exec 独占一行，命令用代码块标签包裹，多行原样执行，不做 ``` 还原。陷阱：项目根目录存在「GOAL-PLAN-…- 副本.md」，通配符按字母序先命中副本，追加必须用精确完整文件名。
tag: 工具bug, PowerShell, PokerAgent, exec, 编码
<!-- END:011 -->
<!-- ID:012 -->
V41根因定案(2026-09-02)：TreePlacer.Data.bin_convert_futures负缓存毒化——computeIfAbsent把"region文件尚不存在"瞬时态解析成空map永久缓存(会话region key远小于256淘汰阈值,无失效路径),数据落盘后读方仍命中空结果→25442/25442全空读→零树。同窝:FileManager.BIN_CACHE(V19,LRU512)对append式增量文件同样持有旧版永不失效。修复=生产者写后失效:①Data.invalidate(dim,rx,rz)删future ②writeBIN写后自失效BIN_CACHE条目(缓存一致性归缓存所有者,调用方零协调) ③flushCachesAsync落盘后调Data.invalidate,DeferredQueue 400tick重试天然成为失效后重读触发器,事件驱动零轮询。诊断方法论:负缓存指纹=全量空读+零异常+零stderr+数据在盘上。原作latent bug:writeBIN"l"类型写writeBoolean(应为long,从未被使用故未爆)。
tag: 根因, V41, TansHugeTrees, 负缓存, 诊断方法论, 修复, 遗留问题
<!-- END:012 -->
<!-- ID:013 -->
V41验证结果(2026-09-02晚)：跑图100格树+枯树成功生成=V40黑洞+V41负缓存双层修复全链生效,mod核心数据链路已通。新现象:出生点10分钟无树——机制假说:writeData的Test Exist Chunk(树落笔前查覆盖chunk是否达features状态,达到则整棵丢弃,防已成型地形上放树)与spawn area世界创建瞬间预生成冲突:扫描发生在90s后,出生点圈内的树全部被写入前拦截,数据侧从未存在。max报三遗留问题:①劈树(细节在旧GOAL-PLAN) ②大片空白(疑=region扫描81-95s+队列重试节奏结构性慢,跑图快于树出现) ③幽灵方块:隐形树可落雪人被弹回=服务端有方块客户端无渲染,DeferredQueue延迟放置发生在chunk已发客户端之后,疑setBlock缺UPDATE_CLIENTS(2) flag。性能:机械盘持续5MB/s写+卡顿,watchdog无长tick(持续40-50ms级卡非秒级),IO来源候选:debug日志本身(~200KB/s同步刷)/树数据小文件追加/脏chunk .mca重写/光照重算CPU。
tag: V41, 验证, 出生点, 幽灵方块, 遗留问题, 性能, TansHugeTrees
<!-- END:013 -->
<!-- ID:014 -->
V42诊断(2026-09-02晚,世界22)：三个新根因证据。①幽灵方块根因=空头注释:placeForced用setBlock flags=4(不发客户端),L1898注释称"EventCenter统一发送"但全源码搜playersChangedBlock/sendBlockChanged/ClientboundBlockUpdatePacket/markAndNotifyBlock零命中——同步代码根本不存在。②队列溢出政策=丢最老任务不区分类型,184393次丢弃混杀携带真实载荷的FORCED任务=随机空白;溢出stderr日志不受debug_log控制。③churn引擎=start()空数据路径无条件add()重入队,region扫描完成后真无树的chunk永动循环。守卫假说已实锤(0,0.bin spawn圈±10零桶)。IO侧:671MB日志=646K条EARLY RETURN+665K条processTick主导;V41失效致整文件重读~GB级读放大。扫描59s→218s劣化嫌疑=testDistance O(n²)。
tag: V42, 幽灵方块, 根因, 队列溢出, churn, TansHugeTrees, 遗留问题, 性能
<!-- END:014 -->
<!-- ID:015 -->
字节校验canary必须锚定到成员真正所在的class文件:Java嵌套类(含static class如TreePlacerDeferredQueue/HandcodeDeferredQueue/HandcodeDeferredQueue/HandcodeConfig/EventCenterServer)编译为独立的OuterServer)编译为独立的OuterServer)编译为独立的OuterInner.class,在外部类class里grep嵌套类成员名必然FAIL并误报"代码缺失"。教训:V42部署校验首报2个假FAIL(实际路径错),重验按TreePlacerDeferredQueue.class等正确路径全过。校验脚本写法:ZipArchive.OpenRead→Entries精确匹配FullName(注意DeferredQueue.class等正确路径全过。校验脚本写法:ZipArchive.OpenRead→Entries精确匹配FullName(注意DeferredQueue.class等正确路径全过。校验脚本写法:ZipArchive.OpenRead→Entries精确匹配FullName(注意在字符串中无特殊义)→ASCII GetString→Contains(canary)。
tag: 测试方法论, 验证, 工具bug, 字节校验, 内部类
<!-- END:015 -->
