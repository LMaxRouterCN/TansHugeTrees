# GOAL-PLAN — TansHugeTrees 大修战役 (LMax Fix V51)
> 更新: 2026-09-20 深夜·夜班自动轮 | 阶段: 刀O部署→待游戏内验收
> 判例库: .agent/memory.md (长期记忆 001-140), 本文件=作战地图, 判例细节不重复
> 上一代快照: .agent/GOAL-PLAN_v50_snapshot.md (V50.x, 2026-09-17)

## 0. 项目身份
- 仓库: D:\Documents\mcmod\TansHugeTrees (1.20.1 Forge 47.4.10)
- 目标: 修复 Tan's Huge Trees 世界生成管线系列缺陷 (扫描→bin→DQ/executor放置→客户端可见)
- 部署链: gradlew build --offline → build\libs jar → MD5双验 → mods唯一 → (游戏运行中禁部署)
- 测试台: 世界54=草面grove超平坦 / 世界55=雪面grove超平坦(polaris专属试验台)
- commit链: e702ed4(刀J) → 75dc0a9(max护档) → ecb8c93(刀I) → 0558c3f(刀H) → 65f1c80(刀N) → cd5273c(刀O)

## 1. 状态快照 (2026-09-20 深夜)
- HEAD: cd5273c (刀O), 工作树clean(仅.agent随行)
- mods jar: 夜班已部署刀O版(结果见夜班记录§6终报)
- 现役日志: latest.log = 09-19 01:29-01:40会话(世界54→55切换+Esc暂停+任务管理器杀进程)

## 2. 已结案 (核心判决)
| 案 | 修复 | 终验/判例 |
|---|---|---|
| 幽灵方块 | 刀L(直写根治)+刀N(主线程收敛) | 记忆128/130 活体证明 |
| 跨世界静态泄漏 | 刀K(region_scan_claims清场) | 世界55同种子翻案(记忆130) |
| 门黑洞/队列陷阱 | 刀G-J系 | 各记忆判例 |
| Watchdog跨世界假episode | 刀O(armed生命周期) | 源码验证完, 待游戏内验收(判据4条, 记忆137) |
| 第二假家族(单人暂停130s+) | 判别器结案: 非死锁 | 记忆139, max裁决不修 |

## 3. 活跃/待裁 (max醒来后)
1. 刀O验收: 下次开游戏即测. 判据: ①60s+假episode消失 ②真episode分布不变(29个全≤5s) ③切世界窗口零WATCHDOG ④无聊天假警报(tellraw)
2. E4案终判(记忆140): 62次全同一鬼文件=pack内路径错配(zip中bush_...704.bin在presets\bush\storage, polaris的path_storage=wendy/storage), 修法三选一: A改pack数据对齐池与路径(根修) / B日志去重 / C负缓存硬化
3. L1704案D挂账(记忆136): E9=0基线已立, 未来有村庄世界时grep复验(>0且伴冻结再立项)
4. 记忆维护: 顺手制, 097已pin

## 4. 方法论铁律 (血泪浓缩)
- 会计闭环: 入口计数=Σ出口+PASS, 多出口分支全员打点
- 观测者对账: 产出坐标vs观测者坐标, 先对账再判失败(两次翻案同因)
- jar时间戳vs commit时间: 部署前必核(世界55首测作废教训)
- 定性竞态: 必须按查询key(location全文)分组对账, id级聚合=假象(E4三翻案)
- "运行了"≠"写入了": 无落块计数的放置调用不算证据
- 判别实验优先: 先设计一锤定音实验, 再下结论

## 5. 测试环境备忘
- 世界54/55=超平坦grove试验台, 同JVM切世界验收协议见记忆137
- max操作画像: 开LAN时Esc/M地图不暂停(单人暂停机制失效); 结束=任务管理器杀进程; 体感不卡=真无卡顿(人证)
- 单人Esc暂停/外部杀进程=watchdog假episode天然源, 预期行为非bug

## 6. 夜班自动轮记录 (2026-09-20, max睡眠期)
- 判别器结案(139): 130s未闭合episode=单人暂停, P0死锁除名, 刀P否决
- E4终判(140): 鬼文件=路径错配非缺失, 配置级债
- build+部署刀O → mods (MD5双验), 休眠倒计时1.5h(THT_AutoSleep任务)
- 取消倒计时: schtasks /Delete /TN THT_AutoSleep /F