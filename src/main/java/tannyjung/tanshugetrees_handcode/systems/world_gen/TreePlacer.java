        

          
package tannyjung.tanshugetrees_handcode.systems.world_gen;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import tannyjung.tanshugetrees_core.Core;
import tannyjung.tanshugetrees_core.outside.*;
import tannyjung.tanshugetrees_core.game.GameUtils;
import tannyjung.tanshugetrees_handcode.Handcode;
import tannyjung.tanshugetrees_handcode.systems.Caches;
import tannyjung.tanshugetrees_handcode.systems.living_mechanics.LeafLitter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;

public class TreePlacer {

    // [LMax Fix V5] 工业级延迟补种队列 V2：引入区块就绪检查与重试机制
    public static class DeferredQueue {
        private static class DeferredTask {
            String dimension;
            ChunkPos chunk_pos;
            int retries;
            // [方向A重构] 新增：目标 chunk 和是否强制补写标记
            ChunkPos target_chunk; // 用于 PendingBlocks 补写，null 表示重新调 start
            boolean is_forced;     // true 表示只补写 PendingBlocks，不重新调 start
            
            // 旧版构造函数（重新调 start）
            DeferredTask(String d, ChunkPos c) {
                this.dimension = d;
                this.chunk_pos = c;
                this.retries = 0;
                this.target_chunk = null;
                this.is_forced = false;
            }
            
            // [方向A重构] 新增构造函数（PendingBlocks 补写）
            DeferredTask(String d, ChunkPos c, ChunkPos target, boolean forced) {
                this.dimension = d;
                this.chunk_pos = c;
                this.retries = 0;
                this.target_chunk = target;
                this.is_forced = forced;
            }
        }

        private static final java.util.concurrent.ConcurrentLinkedQueue<DeferredTask> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();

        public static void add(String dimension, ChunkPos chunk_pos) {
            // [LMax Fix V7] 容量上限检查，防止无界队列导致 OOM
            while (queue.size() >= Handcode.Config.deferred_queue_max_size) {
                DeferredTask dropped = queue.poll();
                if (dropped == null) break;
                // 丢弃最旧的任务并记录警告
                System.err.println("[TansHugeTrees] DeferredQueue overflow, dropping oldest task: " + dropped.dimension + " " + dropped.chunk_pos);
            }
            queue.add(new DeferredTask(dimension, chunk_pos));
        }

        // [方向A重构] 新增：PendingBlocks 补写任务
        public static void addForced(String dimension, ChunkPos chunk_pos, ChunkPos target_chunk) {
            while (queue.size() >= Handcode.Config.deferred_queue_max_size) {
                DeferredTask dropped = queue.poll();
                if (dropped == null) break;
                System.err.println("[TansHugeTrees] DeferredQueue overflow, dropping oldest task: " + dropped.dimension + " " + dropped.chunk_pos);
            }
            queue.add(new DeferredTask(dimension, chunk_pos, target_chunk, true));
        }

        public static void processTick(net.minecraft.server.MinecraftServer server) {
            int processed = 0;
            DeferredTask task;
            java.util.List<DeferredTask> retryList = new java.util.ArrayList<>();

            // 每次 Tick 最多处理 N 个任务（配置项 deferred_queue_process_per_tick），防止主线程 TPS 暴跌
            while (processed < Handcode.Config.deferred_queue_process_per_tick && (task = queue.poll()) != null) {
                processed++;
                try {
                    net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimKey =
        

          
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(task.dimension));
                    ServerLevel targetLevel = server.getLevel(dimKey);

                    if (targetLevel == null) continue;

                    // [方向A重构] 区分两种任务：重新调 start vs 只补写 PendingBlocks
                    if (task.is_forced) {
                        // PendingBlocks 补写任务
                        // 检查目标 chunk 是否就绪
                        boolean allReady = true;
                        for (int dx = -4; dx <= 4; dx += 8) {
                            for (int dz = -4; dz <= 4; dz += 8) {
                                int cx = task.chunk_pos.x + dx;
                                int cz = task.chunk_pos.z + dz;
                                if (!targetLevel.getChunkSource().hasChunk(cx, cz)) {
                                    allReady = false;
                                    break;
                                }
                                net.minecraft.world.level.chunk.ChunkAccess chunk = targetLevel.getChunk(cx, cz);
                                if (!chunk.getHighestGeneratedStatus().isOrAfter(net.minecraft.world.level.chunk.ChunkStatus.FULL)) {
                                    allReady = false;
                                    break;
                                }
                            }
                            if (!allReady) break;
                        }

                        if (!allReady) {
                            task.retries++;
                            if (task.retries < Handcode.Config.deferred_queue_retry_limit) {
                                retryList.add(task);
                            }
                        } else {
                            // 区块已就绪，强制补写 PendingBlocks
                            PendingBlocks.placeForced(targetLevel, task.target_chunk);
                        }
                    } else {
                        // 旧版逻辑：重新调 start
                        // 核心：检查当前 Chunk 及 +-4 偏移的 Chunk 是否全部达到 FULL 状态
                        boolean allReady = true;
                        for (int dx = -4; dx <= 4; dx += 8) { // -4, +4
                            for (int dz = -4; dz <= 4; dz += 8) {
                                int cx = task.chunk_pos.x + dx;
                                int cz = task.chunk_pos.z + dz;
                                if (!targetLevel.getChunkSource().hasChunk(cx, cz)) {
                                    allReady = false;
                                    break;
                                }
                                // [执行代号33] 检查区块生成状态是否达到 FULL，避免跨区块树木劈树问题
                                net.minecraft.world.level.chunk.ChunkAccess chunk = targetLevel.getChunk(cx, cz);
                                if (!chunk.getHighestGeneratedStatus().isOrAfter(net.minecraft.world.level.chunk.ChunkStatus.FULL)) {
                                    allReady = false;
                                    break;
                                }
                            }
                            if (!allReady) break;
                        }

                        if (!allReady) {
                            task.retries++;
                            if (task.retries < Handcode.Config.deferred_queue_retry_limit) { // 最多重试 N Tick (配置项 deferred_queue_retry_limit)
                                retryList.add(task);
                            }
                        } else {
                            // 区块已就绪，安全执行补种
                            ChunkGenerator gen = targetLevel.getChunkSource().getGenerator();
                            start(targetLevel, targetLevel, gen, task.dimension, task.chunk_pos);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            queue.addAll(retryList);
        }
    }

    public static void start (LevelAccessor level_accessor, ServerLevel level_server, ChunkGenerator chunk_generator, String dimension, ChunkPos chunk_pos) {

        Core.GlobalLocking.test();

        ByteBuffer data = Data.get(dimension, chunk_pos);

        if (data.remaining() == 0) {
            DeferredQueue.add(dimension, chunk_pos);
            return;
        }

        // [LMax Debug] 追踪 TreePlacer 执行情况
        int data_size = data.remaining();
        long placer_start = System.currentTimeMillis();
        System.out.println("[THT-DEBUG] TreePlacer.start() chunk " + chunk_pos + " data: " + data_size + " bytes");

        String id = "";
        String chosen = "";
        int centerX = 0;
        int centerZ = 0;
        int from_chunkX = 0;
        int from_chunkZ = 0;
        int to_chunkX = 0;
        int to_chunkZ = 0;

        while (data.remaining() > 0) {

            // Get data
            {

                try {

                    id = CacheManager.getDictionary(String.valueOf(data.getShort()), true);
                    chosen = CacheManager.getDictionary(String.valueOf(data.getShort()), true);
                    centerX = data.getInt();
                    centerZ = data.getInt();
                    from_chunkX = data.getInt();
                    from_chunkZ = data.getInt();
                    to_chunkX = data.getInt();
                    to_chunkZ = data.getInt();

                } catch (Exception exception) {

                    OutsideUtils.exception(new Exception(), exception, "");
                    return;

                }

            }

            DetailedDetection.test(level_accessor, level_server, chunk_generator, dimension, chunk_pos, from_chunkX, from_chunkZ, to_chunkX, to_chunkZ, id, chosen, centerX, centerZ);
        // [方向A重构] 写入当前 chunk 缓存的所有方块，解决跨 chunk 写入时序问题
        PendingBlocks.place(level_accessor, chunk_pos);

        LeafLitterGeneration.place(level_accessor, level_server, chunk_generator, chunk_pos);
        Function.run(level_accessor, level_server, chunk_pos);
        Data.clearChunk(dimension, chunk_pos);

        // [LMax Debug] 追踪 TreePlacer 完成时间
        long placer_time = System.currentTimeMillis() - placer_start;
        if (placer_time > 100) {
            Core.logger.info("[THT-DEBUG] TreePlacer.start() chunk " + chunk_pos + " completed in " + placer_time + "ms (data was " + data_size + " bytes)");
        }
    }
        Data.clearChunk(dimension, chunk_pos);

    }

    private static int[] getPartReduce (LevelAccessor level_accessor, String location, int centerX, int centerZ, int dead_tree_level) {

        RandomSource random = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ ((centerX * 341873128712L) + (centerZ * 132897987541L)));
        int count_trunk = 0;
        int count_bough = 0;
        int count_branch = 0;
        int count_limb = 0;
        int count_twig = 0;
        int count_sprig = 0;

        // Get Data
        {

            try {

                int[] data = Caches.TreeShape.getTreeShapeBlockCount(location);
                count_trunk = data[0];
                count_bough = data[1];
                count_branch = data[2];
                count_limb = data[3];
                count_twig = data[4];
                count_sprig = data[5];

            } catch (Exception exception) {

                OutsideUtils.exception(new Exception(), exception, "");
                return new int[0];

            }

        }

        // Convert Dead Level
        {

            if (dead_tree_level < 200) {

                dead_tree_level = dead_tree_level - 100;

            } else if (dead_tree_level < 300) {

                dead_tree_level = dead_tree_level - 200;

            } else if (dead_tree_level < 400) {

                dead_tree_level = dead_tree_level - 300;

            }

        }

        if (dead_tree_level >= 60) {

            // Only Trunk
            {

                count_bough = 0;
                count_branch = 0;
                count_limb = 0;
                count_twig = 0;
                count_sprig = 0;

                if (dead_tree_level == 60 || dead_tree_level == 70) {

                    count_trunk = (int) (Mth.nextDouble(random, 0.5, 1.0) * (double) count_trunk);

                } else if (dead_tree_level == 80 || dead_tree_level == 90) {

                    count_trunk = (int) (Mth.nextDouble(random, 0.1, 0.5) * (double) count_trunk);

                }

            }

        } else {

            // General
            {

                if (dead_tree_level == 50) {

                    count_bough = (int) (random.nextDouble() * count_bough);

                }

                if (dead_tree_level < 50) {

                    if (dead_tree_level == 40) {

                        count_branch = (int) (random.nextDouble() * count_branch);

                    }

                } else {

                    count_branch = 0;

                }

                if (dead_tree_level < 40) {

                    if (dead_tree_level == 30 && count_limb > 0) {

                        count_limb = (int) (random.nextDouble() * count_limb);

                    }

                } else {

                    count_limb = 0;

                }

                if (dead_tree_level < 30) {

                    if (dead_tree_level == 20 && count_twig > 0) {

                        count_twig = (int) (random.nextDouble() * count_twig);

                    }

                } else {

                    count_twig = 0;

                }

                if (dead_tree_level < 20) {

                    if (dead_tree_level == 10 && count_sprig > 0) {

                        count_sprig = (int) (random.nextDouble() * count_sprig);

                    }

                } else {

                    count_sprig = 0;

                }

            }

        }

        return new int[]{count_trunk, count_bough, count_branch, count_limb, count_twig, count_sprig};

    }

    // [方向A重构] 原 place() 拆分为 placeCalculate()（计算+分组缓存）+ PendingBlocks.place()（拉取+写入）
    // 移除 chunk 过滤，所有方块统一存入 PendingBlocks，由各 chunk 的 place() 统一写入
    private static void placeCalculate (LevelAccessor level_accessor, ServerLevel level_server, ChunkPos chunk_pos, String id, String location, String path_settings, BlockPos pos_center, int[] rotation_mirrored, int dead_tree_level, int fallen_direction) {

        boolean can_disable_roots = false;
        boolean can_leaves_decay = false;
        boolean can_leaves_drop = false;
        boolean can_leaves_regrow = false;

        // Get Data
        {

            Map<String, String> data_normal = Caches.TreeSettings.getNormal(path_settings);
            can_disable_roots = data_normal.getOrDefault("can_disable_roots", "").equals("true");
            can_leaves_decay = data_normal.getOrDefault("can_leaves_decay", "").equals("true");
            can_leaves_drop = data_normal.getOrDefault("can_leaves_drop", "").equals("true");
            can_leaves_regrow = data_normal.getOrDefault("can_leaves_regrow", "").equals("true");

        }

        Map<Short, BlockState> blocks = Caches.TreeSettings.getBlock(level_server, path_settings);
        Set<Short> keep = Caches.TreeSettings.getKeep(path_settings);
        short[] leaves_type = Caches.TreeSettings.getLeavesType(path_settings);
        Map<Short, String> functions = Caches.TreeSettings.getFunction(path_settings);
        List<String> tree_decoration_normal = getTreeDecoration("normal");
        List<String> tree_decoration_decay = getTreeDecoration("decay");

        boolean coarse_woody_debris = false;
        boolean no_roots = false;
        boolean hollowed = false;
        boolean abscission = false;
        int reduce_trunk = 0;
        int reduce_bough = 0;
        int reduce_branch = 0;
        int reduce_limb = 0;
        int reduce_twig = 0;
        int reduce_sprig = 0;

        if (dead_tree_level == 0) {

            {

                no_roots = Handcode.Config.world_gen_roots == false && can_disable_roots == true;

                if (Handcode.Config.abscission_world_gen == true) {

                    if (leaves_type[0] == 1 || leaves_type[1] == 1) {

                        if (GameUtils.Environment.test(GameUtils.Environment.getAt(level_accessor, pos_center), "#tanshugetrees:snowy_biomes") == true) {

                            abscission = true;

                        }

                    }

                }

            }

        } else {

            {

                // Get Reduce
                {

                    try {

                        int[] data = getPartReduce(level_accessor, location, pos_center.getX(), pos_center.getZ(), dead_tree_level);
                        reduce_trunk = data[0];
                        reduce_bough = data[1];
                        reduce_branch = data[2];
                        reduce_limb = data[3];
                        reduce_twig = data[4];
                        reduce_sprig = data[5];

                    } catch (Exception exception) {

                        OutsideUtils.exception(new Exception(), exception, "");
                        return;

                    }

                }

                boolean force_no_roots = false;

                if (dead_tree_level < 200) {

                    dead_tree_level = dead_tree_level - 100;

                } else if (dead_tree_level < 300) {

                    dead_tree_level = dead_tree_level - 200;
                    coarse_woody_debris = true;

                } else if (dead_tree_level < 400) {

                    dead_tree_level = dead_tree_level - 300;
                    coarse_woody_debris = true;
                    force_no_roots = true;

                }

                if (dead_tree_level == 70 || dead_tree_level == 90) {

                    hollowed = true;

                }

                if (force_no_roots == true) {

                    no_roots = true;

                }

            }

        }

        boolean can_run_function = false;
        BlockState block = null;
        String function = "";
        BlockPos pos = null;
        boolean is_leaves = false;
        boolean is_function = false;
        double leaf_litter_chance = 0.0;

        int loop = 0;
        short type = 0;
        short posX = 0;
        short posY = 0;
        short posZ = 0;

        // [LMax Debug] 追踪形状数据大小
        short[] shape_data = Caches.TreeShape.getTreeShapeData(location);
        System.out.println("[THT-DEBUG] placeCalculate '" + id + "' shape blocks: " + shape_data.length);

        for (short scan : shape_data) {

            // Loop Skip
            {

                loop = loop + 1;

                if (loop == 1) {

                    type = scan;

                } else if (loop == 2) {

                    posX = scan;

                } else if (loop == 3) {

                    posY = scan;

                } else {

                    posZ = scan;
                    loop = 0;

                }

                if (loop > 0) {

                    continue;

                }

            }

            is_leaves = OutsideUtils.Mathematics.isNumberStartWith(type, 120) == true;
            is_function = OutsideUtils.Mathematics.isNumberStartWith(type, 2) == true;

            if (is_function == false) {

                can_run_function = false;

                // Dead Tree Reduction
                {

                    if (dead_tree_level > 0) {

                        if (is_leaves == true) {

                            continue;

                        } else {

                            // Basic Style
                            {

                                if (OutsideUtils.Mathematics.isNumberStartWith(type, 119) == true) {

                                    if (reduce_sprig > 0) {

                                        reduce_sprig = reduce_sprig - 1;

                                    } else {

                                        continue;

                                    }

                                } else if (OutsideUtils.Mathematics.isNumberStartWith(type, 118) == true) {

                                    if (reduce_sprig == 0) {

                                        if (reduce_twig > 0) {

                                            reduce_twig = reduce_twig - 1;

                                        } else {

                                            continue;

                                        }

                                    }

                                } else if (OutsideUtils.Mathematics.isNumberStartWith(type, 117) == true) {

                                    if (reduce_twig == 0) {

                                        if (reduce_limb > 0) {

                                            reduce_limb = reduce_limb - 1;

                                        } else {

                                            continue;

                                        }

                                    }

                                } else if (OutsideUtils.Mathematics.isNumberStartWith(type, 116) == true) {

                                    if (reduce_limb == 0) {

                                        if (reduce_branch > 0) {

                                            reduce_branch = reduce_branch - 1;

                                        } else {

                                            continue;

                                        }

                                    }

                                } else if (OutsideUtils.Mathematics.isNumberStartWith(type, 115) == true) {

                                    if (reduce_branch == 0) {

                                        if (reduce_bough > 0) {

                                            reduce_bough = reduce_bough - 1;

                                        } else {

                                            continue;

                                        }

                                    }

                                }

                            }

                            if (dead_tree_level >= 60) {

                                // Only Trunk
                                {

                                    if (OutsideUtils.Mathematics.isNumberStartWith(type, 114) == true) {

                                        if (reduce_trunk > 0) {

                                            reduce_trunk = reduce_trunk - 1;

                                            if (hollowed == true) {

                                                if (type == 1143) {

                                                    continue;

                                                }

                                            }

                                        } else {

                                            continue;

                                        }

                                    }

                                }

                            }

                        }

                    }

                }

                pos = new BlockPos(posX, posY, posZ);
                pos = OutsideUtils.convertPosRotationMirrored(pos, rotation_mirrored);
                pos = OutsideUtils.convertPosFallen(pos, fallen_direction);
                pos = pos.offset(pos_center.getX(), pos_center.getY(), pos_center.getZ());

                // [方向A重构] 移除 chunk 过滤，所有方块统一处理
                if (is_function == false) {

                    block = blocks.get(type);

                    if (block == null) {

                        continue;

                    }

                    // Keep
                    {

                        if (keep.contains(type) == true) {

                            if (level_accessor.getBlockState(pos).isAir() == false) {

                                continue;

                            }

                        }

                    }

                    // No Roots
                    {

                        if (coarse_woody_debris == false) {

                            if (no_roots == true) {

                                if (OutsideUtils.Mathematics.isNumberStartWith(type, 111) == true || OutsideUtils.Mathematics.isNumberStartWith(type, 112) == true || OutsideUtils.Mathematics.isNumberStartWith(type, 113) == true) {

                                    continue;

                                }

                            }

                        } else {

                            if (OutsideUtils.Mathematics.isNumberStartWith(type, 112) == true || OutsideUtils.Mathematics.isNumberStartWith(type, 113) == true) {

                                continue;

                            }

                            if (no_roots == true) {

                                if (OutsideUtils.Mathematics.isNumberStartWith(type, 110) == true || OutsideUtils.Mathematics.isNumberStartWith(type, 111) == true) {

                                    continue;

                                }

                            }

                        }

                    }

                    RandomSource random = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ ((pos.getX() * 341873128712L) + (pos.getZ() * 132897987541L)) * pos.getY());

                    if (is_leaves == true) {

                        // Leaf Litter
                        {

                            if (can_leaves_drop == true) {

                                if (Handcode.Config.leaf_litter == true && Handcode.Config.leaf_litter_world_gen == true) {

                                    if ((OutsideUtils.Mathematics.isNumberEndWith(type, 1) == true && leaves_type[0] == 2) || (OutsideUtils.Mathematics.isNumberEndWith(type, 2) == true && leaves_type[1] == 2)) {

                                        leaf_litter_chance = Handcode.Config.leaf_litter_world_gen_chance_coniferous;

                                    } else {

                                        leaf_litter_chance = Handcode.Config.leaf_litter_world_gen_chance;

                                    }

                                    if (random.nextDouble() < leaf_litter_chance) {

                                        // [方向A重构] 用方块所在 chunk 而非中心 chunk
                                        LeafLitterGeneration.add(new ChunkPos(pos), pos, block);

                                    }

                                }

                            }

                        }

                        // Abscission
                        {

                            if (abscission == true) {

                                continue;

                            }

                        }

                    }

                    // [方向A重构] 所有方块统一存入 PendingBlocks，由各 chunk 的 place() 统一写入
                    PendingBlocks.add(pos, block);

                    // Tree Decoration
                    {

                        if (Handcode.Config.tree_decorations == true) {

                            if (is_leaves == false) {

                                if (random.nextDouble() < Handcode.Config.tree_decorations_normal_chance) {

                                    if (tree_decoration_normal.isEmpty() == false) {

                                        // [方向A重构] 用方块所在 chunk
                                        Function.add(new ChunkPos(pos), pos, tree_decoration_normal.get(random.nextInt(tree_decoration_normal.size())));

                                    }

                                }

                            }

                            if (dead_tree_level > 0) {

                                if (random.nextDouble() < Handcode.Config.tree_decorations_decay_chance) {

                                    if (tree_decoration_decay.isEmpty() == false) {

                                        // [方向A重构] 用方块所在 chunk
                                        Function.add(new ChunkPos(pos), pos, tree_decoration_decay.get(random.nextInt(tree_decoration_decay.size())));

                                    }

                                }

                            }

                        }

                    }

                    // Summon Marker
                    {

                        // At Center
                        if (posX == 0 && posY == 0 && posZ == 0) {

                            if (Handcode.Config.tree_location == true && dead_tree_level == 0) {

                                if (can_leaves_decay == true || can_leaves_drop == true || can_leaves_regrow == true) {

                                    String marker_data = "{ForgeData:{tanshugetrees:{file:\"" + location + "\",tree_settings:\"" + path_settings + "\",rotation:" + rotation_mirrored[0] + ",mirrored:" + rotation_mirrored[1] + "}}}";
                                    GameUtils.Mob.summonWorldGen(level_server, pos.getCenter(), "minecraft:marker", id, "TANSHUGETREES-tree_location", marker_data);

                                }

                            }

                        }

                    }

                    can_run_function = true;

                } else {

                    // Function
                    {
                        // Separate because start and end function no need to test "can run here?"
                        if (can_run_function == true || type == 210 || type == 220) {

                            function = functions.get(type);

                            if (function == null) {

                                continue;

                            }

                            // [方向A重构] 用方块所在 chunk
                            Function.add(new ChunkPos(pos), pos, function);

                        }

                    }

                }

            }

        }

    }

    private static List<String> getTreeDecoration (String type) {

        List<String> data = CacheManager.DataText.getList("tree_decorations").get(type);

        if (data == null) {

            data = new ArrayList<>();
            String[] names = null;

            // Get List of Names
            {

                if (type.equals("normal") == true) {

                    names = new File(Core.path_config + "/dev/temporary/tree_decorations").list();

                } else {

                    names = new File(Core.path_config + "/dev/temporary/tree_decorations/decay").list();

                }

                if (names == null) {

                    names = new String[0];

                }

            }

            for (String name : names) {

                if (name.endsWith(".txt") == true) {

                    name = name.substring(0, name.length() - ".txt".length());

                    if (type.equals("normal") == true) {

                        name = "tree_decorations/" + name;

                    } else {

                        name = "tree_decorations/decay/" + name;

                    }

                    data.add(name);

                }

            }

            CacheManager.DataText.setList("tree_decorations", type, data);

        }

        return data;

    }

    public static class Data {

        // [LMax Fix V3]彻底废除同步等待，改用 CompletableFuture 异步加载。
        // 任何线程请求数据时，如果未加载完成，直接返回空，绝不阻塞当前线程。
        // 彻底根除 DH 线程池排队、主线程级联卡死的问题。
        private static final Map<String, java.util.concurrent.CompletableFuture<Map<ChunkPos, ByteArrayOutputStream>>> bin_convert_futures = new java.util.concurrent.ConcurrentHashMap<>();

        public static void clear () {
            bin_convert_futures.clear();
        }

        private static void clearChunk (String dimension, ChunkPos chunk_pos) {
            // 异步模式下，clearChunk 操作变得复杂且无必要，直接清空整个 Future 缓存即可。
        }

        private static ByteBuffer get (String dimension, ChunkPos chunk_pos) {
            int regionX = chunk_pos.x >> 5;
            int regionZ = chunk_pos.z >> 5;

            String key = dimension + "/" + regionX + "," + regionZ;

            // [LMax Fix V7] 容量上限淘汰，防止 bin_convert_futures 无限增长导致 OOM
            if (!bin_convert_futures.containsKey(key)) {
                while (bin_convert_futures.size() >= Handcode.Config.bin_convert_futures_max_entries) {
                    java.util.Iterator<Map.Entry<String, java.util.concurrent.CompletableFuture<Map<ChunkPos, ByteArrayOutputStream>>>> it = bin_convert_futures.entrySet().iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    } else {
                        break;
                    }
                }
            }

            java.util.concurrent.CompletableFuture<Map<ChunkPos, ByteArrayOutputStream>> future = bin_convert_futures.computeIfAbsent(key, k ->
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    Map<ChunkPos, ByteArrayOutputStream> regionData = new HashMap<>();
                    ByteBuffer bin = FileManager.readBIN(Core.path_world_mod + "/world_gen/place/" + k + ".bin");
                    if (bin != null) {
                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                        int from_chunkX = 0;
                        int from_chunkZ = 0;
                        int to_chunkX = 0;
                        int to_chunkZ = 0;
                        short id = 0;
                        short chosen = 0;
                        int centerX = 0;
                        int centerZ = 0;

                        while (bin.remaining() > 0) {
                            try {
                                id = bin.getShort();
                                chosen = bin.getShort();
                                centerX = bin.getInt();
                                centerZ = bin.getInt();
                                from_chunkX = bin.getInt();
                                from_chunkZ = bin.getInt();
                                to_chunkX = bin.getInt();
                                to_chunkZ = bin.getInt();

                                stream.reset();
                                stream.write(OutsideUtils.Data.convertShortToArrayByte(id));
                                stream.write(OutsideUtils.Data.convertShortToArrayByte(chosen));
                                stream.write(OutsideUtils.Data.convertIntToArrayByte(centerX));
                                stream.write(OutsideUtils.Data.convertIntToArrayByte(centerZ));
                                stream.write(OutsideUtils.Data.convertIntToArrayByte(from_chunkX));
                                stream.write(OutsideUtils.Data.convertIntToArrayByte(from_chunkZ));
                                stream.write(OutsideUtils.Data.convertIntToArrayByte(to_chunkX));
                                stream.write(OutsideUtils.Data.convertIntToArrayByte(to_chunkZ));

                                for (int scanX = from_chunkX; scanX <= to_chunkX; scanX++) {
                                    for (int scanZ = from_chunkZ; scanZ <= to_chunkZ; scanZ++) {
                                        if (regionX == scanX >> 5 && regionZ == scanZ >> 5) {
                                            regionData.computeIfAbsent(new ChunkPos(scanX, scanZ), create -> new ByteArrayOutputStream()).write(stream.toByteArray());
                                        }
                                    }
                                }
                            } catch (Exception exception) {
                                OutsideUtils.exception(new Exception(), exception, "");
                                break;
                            }
                        }
                    }
                    return regionData;
                })
            );

            // 核心妥协：如果 Future 还没完成，当前线程绝对不等待，直接返回空数据放弃本次生成。
            // 依靠 TreeLocation 中的 +-4 Chunk 偏移重复生成逻辑来补偿漏掉的树木。
            if (future.isDone()) {
                try {
                    Map<ChunkPos, ByteArrayOutputStream> data = future.get();
                    ByteArrayOutputStream stream = data.get(chunk_pos);
                    if (stream == null) {
                        return ByteBuffer.allocate(0);
                    } else {
                        return ByteBuffer.wrap(stream.toByteArray());
                    }
                } catch (Exception e) {
                    return ByteBuffer.allocate(0);
                }
            } else {
                return ByteBuffer.allocate(0);
            }
        }

    }
    private static class DetailedDetection {

        // [方向A重构] 废除 memoryCache：中心 chunk 检测确保每棵树只被处理一次，无需跨 chunk 缓存

        private static void test (LevelAccessor level_accessor, ServerLevel level_server, ChunkGenerator chunk_generator, String dimension, ChunkPos chunk_pos, int from_chunkX, int from_chunkZ, int to_chunkX, int to_chunkZ, String id, String chosen, int centerX, int centerZ) {

            // [方向A重构] 中心 chunk 检测：只有树中心所在的 chunk 才执行检测和分发，其余 chunk 直接返回
            if (chunk_pos.x != centerX >> 4 || chunk_pos.z != centerZ >> 4) {
                return;
            }

            // [LMax Debug] 追踪每棵树的放置
            long tree_start = System.currentTimeMillis();
            System.out.println("[THT-DEBUG] DetailedDetection: placing tree '" + id + "' at " + centerX + "," + centerZ + " in chunk " + chunk_pos);
            String location = "";
            String path_settings = "";
            String ground_block = "";
            String[] start_height_offset = null;

            // Get Config Data
            {

                Map<String, String> config = ConfigDynamic.getData("world_gen").get(id);

                if (config == null) {

                    return;

                }

                location = config.get("path_storage") + "|" + chosen;
                path_settings = config.get("path_settings");
                ground_block = config.get("ground_block");
                start_height_offset = config.get("start_height_offset").split(" <> ");

            }

            int dead_tree_level = TreeLocation.getDeadTreeLevel(level_accessor, id, location, centerX, centerZ, false);
            int fallen_direction = 0;

            if (dead_tree_level > 200) {

                fallen_direction = TreeLocation.getFallenDirection(level_accessor, centerX, centerZ);

            }

            int[] rotation_mirrored = TreeLocation.getRotationMirrored(level_accessor, centerX, centerZ, id);

            if (rotation_mirrored == null) {

                return;

            }

            BlockPos pos_center = new BlockPos(centerX, 0, centerZ);
            boolean pass = false;

            // [方向A重构] 废除 memoryCache：中心 chunk 检测确保每棵树只被处理一次
            {

                String type = "";
                int start_height = 0;

                // Scan Tree Settings
                {

                    Map<String, String> tree_settings = Caches.TreeSettings.getNormal(path_settings);
                    type = tree_settings.getOrDefault("type", "");
                    start_height = Integer.parseInt(tree_settings.getOrDefault("start_height", "0"));

                }

                test:
                {
                    // [LMax Debug] 检查点：高度查询前
                    System.out.println("[THT-DEBUG] CP1: before getHeightWorldGen for '" + id + "' at " + centerX + "," + centerZ);
                    BlockPos pos_original = new BlockPos(centerX, GameUtils.Space.getHeightWorldGen(level_accessor, level_server, chunk_generator, centerX, centerZ, "OCEAN_FLOOR_WG", "OCEAN_FLOOR_WG"), centerZ);
                    System.out.println("[THT-DEBUG] CP2: after getHeightWorldGen, Y=" + pos_original.getY());

                    // Ground Level
                    {

                        if (GameUtils.Space.testChunkStatus(level_accessor, new ChunkPos(pos_original), "carvers") == true) {

                            BlockState block = level_accessor.getBlockState(pos_original.below());

                            if (block.canBeReplaced() == true) {

                                break test;

                            } else if (GameUtils.Tile.test(block, ground_block) == false) {

                                break test;

                            }

                        }

                    }

                    RandomSource random = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ ((centerX * 341873128712L) + (centerZ * 132897987541L)));

                    // Tree Type
                    {

                        if (type.equals("special") == false && type.equals("emergent") == false) {

                            int highestY = GameUtils.Space.getHeightWorldGen(level_accessor, level_server, chunk_generator, centerX, centerZ, "WORLD_SURFACE_WG", "WORLD_SURFACE_WG");

        

          
                            // [P0-2 修复] 使用可配置高度容差，避免超平坦世界中 getBaseHeight 噪声导致 1 格偏差时 terrestrial 树被误判为 unviable ecology
                            if ((type.equals("terrestrial") == true && (pos_original.getY() < highestY - Handcode.Config.unviable_ecology_height_tolerance)) || (type.equals("aquatic") == true && (pos_original.getY() == highestY))) {

                                if (random.nextDouble() < Handcode.Config.unviable_ecology_skip_chance) {

                                    break test;

                                }

                                if (dead_tree_level == 0) {

                                    dead_tree_level = TreeLocation.getDeadTreeLevel(level_accessor, id, location, centerX, centerZ, true);

                                }

                            }

                        }

                    }

                    // Height Offset
                    {

                        int offsetY = pos_original.getY() + start_height;

                        if (dead_tree_level < 200) {

                            offsetY = offsetY + random.nextInt(Integer.parseInt(start_height_offset[0]), Integer.parseInt(start_height_offset[1]) + 1);

                        }

                        pos_center = pos_center.atY(pos_center.getY() + offsetY);

                    }

                    int center_sizeX = 0;
                    int center_sizeY = 0;
                    int center_sizeZ = 0;
                    int sizeX = 0;
                    int sizeY = 0;
                    int sizeZ = 0;

                    // Get Size
                    {

                        try {

                            short[] size_data = Caches.TreeShape.getTreeShapeSize(location);
                            sizeX = size_data[0];
                            sizeY = size_data[1];
                            sizeZ = size_data[2];
                            center_sizeX = size_data[3];
                            center_sizeY = size_data[4];
                            center_sizeZ = size_data[5];

                        } catch (Exception exception) {

                            OutsideUtils.exception(new Exception(), exception, "This is normal error when a tree shape is no longer in your world. Here is that shape ID [ " + location + " ].");
                            break test;

                        }

                    }

                    // Size Convert
                    {

                        int[] convert = OutsideUtils.convertSizeRotationMirrored(rotation_mirrored, sizeX, sizeZ, center_sizeX, center_sizeZ);
                        sizeX = convert[0];
                        sizeZ = convert[1];
                        center_sizeX = convert[2];
                        center_sizeZ = convert[3];

                        if (fallen_direction > 0) {

                            convert = OutsideUtils.convertSizeFallen(fallen_direction, sizeX, sizeY, sizeZ, center_sizeX, center_sizeY, center_sizeZ);
                            sizeX = convert[0];
                            sizeY = convert[1];
                            sizeZ = convert[2];
                            center_sizeX = convert[3];
                            center_sizeY = convert[4];
                            center_sizeZ = convert[5];

                        }

                    }

                    // Height Y Test
                    {

                        if ((sizeY - center_sizeY) + pos_center.getY() >= level_accessor.getMaxBuildHeight()) {

                            break test;

                        }

                        if (pos_original.getY() == GameUtils.Space.getBuildHeight(level_accessor, false)) {

                            break test;

                        }

                        if (Handcode.Config.max_height_spawn != 0) {

                            if (pos_original.getY() > Handcode.Config.max_height_spawn) {

                                break test;

                            }

                        }

                    }

                    // Structure Detection
                    {

                        int size = Handcode.Config.structure_detection_size;
                        ChunkPos chunk_pos_test = null;

                        if (size >= 0) {

        

          
                            Map<Structure, LongSet> references = new HashMap<>();
                            // [P2 修复] 限制扫描 chunk 数量，防止大范围扫描导致卡顿
                            int chunk_scan_count = 0;
                            boolean chunk_scan_limit_reached = false;

                            for (int scanX = from_chunkX - size; scanX <= to_chunkX + size; scanX++) {

                                if (chunk_scan_limit_reached) {

                                    break;

                                }

                                for (int scanZ = from_chunkZ - size; scanZ <= to_chunkZ + size; scanZ++) {

                                    // [P2 修复] 超过最大扫描数量时停止，避免大范围扫描卡顿
                                    if (chunk_scan_count >= Handcode.Config.structure_detection_max_chunks) {

                                        chunk_scan_limit_reached = true;
                                        break;

                                    }

                                    chunk_scan_count = chunk_scan_count + 1;
                                    chunk_pos_test = new ChunkPos(scanX, scanZ);

                                    if (GameUtils.Space.testChunkStatus(level_accessor, chunk_pos_test, "structure_references") == true) {

                                        references = level_accessor.getChunk(chunk_pos_test.x, chunk_pos_test.z).getAllReferences();

                                        if (references.size() > 0) {

                                            for (Structure structure : references.keySet()) {

                                                if (structure.step() == GenerationStep.Decoration.SURFACE_STRUCTURES) {

                                                    break test;

                                                }

                                            }

                                        }

                                    }

                                }

                            }

                        }

                    }

                    if (sizeX != 0 || sizeZ != 0) {

                        if (testSurfaceSmoothness(level_accessor, level_server, chunk_generator, pos_center, sizeX, sizeY, sizeZ, center_sizeX, center_sizeY, center_sizeZ, pos_original) == false) {

                            break test;

                        }

                        if (dead_tree_level > 200) {

                            if (testFallenArea(level_accessor, level_server, chunk_generator, location, pos_center, rotation_mirrored, fallen_direction, dead_tree_level) == false) {

                                break test;

                            }

                        }

                    }

                    pass = true;

                }

            }

        

          
            if (pass == true) {

                // [LMax Debug] 追踪 placeCalculate 耗时
                long pc_start = System.currentTimeMillis();
                // [方向A重构] 调用 placeCalculate() 替代旧 place()
                placeCalculate(level_accessor, level_server, chunk_pos, id, location, path_settings, pos_center, rotation_mirrored, dead_tree_level, fallen_direction);
                long pc_time = System.currentTimeMillis() - pc_start;
                if (pc_time > 50) {
                    System.out.println("[THT-DEBUG] placeCalculate '" + id + "' at " + centerX + "," + centerZ + " took " + pc_time + "ms");
                }

                // [LMax Fix V16] 修复死锁：level_server.getChunk() 会阻塞等待目标区块 FULL，
                // 在多线程区块生成中，两个区块互相等对方 FULL 导致永久死锁。
                // 修复：全部走 DeferredQueue，不在生成期间直接访问邻近区块。
                for (int scanX = from_chunkX; scanX <= to_chunkX; scanX++) {
                    for (int scanZ = from_chunkZ; scanZ <= to_chunkZ; scanZ++) {

                        ChunkPos target_cp = new ChunkPos(scanX, scanZ);

                        if (target_cp.equals(chunk_pos)) {
                            continue;
                        }

                        DeferredQueue.addForced(dimension, chunk_pos, target_cp);

                    }
                }

            }

        }

        private static boolean testSurfaceSmoothness (LevelAccessor level_accessor, ServerLevel level_server, ChunkGenerator chunk_generator, BlockPos pos_center, int sizeX, int sizeY, int sizeZ, int center_sizeX, int center_sizeY, int center_sizeZ, BlockPos pos_original) {

            if (Handcode.Config.surface_smoothness_detection == true) {

                int test_sizeX = sizeX - center_sizeX;
                int test_sizeZ = sizeZ - center_sizeZ;
                int test_center_sizeX = center_sizeX;
                int test_center_sizeZ = center_sizeZ;

                int pos1 = GameUtils.Space.getHeightWorldGen(level_accessor, level_server, chunk_generator, pos_center.getX() - test_center_sizeX, pos_center.getZ() - test_center_sizeZ, "OCEAN_FLOOR", "OCEAN_FLOOR_WG");
                int pos2 = GameUtils.Space.getHeightWorldGen(level_accessor, level_server, chunk_generator, pos_center.getX() - test_center_sizeX, pos_center.getZ() + test_sizeZ, "OCEAN_FLOOR", "OCEAN_FLOOR_WG");
                int pos3 = GameUtils.Space.getHeightWorldGen(level_accessor, level_server, chunk_generator, pos_center.getX() + test_sizeX, pos_center.getZ() - test_center_sizeZ, "OCEAN_FLOOR", "OCEAN_FLOOR_WG");
                int pos4 = GameUtils.Space.getHeightWorldGen(level_accessor, level_server, chunk_generator, pos_center.getX() + test_sizeX, pos_center.getZ() + test_sizeZ, "OCEAN_FLOOR", "OCEAN_FLOOR_WG");

                int height_up = (sizeY - center_sizeY) + Math.abs(pos_center.getY() - pos_original.getY());
                height_up = pos_original.getY() + (int) Math.ceil(height_up * Handcode.Config.surface_smoothness_detection_height_up * 0.01);
                int height_down = center_sizeY + Math.abs(pos_center.getY() - pos_original.getY());
                height_down = pos_original.getY() - (int) Math.ceil(height_down * Handcode.Config.surface_smoothness_detection_height_down * 0.01);
                boolean test1 = (pos_original.getY() < pos1 && height_up > pos1) || (pos_original.getY() >= pos1 && pos1 > height_down);
                boolean test2 = (pos_original.getY() < pos2 && height_up > pos2) || (pos_original.getY() >= pos2 && pos2 > height_down);
                boolean test3 = (pos_original.getY() < pos3 && height_up > pos3) || (pos_original.getY() >= pos3 && pos3 > height_down);
                boolean test4 = (pos_original.getY() < pos4 && height_up > pos4) || (pos_original.getY() >= pos4 && pos4 > height_down);

                return test1 == true && test2 == true && test3 == true && test4 == true;

            }

            return true;

        }

        private static boolean testFallenArea (LevelAccessor level_accessor, ServerLevel level_server, ChunkGenerator chunk_generator, String location, BlockPos pos_center, int[] rotation_mirrored, int fallen_direction, int dead_tree_level) {

            int reduce_trunk = 0;
            int reduce_bough = 0;
            int reduce_branch = 0;
            int reduce_limb = 0;
            int reduce_twig = 0;
            int reduce_sprig = 0;

            // Get Reduce
            {

                try {

                    int[] data = getPartReduce(level_accessor, location, pos_center.getX(), pos_center.getZ(), dead_tree_level);
                    reduce_trunk = data[0];
                    reduce_bough = data[1];
                    reduce_branch = data[2];
                    reduce_limb = data[3];
                    reduce_twig = data[4];
                    reduce_sprig = data[5];

                } catch (Exception exception) {

                    OutsideUtils.exception(new Exception(), exception, "");
                    return false;

                }

            }

            int left_before_test = 0;

            // Get Left
            {

                double total = reduce_trunk + reduce_bough + reduce_branch + reduce_limb + reduce_twig + reduce_sprig;
                left_before_test = (int) Math.ceil(total * 0.5);

            }

            boolean is_only_trunk = dead_tree_level >= 60;
            int distance_skip = (int) Math.floor(left_before_test / 16.0);
        

          
            int distance_skip_test = 0;
            // [P1 修复] 高度检查计数器，用于限制 getHeightWorldGen 调用次数
            int height_check_count = 0;
            BlockPos pos = null;

            int loop = 0;
            short type = 0;
            short posX = 0;
            short posY = 0;
            short posZ = 0;

            for (short scan : Caches.TreeShape.getTreeShapeData(location)) {

                // Get Data
                {

                    loop = loop + 1;

                    if (loop == 1) {

                        type = scan;

                    } else if (loop == 2) {

                        posX = scan;

                    } else if (loop == 3) {

                        posY = scan;

                    } else {

                        posZ = scan;
                        loop = 0;

                    }

                }

                if (loop == 0) {

                    if (OutsideUtils.Mathematics.isNumberStartWith(type, 1) == true) {

                        // Skip Roots
                        {

                            if (OutsideUtils.Mathematics.isNumberStartWith(type, 110) == true || OutsideUtils.Mathematics.isNumberStartWith(type, 111) == true || OutsideUtils.Mathematics.isNumberStartWith(type, 112) == true || OutsideUtils.Mathematics.isNumberStartWith(type, 113) == true) {

                                continue;

                            }

                        }

                        // Dead Tree Reduction
                        {

                            // Basic Style
                            {

                                if (OutsideUtils.Mathematics.isNumberStartWith(type, 120) == true) {

                                    continue;

                                } else if (OutsideUtils.Mathematics.isNumberStartWith(type, 119) == true) {

                                    if (reduce_sprig > 0) {

                                        reduce_sprig = reduce_sprig - 1;

                                    } else {

                                        continue;

                                    }

                                } else if (OutsideUtils.Mathematics.isNumberStartWith(type, 118) == true) {

                                    if (reduce_sprig == 0) {

                                        if (reduce_twig > 0) {

                                            reduce_twig = reduce_twig - 1;

                                        } else {

                                            continue;

                                        }

                                    }

                                } else if (OutsideUtils.Mathematics.isNumberStartWith(type, 117) == true) {

                                    if (reduce_twig == 0) {

                                        if (reduce_limb > 0) {

                                            reduce_limb = reduce_limb - 1;

                                        } else {

                                            continue;

                                        }

                                    }

                                } else if (OutsideUtils.Mathematics.isNumberStartWith(type, 116) == true) {

                                    if (reduce_limb == 0) {

                                        if (reduce_branch > 0) {

                                            reduce_branch = reduce_branch - 1;

                                        } else {

                                            continue;

                                        }

                                    }

                                } else if (OutsideUtils.Mathematics.isNumberStartWith(type, 115) == true) {

                                    if (reduce_branch == 0) {

                                        if (reduce_bough > 0) {

                                            reduce_bough = reduce_bough - 1;

                                        } else {

                                            continue;

                                        }

                                    }

                                }

                            }

                            if (is_only_trunk == true) {

                                // Only Trunk
                                {

                                    if (OutsideUtils.Mathematics.isNumberStartWith(type, 114) == true) {

                                        if (reduce_trunk > 0) {

                                            reduce_trunk = reduce_trunk - 1;

                                        } else {

                                            continue;

                                        }

                                    }

                                }

                            }

                        }

                        if (left_before_test > 0) {

                            left_before_test = left_before_test - 1;

                        } else {

                            // Distance Skip
                            {

                                if (distance_skip > 0) {

                                    if (distance_skip_test > 0) {

                                        distance_skip_test = distance_skip_test - 1;
                                        continue;

                                    } else {

                                        distance_skip_test = distance_skip;

                                    }

                                }

                            }

        

          
                            pos = new BlockPos(posX, posY, posZ);
                            pos = OutsideUtils.convertPosRotationMirrored(pos, rotation_mirrored);
                            pos = OutsideUtils.convertPosFallen(pos, fallen_direction);
                            pos = pos.offset(pos_center.getX(), pos_center.getY(), pos_center.getZ());

                            // [P1 修复] 超过最大高度检查次数时停止检测，避免大树形状导致大量噪声计算卡顿
                            if (height_check_count >= Handcode.Config.test_fallen_area_max_height_checks) {

                                return false;

                            }

                            height_check_count = height_check_count + 1;

                            if (pos.getY() <= GameUtils.Space.getHeightWorldGen(level_accessor, level_server, chunk_generator, pos.getX(), pos.getZ(), "OCEAN_FLOOR_WG", "OCEAN_FLOOR_WG")) {

                                return true;

                            }

                        }

                    }

                }

            }

            return false;

        }

    }

    private static class LeafLitterGeneration {

        // [LMax Fix V7] 替换为线程安全容器，解决 ForkJoinPool 并发修改异常
        private static final Map<ChunkPos, Map<BlockPos, BlockState>> cache_locations = new java.util.concurrent.ConcurrentHashMap<>();

        private static void add (ChunkPos chunk_pos, BlockPos pos, BlockState block) {
            cache_locations.computeIfAbsent(chunk_pos, create -> new java.util.concurrent.ConcurrentHashMap<>()).put(pos, block);
        }

        private static void  place (LevelAccessor level_accessor, ServerLevel level_server, ChunkGenerator chunk_generator, ChunkPos chunk_pos) {

            {

                Map<BlockPos, BlockState> data = cache_locations.get(chunk_pos);

                if (data == null) {

                    return;

                }

                int height_motion = 0;

                for (Map.Entry<BlockPos, BlockState> entry : data.entrySet()) {

                    height_motion = GameUtils.Space.getHeightWorldGen(level_accessor, level_server, chunk_generator, entry.getKey().getX(), entry.getKey().getZ(), "MOTION_BLOCKING_NO_LEAVES", "WORLD_SURFACE_WG");

                    if (height_motion < entry.getKey().getY()) {

                        LeafLitter.create(level_accessor, level_server, entry.getKey().atY(height_motion), entry.getValue(), false);

                    }

                }

                cache_locations.remove(chunk_pos);

            }

        }

    }

    // [方向A重构] 新增 PendingBlocks 类：按 chunk 分组缓存方块，解决跨 chunk 写入时序问题
    private static class PendingBlocks {

        // 线程安全容器：ChunkPos -> (BlockPos -> BlockState)
        private static final Map<ChunkPos, Map<BlockPos, BlockState>> cache_blocks = new java.util.concurrent.ConcurrentHashMap<>();

        // 添加方块到缓存（按方块所在 chunk 分组）
        private static void add (BlockPos pos, BlockState block) {
            ChunkPos chunk_pos = new ChunkPos(pos);
            cache_blocks.computeIfAbsent(chunk_pos, create -> new java.util.concurrent.ConcurrentHashMap<>()).put(pos, block);
        }

        // 从缓存中拉取并写入指定 chunk 的所有方块（由各 chunk 的 start() 调用）
        private static void place (LevelAccessor level_accessor, ChunkPos chunk_pos) {

            Map<BlockPos, BlockState> data = cache_blocks.get(chunk_pos);

            if (data == null) {
                return;
            }

            for (Map.Entry<BlockPos, BlockState> entry : data.entrySet()) {

                GameUtils.Tile.set(level_accessor, entry.getKey(), entry.getValue(), true);

            }

            // 写入完成后清除该 chunk 的缓存
            cache_blocks.remove(chunk_pos);

        }

        // [方向A重构] 强制补写方法：用于 DeferredQueue 补写已 FULL 的 chunk
        private static void placeForced (ServerLevel level_server, ChunkPos chunk_pos) {

            Map<BlockPos, BlockState> data = cache_blocks.get(chunk_pos);

            if (data == null) {
                return;
            }

            for (Map.Entry<BlockPos, BlockState> entry : data.entrySet()) {

                // 强制写入：使用 ServerLevel.setBlock 而非 GameUtils.Tile.set
                level_server.setBlock(entry.getKey(), entry.getValue(), 3);

            }

            cache_blocks.remove(chunk_pos);

        }

    }

    private static class Function {

        // [LMax Fix V7] 替换为线程安全容器，解决 ForkJoinPool 并发修改异常
        private static final Map<ChunkPos, Map<BlockPos, List<String>>> cache_functions = new java.util.concurrent.ConcurrentHashMap<>();

        private static void add (ChunkPos chunk_pos, BlockPos pos, String path) {
            cache_functions.computeIfAbsent(chunk_pos, create -> new java.util.concurrent.ConcurrentHashMap<>())
                           .computeIfAbsent(pos, create -> java.util.Collections.synchronizedList(new ArrayList<>()))
                           .add(path);
        }

        private static void run (LevelAccessor level_accessor, ServerLevel level_server, ChunkPos chunk_pos) {

            {

                Map<BlockPos, List<String>> data = cache_functions.get(chunk_pos);

                if (data == null) {

                    return;

                }

                for (Map.Entry<BlockPos, List<String>> entry : data.entrySet()) {

                    for (String get : entry.getValue()) {

                        TXTFunction.run(level_accessor, level_server, entry.getKey(), get, false);

                    }

                }

                cache_functions.remove(chunk_pos);

            }

        }

    }

}