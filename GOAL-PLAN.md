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
