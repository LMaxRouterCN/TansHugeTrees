package tannyjung.tanshugetrees_handcode.systems.world_gen;

// [LMax Fix V55 刀U2] P0-R2 预生成引擎: 玩家中心差分 → region 粒度计算任务(调度) [长期记忆: 167,168,169]
// 手术单: P0-R2手术单.md §3.4 —— Observer 差分产物接进计算, 任务粒度=region, 计算复用
//   TreeLocation.pregenComputeRegion(getData 链纯计算已字面审计 100%)。
// 职责边界(计算与调度解耦): 本类只做调度 —— claims 快标记(mode 门刀Z退役)/computed 台账/去重入队/软背压/epoch。
//   零计算逻辑(计算全在 pregenComputeRegion), 零 I/O(冲刷=§3.5=d 每树即冲自动继承, 引擎无冲刷代码)。
// [刀Z] legacy 旧链已物理退场, 本引擎为唯一计算链。任务失败=不标记 computed —— 观察者后续差分
//   (玩家移动/窗口变化)自然 re-offer 补算; 无兜底链。采样骰子种子确定性保留(重叠双写场景已消失)。
// 生命周期: Observer AboutToStart → reset(epoch++/清台账与队列/in_flight 清零); straggler 任务开工前验
//   epoch 丢弃(跨世界投毒免疫——仅防引擎自身在途任务; 旧链在途写穿洞已随刀Z退场消失)。
// in_flight 清零改判: 关服窗口 submitTreeGen 拒绝时吞任务(drop 无 finally)=计数器永久虚高=重启后引擎
//   永久饿死; 反向代价 straggler finally 递减产生负值——计数器仅用于 <max 上限比较, 负值无害(多跑不多丢)。
//   drop 泄漏(永久瘫) > 负漂移(瞬时超发), 故 reset 清零。[原判决③据此翻转]
// 线程契约: offer=主线程(Observer 慢路径, chunk/维度变化瞬间); RegionTask.run=池线程; 台账全
//   ConcurrentHashMap; 软背压 check-then-act 非原子容忍瞬时超发(池 12 线程吸收, 超发只多算不多丢)。
// 内存预算: claims 每 region 一 entry(CHM Boolean), 百万 chunk 级探索远低于 2MB(与 observed 同量级, 手术单 §3.3; U3 统一后无独立引擎台账)。

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tannyjung.tanshugetrees_core.Core;
import tannyjung.tanshugetrees_core.ExprEngine;
import tannyjung.tanshugetrees_core.game.EventCenter;
import tannyjung.tanshugetrees_handcode.Handcode;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PregenEngine {

    // [U3] [长期记忆: 163] 引擎侧计算台账退役: 调度去重统一至 TreeLocation.region_scan_claims
    // (region 级布尔, 语义 = "该 region 已由引擎算完", 写点唯一 = pregenComputeRegion 尾部;
    // 非 1024 chunk 全有数据——那是 region_scan_percent 采样骰子的事, 同旧语义口径;
    // regionKey 同构 "<dim>,<regionX>,<regionZ>" 契约不变)。与 Observer.observed 的防预谎
    // 分离保持不变(observed = 观察过, claims = 算完, 仍两本)。双账本+抄写桥冗余根除。

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

    // [刀X] [长期记忆: 178] 玩家坐标快照组(offer 主线程写 / nextTask 池线程读): 三 volatile 非原子,
    // 极端交错仅排序启发式受害无正确性影响; dim null = 无快照(极值模式退化 fifo)。
    private static volatile String snapshot_dim = null;
    private static volatile int snapshot_px = 0;
    private static volatile int snapshot_pz = 0;

    // ===== 入口: Observer 慢路径(主线程)调用, 差分产物 = 本窗口出现新 chunk 的 region 全键集 =====
    public static void offer (String dimension, ServerLevel level, Collection<String> fullRegionKeys) {
        // [刀X] [长期记忆: 178] 玩家坐标快照(offer 主线程串行写): 首位玩家 = 单人语义; 空列表不更新
        // (保留旧快照, 比退化更平滑)。池线程 nextTask 读(三 volatile 非原子组, 极端交错仅排序
        // 启发式受害, 无正确性影响)。
        if (!level.players().isEmpty()) {
            ServerPlayer p = level.players().get(0);
            snapshot_dim = dimension;
            snapshot_px = p.blockPosition().getX();
            snapshot_pz = p.blockPosition().getZ();
        }
        for (String regionKey : fullRegionKeys) {
            // [U3] [长期记忆: 163] 去重账本统一至 TreeLocation.region_scan_claims(claims TRUE =
            // 本会话已算完, 写点唯一 = pregenComputeRegion 尾部): 已算过免入队, 抄写桥退役。
            if (TreeLocation.isRegionScanComplete(regionKey)) continue; // 引擎已算过
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
        // [U3] [长期记忆: 163] 引擎台账清场退役: claims(统一后唯一账本)的跨世界清场走
        // EventCenter.AboutToStart → TreeLocation.clearWorldState, 本处不再有独立台账。
        in_flight.set(0); // [改判] drop 泄漏饿死 > 负漂移瞬时超发, 理由见类头注释
    }

    // ===== 泵: 双站点(offer 尾=主线程 / 任务 finally=池线程)接力, 零定时器零轮询 =====
    private static void pump () {
        // 软背压: check-then-act 非原子, 双站点并发下容忍瞬时超发(池吸收, 超发只多算不多丢)
        // [刀X] [长期记忆: 178] 出队改 nextTask(策略外壳化: fifo 原语义 / 极值模式见 nextTask 注释)
        RegionTask task;
        while (in_flight.get() < Handcode.Config.pregen_max_inflight && (task = nextTask()) != null) {
            pending_regions.remove(task.regionKey); // 出队即解除去重登记(失败后可重新 offer)
            in_flight.incrementAndGet();
            EventCenter.Server.submitTreeGen(task); // 守卫复用: 拒绝吞/关服窗口防崩 [长期记忆: 167]
        }
    }

    // [刀X] [长期记忆: 178] 出队策略(计算与调度解耦: 策略全在本外壳, 计算核心零改动):
    // fifo = poll 原语义(缺省); nearest/farthest = CLQ 弱一致遍历选极值 + 原子 remove(失败 = 并发
    // 先取, 本拍空手由双站点接力重泵收敛, 无饿死); 非法值/无快照一律退化 fifo。
    // 维度外任务两种极值模式均排尾(玩家不在场, 先做零收益; 不饿死: 无同维度任务时照常出队);
    // 平局保先见者 = CLQ 插入序 = FIFO 次序。
    private static RegionTask nextTask () {
        String mode = Handcode.Config.pregen_task_priority;
        if (!"nearest".equals(mode) && !"farthest".equals(mode)) {
            return pending.poll(); // fifo / 非法值: 原语义
        }
        String dim = snapshot_dim;
        if (dim == null) {
            return pending.poll(); // 无快照退化 fifo(极值无基准)
        }
        boolean nearest = "nearest".equals(mode);
        long px = snapshot_px, pz = snapshot_pz;
        RegionTask best = null;
        long best_score = 0;
        for (RegionTask t : pending) { // CLQ 弱一致遍历: 漏看的新任务由下轮双站点接力
            long score = distanceScore(t, dim, px, pz, nearest);
            if (best == null || (nearest ? score < best_score : score > best_score)) {
                best = t;
                best_score = score; // 严格不等: 平局保先见者
            }
        }
        if (best == null) {
            return null;
        }
        return pending.remove(best) ? best : null; // 原子摘牌; 失败 = 并发先取, 本拍空手
    }

    // [刀X] [长期记忆: 178] region 中心(rx*512+256, 512 块/region)到玩家的平方距离(单调等价免 sqrt)。
    // 维度外: 值域外打分排尾 —— nearest 给 MAX_VALUE(永不最小), farthest 给 MIN_VALUE(永不最大)。
    private static long distanceScore (RegionTask t, String dim, long px, long pz, boolean nearest) {
        if (!t.dimension.equals(dim)) {
            return nearest ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        long dx = (t.regionX * 512L + 256L) - px;
        long dz = (t.regionZ * 512L + 256L) - pz;
        return dx * dx + dz * dz;
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
                    // [U3] [长期记忆: 163] pregenComputeRegion 尾部已置 claims TRUE(唯一事实源),
                    // 引擎侧重复记账退役。
                    TreeLocation.pregenComputeRegion(level, dimension, regionX, regionZ);
                    // 失败/异常: 不置 claims TRUE(写点仅在 pregenComputeRegion 尾部, 早退/异常路径不置) → 观察者差分 re-offer 补算(刀Z后唯一恢复路径)
                } catch (Throwable t) {
                    Core.logger.error("[THT][U2] pregen task failed (region NOT marked, observer diff re-offer will recompute): " + regionKey, t);
                }
            } finally {
                in_flight.decrementAndGet();
                pump(); // 双站点之二: 池线程接力
            }
        }
    }
}
