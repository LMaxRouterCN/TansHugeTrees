package tannyjung.tanshugetrees_core.game;

// [LMax Fix V49 刀B2] 按区块分组的延迟落块缓存（core 层原子原语） [长期记忆: 078/086]
// 病理：GameUtils.Tile.set 的 worldgen 分支曾裸调 sl.getChunk()（=getChunk(FULL,load=true) 强制 join），
// 12 条树线程洪峰期向区块管线塞 272 个阻塞 join 块，主线程同队挨饿（微冻结残存主成分）。
// 职责边界（最小执行单元）：只做容器原语 add（存写入意图）/ take（原子取走）/ size（诊断）。
// 何时冲刷、怎么写块（Tile.set 直写 / setBlock flag=4）全部留在 handcode 调用方——计算与调度解耦。
// 冲刷闭环由既有事件链承担：chunk Load 事件 → TreePlacer.flushPendingBlocks（A3 路径）→ PendingBlocks.place。
// 线程契约：ConcurrentHashMap 原子；take=remove 原子取走，取走后并发 add 的新块进新容器留存
// （对比旧 get→写→remove 的丢块窗口=严格改进，零丢失）。

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import tannyjung.tanshugetrees_core.Core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class DeferredBlocks {

    // ChunkPos -> (BlockPos -> BlockState)：写入意图缓存（键=目标方块所在 chunk）
    private static final Map<ChunkPos, Map<BlockPos, BlockState>> cache_blocks = new ConcurrentHashMap<>();

    // [LMax Debug V39 语义保留] 诊断计数器：追踪入缓存方块总数（键控 Core.log_pending_blocks）
    private static final AtomicInteger add_count = new AtomicInteger(0);

    // 存入一个写入意图（GameUtils.Tile.set 未加载分支 / handcode 侧 PendingBlocks.add 调用）
    public static void add (BlockPos pos, BlockState block) {
        ChunkPos chunk_pos = new ChunkPos(pos);
        cache_blocks.computeIfAbsent(chunk_pos, create -> new ConcurrentHashMap<>()).put(pos, block);
        // [LMax Debug V39 语义保留] 诊断：追踪方块入缓存总数（措辞与旧 PendingBlocks.add 一致，日志判读连续）
        if (Core.log_pending_blocks) {
            long total = add_count.incrementAndGet();
            if (total % 100 == 0) System.out.println("[THT-DEBUG] PendingBlocks.add() total: " + total + " blocks, last target chunk: " + chunk_pos);
        }
    }

    // 原子取走指定 chunk 的全部缓存（冲刷方调用；null=无缓存）
    public static Map<BlockPos, BlockState> take (ChunkPos chunk_pos) {
        return cache_blocks.remove(chunk_pos);
    }

    // 诊断用：当前缓存 chunk 数（保持旧 place 日志措辞连续）
    public static int size () {
        return cache_blocks.size();
    }
}
