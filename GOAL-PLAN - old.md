# TansHugeTrees (1.20.1) 屎山解剖与重构报告
> 记录一次对 MCreator 生成代码与反人类并发设计的深度外科手术。
> 维护者: LMaxRouterCN & AI 协作者
> 分支: `1.20.1forge-LMaxFixAndImprove`
> 核心痛点: 作者不懂并发，MCreator 冗余多，硬编码泛滥，主线程阻塞。
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

### 7. 史诗级 I/O 灾难：多线程并发追加写入 (`TreeLocation.start` & `FileManager`)
*   **症状**：机械硬盘 100% 占用，CPU 100%，SQLite 锁死，Watchdog 报警 40 秒。DH 树木密集重叠，MC 接手后大片空白。
*   **病理**：原作者把数据存入全局 `static` Map，然后让每个并发生成的区块都去遍历、追加写入 `.bin` 文件并 `clear()` 全局 Map。多线程同时 `append` 导致数据重叠，多线程同时 `clear()` 导致数据丢失，HDD 磁头疯狂寻道导致 I/O 瘫痪。
*   **手术**：引入 `io_executor` 异步批量刷盘。生成线程只负责原子级快照并清空内存缓存，极重的磁盘 I/O 交由后台单线程串行、安全地执行。并在 `FileManager` 加入文件级锁兜底。

---

## 🛠️ 核心重构架构 (Current Architecture)

**目标:优化性能,修复卡顿,崩溃,运行和各种不正常行为问题,不改变原版行为**

1. **非阻塞异步数据管线**：`TreePlacer$Data` 使用 `CompletableFuture` 异步加载。
2. **工业级延迟补种队列 (Deferred Queue V2)**：检查 `ChunkStatus.FEATURES`，安全补种。
3. **纯内存级地形检测缓存**：`DetailedDetection` 使用 `ConcurrentHashMap`。
4. **全局线程安全容器**：彻底消灭 `HashMap` 和 `ArrayList` 的并发修改异常。
5. **异步 I/O 调度器**：彻底解耦区块生成与磁盘写入，解决 HDD 瓶颈。

---

## 🚧 下一步计划 (Next Steps)

- [ ] 观察树木密度是否彻底恢复正常（不再重叠，不再空白）。
- [ ] 审计 MCreator 注册表，剔除无用代码。

## 🚨 当前未解决的核心痛点 (Unresolved Issues)

尽管 V11 异步 I/O 架构已经部署，且 CPU 100% 证明线程不再死锁，但以下症状依然存在：

### 1. 数据重叠与丢失 (DH 密集，MC 空白定形)
*   **症状**：DH 生成的区域树木密密麻麻（重叠），而 MC 接手生成的区域大片空白，且一旦空白就永久定形。
*   **病理分析**：
    *   虽然引入了 `io_executor` 异步刷盘，但 `FileManager.writeBIN` 依然使用了 `append=true`（追加写入）。
    *   当多个线程（DH 和 MC）同时生成同一个 Region 的区块时，它们会**并发触发 `TreeLocation.start`**，将数据塞入缓存并提交刷盘任务。
    *   即使有文件级锁，多个刷盘任务排队对同一个 `.bin` 文件进行 `append`，极易导致文件指针错乱或数据重复写入（导致 DH 密集）。
    *   如果某个刷盘任务因为 I/O 瓶颈失败或被覆盖，MC 后续读取该 `.bin` 文件时就会读到损坏或空的数据，导致大片空白。

### 2. 劈树现象与大片空白 (跨区块截断)
*   **症状**：树木在区块边界被劈开，或者出现大面积的无树空白区。
*   **病理分析**：
    *   我们在 `DeferredQueue` 中使用了 `ChunkStatus.FEATURES` 作为区块就绪检查标准。
    *   但在 Minecraft 1.18+ 的底层架构中，`FEATURES` 阶段的区块**依然不允许安全地跨区块写入非固体方块（如树叶）**。强行写入会被 MC 底层静默丢弃（Silent Drop）。
    *   必须将检查标准提升至 `ChunkStatus.FULL`，或者在写入时强制加载目标区块。

### 3. TPS 尖峰与 HDD 100% 占用
*   **症状**：跑图时 CPU 100%，机械硬盘 100%，伴随 500ms-1300ms 的 TPS 尖峰。
*   **病理分析**：
    *   `io_executor` 单线程处理刷盘时，如果队列堆积过多，会疯狂向 HDD 发起小文件 I/O 请求，打爆机械硬盘的寻道能力。
    *   `DeferredQueue` 在主 Tick 消费时，如果没有做严格的平滑限流，一次性处理大量补种任务会直接卡死主线程。

---

## 🛠️ 下一步手术方案 (V12 架构预想)

### 方案 A：废除 Append，改用“内存全量覆盖写入”
彻底抛弃 `append=true`。在 `flushCachesAsync` 中，不要只写入增量数据，而是把内存中该 Region 的**所有树木数据**提取出来，一次性 `overwrite`（覆盖）写入 `.bin` 文件。这样无论多少个线程并发提交刷盘任务，最终写入硬盘的永远是一份完整且干净的数据，彻底杜绝文件损坏和数据重叠。

### 方案 B：提升区块就绪检查至 `ChunkStatus.FULL`
修改 `DeferredQueue` 中的 `isOrAfter(ChunkStatus.FEATURES)` 为 `isOrAfter(ChunkStatus.FULL)`。确保只有在目标区块及周围区块**完全加载并准备好接受一切方块修改**时，才执行补种逻辑，彻底消灭劈树和静默丢弃。

### 方案 C：主 Tick 消费平滑限流
在 `EventCenter` 消费 `DeferredQueue` 时，引入基于 MSPT 的动态限流。如果当前 Tick 耗时已经超过 40ms，立刻停止消费，把剩下的任务留到下一个 Tick，保证 TPS 永远不掉。