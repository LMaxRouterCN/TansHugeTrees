package tannyjung.tanshugetrees_handcode.systems.world_gen;

// [LMax Fix V55 刀U1] P0-R2 树预生成换脑第一刀: 玩家中心窗口观察者(纯增量, 零接线) [长期记忆: 157]
// 手术单: P0-R2手术单.md §5 刀U1 —— 玩家 tick 订阅 + 窗口差分 + 台账, 只打日志不接线, 零风险纯增量。
// 实施偏差(已报备): 挂点 PlayerTickEvent → LevelTickEvent.END + ServerLevel + players() 遍历
//   (理由: 全库零 PlayerTickEvent 现踪=映射风险; TanshugetreesModVariables.onWorldTick 现成样板;
//    LevelTick 天然携带 dimension = 台账 per-dimension 刚需; 每玩家每 tick 防抖语义与手术单 §3.1 等同)。
// 职责边界(最小执行单元): 只观察 —— 玩家 chunk 防抖 → 窗口差分 → observed 台账记账 → 日志。
//   不提交任务 / 不碰 TreeLocation / 不写盘 / 不接线任何现有链路。U2 起才把差分产物接进计算。
// 台账语义: observed 位图 = "本 JVM 会话内该 chunk 曾进入任一玩家窗口"(纯观察记录)。
//   与 U2 的 computed 台账("差分产物已提交计算")刻意分离 —— U1 若预写 computed 会让 U2 误跳未算 chunk(预谎);
//   两图并存: 128B/region × 2, 百万 chunk 级探索 < 2MB(手术单 §3.3 内存预算内)。
// 生命周期: ServerAboutToStart 自清(刀K 世界55 静态池防线推广 —— 观察者自有生命周期, 不依赖 EventCenter 聚合);
//   玩家退出后 trace 残留(数十字节/人)无害, 新世界 AboutToStart 全清(Trace 持 ServerLevel 引用一并释放)。
// 线程契约: LevelTick END = 服务器主线程串行回调, 本类全部状态仅主线程读写;
//   ConcurrentHashMap 为 U2 树线程读预留前瞻兼容(非当前必需, 语义零差)。
// 内存契约: 稳态零分配 —— 每玩家每 tick 字段读 + 移位 + 引用比较; 仅 chunk 变化时差分(位测试 O(1) × 窗口)。

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import tannyjung.tanshugetrees_core.Core;
import tannyjung.tanshugetrees_core.game.GameUtils;

import java.util.BitSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber
public class PregenObserver {

    // ===== 观察台账: dimension → (regionKey → 1024-bit observed 位图) =====
    // regionKey 与 TreeLocation 严格同构: "<dim>,<regionX>,<regionZ>"
    //   (dim = GameUtils.Space.getDimensionID(level).replace(":", "-"), 与 EventCenter.eventChunkLoaded 同法);
    // bit 索引 = (chunkX & 31) << 5 | (chunkZ & 31)(region = 32×32 chunk, V38 既有坐标约定)。
    // 多玩家窗口并集自然形成(手术单 §3.1): 各自差分写同一张图, 无需合并步骤。
    private static final Map<String, Map<String, BitSet>> observed_ledger = new ConcurrentHashMap<>();

    // ===== 玩家防抖: UUID → 上次采样; 不变 → 每 tick 一次比较直接跳过(主线程无感) =====
    // Trace.level 引用比较 = 维度身份零分配判定(玩家跨维度 = 引用不等 → 触发重差分);
    // 持 ServerLevel 引用不构成跨世界泄漏: 世界切换由 AboutToStart 全清兜底(刀K 先例推广)。
    private static final Map<UUID, Trace> player_traces = new ConcurrentHashMap<>();

    // 窗口视距外圈数: U1 固定默认式 v+8 的常数项; U2 起由 pregen_radius_expr 表达式接管(手术单 §3.2, 一引擎两张表)
    private static final int RADIUS_MARGIN = 8;

    private static final class Trace {
        final ServerLevel level; final int chunkX; final int chunkZ;
        Trace (ServerLevel l, int x, int z) { level = l; chunkX = x; chunkZ = z; }
    }

    @SubscribeEvent
    public static void onServerAboutToStart (ServerAboutToStartEvent event) {
        // [刀K 世界55 防线推广] 同 JVM 世界切换: 观察台账 + 玩家 trace 全清(观察者自有生命周期, 零接线)
        observed_ledger.clear();
        player_traces.clear();
    }

    @SubscribeEvent
    public static void onLevelTick (TickEvent.LevelTickEvent event) {
        // 仅 END 相位(窗口判定取稳态) + 服务端维度(TanshugetreesModVariables.onWorldTick 同款守卫)
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;
        if (level.players().isEmpty()) return;

        for (ServerPlayer player : level.players()) {
            // 稳态零分配: Entity 字段读 + 移位(不经 BlockPos/ChunkPos 对象分配)
            int chunk_x = Mth.floor(player.getX()) >> 4;
            int chunk_z = Mth.floor(player.getZ()) >> 4;
            Trace last = player_traces.get(player.getUUID());
            if (last != null && last.level == level && last.chunkX == chunk_x && last.chunkZ == chunk_z) continue;

            // 慢路径(仅 chunk/维度变化瞬间): 半径与维度串仅在此时求值, 稳态零字符串分配
            int radius = level.getServer().getPlayerList().getViewDistance() + RADIUS_MARGIN;
            String dimension = GameUtils.Space.getDimensionID(level).replace(":", "-");
            player_traces.put(player.getUUID(), new Trace(level, chunk_x, chunk_z));
            diffWindow(dimension, chunk_x, chunk_z, radius);
        }
    }

    // ===== 窗口差分: Chebyshev 方形窗口(玩家中心 ±R chunk)逐 chunk 查台账, 未观察过 = 增量 =====
    // regionKey/BitSet 双重缓存: 同 region 内 1024 chunk 复用同一 key 串与位图(每差分 ≤ ~9 次串拼接)
    private static void diffWindow (String dimension, int center_x, int center_z, int radius) {
        long time_start = System.nanoTime();
        Map<String, BitSet> dim_ledger = observed_ledger.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        int count_new = 0;
        int count_window = 0;
        int last_region_x = Integer.MIN_VALUE;
        int last_region_z = Integer.MIN_VALUE;
        String region_key = null;
        BitSet region_bits = null;
        for (int x = center_x - radius; x <= center_x + radius; x++) {
            for (int z = center_z - radius; z <= center_z + radius; z++) {
                count_window++;
                int region_x = x >> 5;
                int region_z = z >> 5;
                if (region_x != last_region_x || region_z != last_region_z) {
                    region_key = dimension + "," + region_x + "," + region_z;
                    region_bits = dim_ledger.computeIfAbsent(region_key, k -> new BitSet(1024));
                    last_region_x = region_x;
                    last_region_z = region_z;
                }
                int bit = ((x & 31) << 5) | (z & 31);
                if (!region_bits.get(bit)) {
                    region_bits.set(bit);
                    count_new++;
                }
            }
        }
        // 键控日志(手术单 §5 U1): 观察期采集 U2 接线前的差分实测(窗口大小/增量/耗时)
        if (Core.log_tree_location) {
            System.out.println("[THT-DEBUG] [U1] window diff: dim=" + dimension
                + " center=(" + center_x + "," + center_z + ") R=" + radius
                + " (v+" + RADIUS_MARGIN + ") +" + count_new + " new / " + count_window
                + " window | ledger regions=" + dim_ledger.size()
                + " in " + ((System.nanoTime() - time_start) / 1000) + "us");
        }
    }
}
