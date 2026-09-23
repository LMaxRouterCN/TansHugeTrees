package tannyjung.tanshugetrees_core.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import tannyjung.tanshugetrees_core.Core;
import tannyjung.tanshugetrees_core.game.world_gen.WorldGenStepEnd;
import tannyjung.tanshugetrees_core.outside.CustomPackOrganizing;
import tannyjung.tanshugetrees_core.outside.TXTFunction;
import tannyjung.tanshugetrees_core.outside.TannyPackManager;
import tannyjung.tanshugetrees_handcode.systems.Commands;
import tannyjung.tanshugetrees_handcode.systems.Overlays;

public class EventCenter {
    
    @EventBusSubscriber({Dist.CLIENT})
    public static class Client {

        @SubscribeEvent(priority = EventPriority.NORMAL)
        public static void eventMenu (ScreenEvent.Render.Post event) {

            Screen screen = event.getScreen();
            GuiGraphics graphic = event.getGuiGraphics();
            int screen_width = event.getScreen().width;
            int screen_height = event.getScreen().height;
            Overlays.eventMenu(screen, graphic, screen_width, screen_height);

        }

        @SubscribeEvent(priority = EventPriority.NORMAL)
        public static void eventInGame (RenderGuiEvent.Post event) {

            if (Minecraft.getInstance().options.hideGui == true) {

                return;

            }

            GuiGraphics graphic = event.getGuiGraphics();
            int screen_width = event.getGuiGraphics().guiWidth();
            int screen_height = event.getGuiGraphics().guiHeight();
            Overlays.eventInGame(graphic, screen_width, screen_height);

            if (Core.developer_mode == true) {

                OverlayMaker.createText(graphic, screen_width, screen_height, "top-left", 8, 58, 0.75, false, "§9Delayed Command = " + TXTFunction.count_delayed_command);

            }

        }

    }
    
    @EventBusSubscriber
    public static class Server {

        private static boolean first_player_joined = false;

        @SubscribeEvent
        public static void eventWorldAboutToStart (ServerAboutToStartEvent event) {

            // [LMax Fix V50.2 刀J] [长期记忆: 105] 静态池复活：上一世界 ServerStopping 已 shutdown 本池
            // （单机整合服务器同 JVM 多世界进出的常态），新服务器接管前复活；同时复位停服窗口 warn-once 标志。
            if (TREE_GEN_EXECUTOR.isShutdown() == true) {
                TREE_GEN_EXECUTOR = createTreeGenExecutor();
            }
            tree_gen_rejected_logged = false;

            // [LMax Fix V50.4 刀K] [长期记忆: 104] 世界切换静态池清场：同 JVM 换世界零树击杀链三段根治——
            // ① processed_chunks 无维度键（同坐标 add=false 整链短路）② region_scan_claims TRUE 残留
            // （region 判"已扫"跳过）③ Data future 跨世界投毒（旧世界解析结果命中新世界）。
            // 时机判决：AboutToStart = 旧任务死透的 menu 间隙（人类时间尺度）；理论外 straggler 写旧
            // 路径 = 不污染新存档（注释记录）。生成链经各类聚合入口清场；config/字典层由 Core.restart
            // → CacheManager.clear 各清各层（V16 前哨战战线闭环）。
            processed_chunks.clear();
            tannyjung.tanshugetrees_handcode.systems.world_gen.TreeLocation.clearWorldState();
            tannyjung.tanshugetrees_handcode.systems.world_gen.TreePlacer.clearWorldState();

            String path_world = event.getServer().getWorldPath(new LevelResource(".")).toString();
            Core.path_world_core = path_world + "/data/tannyjung/" + Core.data_structure_version_core;
            Core.path_world_mod = path_world + "/data/" + Core.mod_id;

            Core.DataMigration.run(true);
            Core.restart(null, false, true);

        }
        @SubscribeEvent
        public static void eventWorldStarted (ServerStartedEvent event) {

            ServerLevel level_server = event.getServer().overworld();
            // [LMax Fix V16] 动态设置 path_world_mod 为当前存档路径，确保 dictionary.txt 生成在存档内部，彻底解决跨存档字典污染
            Core.path_world_mod = event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toString();

            // [LMax Fix V50 刀F] [长期记忆: 095] 会话开始清空放置门等待表（跨存档/重进世界的陈旧登记防御）
            tannyjung.tanshugetrees_handcode.systems.world_gen.TreePlacer.PlacementGate.clear();
            // [LMax Debug] 检查 biome modifier 是否生效
            try {
                net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome_holder = level_server.getBiome(new net.minecraft.core.BlockPos(0, 64, 0));
                net.minecraft.world.level.biome.BiomeGenerationSettings gen_settings = biome_holder.value().getGenerationSettings();
                boolean found = false;
                for (net.minecraft.core.HolderSet<net.minecraft.world.level.levelgen.placement.PlacedFeature> step_features : gen_settings.features()) {
                    for (net.minecraft.core.Holder<net.minecraft.world.level.levelgen.placement.PlacedFeature> pf : step_features) {
                        if (pf.unwrapKey().map(k -> k.location().toString()).orElse("").contains("tanshugetrees")) {
                            found = true;
                            if (Core.log_event_center) System.out.println("[THT-DEBUG] Found tanshugetrees feature in biome: " + pf.unwrapKey().get().location());
                        }
                    }
                }
                if (!found) {
                    if (Core.log_event_center) System.out.println("[THT-DEBUG] WARNING: No tanshugetrees features found in biome at spawn! Biome modifier NOT applied!");
                    if (Core.log_event_center) System.out.println("[THT-DEBUG] Biome: " + biome_holder.unwrapKey().map(k -> k.location().toString()).orElse("unknown"));
                    if (Core.log_event_center) System.out.println("[THT-DEBUG] Total feature steps: " + gen_settings.features().size());
                    int total = 0;
                    for (net.minecraft.core.HolderSet<net.minecraft.world.level.levelgen.placement.PlacedFeature> step_features : gen_settings.features()) {
                        total += step_features.size();
                    }
                    if (Core.log_event_center) System.out.println("[THT-DEBUG] Total features in biome: " + total);
                }
            } catch (Exception e) {
                if (Core.log_event_center) System.out.println("[THT-DEBUG] Error checking biome features: " + e);
            }

            Core.restart(level_server, true, false);

        }

        @SubscribeEvent
        public static void eventWorldStopping (ServerStoppingEvent event) {

            first_player_joined = false;
            // [LMax Fix V51 刀O] [长期记忆: 131] 跨世界假 episode 根治：通知 watchdog 闭合在挂事件并熄火。
            // 同 JVM 退世界→菜单期→进世界全程无 server tick，曾整段计成单次巨型 stall（171s 实录）；
            // armed=false 后守护线程跳过检查，新服首 tick 的 updateTickTime() 重新武装。
            tannyjung.tanshugetrees_handcode.debug.Watchdog.onServerStopping();
            // [LMax Fix V3] 优雅关闭专属线程池
            // [LMax Fix V50.2 刀J] 保留 shutdown 让在途任务自然排空；池由下一个世界的 AboutToStart 复活，不再永久死亡
            TREE_GEN_EXECUTOR.shutdown();
        }

        private static int chunk_load_count = 0;
        private static int chunk_event_count = 0;

        // [LMax Fix] 防重复处理：记录已经处理过的 chunk
        private static final java.util.Set<net.minecraft.world.level.ChunkPos> processed_chunks
            = java.util.concurrent.ConcurrentHashMap.newKeySet();

        // [LMax Fix V20] 修复线程池饥饿与任务丢弃：使用固定大小的无界队列线程池
        // 之前的有界队列 (8192) 在主线程卡顿时会满载，导致 RejectedExecutionException 并静默丢弃区块生成任务。
        // 现在使用 Executors.newFixedThreadPool，确保有足够的线程并发，且永远不会丢弃任务。
        private static final int TREE_GEN_THREADS = Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors()));
        // [LMax Fix V50.2 刀J] [长期记忆: 105] static final → volatile + 工厂复活：单机整合服务器同一 JVM 内
        // 世界可多次进出（每个世界一个 MinecraftServer 实例），上个世界退出 ServerStopping→shutdown() 后，
        // JVM 级静态池对下一个世界永久 Terminated——世界55实录：chunk Load → PlacementGate.wake →
        // resubmitPlacement 往死池 submit → RejectedExecutionException 沿 ChunkMap future 链上浮
        // = "Exception ticking world" 崩溃 + 存档收尾期 "Failed to save chunk" 刷屏（同根因余震）。
        // 生命周期对齐：池是 JVM 级单例，服务器是实例级短命对象 → 池随新服务器 AboutToStart 复活（见上），
        // 投递统一走守卫入口（见下）。工厂体提取自原声明，线程命名/daemon/线程数语义不变。
        private static volatile java.util.concurrent.ExecutorService TREE_GEN_EXECUTOR = createTreeGenExecutor();

        private static java.util.concurrent.ExecutorService createTreeGenExecutor () {
            return java.util.concurrent.Executors.newFixedThreadPool(
                TREE_GEN_THREADS,
                r -> {
                    Thread t = new Thread(r, "THT-TreeGen");
                    t.setDaemon(true);
                    return t;
                }
            );
        }

        // [LMax Fix V50.2 刀J] [长期记忆: 105] 停服窗口守卫：ServerStopping 之后、存档收尾期仍有
        // ChunkEvent.Load → wake → resubmitPlacement 链路活跃（世界55崩溃栈实证），撞已 shutdown 的池必抛
        // RejectedExecutionException。单点吞掉 + 每世界 warn-once（AboutToStart 复位标志）：该窗口内的
        // 任务属于正在死去的旧世界，丢弃即正确语义；新世界由复活后的池接管，无任务损失。
        private static boolean tree_gen_rejected_logged = false;

        public static void submitTreeGen (Runnable task) { // [刀U2] private→public: PregenEngine 跨包提交复用守卫(拒绝吞掉/关服窗口防崩) [长期记忆: 167]
            try {
                TREE_GEN_EXECUTOR.submit(task);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                if (tree_gen_rejected_logged == false) {
                    tree_gen_rejected_logged = true;
                    Core.logger.warn("[TansHugeTrees] TreeGen executor rejected a task in server shutdown window (old world ending, task dropped)");
                }
            }
        }




        @SubscribeEvent
        public static void eventChunkLoaded (ChunkEvent.Load event) {
            // [LMax Fix V34] 绝对防御：拦截客户端事件！
            if (event.getLevel().isClientSide()) return;

            net.minecraft.server.level.ServerLevel level_server = (net.minecraft.server.level.ServerLevel) event.getLevel();
            String dimension = GameUtils.Space.getDimensionID(level_server).replace(":", "-");
            net.minecraft.world.level.ChunkPos chunk_pos = event.getChunk().getPos();
            net.minecraft.world.level.chunk.ChunkGenerator generator = level_server.getChunkSource().getGenerator();

            // [LMax Fix V50 刀F] [长期记忆: 095] Load 唤醒接线：PlacementGate 登记的等待者在本 chunk 加载完成时
            // 被唤醒（纯内存 map 操作，微秒级，无磁盘/管线访问）。置于 DelayedWork 提交之前：唤醒链与
            // 5 秒延迟链相互独立，互不依赖。
            tannyjung.tanshugetrees_handcode.systems.world_gen.TreePlacer.PlacementGate.wake(level_server, chunk_pos);

            // [LMax Fix V37] 延迟 100 Tick (5 秒) 后在后台线程执行种树！
            // 5 秒后区块加载风暴结束，异步读取绝对不会死锁，且绝不阻塞世界生成！
            Core.DelayedWork.create(true, 100, () -> {
                // [LMax Fix V38] 裸new Thread→TREE_GEN_EXECUTOR.submit
                // 原实现每区块一个线程，2400区块=2400并发线程把12核饿死（主线程stall 10.5s根因）
                // submit进固定池(4-16线程)由无界队列调度，线程数恒定，任务被排队消化
                // [长期记忆: 004] 先A后B的A2：消灭线程风暴
                submitTreeGen(() -> {
                    try {
                        // [LMax Fix V38] contains+add两步非原子(check-then-act竞态)→add()原子check-and-add
                        // Set.add()返回true=新增成功(本次处理)，false=已存在(跳过)，彻底消除窗口
                        if (processed_chunks.add(chunk_pos)) {
                            tannyjung.tanshugetrees_handcode.systems.world_gen.TreeLocation.start(level_server, dimension, chunk_pos);
                            tannyjung.tanshugetrees_handcode.systems.world_gen.TreePlacer.start(level_server, level_server, generator, dimension, chunk_pos);

                        }
                        // [LMax Fix A3] 重载 chunk：不重扫描（processed_chunks 幂等拦重复种树），但必须冲方块缓存。[长期记忆: 071]
                        // V42 旧实现重载后整体跳过 → 跨 chunk 树半边方块永久滞留 PendingBlocks =
                        // 回型甜甜圈 + 边界劈树 + 缓存泄漏三合一根因。刀N 后落块走主线程 setBlock(2) 增量包（resyncChunk 已退役）。
                        else {
                            // [LMax Fix V50.4 刀N] A3 重载冲刷改 DQ 主线程消费：异步线程直接 flushPendingBlocks 会因
                            // Tile.set 无条件转投形成 take→add 回缓存永动机（方块永不落地）；forced 任务由 processTick
                            // 主线程消费（就绪检查 + setBlock(2) 增量包全主线程闭环），冲刷延迟从 executor 异步窗口
                            // 收敛到下一 server tick（≤50ms）。数据滞留缓存零丢失（就绪检查不过 = 缓存留存重试）。
                            tannyjung.tanshugetrees_handcode.systems.world_gen.TreePlacer.DeferredQueue.addForced(dimension, level_server.dimension(), chunk_pos, chunk_pos);
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                });
            });
        }

        // [LMax Fix V50 刀F] [长期记忆: 095] PlacementGate 唤醒重提交入口：镜像 eventChunkLoaded 的提交闭包
        // （TREE_GEN_EXECUTOR 异步线程跑 TreePlacer.start；刀N 后落块全走主线程 setBlock(2) 增量包，无后置发包）。
        // 门在 start() 内部（四个调用方全覆盖）；resubmit → start → 门再验 = 唤醒链自愈。
        public static void resubmitPlacement (ServerLevel level_server, String dimension, net.minecraft.world.level.ChunkPos chunk_pos) {
            submitTreeGen(() -> {
                try {
                    net.minecraft.world.level.chunk.ChunkGenerator generator = level_server.getChunkSource().getGenerator();
                    // [LMax Fix V50.4 刀N] 落块全走主线程 setBlock(2) 增量包，resyncChunk 整包重发退役（返回值无消费者）
                    tannyjung.tanshugetrees_handcode.systems.world_gen.TreePlacer.start(level_server, level_server, generator, dimension, chunk_pos);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
            
        // [LMax Fix V50.4 刀N] resyncChunk 退役：刀N 后全部落块走主线程 setBlock(2) 增量包（原版 section 级
        // 自动合并），整包重发 32 格补偿不再必要；五调用点（EC×3 + TreePlacer×2）同步拆除。

        @SubscribeEvent
        public static void eventPlayerJoined (PlayerEvent.PlayerLoggedInEvent event) {

            Entity entity = event.getEntity();
            ServerLevel level_server = (ServerLevel) entity.level();

            if (first_player_joined == false) {

                first_player_joined = true;

                Core.DelayedWork.create(true, 100, () -> {

                    CustomPackOrganizing.Error.sendMessage(level_server);

                    if (Core.auto_check_update == true) {

                        Core.thread_main.submit(() -> {

                            TannyPackManager.runCheckUpdate(level_server);

                        });

                    }

                });

            }

        }

        @SubscribeEvent
        public static void eventRegisterCommand (RegisterCommandsEvent event) {

            CommandMaker.BuiltinCommands.registry(event);
            Commands.registry(event);

        }

        @SubscribeEvent
        /*
        (1.20.1)
        public static void eventTickServer (TickEvent.ServerTickEvent event) {
        (1.21.1)
        public static void eventTickServer (ServerTickEvent.Post event) {
        */
        public static void eventTickServer (TickEvent.ServerTickEvent event) {

            /*
            (1.20.1)
            if (event.phase == TickEvent.Phase.START) return;
            (1.21.1)
            ### Nothing ###
            */
            if (event.phase == TickEvent.Phase.START) {
                // [LMax Fix V54 刀S] [长期记忆: 149] tick 前段计时: START 相位记时戳, END 相位 processTick 求值前取差
                // = 本 tick 实时负载(滴灌自身在 END 之后才跑, t 天然不含 d) — 消除跨拍滞后的主部
                // 1.21.1 移植注: 无 Phase 枚举, 本调用迁至 eventTickServer 方法头(时戳语义等价)
                tannyjung.tanshugetrees_handcode.systems.world_gen.TreePlacer.DeferredQueue.onTickStart();
                return;
            }

            Core.currentServer = event.getServer();
            tannyjung.tanshugetrees_handcode.systems.world_gen.TreePlacer.DeferredQueue.processTick(event.getServer());

            if (Core.global_locking == false) {

                LevelAccessor level_accessor = event.getServer().overworld();
                ServerLevel level_server = event.getServer().overworld();

                Core.DelayedWork.runTick();
                Core.Loop.loopTick(level_accessor, level_server);

                // [LMax Fix V10] 消费实体生成队列
                Runnable entityTask;
                int processed = 0;
                while (processed < 50 && (entityTask = GameUtils.Mob.entity_queue.poll()) != null) {
                    try { entityTask.run(); } catch (Exception e) { e.printStackTrace(); }
                    processed++;
                }



            }

        }

    }

}
