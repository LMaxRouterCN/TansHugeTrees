# TansHugeTrees (1.20.1) 屎山解剖与重构报告
> 记录一次对 MCreator 生成代码与反人类并发设计的深度外科手术。
> 维护者: LMaxRouterCN & AI 协作者
> 分支: `1.20.1forge-LMaxFixAndImprove`

---

## 💀 案发现场与病理分析 (Bugs Found)

### 1. 夺命第一锁：全局对象锁 (`Core$GlobalLocking`)
*   **病理**：作者在 `TreeLocation.start` 中使用了全局 `synchronized` 锁。DH 的十几个线程全在排队等这把锁，只要一个线程在算树，全服区块生成瘫痪。
*   **手术**：直接物理超度，将 `lock()`, `unlock()`, `test()` 变为空方法。

### 2. 夺命第二锁：ConcurrentHashMap 桶锁陷阱 (`TreePlacer$Data`)
*   **病理**：作者用 `synchronized (lock)` 包裹极重的 `.bin` 文件 I/O。Java 底层机制会在 Lambda 执行期间锁住当前的 Hash 桶。
*   **手术**：彻底废除同步等待，引入 `CompletableFuture` 纯异步加载。

### 3. 夺命第三锁：硬盘缓存锁 (`DetailedDetection`)
*   **病理**：作者搞了一套 `.bin` 硬盘缓存来记录"这棵树该不该生"，并用全局锁同步读写。
*   **手术**：彻底废除硬盘缓存，改用纯内存 `ConcurrentHashMap` 缓存判定结果。

### 4. 隐藏的雷区：非线程安全容器 (CME 连环雷)
*   **病理**：现代 MC 的 `ChunkGenerator` 使用 `ForkJoinPool` 并发跑区块。作者在 `TreePlacer`、`TreeLocation` 的静态内部类里用了大量的普通 `HashMap` 和 `ArrayList`，导致 `ConcurrentModificationException`。
*   **手术**：全局替换为 `ConcurrentHashMap` 和 `Collections.synchronizedList`。

### 5. 终极毒药：`+-4` 重复生成逻辑 (`TreeLocation`)
*   **病理**：原作者为了防止"跨区块截断（劈树）"，写了 `TreeLocation.run(..., chunk_pos +- 4)`。导致 9 倍的并发计算量和重复放置。
*   **手术**：直接砍掉！依赖 V2 延迟补种队列的"区块就绪检查"兜底。

### 6. 跨线程实体生成幽灵 (`GameUtils$Mob`)
*   **病理**：原作者用 `execute()` 试图把实体生成丢回主线程，但 Cupboard 依然在 Worker 线程拦截到 off-thread 警告，加剧死锁。
*   **手术**：废弃 `execute()`，建立专属 `entity_queue`，在 `EventCenter` 主 Tick 中安全消费。

---

## 🛠️ 核心重构架构 (Current Architecture)

1. **非阻塞异步数据管线**：`TreePlacer$Data` 使用 `CompletableFuture` 异步加载。
2. **工业级延迟补种队列 (Deferred Queue V2)**：检查 `ChunkStatus.FEATURES`，安全补种。
3. **纯内存级地形检测缓存**：`DetailedDetection` 使用 `ConcurrentHashMap`。
4. **全局线程安全容器**：彻底消灭 `HashMap` 和 `ArrayList` 的并发修改异常。

---

## 🚧 下一步计划 (Next Steps)

- [ ] 测试 V10 架构，确认 Watchdog 不再报警，CME 彻底消失。
- [ ] 观察树木密度是否恢复正常（DH 与 MC 生成区域一致）。
- [ ] 审计 MCreator 注册表，剔除无用代码。