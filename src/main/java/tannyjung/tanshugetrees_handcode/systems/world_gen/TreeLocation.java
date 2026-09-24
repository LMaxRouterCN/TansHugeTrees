package tannyjung.tanshugetrees_handcode.systems.world_gen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import tannyjung.tanshugetrees_core.Core;
import tannyjung.tanshugetrees_core.outside.CacheManager;
import tannyjung.tanshugetrees_core.outside.ConfigDynamic;
import tannyjung.tanshugetrees_core.outside.FileManager;
import tannyjung.tanshugetrees_core.outside.OutsideUtils;
import tannyjung.tanshugetrees_core.game.GameUtils;
import tannyjung.tanshugetrees_handcode.Handcode;
import tannyjung.tanshugetrees_handcode.systems.Caches;

        

          
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
public class TreeLocation {

    // [LMax Fix V9] 替换为 ConcurrentHashMap 解决 CME
    // [刀U2前置 无维度键修复] [长期记忆: 160] 四缓存外层嵌套 per-dimension 容器:
    // 原键(cache_write_tree_location/cache_biome=裸ChunkPos; cache_write_place/cache_other_region=裸"rx,rz")
    // 无维度身份, 同世界跨维度同坐标互偷; 磁盘 bin 按 dimension 目录隔离而内存不对称(跨世界有
    // clearWorldState 兜底, 跨维度修复前无防线). 外层键=调用链既有 dimension 串, 调用方参数现成.
    // clearWorldState 对外层 clear 语义不变(整树清空).
    private static final Map<String, Map<ChunkPos, Map<BlockPos, String>>> cache_write_tree_location = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Map<String, List<String>>> cache_write_place = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Map<String, Map<ChunkPos, Map<BlockPos, String>>>> cache_other_region = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Map<ChunkPos, Holder<Biome>>> cache_biome = new java.util.concurrent.ConcurrentHashMap<>();



        
        

          

    // [执行代号22 - 修复] cache_other_region 最大缓存区域数，超过此值时淘汰旧条目，防止内存泄漏
    // [LMax Fix V7] 已迁移到 Handcode.Config.cache_other_region_max，此处不再硬编码

    // [LMax Fix V38] Region三态认领：null=未认领 FALSE=扫描中 TRUE=已完成
    // putIfAbsent原子操作保证同region只有一个线程能进入扫描（V15的Set实现是"扫完才add"，600个chunk并发全进）
    // [LMax Fix V38] 已清理死代码：scanned_regions（职责被三态认领完全取代，读取点已删）与 region_locks
    // （全项目仅"声明+remove"两处引用，从未put加锁，remove恒空转，连同注释一并移除）
    private static final Map<String, Boolean> region_scan_claims = new java.util.concurrent.ConcurrentHashMap<>();

    // [刀V] [长期记忆: 176] 脏 region 台账: dimension → ("rx,rz" 集合)。writeData 每树 put 缓存后
    // 标脏(先写后标铁律: JMM 保证任何摘牌者的 flush 必见标脏时已在缓存的数据), 由宿主任务尾部
    // drainDirty 统一冲刷 —— 每树即冲 33k 次文件开关/region(冷启动 259s 主犯) → 每 region 数次。
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> dirty_regions = new java.util.concurrent.ConcurrentHashMap<>();

    // [刀V-2] [长期记忆: 176] path_storage → 过滤后 .bin 文件数组缓存(会话内目录不变量: custom_packs
    // 解包于世界装载期; clearWorldState 清场兜底重解包/改名场景)。
    private static final java.util.concurrent.ConcurrentHashMap<String, File[]> list_files_cache = new java.util.concurrent.ConcurrentHashMap<>();

    // [LMax Fix V13] 使用 AtomicInteger 保证多线程下 UI 状态的原子更新
    public static final java.util.concurrent.atomic.AtomicInteger world_gen_overlay_animation = new java.util.concurrent.atomic.AtomicInteger(0);
    public static final java.util.concurrent.atomic.AtomicInteger world_gen_overlay_bar = new java.util.concurrent.atomic.AtomicInteger(0);
    public static volatile String world_gen_overlay_details_biome = "";
    public static volatile String world_gen_overlay_details_tree = "";


        

          
    public static void flushCachesAsync(String dimension, int regionX, int regionZ) {
        String regionKey = regionX + "," + regionZ;

        // [执行代号22 - 修复] 原子提取缓存数据，将 remove 和 snapshot 合为一步
        // 原代码先快照、提交异步任务后立即清空缓存，存在两个致命竞态：
        // 1. I/O 失败时数据永久丢失（catch 块仅 printStackTrace）
        // 2. 快照后到清空前新写入的数据被误删
        // 修复：使用迭代器先 remove 再 snapshot，移除后新写入的数据自动进入新条目

        // 原子提取 tree_location 缓存
        Map<BlockPos, String> locSnapshot = new HashMap<>();
        // [刀U2前置 无维度键修复] [长期记忆: 160] per-dimension 提取: null=该维度无待冲缓存(跳过遍历)
        Map<ChunkPos, Map<BlockPos, String>> loc_dim = cache_write_tree_location.get(dimension);
        if (loc_dim != null) {
            Iterator<Map.Entry<ChunkPos, Map<BlockPos, String>>> locIt = loc_dim.entrySet().iterator();
            while (locIt.hasNext()) {
                Map.Entry<ChunkPos, Map<BlockPos, String>> entry = locIt.next();
                if ((entry.getKey().x >> 5) == regionX && (entry.getKey().z >> 5) == regionZ) {
                    locIt.remove(); // 先从外层 Map 移除，阻止新写入命中此条目
                    locSnapshot.putAll(entry.getValue()); // 再快照内层 Map，此时无其他线程可访问
                }
            }
        }

        // 原子提取 place 缓存：remove 原子返回被移除的值
        // [刀U2前置 无维度键修复] [长期记忆: 160] per-dimension 提取, remove 原子语义不变
        List<String> placeSnapshot = null;
        Map<String, List<String>> place_dim = cache_write_place.get(dimension);
        if (place_dim != null) {
            placeSnapshot = place_dim.remove(regionKey);
        }

        // 两者都为空时跳过 I/O
        if (locSnapshot.isEmpty() && (placeSnapshot == null || placeSnapshot.isEmpty())) {
            return;
        }

        final Map<BlockPos, String> finalLocSnapshot = locSnapshot;
        final List<String> finalPlaceSnapshot = placeSnapshot;

        // [LMax Fix V26] I/O 同步化：降维打击彻底消灭数据竞态
        // TreeLocation.run 已经在 THT-TreeGen 异步线程池中执行，完全不需要再提交给 io_executor。
        // 直接在当前线程同步写盘，确保 TreeLocation.run 返回时，硬盘数据 100% 写入完毕，
        // 后续的 TreePlacer.start 绝对能读到完整数据，彻底消灭"出生点无树"和"区域空白"！
        try {
            if (!finalLocSnapshot.isEmpty()) {
                List<String> newLocData = new ArrayList<>();
                for (Map.Entry<BlockPos, String> entry2 : finalLocSnapshot.entrySet()) {
                    newLocData.add("s" + entry2.getValue());
                    newLocData.add("i" + entry2.getKey().getX());
                    newLocData.add("i" + entry2.getKey().getZ());
                }
                FileManager.writeBIN(Core.path_world_mod + "/world_gen/tree_locations/" + dimension + "/" + regionKey + ".bin", newLocData, true);
            }

            if (finalPlaceSnapshot != null && !finalPlaceSnapshot.isEmpty()) {
                FileManager.writeBIN(Core.path_world_mod + "/world_gen/place/" + dimension + "/" + regionKey + ".bin", finalPlaceSnapshot, true);
                // [LMax Fix V41] 落盘后失效消费方解析缓存 [长期记忆: 012]
                // 链条：writeBIN(place) 已自失效 FileManager.BIN_CACHE（同版本修复），此处再失效
                // TreePlacer.Data 的解析 future——否则首次空读的空 map 被永久缓存，重试永远命中空结果。
                TreePlacer.Data.invalidate(dimension, regionX, regionZ);
            }
        } catch (Exception e) {
            Core.logger.error("flushCachesAsync I/O failed for region " + regionKey + " (dimension: " + dimension + "), data lost: " + finalLocSnapshot.size() + " tree locations, " + (finalPlaceSnapshot != null ? finalPlaceSnapshot.size() : 0) + " place entries", e);
        }
    }

    // [刀V] [长期记忆: 176] 标脏(put-then-mark 铁律的 mark 半边): writeData 写完两级缓存后调用,
    // 宿主任务(run/pregenComputeRegion)尾部 drainDirty 收走。Set.add 幂等, 并发重复标脏无副作用。
    private static void markDirty (String dimension, int regionX, int regionZ) {
        dirty_regions.computeIfAbsent(dimension, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(regionX + "," + regionZ);
    }

    // [刀V] [长期记忆: 176] 摘牌-冲刷-复查循环(宿主任务尾部调用)。摘牌 remove 原子(失败 = 并发任务已
    // 接管, 跳过); 同步 flushCachesAsync 落盘; 复查 = 收摘牌竞态窗口内的新标记。8 轮熔断 = 活锁防护
    // (残余标记由其宿主尾 drain 兜底, 熔断只延后不丢数据)。
    // 范围 = 全维度脏集: 跨 region 超大树足迹写邻 region 缓存, 只冲宿主自己 region = 邻 region 数据
    // 坐死缓存(其任务已结束再无人触碰) = 树永久丢 —— 故必须全维度。
    private static void drainDirty (String dimension) {
        java.util.Set<String> set = dirty_regions.get(dimension);
        if (set == null) {
            return;
        }
        for (int round = 0; round < 64; round++) { // [刀Y][长期记忆:180] 熔断上限 8→64: 并发波(16 region 任务 × 跨区足迹)脏键约 30, 8 轮不够覆盖; 64 次冲刷各 ms 级, 活锁防护保留
            String key = null;
            for (String k : set) { // 弱一致取一个
                key = k;
                break;
            }
            if (key == null) {
                return; // 空 = 收工
            }
            if (!set.remove(key)) {
                round--; // [刀Y] [长期记忆: 180] 摘牌失败不再烧轮(惊群修复): 8 池线程尾部同时抓共享脏集同一首元素,
                         // 仅 1 个 remove 成功, 旧代码 7 个失败者各烧一轮 → 有效吞吐塌缩 1/8, 本区键饿死幸存熔断。
                         // 失败必因他线程已摘此键(全局进展), 下轮迭代重抓新头必出新进展, 无死循环。
                continue;
            }
            String[] parts = key.split(",");
            flushCachesAsync(dimension, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
    }

    // [刀V-2] [长期记忆: 176] 目录列举 + V16 过滤(缓存值工厂): null = 目录不存在(CHM 语义: mapping
    // 返回 null 不建立映射 → 保留重探, 对齐旧 listFiles==null); 空数组照常缓存。
    // [LMax Fix V16] 过滤掉目录和非 .bin 文件，防止随机选中 "storage" 等子目录导致字典污染和路径错误(注释随逻辑搬家)。
    private static File[] filterBinFiles (File dir) {
        File[] allFiles = dir.listFiles();
        if (allFiles == null) {
            return null;
        }
        java.util.List<File> binFiles = new java.util.ArrayList<>();
        for (File f : allFiles) {
            if (f.isFile() && f.getName().endsWith(".bin")) {
                binFiles.add(f);
            }
        }
        return binFiles.toArray(new File[0]);
    }

    public static void start(LevelAccessor level_accessor, String dimension, ChunkPos chunk_pos) {
        Map<String, Map<String, String>> data = ConfigDynamic.getData("world_gen");
        if (Core.log_tree_location) System.out.println("[THT-DEBUG] TreeLocation.start() called. Data empty: " + data.isEmpty() + ", Data size: " + data.size());
        // 修复：data 为空时显式记录警告日志，而非静默跳过
        if (data.isEmpty()) {
            Core.logger.warn("[THT-DEBUG] TreeLocation.start() - config_world_gen data is empty! Tree generation will be skipped.");
            return;
        }
        TreeLocation.run(level_accessor, dimension, chunk_pos, data);
}
        

          
    // [LMax Fix V42] churn 斩杀：空数据 chunk 等待集 [长期记忆: 014]
    // TreePlacer.start() 读不到树数据时登记于此，不再无限重入 DeferredQueue
    // （旧实现单日 646156 次 EARLY RETURN 空转，队列永久满载 4096/4096，挤压真实任务）。
    // 事件驱动唤醒：region 扫描完成时经 wakeOnRegionComplete 重跑一次；
    // 3×3 邻 region 全部完成仍无数据 = 确定终态，注销退出。
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<ChunkPos>> pendingEmptyChunks = new java.util.concurrent.ConcurrentHashMap<>();

    // [LMax Fix V42] 登记：start() 空数据时调用。Set.add 幂等，重复登记无副作用。
    public static void registerPendingEmpty(String dimension, ChunkPos chunk_pos) {
        pendingEmptyChunks.computeIfAbsent(dimension, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(chunk_pos);
    }

    // [LMax Fix V42] 注销：拿到树数据 / 判定终态时调用。
    public static void unregisterPendingEmpty(String dimension, ChunkPos chunk_pos) {
        java.util.Set<ChunkPos> set = pendingEmptyChunks.get(dimension);
        if (set != null) set.remove(chunk_pos);
    }

    // [LMax Fix V50.4 刀K] [长期记忆: 104] 世界切换静态池清场（EventCenter AboutToStart 钩子聚合调用）：
    // 同 JVM 世界切换时各池 key 无存档身份——region_scan_claims TRUE 残留 = 新世界 region 判"已扫"
    // → 零树（世界55 实锤 E2=0）；其余五池同理携带旧世界坐标/生物群系/等待表语义。磁盘 bin 按
    // 存档路径隔离不受影响，仅清内存。EventCenter 不直接摸私有字段，经本类聚合入口（PlacementGate 先例推广）。
    // [刀U2] [长期记忆: 167,168] 引擎计算入口: 复刻 run() 采样扫描(同种子同序同 region_scan_percent
    // 骰子 = 共存双写同结果; region 完成 = 采样子集算完, 非全量——与旧链 claims TRUE 同语义口径)。
    // [刀V/W-2 终稿修订] writeData 标脏(每树即冲退役), 尾部 drainDirty 统一落盘对齐 run() 尾部;
    // 尾部置 claims TRUE + 唤醒(player_center 下 legacy 已禁, 三断线由本尾部接管, 详方法尾注释)。
    // 线程契约: THT-TreeGen 池线程(与旧链 run 同池, getData 链纯计算字面审计 100%)。
    public static boolean pregenComputeRegion(LevelAccessor level_accessor, String dimension, int regionX, int regionZ) {
        Map<String, Map<String, String>> data = ConfigDynamic.getData("world_gen");
        if (data == null || data.isEmpty()) return false; // run() 同款空守卫
        int posX = regionX * 32;
        int posZ = regionZ * 32;
        int scan_count = 0;
        long scan_start = System.currentTimeMillis();
        for (int scanX = 0; scanX < 32; scanX++) {
            for (int scanZ = 0; scanZ < 32; scanZ++) {
                ChunkPos chunk_pos_scan = new ChunkPos(posX + scanX, posZ + scanZ);
                RandomSource random = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ ((chunk_pos_scan.x * 341873128712L) + (chunk_pos_scan.z * 132897987541L)));
                if (random.nextDouble() < Handcode.Config.region_scan_percent * 0.01) {
                    getData(level_accessor, dimension, chunk_pos_scan, data);
                    scan_count++;
                }
            }
        }
        // [刀V] [长期记忆: 176] 尾部兜底清残改 drainDirty(全维度: writeData 本会话只标脏, 含跨 region 足迹)
        // [刀W-2] [长期记忆: 177] 三断线桥接(原仅 legacy 尾部供血, player_center 下 legacy 已禁由本尾部接管):
        // drainDirty 同步落盘 → claims TRUE(读侧终态判定/offer 快标记) → wakeOnRegionComplete(等待者闹钟)。
        // V42 不变量保持: drain 在前, TRUE 严格蕴含落盘。失败/异常不置 TRUE = 安全网语义(观察者重差分再 offer)。
        drainDirty(dimension);
        // [刀Y] [长期记忆: 180] 本区直冲硬保证(世界68空白区根因修复): drainDirty 多线程惊群下可饿死本区脏键
        // (世界68 r.-1,-1: claims TRUE 于 02:36:01, bin 02:40:52 才落盘, 迟到 4分51秒) → 被 wake 的 chunk 读空盘
        // + 3x3 全 TRUE → V42 终审判决误杀全区 1064 chunk = 空白正方形。此处显式同步冲刷恢复不变量:
        // TRUE 严格蕴含本区在盘。幂等(已冲则快照空无操作)。跨区足迹残余竞态(秒级窗口)呈报max今日不展开。
        flushCachesAsync(dimension, regionX, regionZ);
        region_scan_claims.put(dimension + "," + regionX + "," + regionZ, Boolean.TRUE);
        wakeOnRegionComplete(dimension, regionX, regionZ, level_accessor);
        if (Core.log_tree_location) {
            System.out.println("[THT-DEBUG] [U2] pregenComputeRegion: " + dimension + "," + regionX + "," + regionZ
                + " scanned=" + scan_count + " in " + (System.currentTimeMillis() - scan_start) + "ms");
        }
        return true;
    }

    // [刀U2] claims 只读查询口: 引擎 offer 快标记用(旧链本会话已扫完的 region 免算, 翻模式不重算已扫区)。
    // fullRegionKey = "<dim>,<regionX>,<regionZ>", 与 run() 236 行拼法严格同构。
    public static boolean isRegionScanComplete(String fullRegionKey) {
        return region_scan_claims.get(fullRegionKey) == Boolean.TRUE;
    }

    public static void clearWorldState () {
        // [刀U2前置][长期记忆:160] 四缓存已嵌套 per-dim 外层, 此处外层 clear 语义不变(整树清空)
        cache_write_tree_location.clear();
        cache_write_place.clear();
        cache_other_region.clear();
        cache_biome.clear();
        region_scan_claims.clear();
        pendingEmptyChunks.clear();
        // [刀V/刀V-2] [长期记忆: 176] 脏台账与目录列举缓存随世界切换清场(重解包/改名兜底, 跨世界不泄漏)
        dirty_regions.clear();
        list_files_cache.clear();
    }

    // [LMax Fix V42] 终态判定：chunk 的 3×3 邻 region 全部扫描完成（TRUE）仍无数据 → 覆盖本 chunk 的
    // 树记录确定不存在（place 数据按树途经 region 写入，半径 >512m 的跨 region 超大树不在保证范围）。
    public static boolean allNeighborRegionsComplete(String dimension, ChunkPos chunk_pos) {
        int rx = chunk_pos.x >> 5;
        int rz = chunk_pos.z >> 5;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (region_scan_claims.get(dimension + "," + (rx + dx) + "," + (rz + dz)) != Boolean.TRUE) {
                    return false;
                }
            }
        }
        return true;
    }

    // [LMax Fix V42] 事件唤醒：region 扫描完成时调用。等待集中所有 3×3 邻域含此 region 的空数据 chunk
    // 经 TreePlacer.requeueChunk 重新入队（NORMAL 路径，processTick 带 FULL 就绪检查与重试）。
    // 每 chunk 至多被唤醒 9 次（3×3 region 各完成一次），有界收敛，永动消失。
    // 必须在 flushCachesAsync 之后调用：保证被唤醒的 start() 读到失效重建后的最新数据。
    public static void wakeOnRegionComplete(String dimension, int regionX, int regionZ, LevelAccessor level_accessor) {
        java.util.Set<ChunkPos> set = pendingEmptyChunks.get(dimension);
        if (set == null || set.isEmpty() == true) return;
        java.util.List<ChunkPos> woke = null;
        for (ChunkPos p : set) {
            if (Math.abs((p.x >> 5) - regionX) <= 1 && Math.abs((p.z >> 5) - regionZ) <= 1) {
                if (woke == null) woke = new java.util.ArrayList<>();
                woke.add(p);
            }
        }
        if (woke == null) return;
        for (ChunkPos p : woke) {
            // [LMax Fix V42] instanceof 收窄：run() 的 level_accessor 运行时实为 ServerLevel
            // （eventChunkLoaded 传入），但参数类型 LevelAccessor 接口没有 dimension()。
            // 仅 Server 上下文执行唤醒；理论外的非 Server 上下文保留等待（与 region 未扫描同语义，不丢正确性）
            if (level_accessor instanceof net.minecraft.server.level.ServerLevel sl_wake) {
                set.remove(p);
                tannyjung.tanshugetrees_handcode.systems.world_gen.TreePlacer.requeueChunk(dimension, sl_wake.dimension(), p);
                if (Core.log_tree_location) System.out.println("[THT-DEBUG] wakeOnRegionComplete: chunk " + p + " requeued after region " + regionX + "," + regionZ + " completed");
            }
        }
    }


    public static void run(LevelAccessor level_accessor, String dimension, ChunkPos chunk_pos, Map<String, Map<String, String>> data) {
        // 修复：添加详细日志，追踪大树生成流程
        if (Core.log_tree_location) System.out.println("[THT-DEBUG] TreeLocation.run() started - dimension: " + dimension + ", chunk: " + chunk_pos);
        
        // 检查传入数据是否为空
        if (data == null || data.isEmpty()) {
            Core.logger.warn("[THT-DEBUG] TreeLocation.run() - data parameter is null or empty! This may be why trees don't generate.");
            if (Core.log_tree_location) System.out.println("[THT-DEBUG] TreeLocation.run() - data is null or empty, returning early");
            return;
        } else {
            if (Core.log_tree_location) System.out.println("[THT-DEBUG] TreeLocation.run() - data size: " + data.size() + " entries");
        }
        
        int regionX = chunk_pos.x >> 5;
        int regionZ = chunk_pos.z >> 5;
        String regionKey = dimension + "," + regionX + "," + regionZ;
        // [刀W] [长期记忆: 177] player_center 模式下 legacy 让路: 认领前退出, U2 引擎独占计算
        // (消灭双扫: 世界66实测 5 region 双算 ≈40% 算力浪费)。region 模式缺省零行为变化(U2 休眠,
        // 本链是唯一计算链)。[region 模式退场预备] 默认翻 player_center 后本门改无条件 return
        // → legacy 链可物理删除。
        if ("player_center".equals(Handcode.Config.pregen_mode)) {
            return;
        }
        
        // [LMax Fix V38] Region三态原子认领：putIfAbsent返回null=抢到扫描权，返回FALSE=别人在扫(跳过)，返回TRUE=扫描完成(跳过)
        // [长期记忆: 004] 先A后B的A1：600×冗余扫描→1×，被跳chunk由DeferredQueue 400tick重试兜底
        Boolean claim = region_scan_claims.putIfAbsent(regionKey, Boolean.FALSE);
        if (claim != null) {
            return; // FALSE=扫描中 TRUE=已完成 都跳过
        }

        // [LMax Fix] 不再用文件存在就跳过扫描。chunk 数据会被 Data.clearChunk() 清掉，
        // 但 region 文件还在，导致下次启动扫描被跳过、树全部消失。
        // [LMax Fix V38] 改用 region_scan_claims 三态内存缓存（JVM 重启自动清空）防止同 session 重复扫描。
        // [THT-DEBUG] 诊断扫描循环执行情况
        if (Core.log_tree_location) System.out.println("[THT-DEBUG] TreeLocation.run() - Starting region scan for: " + regionKey);
        if (Core.log_tree_location) System.out.println("[THT-DEBUG] TreeLocation.run() - Scan loop begins, region_scan_percent: " + Handcode.Config.region_scan_percent);

        // [LMax Fix V38] try-finally 兜底：扫描中途抛异常时回滚认领（remove FALSE），
        // 否则 region 永远卡 FALSE、后续所有 chunk（含 DeferredQueue 重试）永久跳过，该 region 树全部消失
        try {
            // Scanning
            {
                int posX = regionX * 32;
                int posZ = regionZ * 32;
                ChunkPos chunk_pos_scan = null;
                long scan_start = System.currentTimeMillis();
                int scan_count = 0;
                for (int scanX = 0; scanX < 32; scanX++) {
                    for (int scanZ = 0; scanZ < 32; scanZ++) {
                        // [THT-DEBUG] 确认循环开始执行
                        if (scanX == 0 && scanZ == 0) {
                            if (Core.log_tree_location) System.out.println("[THT-DEBUG] TreeLocation.run() - Scan loop first iteration (0,0)");
                        }

                        world_gen_overlay_bar.incrementAndGet();
                        chunk_pos_scan = new ChunkPos(posX + scanX, posZ + scanZ);
                        RandomSource random = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ ((chunk_pos_scan.x * 341873128712L) + (chunk_pos_scan.z * 132897987541L)));
                        if (random.nextDouble() < Handcode.Config.region_scan_percent * 0.01) {
                            getData(level_accessor, dimension, chunk_pos_scan, data);
                            scan_count++;
                        }
                    }
                }
                long scan_time = System.currentTimeMillis() - scan_start;
                if (Core.log_tree_location) System.out.println("[THT-DEBUG] TreeLocation.run() - Scan loop completed, count: " + scan_count + ", time: " + scan_time + "ms");
                Core.logger.info("[THT-DEBUG] Region " + regionKey + " scan completed: " + scan_count + " chunks scanned in " + scan_time + "ms");
            }

            // [LMax Fix V38] 扫描完成标记：FALSE→TRUE，后续chunk的TreePlacer.start可读到落盘数据
            // [长期记忆: 004] 先A后B决策的A1：region原子认领消除600×冗余
            // [LMax Fix V42] 顺序修正：先同步落盘再置 TRUE（V26 起 flushCachesAsync 已是同步写盘）。
            // claims=TRUE 从此严格蕴含「数据已落盘且解析缓存已失效」，终态判定与事件唤醒共享此不变量。
        // [刀V] [长期记忆: 176] run 尾部改 drainDirty(全维度脏集): 扫描期间 writeData 只标脏,
        // 此处统一落盘(含跨 region 足迹写的邻 region 标记); V42 不变量保持 = drain 同步在前,
        // TRUE 严格在后蕴含「数据已落盘」
        drainDirty(dimension);
        // [刀Y] [长期记忆: 180] 本区直冲硬保证: 熔断是尽力而为, TRUE 必须严格蕴含落盘 —— 与 pregenComputeRegion
        // 尾修复统一, 双链不变量同口径。幂等: drain 已冲过则写缓存快照为空, 无操作。
        flushCachesAsync(dimension, regionX, regionZ);
        region_scan_claims.put(regionKey, Boolean.TRUE); // [LMax Fix V42] 移至同步落盘之后（原在 flush 之前，存在"TRUE但数据在途"窗口）
            // [LMax Fix V42] 事件唤醒：本 region 扫描完成 → 唤醒等待集中 3×3 邻域含本 region 的空数据 chunk
            // 重跑一次。必须在 flushCachesAsync 之后（数据先落盘失效，唤醒的 start() 才能读到最新）。[长期记忆: 014]
            wakeOnRegionComplete(dimension, regionX, regionZ, level_accessor);
            world_gen_overlay_animation.set(0);
            Core.logger.info("Completed!");

            // [Poker Agent Fix] 彻底移除全局 clear() 调用。在并发环境下，一个 Region 扫描完成不应清空全局缓存，
            // 这会导致其他正在生成的区块丢失数据并引发 NPE。缓存生命周期应由系统统一管理。
        } finally {
            // [LMax Fix V38] 原子回滚：remove(key, FALSE) 仅当值仍为 FALSE（扫描未完成）时才移除认领。
            // 正常完成时值已是 TRUE，此调用空转无副作用；异常时移除，下一个 chunk 可重新认领并重试扫描。
            region_scan_claims.remove(regionKey, Boolean.FALSE);
        }
    }

    private static void scanning_overlay_loop() {
        int current = world_gen_overlay_animation.get();
        if (current != 0) {
            if (current < 4) {
                world_gen_overlay_animation.incrementAndGet();
            } else {
                world_gen_overlay_animation.set(1);
            }
            Core.DelayedWork.create(true, 20, TreeLocation::scanning_overlay_loop);
        }
    }

    private static void getData(LevelAccessor level_accessor, String dimension, ChunkPos chunk_pos, Map<String, Map<String, String>> data) {
        // [THT-DEBUG] 方法入口日志
        if (Core.log_tree_location) System.out.println("[THT-DEBUG] getData() ENTER - chunk: " + chunk_pos);
        Holder<Biome> biome_center = getBiome(level_accessor, dimension, chunk_pos); // [刀U2前置][长期记忆:160] +dimension
        String biome_id = GameUtils.Environment.toID(biome_center);
        // [THT-DEBUG] 群系ID
        if (Core.log_tree_location) System.out.println("[THT-DEBUG] getData() - biome_id: " + biome_id);
        world_gen_overlay_details_biome = biome_id;
        world_gen_overlay_details_tree = "No Matching";

        Set<String> set_tree = null;
        {
            set_tree = CacheManager.DataText.getSet("set_tree").get(biome_id);
            // [THT-DEBUG] set_tree大小
            if (Core.log_tree_location) System.out.println("[THT-DEBUG] getData() - set_tree size: " + (set_tree == null ? "NULL" : set_tree.size()));
            if (set_tree == null) {
                set_tree = new HashSet<>();
                for (Map.Entry<String, Map<String, String>> entry : data.entrySet()) {
                    if (entry.getValue().get("enable").equals("true") == true) {
                        if (GameUtils.Environment.test(biome_center, entry.getValue().get("biome")) == true) {
                            set_tree.add(entry.getKey());
                        }
                    }
                }
                CacheManager.DataText.setSet("set_tree", biome_id, set_tree);
            }
        }

        Map<String, String> config = null;
        String config_spawn_type = "";
        String config_biome = "";
        double config_rarity = 0.0;
        int config_min_distance = 0;
        int config_group_size = 0;
        int center_posX = 0;
        int center_posZ = 0;
        String[] split = null;

        for (String scan : set_tree) {
            config = data.get(scan);
            config_rarity = (Double.parseDouble(config.get("rarity")) * 0.01) * Handcode.Config.multiply_rarity;
            RandomSource random = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ ((chunk_pos.x * 341873128712L) + (chunk_pos.z * 132897987541L)) + scan.hashCode());

            if (random.nextDouble() < config_rarity) {
                center_posX = (chunk_pos.x * 16) + random.nextInt(0, 16);
                center_posZ = (chunk_pos.z * 16) + random.nextInt(0, 16);
                config_min_distance = (int) Math.ceil(Integer.parseInt(config.get("min_distance")) * Handcode.Config.multiply_min_distance);

                // Min Distance
                {
                    if (config_min_distance > 0) {
                        if (testDistance(dimension, scan, center_posX, center_posZ, config_min_distance) == false) {
                            continue;
                        }
                    }
                }

                // Shoreline
                {
                    if (config.get("spawn_type").equals("normal") == false) {
                        if (Handcode.Config.shoreline_detection == false) {
                            continue;
                        } else if (testShoreline(level_accessor, dimension, new ChunkPos(center_posX >> 4, center_posZ >> 4)) == false) { // [刀U2前置][长期记忆:160] +dimension
                            continue;
                        }
                    }
                }

                world_gen_overlay_details_tree = scan;
                writeData(level_accessor, dimension, center_posX, center_posZ, scan, config);

                // Group Spawning
                {
                    split = config.get("group_size").split(" <> ");
                    config_group_size = (int) ((double) Mth.nextInt(random, Integer.parseInt(split[0]), Integer.parseInt(split[1])) * Handcode.Config.multiply_group_size);
                    if (config_group_size > 1) {
                        config_spawn_type = config.get("spawn_type");
                        config_biome = config.get("biome");
                        if (config_spawn_type.equals("landside") == true) {
                            config_biome = "tanshugetrees:water_biomes";
                        } else if (config_spawn_type.equals("shoreline") == true) {
                            config_biome = config_biome + " / tanshugetrees:water_biomes";
                        }
                        while (config_group_size > 0) {
                            config_group_size = config_group_size - 1;
                            center_posX = center_posX + random.nextInt(-(config_min_distance + 1), (config_min_distance + 1) + 1);
                            center_posZ = center_posZ + random.nextInt(-(config_min_distance + 1), (config_min_distance + 1) + 1);

                            // Min Distance
                            {
                                if (config_min_distance > 0) {
                                    if (testDistance(dimension, scan, center_posX, center_posZ, config_min_distance) == false) {
                                        continue;
                                    }
                                }
                            }

                            // Biome
                            {
                                biome_center = getBiome(level_accessor, dimension, new ChunkPos(center_posX >> 4, center_posZ >> 4)); // [刀U2前置][长期记忆:160] +dimension
                                if (GameUtils.Environment.test(biome_center, config_biome) == false) {
                                    continue;
                                }
                            }

                            writeData(level_accessor, dimension, center_posX, center_posZ, scan, config);
                        }
                    }
                }
            }
        }
    }

    // [执行代号22 - 任务 1.3] 提取 region 加载逻辑，实现原子化抽象
    // 原子操作：从磁盘加载并解析 region 文件，处理缓存淘汰
    // 由 ConcurrentHashMap.computeIfAbsent 调用，JVM 保证原子性
    private static Map<ChunkPos, Map<BlockPos, String>> loadRegionFromDisk(String dimension, String regionKey, Map<String, Map<ChunkPos, Map<BlockPos, String>>> dim_cache) { // [刀U2前置][长期记忆:160] +dim_cache(淘汰改本维度内层, 全局→per-dim 口径)
        Map<ChunkPos, Map<BlockPos, String>> loadedData = new ConcurrentHashMap<>();
        
        // [执行代号22 - 任务 1.2] 安全的缓存淘汰：加载完成后同步执行
        // 避免在异步线程中迭代 keySet 导致 ConcurrentModificationException
        if (dim_cache.size() > Handcode.Config.cache_other_region_max) { // [刀U2前置][长期记忆:160] 淘汰口径 per-dim(实践单维度活跃=原语义; compute内改本表与既发货形态一致)
            Iterator<String> evictIt = dim_cache.keySet().iterator();
            boolean evicted = false;
            while (evictIt.hasNext() && !evicted) {
                String evictKey = evictIt.next();
                if (!evictKey.equals(regionKey)) {
                    dim_cache.remove(evictKey);
                    evicted = true;
                }
            }
        }
        
        // 读取并解析 bin 文件
        ByteBuffer localBuffer = FileManager.readBIN(Core.path_world_mod + "/world_gen/tree_locations/" + dimension + "/" + regionKey + ".bin");
        if (localBuffer != null) {
            while (localBuffer.remaining() > 0) {
                try {
                    String localTestId = String.valueOf(localBuffer.getShort());
                    int localTestPosX = localBuffer.getInt();
                    int localTestPosZ = localBuffer.getInt();
                    loadedData.computeIfAbsent(new ChunkPos(localTestPosX >> 4, localTestPosZ >> 4), c -> new ConcurrentHashMap<>()).put(new BlockPos(localTestPosX, 0, localTestPosZ), localTestId);
                } catch (Exception exception) {
                    OutsideUtils.exception(new Exception(), exception, "");
                    break;
                }
            }
        }
        return loadedData;
    }
    private static Holder<Biome> getBiome(LevelAccessor level_accessor, String dimension, ChunkPos chunk_pos) { // [刀U2前置][长期记忆:160] +dimension(cache_biome per-dim)
        // [LMax Fix V48] 连锁强载根治：改走 getUncachedNoiseBiome 纯函数路。[长期记忆: 085] V47 判读定案后的修复。
        // 旧路 GameUtils.Environment.getAt -> testChunkStatus(hasChunk 通过后裸 getChunk = 强制 FULL join)，
        // 12 线程洪峰期向 chunk 管线塞上百阻塞请求，主线程同队挨饿（20-28s 冻结，597 episodes/276s 总停摆）。
        // 等价性：chunk 存储的 noise biome 由同一 BiomeSource 公式写入，作者在 getAt 的 else 分支已视两路等价。
        // 采样点与旧实现严格一致：chunk 中心 (x*16+7, z*16+7)，Y=建筑高度上限（getBuildHeight 纯函数，只读 levelData）。
        // computeIfAbsent 顺修旧 containsKey+put 的 check-then-act 竞态（重复计算幂等无危害，但不再发生）。
        return cache_biome.computeIfAbsent(dimension, k -> new java.util.concurrent.ConcurrentHashMap<>()).computeIfAbsent(chunk_pos, key -> { // [刀U2前置][长期记忆:160] per-dim 嵌套
            int quartX = ((chunk_pos.x * 16) + 7) >> 2;
            int quartZ = ((chunk_pos.z * 16) + 7) >> 2;
            int quartY = (GameUtils.Space.getBuildHeight(level_accessor, true)) >> 2;
            return level_accessor.getUncachedNoiseBiome(quartX, quartY, quartZ);
        });
    }

    private static boolean testDistance(String dimension, String id, int centerX, int centerZ, int min_distance) {
        BlockPos center_pos = new BlockPos(centerX, 0, centerZ);
        ChunkPos center_chunk = new ChunkPos(center_pos);
        String id_number = CacheManager.getDictionary(id, false);
        int scanX = 0;
        int scanZ = 0;
        ChunkPos scan_pos = null;
        int step = 0;
        int explorer_step = 0;
        boolean is_first = true;
        Map<BlockPos, String> data = new java.util.concurrent.ConcurrentHashMap<>();
        Map<ChunkPos, Map<BlockPos, String>> regionMap = null;
        ByteBuffer buffer = null;
        String key = "";
        String test_id = "";
        int test_posX = 0;
        int test_posZ = 0;

        for (int radius = 0; radius <= Math.ceil((double) min_distance / 16.0); radius++) {
            scanX = -radius;
            scanZ = -radius;
            step = 1;
            explorer_step = radius + radius;
            while (true) {
                // Get Data
                {
                    scan_pos = new ChunkPos(center_chunk.x + scanX, center_chunk.z + scanZ);
                    // [刀U2前置 无维度键修复] [长期记忆: 160] 两级寻址: per-dim 容器 → 原键
                    Map<ChunkPos, Map<BlockPos, String>> write_dim = cache_write_tree_location.get(dimension);
                    if (write_dim != null && write_dim.containsKey(scan_pos)) {
                        data = write_dim.get(scan_pos);
                    } else {
                        key = (scan_pos.x >> 5) + "," + (scan_pos.z >> 5);
                        // [执行代号22 - 任务 1.3] 原子化抽象：使用 computeIfAbsent 调用 loadRegionFromDisk
                        // JVM 保证 computeIfAbsent 的原子性，避免竞态条件
                        // 调用 loadRegionFromDisk 完成从磁盘加载、缓存淘汰、数据解析，保证每次只有一个线程执行
                        Map<String, Map<ChunkPos, Map<BlockPos, String>>> other_dim = cache_other_region.computeIfAbsent(dimension, k -> new java.util.concurrent.ConcurrentHashMap<>());
                        regionMap = other_dim.computeIfAbsent(key, k -> loadRegionFromDisk(dimension, k, other_dim));
                        data = regionMap.getOrDefault(scan_pos, new HashMap<>());
                    }

                }
                if (data != null && data.isEmpty() == false) {
                    // Test
                    {
                        for (Map.Entry<BlockPos, String> entry : data.entrySet()) {
                            if (entry.getKey() == center_pos) {
                                return false;
                            } else {
                                if (entry.getValue().equals(id_number) == true) {
                                    if ((Math.abs(centerX - entry.getKey().getX()) <= min_distance) && (Math.abs(centerZ - entry.getKey().getZ()) <= min_distance)) {
                                        return false;
                                    }
                                }
                            }
                        }
                    }
                }

                // Next Point
                {
                    if (step == 1) {
                        scanX = scanX + 1;
                    } else if (step == 2) {
                        scanZ = scanZ + 1;
                    } else if (step == 3) {
                        scanX = scanX - 1;
                    } else {
                        scanZ = scanZ - 1;
                    }
                }

                explorer_step = explorer_step - 1;
                if (explorer_step <= 0) {
                    // Next Step
                    {
                        if (is_first == true) {
                            is_first = false;
                            break;
                        }
                        if (step == 1) {
                            step = 2;
                        } else if (step == 2) {
                            step = 3;
                        } else if (step == 3) {
                            step = 4;
                        } else {
                            break;
                        }
                        explorer_step = radius + radius;
                    }
                }
            }
        }
        return true;
    }

    private static boolean testShoreline(LevelAccessor level_accessor, String dimension, ChunkPos center_chunk_pos) { // [刀U2前置][长期记忆:160] +dimension
        if (Handcode.Config.shoreline_detection == false) {
            return false;
        } else {
            Holder<Biome> biome_side1 = getBiome(level_accessor, dimension, new ChunkPos(center_chunk_pos.x + 1, center_chunk_pos.z + 1)); // [刀U2前置] +dimension
            Holder<Biome> biome_side2 = getBiome(level_accessor, dimension, new ChunkPos(center_chunk_pos.x + 1, center_chunk_pos.z - 1)); // [刀U2前置] +dimension
            Holder<Biome> biome_side3 = getBiome(level_accessor, dimension, new ChunkPos(center_chunk_pos.x - 1, center_chunk_pos.z + 1)); // [刀U2前置] +dimension
            Holder<Biome> biome_side4 = getBiome(level_accessor, dimension, new ChunkPos(center_chunk_pos.x - 1, center_chunk_pos.z - 1)); // [刀U2前置] +dimension
            boolean waterside_test1 = GameUtils.Environment.test(biome_side1, "#tanshugetrees:water_biomes");
            boolean waterside_test2 = GameUtils.Environment.test(biome_side2, "#tanshugetrees:water_biomes");
            boolean waterside_test3 = GameUtils.Environment.test(biome_side3, "#tanshugetrees:water_biomes");
            boolean waterside_test4 = GameUtils.Environment.test(biome_side4, "#tanshugetrees:water_biomes");
            return waterside_test1 == true || waterside_test2 == true || waterside_test3 == true || waterside_test4 == true;
        }
    }

    private static void writeData(LevelAccessor level_accessor, String dimension, int centerX, int centerZ, String id, Map<String, String> data) {
        String path_storage = data.get("path_storage");
        File chosen = new File(Core.path_config + "/dev/temporary/" + path_storage);

        // Random Select File
        {
            // [刀V-2] [长期记忆: 176] 目录列举缓存: 每树一次 listFiles → 会话内零次(computeIfAbsent;
            // 映射返回 null = 目录不存在 → CHM 不登记保留重探, 对齐旧 allFiles==null 早退行为)。
            // V16 防字典污染过滤搬进 filterBinFiles(注释随逻辑走); 空数组照常缓存短路。
            final File dir = chosen; // [刀V-2] lambda 捕获别名: chosen 下方将重赋值(非 effectively final 禁直接捕获)
            File[] list = list_files_cache.computeIfAbsent(path_storage, k -> filterBinFiles(dir));
            if (list == null || list.length == 0) {
                return;
            }

            RandomSource random = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ ((centerX * 341873128712L) + (centerZ * 132897987541L)));
            chosen = new File(chosen.getPath() + "/" + list[random.nextInt(list.length)].getName());
        }

        if (chosen.exists() == true && chosen.isDirectory() == false) {
            int sizeX = 0;
            int sizeY = 0;
            int sizeZ = 0;
            int center_sizeX = 0;
            int center_sizeY = 0;
            int center_sizeZ = 0;
            try {
                short[] size = Caches.TreeShape.getTreeShapeSize(path_storage + "|" + chosen.getName());
                sizeX = size[0];
                sizeY = size[1];
                sizeZ = size[2];
                center_sizeX = size[3];
                center_sizeY = size[4];
                center_sizeZ = size[5];
            } catch (Exception exception) {
                OutsideUtils.exception(new Exception(), exception, "");
                return;
            }

            // Convert Size
            {
                int[] rotation_mirrored = getRotationMirrored(level_accessor, centerX, centerZ, id);
                if (rotation_mirrored == null) {
                    return;
                }
                int[] convert = OutsideUtils.convertSizeRotationMirrored(rotation_mirrored, sizeX, sizeZ, center_sizeX, center_sizeZ);
                sizeX = convert[0];
                sizeZ = convert[1];
                center_sizeX = convert[2];
                center_sizeZ = convert[3];
            }

            int dead_tree_level = getDeadTreeLevel(level_accessor, id, path_storage + "|" + chosen.getName(), centerX, centerZ, false);

            // Coarse Woody Debris
            {
                if (dead_tree_level > 200) {
                    int fallen_direction = getFallenDirection(level_accessor, centerX, centerZ);
                    int[] convert = OutsideUtils.convertSizeFallen(fallen_direction, sizeX, sizeY, sizeZ, center_sizeX, center_sizeY, center_sizeZ);
                    sizeX = convert[0];
                    sizeY = convert[1];
                    sizeZ = convert[2];
                    center_sizeX = convert[3];
                    center_sizeY = convert[4];
                    center_sizeZ = convert[5];
                }
            }

            int from_chunkX = centerX - center_sizeX;
            int from_chunkZ = centerZ - center_sizeZ;
            int to_chunkX = (from_chunkX + sizeX) >> 4;
            int to_chunkZ = (from_chunkZ + sizeZ) >> 4;
            from_chunkX = from_chunkX >> 4;
            from_chunkZ = from_chunkZ >> 4;

            // [LMax Fix V42] 守卫改为可配置开关（默认关=废除）[长期记忆: 014]：
            // 原逻辑无条件扫描 ±4 chunk 的 features 状态，任一命中即丢弃整棵树——
            // 实锤副作用：出生点/快速跑图轨迹后方的地形已过 features 阶段，树在写入前被整棵拦截，
            // 0,0.bin 735 桶中 spawn 圈 ±10 chunk 零桶零条。前放与后放功能等价（唯一差异是客户端
            // 同步，刀N 后由主线程 setBlock(2) 增量包原生覆盖（resyncChunk 已退役）），故默认废除，保留开关供调试对比。
            if (Handcode.Config.chunk_status_guard == true) {
                    int scan_fromX = from_chunkX - 4;
                    int scan_fromZ = from_chunkZ - 4;
                    int scan_toX = to_chunkX + 4;
                    int scan_toZ = to_chunkZ + 4;
                    for (int scanX = scan_fromX; scanX <= scan_toX; scanX++) {
                        for (int scanZ = scan_fromZ; scanZ <= scan_toZ; scanZ++) {
                            if (GameUtils.Space.testChunkStatus(level_accessor, new ChunkPos(scanX, scanZ), "features") == true) {
                                return;
                            }
                        }
            }
            }

        

          
            // Write Tree Location
            {
                int regionX = centerX >> 9;
                int regionZ = centerZ >> 9;
                String regionKey = regionX + "," + regionZ;
                String dictId = CacheManager.getDictionary(id, false);

                // [执行代号22 - 任务 2.2 & 3.1] 废除 scanned_regions 判断，统一走内存缓冲，彻底解决老区域重启后不刷盘的问题
                ChunkPos chunk_pos = new ChunkPos(centerX >> 4, centerZ >> 4);
                BlockPos pos = new BlockPos(centerX, 0, centerZ);
                cache_write_tree_location.computeIfAbsent(dimension, k -> new java.util.concurrent.ConcurrentHashMap<>()).computeIfAbsent(chunk_pos, create -> new java.util.concurrent.ConcurrentHashMap<>()).put(pos, dictId); // [刀U2前置][长期记忆:160] per-dim 嵌套

                // [刀V] [长期记忆: 176] 每树即冲退役 → 标脏: 上方 put 先写缓存, 此处后标脏
                // (put-then-mark 铁律), 由宿主任务尾部 drainDirty 统一冲刷(33k 次文件开关/region
                // → 每 region 数次, 冷启动 259s → 秒级)
                markDirty(dimension, regionX, regionZ);
            }

            // Write Place
            {
                List<String> write = new ArrayList<>();
                String dictIdPlace = CacheManager.getDictionary(id, false);
                String dictName = CacheManager.getDictionary(chosen.getName(), false);
                write.add("s" + dictIdPlace);
                write.add("s" + dictName);
                write.add("i" + centerX);
                write.add("i" + centerZ);
                write.add("i" + from_chunkX);
                write.add("i" + from_chunkZ);
                write.add("i" + to_chunkX);
                write.add("i" + to_chunkZ);

                int from_chunkX_test = from_chunkX >> 5;
                int from_chunkZ_test = from_chunkZ >> 5;
                int to_chunkX_test = to_chunkX >> 5;
                int to_chunkZ_test = to_chunkZ >> 5;

                for (int scanX = from_chunkX_test; scanX <= to_chunkX_test; scanX++) {
                    for (int scanZ = from_chunkZ_test; scanZ <= to_chunkZ_test; scanZ++) {
                        String placeRegionKey = scanX + "," + scanZ;
                        // [执行代号22 - 任务 2.2 & 3.1] 统一走内存缓冲，解决老区域重启后不刷盘的问题
                        cache_write_place.computeIfAbsent(dimension, k -> new java.util.concurrent.ConcurrentHashMap<>()).computeIfAbsent(placeRegionKey, create -> java.util.Collections.synchronizedList(new java.util.ArrayList<>())).addAll(write); // [刀U2前置][长期记忆:160] per-dim 嵌套
                        // [刀V] [长期记忆: 176] 每树即冲退役 → 标脏(put-then-mark: 上方 put 先写缓存,
                        // 此处后标脏, JMM 保证任何摘牌者的 flush 必见数据), 宿主任务尾 drainDirty 统一收走
                        markDirty(dimension, scanX, scanZ);
                    }
                }
        

          
            } // 关闭 Write Place 块
        } // 关闭 if (chosen.exists && !isDirectory) 块
    } // 关闭 writeData 方法

    public static int[] getRotationMirrored(LevelAccessor level_accessor, int centerX, int centerZ, String id) {
        RandomSource random = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ ((centerX * 341873128712L) + (centerZ * 132897987541L)));
        String rotation = "";
        String mirrored = "";

        // Get Config Data
        {
            Map<String, String> data = ConfigDynamic.getData("world_gen").get(id);
            if (data == null) {
                return new int[0];
            }
            rotation = data.get("rotation");
            mirrored = data.get("mirrored");
        }

        // Apply Value
        {
            if (rotation.equals("north") == true) {
                rotation = "1";
            } else if (rotation.equals("west") == true) {
                rotation = "4";
            } else if (rotation.equals("east") == true) {
                rotation = "2";
            } else if (rotation.equals("south") == true) {
                rotation = "3";
            } else {
                rotation = String.valueOf(random.nextInt(4) + 1);
            }

            if (mirrored.equals("random") == true) {
                mirrored = String.valueOf(random.nextInt(3));
            } else if (mirrored.equals("random_x") == true) {
                if (random.nextBoolean() == true) {
                    mirrored = "1";
                } else {
                    mirrored = "0";
                }
            } else if (mirrored.equals("random_z") == true) {
                if (random.nextBoolean() == true) {
                    mirrored = "2";
                } else {
                    mirrored = "0";
                }
            } else {
                mirrored = "0";
            }
        }

        return new int[]{Integer.parseInt(rotation), Integer.parseInt(mirrored)};
    }

    public static int getDeadTreeLevel(LevelAccessor level_accessor, String id, String location, int centerX, int centerZ, boolean unviable_ecology) {
        RandomSource random = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ ((centerX * 341873128712L) + (centerZ * 132897987541L)));
        double chance = 0.0;
        String level = "";

        // Get Config Data
        {
            Map<String, String> data = ConfigDynamic.getData("world_gen").get(id);
            if (data == null) {
                return 0;
            }
            chance = Double.parseDouble(data.get("dead_tree_chance")) * Handcode.Config.multiply_dead_tree_chance;
            level = data.get("dead_tree_level");
        }

        if (unviable_ecology == true) {
            id = id + "_unviable_ecology";
        } else {
            if (random.nextDouble() >= chance) {
                return 0;
            }
        }

        short[] data = CacheManager.DataShort.getArray("dead_tree_level").get(id);
        if (data == null) {
            List<Short> list = new ArrayList<>();
            if (level.startsWith("auto") == false) {
                {
                    for (String scan : level.split(" / ")) {
                        if (unviable_ecology == false) {
                            if (scan.startsWith("3") == true) {
                                continue;
                            }
                        }
                        list.add(Short.parseShort(scan));
                    }
                }
            } else {
                {
                    short is_pine = 0;
                    if (level.equals("auto_pine") == true) {
                        is_pine = 1;
                    }

                    // Write Data
                    {
                        int count_trunk = 0;
                        int count_bough = 0;
                        int count_branch = 0;
                        int count_limb = 0;
                        int count_twig = 0;
                        int count_sprig = 0;

                        // Get Data
                        {
                            try {
                                int[] count = Caches.TreeShape.getTreeShapeBlockCount(location);
                                count_trunk = count[0];
                                count_bough = count[1];
                                count_branch = count[2];
                                count_limb = count[3];
                                count_twig = count[4];
                                count_sprig = count[5];
                            } catch (Exception exception) {
                                OutsideUtils.exception(new Exception(), exception, "");
                                return 0;
                            }
                        }

                        if (count_trunk > 0) {
                            if (Handcode.Config.dead_tree_auto_level.contains("18") == true) list.add((short) 180);
                            if (Handcode.Config.dead_tree_auto_level.contains("19") == true) list.add((short) 190);
                            if (unviable_ecology == false) {
                                if (Handcode.Config.dead_tree_auto_level.contains("28") == true) list.add((short) 280);
                                if (Handcode.Config.dead_tree_auto_level.contains("29") == true) list.add((short) 290);
                                if (Handcode.Config.dead_tree_auto_level.contains("38") == true) list.add((short) 380);
                                if (Handcode.Config.dead_tree_auto_level.contains("39") == true) list.add((short) 390);
                            }
                        }
                        if (count_bough > 0) {
                            if (Handcode.Config.dead_tree_auto_level.contains("16") == true) list.add((short) 160);
                            if (Handcode.Config.dead_tree_auto_level.contains("17") == true) list.add((short) 170);
                            if (Handcode.Config.dead_tree_auto_level.contains("15") == true) list.add((short) (150 + is_pine));
                            if (unviable_ecology == false) {
                                if (Handcode.Config.dead_tree_auto_level.contains("26") == true) list.add((short) 260);
                                if (Handcode.Config.dead_tree_auto_level.contains("27") == true) list.add((short) 270);
                                if (Handcode.Config.dead_tree_auto_level.contains("25") == true) list.add((short) (250 + is_pine));
                                if (Handcode.Config.dead_tree_auto_level.contains("36") == true) list.add((short) 360);
                                if (Handcode.Config.dead_tree_auto_level.contains("37") == true) list.add((short) 370);
                                if (Handcode.Config.dead_tree_auto_level.contains("35") == true) list.add((short) (350 + is_pine));
                            }
                        }
                        if (count_branch > 0) {
                            if (Handcode.Config.dead_tree_auto_level.contains("14") == true) list.add((short) (140 + is_pine));
                            if (unviable_ecology == false) {
                                if (Handcode.Config.dead_tree_auto_level.contains("24") == true) list.add((short) (240 + is_pine));
                                if (Handcode.Config.dead_tree_auto_level.contains("34") == true) list.add((short) (340 + is_pine));
                            }
                        }
                        if (count_limb > 0) {
                            if (Handcode.Config.dead_tree_auto_level.contains("13") == true) list.add((short) (130 + is_pine));
                            if (unviable_ecology == false) {
                                if (Handcode.Config.dead_tree_auto_level.contains("23") == true) list.add((short) (230 + is_pine));
                                if (Handcode.Config.dead_tree_auto_level.contains("33") == true) list.add((short) (330 + is_pine));
                            }
                        }
                        if (count_twig > 0) {
                            if (Handcode.Config.dead_tree_auto_level.contains("12") == true) list.add((short) (120 + is_pine));
                            if (unviable_ecology == false) {
                                if (Handcode.Config.dead_tree_auto_level.contains("22") == true) list.add((short) (220 + is_pine));
                                if (Handcode.Config.dead_tree_auto_level.contains("32") == true) list.add((short) (320 + is_pine));
                            }
                        }
                        if (count_sprig > 0) {
                            if (Handcode.Config.dead_tree_auto_level.contains("11") == true) list.add((short) (110 + is_pine));
                            if (unviable_ecology == false) {
                                if (Handcode.Config.dead_tree_auto_level.contains("21") == true) list.add((short) (210 + is_pine));
                                if (Handcode.Config.dead_tree_auto_level.contains("31") == true) list.add((short) (310 + is_pine));
                            }
                        }
                    }
                }
            }

            if (list.isEmpty() == true) {
                list.add((short) 0);
            }

            data = OutsideUtils.Data.convertListShortToArrayShort(list);
            CacheManager.DataShort.setArray("dead_tree_level", id, data);
        }

        return data[random.nextInt(data.length)];
    }

    public static int getFallenDirection(LevelAccessor level_accessor, int centerX, int centerZ) {
        RandomSource random = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ ((centerX * 341873128712L) + (centerZ * 132897987541L)));
        return random.nextInt(4) + 1;
    }
}
