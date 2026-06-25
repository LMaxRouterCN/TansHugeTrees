

>我是max,这条信息是我手写的,如果你看到这条信息,我得提醒你一下,不要绝对相信这个文件里写的东西,他们可能并没有在实际上做完他们说的改动
>并且我在前几天就不允许贡献者覆写这个文件了,所有人都只能追加内容
>我会手动编辑这个文件删除过时的信息
>我有时候会在下面的审计结果下批注,有批注就优先听我的



# TansHugeTrees 并发修复计划
## 📋 问题概览
经过代码审计，发现 `TreeLocation.java`、`TreePlacer.java` 和 `Caches.java` 中存在以下严重并发与内存问题：
### 🔴 严重问题 (Critical)
1.  **全局缓存清理竞态** (`TreeLocation.java:157-161`)
    *   **现象**：在 `run()` 方法末尾，`cache_other_region.clear()`、`cache_biome.clear()`、`TreePlacer.Data.clear()` 和 `TreePlacer.DetailedDetection.clear()` 被调用，这些是全局缓存。当多个线程处理不同区域时，一个线程完成扫描后调用 `clear()` 会清空所有其他线程正在使用的缓存数据。
    *   **影响**：树木生成错误、缺失、甚至游戏崩溃。
    *   **原因**：缓存作用域设计错误，将区域级数据误用全局缓存管理。
2.  **区域文件创建竞态** (`TreeLocation.java:110-114`)
    *   **现象**：`if (file_region.exists() == false) { FileManager.writeBIN(...); ... }` 是一个非原子操作。两个处理同一区域不同区块的线程可能同时通过 `exists()` 检查，导致创建空文件和重复扫描整个32x32区域。
    *   **影响**：重复树木生成、计算资源浪费、可能的数据损坏（`flushCachesAsync` 并发写同一文件）。
    *   **原因**：缺乏对共享资源（区域文件）的原子性访问控制。
### 🟠 高危问题 (High)
3.  **缓存内存泄漏与 I/O 放大** (`TreeLocation.java:42-86`)
    *   **现象**：`flushCachesAsync()` 方法从不清理 `cache_write_tree_location` 和 `cache_write_place`，每次都深拷贝所有历史数据并写入所有区域文件。
    *   **影响**：随着探索区域增多，内存占用线性增长，导致OOM崩溃；每次刷盘写入大量无关文件，造成磁盘I/O风暴。
    *   **原因**：V12修复策略错误，试图通过“全量写入”保证数据一致性，却忽略了内存管理。
### 🟡 中危问题 (Medium)
4.  **进度条UI非线程安全** (`TreeLocation.java:31-34`)
    *   **现象**：`world_gen_overlay_animation` 和 `world_gen_overlay_bar` 是普通静态 `int`，多个扫描线程同时修改它们。
    *   **影响**：客户端进度条UI显示异常、数据撕裂。
    *   **原因**：多线程共享可变状态未使用原子类或同步机制。
5.  **Caches延迟加载非原子** (`Caches.java:103-122`)
    *   **多线程下并发调用 `getTreeShapeSize` 等方法时，`get -> null -> load -> get` 模式无同步保护。**
    *   **影响**：如果底层 `HashMap` 扩容，可能导致死循环（CPU 100%）或 `ConcurrentModificationException` 崩溃；同一树木形状可能被重复加载。
    *   **原因**：懒加载逻辑未考虑并发场景，依赖的 `CacheManager` 线程安全性未明。
## 🛠️ 修复计划
### 阶段一：核心并发问题修复 (优先级最高)
#### 1.1 修复全局缓存清理竞态
*   **目标**：确保缓存清理仅作用于当前完成的区域，不干扰其他线程。
*   **方案**：
    *   将 `cache_other_region` 和 `cache_biome` 改为按区域键（`regionKey`）存储的结构，例如 `ConcurrentHashMap<String, Map<...>>`。
    *   在 `run()` 方法末尾，使用 `remove(regionKey)` 替代全局 `clear()`。
    *   **修改文件**：`TreeLocation.java`
    *   **修改行**：29, 30, 157-158
*   **具体代码**：
    ```java
    // 原 (行29-30)
    // private static final Map<String, Map<ChunkPos, Map<BlockPos, String>>> cache_other_region = ...;
    // private static final Map<ChunkPos, Holder<Biome>> cache_biome = ...;
    // 新
    private static final Map<String, Map<String, Map<ChunkPos, Map<BlockPos, String>>>> cache_other_region_by_region = new ConcurrentHashMap<>();
    private static final Map<String, Map<ChunkPos, Holder<Biome>>> cache_biome_by_region = new ConcurrentHashMap<>();
    // 原 (行157-158)
    // cache_other_region.clear();
    // cache_biome.clear();
    // 新
    cache_other_region_by_region.remove(regionKey); // regionKey 需要在 run() 方法中定义
    cache_biome_by_region.remove(regionKey);
    ```
#### 1.2 修复区域文件创建竞态
*   **目标**：确保每个区域文件只被创建和扫描一次，即便多个线程同时请求。
*   **方案**：
    *   使用 `ConcurrentHashMap<String, Boolean>` 作为“正在处理”标记，或使用 `ConcurrentHashMap<String, CompletableFuture>` 合并并发请求。
    *   更简单的方案：使用 `Files.createFile()` 原子创建，捕获 `FileAlreadyExistsException` 来判断是否已被创建。
    *   **修改文件**：`TreeLocation.java`
    *   **修改行**：108-162
*   **具体代码**：
    ```java
    // 在 run() 方法中，增加区域级锁或使用原子文件创建
    String regionKey = regionX + "," + regionZ;
    // 使用 computeIfAbsent 实现单例扫描
    scanningInProgress.computeIfAbsent(regionKey, key -> {
        try {
            // 原有扫描逻辑...
            // 确保扫描完成后从 scanningInProgress 移除
            return true;
        } finally {
            scanningInProgress.remove(regionKey);
        }
    });
    ```
    *   或者使用更简单的文件原子创建：
    ```java
    try {
        java.nio.file.Files.createFile(file_region.toPath());
        // 创建成功，说明是第一个线程，开始扫描
        // ... 原扫描逻辑 ...
    } catch (java.nio.file.FileAlreadyExistsException e) {
        // 文件已存在，说明另一个线程已创建，直接返回
        return;
    }
    ```
### 阶段二：内存泄漏与I/O优化 (高危)
#### 2.1 修复缓存内存泄漏
*   **目标**：在异步写入成功后，清理当前区域对应的缓存数据，防止内存无限增长。
*   **方案**：
    *   在 `flushCachesAsync()` 的异步任务成功执行后，遍历缓存，移除当前区域的所有区块数据。
    *   **修改文件**：`TreeLocation.java`
    *   **修改行**：42-86
*   **具体代码**：
    ```java
    // 在 flushCachesAsync 方法的异步任务 try 块末尾添加
    // 清理当前区域的数据
    String currentRegionKey = regionKey; // 需要传入或定义
    locSnapshot.keySet().removeIf(cp -> {
        String rk = (cp.x >> 5) + "," + (cp.z >> 5);
        return rk.equals(currentRegionKey);
    });
    // 同样处理 cache_write_place 如果它也是按区域存储的话
    ```
    *   **注意**：需要将 `regionKey` 传递给 `flushCachesAsync` 方法。
#### 2.2 优化I/O写入策略
*   **目标**：避免每次刷盘写入所有历史区域数据，仅写入当前区域的数据。
*   **方案**：
    *   修改 `flushCachesAsync()`，只快照和写入当前区域的数据，而非全量数据。
    *   **修改文件**：`TreeLocation.java`
    *   **修改阶段一**：修改 `run()` 方法中调用 `flushCachesAsync` 的部分，传入区域信息。
    *   **修改行**：153
*   **具体代码**：
    ```java
    // 修改 flushCachesAsync 方法签名
    public static void flushCachesAsync(String dimension, String regionKey) {
        // ... 仅提取当前 regionKey 的数据进行快照和写入 ...
        // ... 成功后从全局缓存中移除该 regionKey 的数据 ...
    }
    // 在 run() 方法中调用
    flushCachesAsync(dimension, regionKey);
    ```
### 阶段三：线程安全加固 (中危)
#### 3.1 修复进度条UI线程安全
*   **目标**：确保多个扫描线程不会同时修改进度条变量，导致UI数据撕裂。
*   **方案**：
    *   将 `world_gen_overlay_animation` 和 `world_gen_overlay_bar` 改为 `AtomicInteger`。
    *   **修改文件**：`TreeLocation.java`
    *   **修改行**：31-32
*   **具体代码**：
    ```java
    // 原
    // public static int world_gen_overlay_animation = 0;
    // public static int world_gen_overlay_bar = 0;
    // 新
    public static final java.util.concurrent.atomic.AtomicInteger world_gen_overlay_animation = new java.util.concurrent.atomic.AtomicInteger(0);
    public static final java.util.concurrent.atomic.AtomicInteger world_gen_overlay_bar = new java.util.concurrent.atomic.atomic.AtomicInteger(0);
    ```
    *   **注意**：所有使用这些变量的地方都需要相应修改为 `.get()` 和 `.set()` 或 `.incrementAndGet()`。
#### 3.2 修复Caches延迟加载线程安全
*   **目标**：确保 `Caches.TreeShape.getTreeShapeSize` 等方法在多线程下安全。
*   **方案**：
    *   使用 `ConcurrentHashMap` 作为底层缓存。
    *   使用 `computeIfAbsent` 实现原子懒加载。
    *   **修改文件**：`Caches.java`
    *   **修改行**：103-122, 145-160
*   **具体代码**：
    ```java
    // 在 Caches.java 中
    // 确保 CacheManager.DataShort.getArray() 返回的是 ConcurrentHashMap
    // 修改 getTreeShapeSize 方法
    public static short[] getTreeShapeSize(String id) {
        // 使用 computeIfAbsent 保证原子性
        return CacheManager.DataShort.getArray("tree_shape_size").computeIfAbsent(id, key -> {
            getTreeShape(id); // 懒加载
            return CacheManager.DataShort.getArray("tree_shape_size").get(id);
        });
    }
    // 或者更简洁的写法
    public static short[] getTreeShapeSize(String id) {
        return CacheManager.DataShort.getArray("tree_shape_size").computeIfAbsent(id, key -> {
            getTreeShape(id);
            return CacheManager.DataShort.getArray("tree_shape_size").get(id);
        });
    }
    ```
    *   **注意**：需要确认 `CacheManager.DataShort.getArray()` 内部实现是否线程安全。如果不可修改，需要在 `Caches` 层加锁。
## 🧪 测试计划
1.  **单元测试**：编写多线程测试用例，模拟多个区域同时扫描，验证：
    *   缓存清理不互相干扰。
    *   区域文件只创建一次。
    *   内存占用稳定。
    *   进度条UI正常。
2.  **集成测试**：在Minecraft世界生成中，使用`/fill`命令快速生成大量区块，观察：
    *   树木生成是否正确，无重复或缺失。
    *   服务器TPS是否稳定。
    *   内存曲线是否平稳。
    *   磁盘I/O是否正常。
3.  **长期运行测试**：创建新世界，玩家飞行探索100+区域，监控内存和I/O。

---

# 代码改动与修复记录 (GOAL-PLAN)

## 1. TreeLocation.java (`run` 方法)
**目标：** 解决并发环境下多区块同时生成导致的数据丢失、NPE 以及重复扫描文件的问题。
**具体改动：**
* **引入区域级锁 (Region Lock)：** 使用 `region_locks.computeIfAbsent(regionKey, k -> new Object())` 和 `synchronized` 块，确保同一 Region 的扫描和文件创建操作在同一时间只有一个线程能执行。
* **增加快速跳过路径：** 在同步块内优先检查内存缓存 `scanned_regions.contains(regionKey)`，若已扫描则直接 return，减少不必要的 IO 和锁等待。
* **移除危险的全局 `clear()` 调用：** 彻底删除了会导致其他正在生成的区块丢失数据的全局缓存清空操作。
* **改用异步刷新缓存：** 替换为 `flushCachesAsync(dimension, regionX, regionZ)`，将缓存刷新操作异步化，提升主线程性能并保证数据安全。

## 2. Caches.java (`getTreeShapeSize`, `getTreeShapeBlockCount`, `getTreeShapeData`)
**目标：** 优化树形状数据的延迟加载逻辑，减少重复调用并防止潜在的 NPE。
**具体改动：**
* **优化延迟加载：** 在获取缓存数据（如 `short[]` / `int[]`）时，若发现数据为 `null`，仅调用一次 `getTreeShape(id)` 触发加载。
* **安全降级返回：** 加载后重新从缓存获取数据，若依然不存在，使用 `getOrDefault(id, new short[0])` 返回安全的空数组，避免下游出现空指针异常。
* **减少冗余请求：** 逻辑更清晰，避免了对 `CacheManager` 的重复调用。

---

# 第二阶段：深层并发与内存缺陷审计及重构计划
> 审计人：架构师 Agent
> 状态：待执行
> 执行代号22

## 🚨 核心结论
前一阶段的修补（引入部分 ConcurrentHashMap 和锁）仅治标不治本。当前代码库在并发控制、内存管理和 I/O 调度上存在系统性缺陷。打补丁已无意义，必须对 `TreeLocation.java`、`TreePlacer.java` 和 `Caches.java` 进行结构性重构。

## 🔍 缺陷审计报告

### 🔴 严重缺陷 (Critical)
1. **`Caches.java`: `TreeSettings` 延迟加载无同步保护**
   - **现象**: `TreeSettings.get(id)` 方法无任何同步控制。多线程同时请求未缓存配置时，会并发触发 `get(id)`，对底层 `CacheManager` 进行并发写入。
   - **影响**: CPU 100% 死循环（HashMap 扩容）或 `ConcurrentModificationException` 崩溃。
   - **对比**: `TreeShape` 加了 `synchronized`，`TreeSettings` 却裸奔。

2. **`TreeLocation.java`: `testDistance` 中 `computeIfAbsent` 嵌套重 I/O 导致线程阻塞**
   - **现象**: `cache_other_region.computeIfAbsent(key, k -> { ... load from disk ... })`。`ConcurrentHashMap` 的 `computeIfAbsent` 执行期间持有该 bin 的锁。
   - **影响**: 磁盘读取慢时，所有试图访问该区域数据的线程被阻塞，导致世界生成线程池卡死，TPS 暴跌。

### 🟠 高危缺陷 (High)
3. **`TreePlacer.java`: 异步数据加载机制存在丢数据风险**
   - **现象**: `Data.get()` 中，若 `future.isDone()` 为 false，返回空 `ByteBuffer`。调用方将其加入 `DeferredQueue`，但队列最多重试 200 次。
   - **影响**: 区域数据文件大时，加载超 200 tick (10秒)，树木被静默丢弃，永远无法生成。

4. **`TreeLocation.java`: `writeData` 中的 I/O 风暴与文件损坏风险**
   - **现象**: `writeData` 中若区域已扫描完毕，向 `io_executor` 提交异步任务：读取整个 `.bin` -> 加一条数据 -> 写回去。区域内多树生成导致多次全量读写。
   - **影响**: 极低效率，高并发下内存溢出。

### 🟡 中危缺陷 (Medium)
5. **内存泄漏三连击**
   - `TreePlacer.DetailedDetection.memoryCache`: key 为 `dimension_X_Z`，永不清理。探索数千区块后 OOM。
   - `TreeLocation.region_locks`: 锁池对象永不释放。
   - `TreePlacer.DeferredQueue`: 无界队列。生成速度大于处理速度时无限增长。

6. **`TreeLocation.java`: `scanned_regions` 逻辑漏洞**
   - **现象**: 服务器重启后内存 `scanned_regions` 清空。玩家在旧区域种植新树（`writeData`），因 `scanned_regions` 为空，数据写入 `cache_write_tree_location`，但不会触发 `flushCachesAsync`。
   - **影响**: 新种的树在重启后丢失。

## 🛠️ 重构执行计划 (交由执行组 LLM)

### 阶段一：修复核心崩溃与死锁 (优先级最高)
**任务 1.1: 修复 `Caches.java` 线程安全**
- 为 `TreeSettings.get(id)` 方法添加 `synchronized` 关键字，或改为双重检查锁（DCL）模式，逻辑对齐 `TreeShape`。

**任务 1.2: 修复 `TreeLocation.testDistance` 阻塞问题**
- 废除 `cache_other_region.computeIfAbsent` 中的直接磁盘读取。
- 改为：先在 Map 外检查是否存在，不存在则通过异步线程加载，主线程跳过当前树木的距离检测（返回 true 允许生成，后续靠 DetailedDetection 兜底）或使用 `CountDownLatch` 等待（如果必须阻塞）。
- *建议方案*：将 Region 数据加载统一收口到 `TreePlacer.Data` 的类似异步机制中，`testDistance` 仅查询内存缓存，未命中则视为无冲突。

### 阶段二：解决内存泄漏与 I/O 瓶颈
**任务 2.1: 引入缓存过期机制**
- 为 `TreePlacer.DetailedDetection.memoryCache`、`TreeLocation.region_locks` 引入 Guava Cache 或 Caffeine，设置基于大小或时间的过期策略。
- `TreePlacer.DeferredQueue` 改为有界队列，满时丢弃旧任务或拒绝新任务（记录日志）。

**任务 2.2: 重构 `TreeLocation.writeData` 的 I/O 逻辑**
- 废除当前的全量读改写模式。
- 对于已扫描区域的新增树木，采用追加写（Append）模式或维护一个独立的内存写入缓冲区，定期批量刷盘。

### 阶段三：修复数据一致性
**任务 3.1: 修复 `scanned_regions` 重启丢失问题**
- `TreeLocation.writeData` 中，若 `scanned_regions` 不包含当前区域，必须确保数据仍能被持久化。
- 方案：在 `writeData` 末尾或定期调用 `flushCachesAsync`，或者将 `scanned_regions` 状态持久化到磁盘（如索引文件）。



        

          
## 编译错误修复记录 (2026-06-23)

### 当前编译错误
1. **Caches.java:159** - FileManager.readTXT 返回 String[] 而非 List<String>
2. **Overlays.java:23-26,72-75** - AtomicInteger 与 int 类型运算错误
3. **TreeLocation.java:97,108** - FileManager.readBIN 返回 ByteBuffer 而非 List<String>
4. **TreeLocation.java:355,385** - 缺少 java.util.concurrent.ConcurrentHashMap import

### 修复方案
1. Caches.java: 使用 Arrays.asList() 转换 String[] 为 List<String>
2. Overlays.java: 使用 .get() 方法获取 AtomicInteger 的值再运算
3. TreeLocation.java: 适配 ByteBuffer 返回类型，修改后续处理逻辑
4. TreeLocation.java: 添加 import java.util.concurrent.ConcurrentHashMap

### 执行状态
- [ ] 修复 import 语句
- [ ] 修复 Caches.java 类型转换
- [ ] 修复 Overlays.java AtomicInteger 运算
- [ ] 修复 TreeLocation.java ByteBuffer 适配
- [ ] 重新编译验证
        

          
---

# 阶段二执行状态确认 (2026-06-24)

> 2026年6月24日23点19分经llm自主确认上述改动已落实

## 阶段二任务实际完成状态

| 任务 | 状态 | 说明 |
|------|------|------|
| 1.1 Caches.java 线程安全 | ✅ 已落实 | TreeSettings.get() 已添加 synchronized 关键字 (行136) |
| 1.2 testDistance 阻塞修复 | ✅ 已落实 | 废除 computeIfAbsent 中的阻塞式磁盘读取，改用 io_executor 异步加载 (行356-398) |
| 2.1 缓存过期机制 | 🔶 部分落实 | region_locks 扫描后 remove 已落实；DetailedDetection.memoryCache 和 DeferredQueue 未做 |
| 2.2 writeData I/O 重构 | ✅ 已落实 | 废除全量读改写，改用内存缓冲 + append=true 追加写 |
| 3.1 scanned_regions 重启丢失 | ✅ 已落实 | 废除 scanned_regions 判断，统一走内存缓冲 |

## LLM 审计中新发现的问题 (GOAL-PLAN 未覆盖)

### 问题 A: TreePlacer.Data.bin_convert_futures 内存泄漏
- clearChunk() 方法体为空，bin_convert_futures 按 dimension/regionX,regionZ 存储的 Future 永不清理
- 每个 Future 持有 Map<ChunkPos, ByteArrayOutputStream>，探索数百区域后必然 OOM

### 问题 B: TreePlacer.DetailedDetection.memoryCache 无限增长
- key 为 dimension_X_Z，永不清理，探索数千区块后 OOM

### 问题 C: TreePlacer.DeferredQueue 无界队列
- ConcurrentLinkedQueue 无容量限制，生成速度大于处理速度时无限增长

### 问题 D: 硬编码值
- CACHE_OTHER_REGION_MAX = 64 (TreeLocation.java:43)
- DeferredQueue 重试上限 200、每 tick 100 个任务
- 应迁移到 Handcode.Config

## 本轮修复计划

1. DetailedDetection.memoryCache — 加容量上限淘汰机制（复用 cache_other_region 已有的迭代器淘汰模式）
2. DeferredQueue — 加容量上限，满了丢弃旧任务并记日志
3. Data.bin_convert_futures — 加容量上限淘汰机制
4. 硬编码值 — 迁移到 Handcode.Config


        

          
---

### 2026年6月25日 (代码修复执行 - 基于上述2024年6月24日审计结果)

**执行状态**: 已完成编译，等待实机测试
**修改目标**: 解决上述4个内存泄漏及无界增长问题，将硬编码缓存上限迁移至可配置项。

**具体改动记录**:

1. **`Handcode.java` (Config类及解析)**
   - 新增6个可配置字段并赋予默认非零值：
     - `memory_cache_max_entries` (4096)
     - `deferred_queue_max_size` (2048)
     - `deferred_queue_retry_limit` (200)
     - `deferred_queue_process_per_tick` (100)
     - `bin_convert_futures_max_entries` (64)
     - `cache_other_region_max` (64)
   - 在 `apply()` 方法中补充了对应的配置解析逻辑。
   - *注：修复过程中发现并恢复了误删的 `world_gen_icon` 字段。*

2. **`TreePlacer.java` (DeferredQueue类)**
   - `add()`: 添加了基于 `deferred_queue_max_size` 的容量检查。当队列满时，丢弃最旧任务并打印警告。
   - `processTick()`: 每 tick 处理上限从硬编码替换为 `deferred_queue_process_per_tick`。
   - `processTick()`: 重试次数上限从硬编码替换为 `deferred_queue_retry_limit`。

3. **`TreePlacer.java` (Data类)**
   - `get()`: 为 `bin_convert_futures` 添加了基于 `bin_convert_futures_max_entries` 的容量淘汰机制。当Map满时，移除最旧的Future。

4. **`TreePlacer.java` (DetailedDetection类)**
   - 写入 `memoryCache` 处: 添加了基于 `memory_cache_max_entries` 的容量淘汰机制。当Map满时，移除最旧的DetectionResult。

5. **`TreeLocation.java`**
   - 将硬编码的常量 `CACHE_OTHER_REGION_MAX` 移除，替换为读取 `Handcode.Config.cache_other_region_max`。

**编译结果**: BUILD SUCCESSFUL (存在既有的 `ResourceLocation(String)` 过时警告，非本次引入)
**后续计划**: 等待用户实机测试反馈。
        

          

---

# 第三阶段：回归测试问题审计与修复计划
> 审计人：架构师 Agent (GLM-5.2)
> 状态：待执行
> 日期：2026-06-26
>max:执行代号33

## 🚨 测试结果与现象

### 1. 树木生成异常：稀疏与密集聚集并存
- **现象**：地图上大面积空白，树木几乎不生成；但偶尔出现5×5区块范围内密集聚集。
- **历史对比**：旧版本虽然密度有问题（DH生成密度是MC原版的3-6倍），但分布均匀，不会出现连续3区块无树的情况。
- **推测原因**：
  - `TreeLocation.testDistance()` 的异步加载逻辑存在漏洞：当区域数据未加载完成时，返回空Map，导致距离检测失效，允许树木生成。多个线程同时遇到此情况时，会重复提交加载任务并允许生成，造成同一区域重复生成树木。
  - `TreePlacer.Data.get()` 的异步加载机制：当数据未加载完成时返回空ByteBuffer，调用方将其加入`DeferredQueue`。但队列有容量限制和重试次数限制，导致部分树木被静默丢弃。
  - `cache_other_region` 和 `bin_convert_futures` 的容量限制：当缓存满时，淘汰旧条目，可能导致正在使用的区域数据被淘汰，引发重复加载和生成。

### 2. 劈树现象（部分减轻）
- **现象**：一棵大树沿区块边界被分割，一半消失一半保留。情况较以前已减轻40-60%。
- **推测原因**：
  - `TreePlacer.start()` 和 `DetailedDetection.test()` 中的区块状态检查不充分：仅检查了`FEATURES`状态，未要求`FULL`状态。树木跨越多个区块时，可能部分区块尚未完全生成，导致树木部分放置失败。
  - `DeferredQueue` 的重试机制：虽然重试次数增加，但如果区块状态长期不满足，任务最终被丢弃，导致树木部分生成。

### 3. TPS与帧率尖峰卡顿（部分减轻）
- **现象**：跑图时MTPS周期性跳至300-800ms。情况较以前已减轻30-50%。
- **推测原因**：
  - `TreeLocation.testDistance()` 中的异步I/O操作：虽然不阻塞主线程，但频繁的磁盘读取导致I/O线程饱和，影响其他I/O操作。
  - `TreePlacer.place()` 中的方块放置：大型树木一次性放置大量方块，造成主线程卡顿。
  - `DeferredQueue.processTick()` 在主线程执行：虽然限制处理数量，但区块状态检查涉及大量查询，累积耗时导致尖峰。

### 4. CPU空转但Tick卡顿
- **现象**：CPU占用20-30%，但游戏Tick周期性卡顿。以前会直接卡死，现在改为卡顿尖峰。
>max:修改:"但(跑图时)游戏Tick周期性卡顿",而且以前不会直接卡死,是(跑图时)大概率会卡死,而且以前也有卡顿尖峰,现在减轻了
- **推测原因**：
  - 主线程等待锁：`TreeLocation.run()` 中的`regionLock`同步块，当多个线程请求同一区域锁时，导致主线程阻塞。
  - `Core.GlobalLocking.test()` 可能持有全局锁：如果持有时间过长，会阻塞其他线程。
  - 异步I/O线程池饱和：单线程`io_executor`处理大量I/O请求，导致任务积压，主线程等待数据时卡顿。

## 🛠️ 修复计划

### 阶段一：修复树木生成异常（优先级最高）
**任务 1.1: 修复 `TreeLocation.testDistance()` 异步加载逻辑**
- **问题**：当区域数据未加载完成时，返回空Map，导致距离检测失效。
- **方案**：
  - 移除异步加载，改为同步加载但限制频率：在`testDistance`中，如果区域数据不在`cache_other_region`中，提交异步加载任务后，**当前线程跳过该树木的距离检测**（返回true允许生成），但记录日志。依赖`DetailedDetection`在后续阶段避免重复生成。
  - 或者，引入`CountDownLatch`等待数据加载完成：但需设置超时，避免长时间阻塞。
- **修改文件**：`TreeLocation.java` - `testDistance()` 方法
- **注意**：需权衡实时性与性能，建议采用方案一。
>max:如果同步会对区块生成速度造成大幅影响就不要同步,(树的生成好像**不应该**阻塞区块生成吧?(能不能不阻塞区块生成呢?)不对,好像不行,地物和结构的放置好像必须在区块生成时就完成,反正我到现在是没见过哪个模组能做到在已生成区块上生成地物和结构的)

**任务 1.2: 修复 `TreePlacer.Data.get()` 数据丢失问题**
- **问题**：当数据未加载完成时返回空，导致任务进入`DeferredQueue`，可能被丢弃。
- **方案**：
  - 增加重试次数和超时时间：调整`Handcode.Config.deferred_queue_retry_limit`和`Handcode.Config.deferred_queue_process_per_tick`。
  - 或者，当数据未加载完成时，**阻塞当前线程等待数据**：但需设置超时，并确保不阻塞主线程。建议在`DeferredQueue`中处理，而非`Data.get()`。
  - 优化`bin_convert_futures`容量限制：增大`Handcode.Config.bin_convert_futures_max_entries`，避免过早淘汰Future。
- **修改文件**：`TreePlacer.java` - `Data.get()` 和 `DeferredQueue`

**任务 1.3: 调整缓存容量限制**
- **问题**：`cache_other_region`和`bin_convert_futures`容量过小，导致数据被淘汰。
- **方案**：
  - 增大`Handcode.Config.cache_other_region_max`和`Handcode.Config.bin_convert_futures_max_entries`。
  - 或改为基于软引用的缓存，自动回收不常用数据。
- **修改文件**：`Handcode.java` 配置类

### 阶段二：修复劈树现象
**任务 2.1: 提升区块状态检查标准**
- **问题**：树木生成时区块状态未达到`FULL`，导致跨区块树木部分失败。
- **方案**：
  - 在`TreePlacer.start()`和`DetailedDetection.test()`中，将区块状态检查从`FEATURES`提升到`FULL`。
  - 调整`DeferredQueue`的重试逻辑：增加重试次数，并在任务被丢弃前记录警告日志。
- **修改文件**：`TreePlacer.java` - `start()` 和 `DetailedDetection.test()`

### 阶段三：修复TPS与CPU卡顿
**任务 3.1: 优化I/O调度**
- **问题**：单线程`io_executor`处理大量I/O请求，导致积压。
- **方案**：
  - 增加I/O线程池大小：改为`Executors.newFixedThreadPool(4)`或根据CPU核心数动态调整。
  - 优化I/O操作：合并写入操作，减少磁盘I/O次数。
- **修改文件**：`TreeLocation.java` - `io_executor` 初始化
>max:要动态调整

**任务 3.2: 优化方块放置性能**
- **问题**：大型树木一次性放置大量方块，造成卡顿。
- **方案**：
  - 分批放置方块：在`TreePlacer.place()`中，将方块分批放置，每批之间让出CPU时间。
  - 或使用异步方块放置：但需确保线程安全。
- **修改文件**：`TreePlacer.java` - `place()` 方法

**任务 3.3: 优化主线程锁竞争**
- **问题**：`regionLock`同步块导致主线程阻塞。
- **方案**：
  - 减小同步块范围：仅同步必要的文件创建操作，扫描逻辑移到同步块外。
  - 或使用读写锁：允许多个线程同时读取区域数据，但写入时独占。
- **修改文件**：`TreeLocation.java` - `run()` 方法

## 📋 配置项调整建议
| 配置项 | 当前值 | 建议值 | 说明 |
|--------|--------|--------|------|
| `cache_other_region_max` | 未知 | 256 | 增大区域缓存容量，避免频繁淘汰 |
| `bin_convert_futures_max_entries` | 未知 | 256 | 增大Future缓存容量，避免过早淘汰 |
| `deferred_queue_retry_limit` | 200 | 400 | 增加重试次数，减少任务丢弃 |
| `deferred_queue_process_per_tick` | 未知 | 4 | 每tick处理任务数，平衡性能与实时性 |
| `deferred_queue_max_size` | 未知 | 1000 | 增大队列容量，避免任务丢弃 |

## 🧪 测试建议
1. **单元测试**：模拟多线程环境，测试`testDistance`和`Data.get`的并发行为。
2. **集成测试**：在测试世界中，使用`/fill`命令快速生成区块，观察树木生成情况。
>max:/fill怎么生成区块?llm又胡言乱语了
3. **性能测试**：使用`/spark`或`/timings`分析卡顿来源，确认修复效果。
        

          

---

# 第四阶段：二次回归测试反馈与深层归因
> 审计人：架构师 Agent (PokerAgent)
> 状态：待执行
> 日期：2026-06-26

## 🚨 最新测试反馈现象

1. **新问题：高度判定疑似失效**
   - 超平坦世界（固定雪林），Y=-59 的高度连雪都没有只有草，且不生树；Y=27 的高度有雪且能生树。
   - **推测**：`DetailedDetection` 中的 `getHeightWorldGen` (OCEAN_FLOOR_WG) 或高度限制逻辑在极端低空/超平坦环境下计算异常，导致直接 `break test`。

2. **旧问题复发：LOD/DH 区块回显与密度异常**
   - DH（Distant Horizons）生成的区块大树密度极高，靠近消失，远离又出现。
   - **推测**：典型的 LOD 特性覆盖问题。DH 的假区块触发了树木生成，但数据未正确持久化，真区块生成时覆盖了假区块数据，或 DH 的区块状态未达 `FULL` 导致 `DeferredQueue` 不断重试。

3. **近期问题：大面积空白未根除**
   - 依然存在大片无树区域，虽有树区域扩大，但空白带仍明显。
   - **推测**：异步加载的容错机制依然在丢数据。`cache_other_region` 和 `bin_convert_futures` 的容量淘汰策略在高频跑图时误杀了正在使用的 Future/Map，导致距离检测和放置阶段大面积失效返回空。

4. & 5. **历史遗留：TPS 尖峰与 CPU 空转**
   - 跑图时偶发极高卡顿尖峰，CPU 空转 20-30%。
   - **推测**：这是典型的"锁池等待"或"I/O 线程饱和"症状。主线程在 `TreeLocation.run()` 中等待 `regionLock`，或者 `io_executor` 单线程积压了大量任务，主线程查询 `future.isDone()` 时虽然不阻塞，但可能在自旋或等待其他受影响的资源。

6. **已解决：MC 原版区块密度**
   - 忽略 1/2/3，MC 原版生成密度恢复正常。说明核心的随机分布逻辑没问题，问题出在边界条件和并发环境。

7. **历史遗留：劈树现象变异（极其重要）**
   - 现象：稀疏、偶发、大规模，一劈就是连着一条 20 区块的区域全劈。
   - **推测**：这是最核心的线索！一劈 20 个区块，绝对不是单棵树的问题，而是**某个坐标轴的边界判定直接被截断**。可能是 `TreePlacer.place()` 中的 `if (chunk_pos.x == pos.getX() >> 4 && chunk_pos.z == pos.getZ() >> 4)` 逻辑在跨区块树木放置时，如果当前区块的 `DetailedDetection` 未通过，导致整片区域的树木在当前区块的部分全被跳过。

## 🛠️ 关于自定义调试看门狗的探讨

max，你提的"轻量级看门狗"思路非常棒。ModernFix 的 30s 确实太鸡肋，我们要抓的是 100ms 级别的尖峰。

**可行性：完全可行，而且开销极小。**

**方案设计：后台守护线程采样法**
我们不钩 `tickStart` 和 `tickEnd`，而是开一个纯粹的守护线程，它只做一件事：死循环检查主线程上次 tick 的时间戳。

1. **主线程埋点**：在 `ServerLevel.tick` 或主 tick 循环里，用 `volatile long` 更新 `lastTickTime = System.nanoTime()`。
2. **守护线程监控**：
   - 每 sleep 10ms 醒来一次。
   - 计算 `delay = System.nanoTime() - lastTickTime`。
   - 如果 `delay > 阈值`（比如 50ms，可配置）：
     - 获取主线程的 `ThreadInfo`（通过 `ManagementFactory.getThreadMXBean()`）。
     - 打印主线程的堆栈（`threadInfo.getStackTrace()`）。
     - 打印主线程在等待的锁（`threadInfo.getLockInfo()`，`threadInfo.getLockOwnerId()`）。
     - 如果在等锁，顺带把持有那个锁的线程的堆栈也打印出来！
3. **优势**：这个方案能精准捕捉卡顿**发生瞬间**主线程在干嘛。如果是等锁，直接告诉你它在等谁，谁拿着锁。如果是 I/O 阻塞，堆栈里会清清楚楚显示卡在 `readBIN`。

这个工具我们可以写成一个独立的小 Mod，或者直接 Mixin 进现在的模组里作为调试模块。