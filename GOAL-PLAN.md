# TansHugeTrees 终极重构与修复计划 (1.20.1 Forge)

## 当前状态
- 分支: `1.20.1forge-LMaxFixAndImprove`
- 核心痛点: 作者不懂并发，MCreator 冗余多，硬编码泛滥，主线程阻塞。

## 阶段一：精准切除毒瘤 (解决 TPS 卡死与死锁) - [已完成]
- [x] 1. 废除 `Core.java` 中的 `GlobalLocking`。
- [x] 2. `CacheManager` 升级 `ConcurrentHashMap`。
- [x] 3. `OutsideUtils` 增加网络超时。
- [x] 4. 拆除 `TreePlacer$Data` 中的全局对象锁。
- [x] 5. 修复 `ConcurrentHashMap.computeIfAbsent` 桶锁死锁。
- [x] 6. 引入 `CompletableFuture` 彻底废除同步等待，根除 TPS 掉底和看门狗超时。

## 阶段二：修复异步架构副作用 (解决漏树与密度减半) - [已完成]
- [x] 7. 引入"延迟补种队列 V2 (Deferred Placement Queue V2)"。
- [x] 8. 引入"区块就绪检查 (Chunk Readiness Check)"与"重试机制"，确保跨区块树木在目标区块达到 `FEATURES` 状态后才安全补种，彻底解决 MC 底层的静默丢弃问题。
- [x] 9. 废除 `DetailedDetection` 中的第三把全局锁，消除 30 秒 TPS 尖峰。

## 阶段三：剥离 MCreator 累赘与深度重构
- [ ] 审计 MCreator 注册表，剔除无用代码。
- [ ] 提取硬编码为配置项。