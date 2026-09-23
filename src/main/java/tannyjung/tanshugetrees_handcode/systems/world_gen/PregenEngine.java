package tannyjung.tanshugetrees_handcode.systems.world_gen;

// [LMax Fix V55 刀U2] P0-R2 预生成引擎: 玩家中心差分 → region 粒度计算任务(调度) [长期记忆: 167,168,169]
// 手术单: P0-R2手术单.md §3.4 —— Observer 差分产物接进计算, 任务粒度=region, 计算复用
//   TreeLocation.pregenComputeRegion(getData 链纯计算已字面审计 100%)。
// 职责边界(计算与调度解耦): 本类只做调度 —— mode 门/claims 快标记/computed 台账/去重入队/软背压/epoch。
//   零计算逻辑(计算全在 pregenComputeRegion), 零 I/O(冲刷=§3.5=d 每树即冲自动继承, 引擎无冲刷代码)。
// 共存安全网(手术单 §4): player_center 试验期旧踩踏链不关 —— 引擎任务失败=不标记 computed=
//   旧链 ChunkEvent.Load 到达时自然补算; 重叠双写=采样骰子同种子确定性+bin 读侧 map 去重; 最坏退化成现状。
// 生命周期: Observer AboutToStart → reset(epoch++/清台账与队列/in_flight 清零); straggler 任务开工前验
//   epoch 丢弃(跨世界投毒免疫——旧链在途写穿洞的引擎侧防线; 旧链洞为既有行为已报备待裁决)。
// in_flight 清零改判: 关服窗口 submitTreeGen 拒绝时吞任务(drop 无 finally)=计数器永久虚高=重启后引擎
//   永久饿死; 反向代价 straggler finally 递减产生负值——计数器仅用于 <max 上限比较, 负值无害(多跑不多丢)。
//   drop 泄漏(永久瘫) > 负漂移(瞬时超发), 故 reset 清零。[原判决③据此翻转]
// 线程契约: offer=主线程(Observer 慢路径, chunk/维度变化瞬间); RegionTask.run=池线程; 台账全
//   ConcurrentHashMap; 软背压 check-then-act 非原子容忍瞬时超发(池 12 线程吸收, 超发只多算不多丢)。
// 内存预算: computed 128B/region, 百万 chunk 级探索 < 2MB(与 observed 同量级, 手术单 §3.3)。

import net.minecraft.server.level.ServerLevel;
import tannyjung.tanshugetrees_core.Core;
import tannyjung.tanshugetrees_core.ExprEngine;
import tannyjung.tanshugetrees_core.game.EventCenter;
import tannyjung.tanshugetrees_handcode.Handcode;
import java.util.BitSet;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PregenEngine {

    // ===== computed 台账: dimension → (regionKey → 1024-bit) =====
    // regionKey 与 TreeLocation/Observer 严格同构: "<dim>,<regionX>,<regionZ>";
    // bit = (chunkX&31)<<5 | (chunkZ&31)。语义 = "该 region 已由引擎算完"(调度粒度: 不再 offer;
    // 非 1024 chunk 全有数据——那是 region_scan_percent 采样骰子的事, 与旧链 claims TRUE 同语义口径)。
    // 与 Observer.observed 刻意分离(防预谎: U1 观察过 ≠ U2 算过)。
    private static final Map<String, Map<String, BitSet>> computed_ledger = new ConcurrentHashMap<>();

    // ===== 待算队列 + region 去重(offer 入队登记, pump 取出移除; 失败/丢弃后可重新 offer) =====
    private static final Queue<RegionTask> pending = new ConcurrentLinkedQueue<>();
    private static final Map<String, Boolean> pending_regions = new ConcurrentHashMap<>();

    private static final AtomicInteger in_flight = new AtomicInteger();
    private static final AtomicInteger epoch = new AtomicInteger();

    // ===== 窗口半径表达式(缺省 "v+8" = U1 常数语义; 变量 v=服务器视距, 结果=完整半径) =====
    // lazy 编译 + source 对比热更 + 编译失败/非有限/负值回退 v+8, resolveBudgetMs 同款先例 [长期记忆: 149]。
    // eval_vars 主线程串行复用(Observer 慢路径), 零高频分配。
    private static volatile ExprEngine.Program radiusProgram = null;
    private static volatile String radiusSource = null;
    private static final double[] radius_eval_vars = new double[1];
    private static final AtomicBoolean radius_warned = new AtomicBoolean();

    // ===== 入口: Observer 慢路径(主线程)调用, 差分产物 = 本窗口出现新 chunk 的 region 全键集 =====
    public static void offer (String dimension, ServerLevel level, Collection<String> fullRegionKeys) {
        // mode 门: 缺省 "region" = 引擎休眠(零行为变化, U1 观察日志照打; 热重载翻 player_center 即激活)
        if (!"player_center".equals(Handcode.Config.pregen_mode)) return;
        Map<String, BitSet> dim_computed = computed_ledger.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        for (String regionKey : fullRegionKeys) {
            if (dim_computed.containsKey(regionKey)) continue; // 引擎已算过
            // claims-TRUE 快标记: 旧链本会话已扫完的 region 直接记账免算(翻模式不重算已扫区)
            if (TreeLocation.isRegionScanComplete(regionKey)) {
                dim_computed.put(regionKey, allBits());
                continue;
            }
            // pending 去重: putIfAbsent 返回非 null = 已在队列(跳过)
            if (pending_regions.putIfAbsent(regionKey, Boolean.TRUE) != null) continue;
            String[] parts = regionKey.split(","); // "<dim>,<rx>,<rz>", dim 含 - 不含 , → split 安全
            pending.add(new RegionTask(regionKey, dimension,
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), level, epoch.get()));
        }
        pump(); // 双站点之一: 主线程
        if (Core.log_tree_location) {
            System.out.println("[THT-DEBUG] [U2] offer: dim=" + dimension + " regions=" + fullRegionKeys.size()
                + " pending=" + pending.size() + " inflight=" + in_flight.get());
        }
    }

    // ===== 世界切换清场(Observer AboutToStart 调用; 刀K 防线推广) =====
    public static void reset () {
        epoch.incrementAndGet(); // straggler 丢弃令牌(在途任务开工前验, 不投毒新存档)
        pending.clear();
        pending_regions.clear();
        computed_ledger.clear();
        in_flight.set(0); // [改判] drop 泄漏饿死 > 负漂移瞬时超发, 理由见类头注释
    }

    // ===== 泵: 双站点(offer 尾=主线程 / 任务 finally=池线程)接力, 零定时器零轮询 =====
    private static void pump () {
        // 软背压: check-then-act 非原子, 双站点并发下容忍瞬时超发(池吸收, 超发只多算不多丢)
        RegionTask task;
        while (in_flight.get() < Handcode.Config.pregen_max_inflight && (task = pending.poll()) != null) {
            pending_regions.remove(task.regionKey); // 出队即解除去重登记(失败后可重新 offer)
            in_flight.incrementAndGet();
            EventCenter.Server.submitTreeGen(task); // 守卫复用: 拒绝吞/关服窗口防崩 [长期记忆: 167]
        }
    }

    // ===== region 全 1024 bit 一把置(调度粒度记账) =====
    private static BitSet allBits () {
        BitSet bits = new BitSet(1024);
        bits.set(0, 1024);
        return bits;
    }

    // ===== 窗口半径求值: Observer 慢路径(主线程串行)调用 =====
    public static int resolveRadius (ServerLevel level) {
        int view_distance = level.getServer().getPlayerList().getViewDistance();
        String source = Handcode.Config.pregen_radius_expr;
        ExprEngine.Program program = radiusProgram;
        if (program == null || !source.equals(radiusSource)) {
            try {
                program = ExprEngine.compile(source, new String[]{"v"});
            } catch (RuntimeException e) { // SyntaxException extends RuntimeException; 失败保持 null 走回退
                program = null;
            }
            radiusSource = source; // 编译失败也记 source(防同错每 tick 重编译); 热更换串才重试
            radiusProgram = program;
        }
        if (program != null) {
            radius_eval_vars[0] = view_distance;
            double v = program.eval(radius_eval_vars);
            if (Double.isFinite(v) && v >= 0) return (int) v; // 上限不钳=策略权在表达式作者
        }
        if (radius_warned.compareAndSet(false, true)) {
            Core.logger.warn("[THT][U2] pregen_radius_expr invalid (compile/eval/negative), fallback to v+8: '" + source + "'");
        }
        return view_distance + 8; // U1 原常数语义(Observer.RADIUS_MARGIN 退役后的单一事实源)
    }

    // ===== 计算任务: 池线程执行, epoch 免疫跨世界 straggler =====
    private static final class RegionTask implements Runnable {
        final String regionKey; final String dimension;
        final int regionX; final int regionZ;
        final ServerLevel level; final int born_epoch;
        RegionTask (String key, String dim, int rx, int rz, ServerLevel lvl, int ep) {
            regionKey = key; dimension = dim; regionX = rx; regionZ = rz; level = lvl; born_epoch = ep;
        }

        @Override
        public void run () {
            try {
                // epoch 验证: 世界切换 reset 已 epoch++, 旧任务直接丢弃(持旧 level 引用一并废弃)
                if (born_epoch != epoch.get()) return;
                try {
                    boolean ok = TreeLocation.pregenComputeRegion(level, dimension, regionX, regionZ);
                    if (ok) {
                        computed_ledger.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                                .put(regionKey, allBits());
                    }
                    // 失败/异常: 不标记 computed = 共存安全网, 旧链到达自然补算
                } catch (Throwable t) {
                    Core.logger.error("[THT][U2] pregen task failed (region NOT marked, legacy chain will recompute): " + regionKey, t);
                }
            } finally {
                in_flight.decrementAndGet();
                pump(); // 双站点之二: 池线程接力
            }
        }
    }
}
