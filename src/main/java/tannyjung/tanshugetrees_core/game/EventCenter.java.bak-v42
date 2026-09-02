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
            // [LMax Debug] 检查 biome modifier 是否生效
            try {
                net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome_holder = level_server.getBiome(new net.minecraft.core.BlockPos(0, 64, 0));
                net.minecraft.world.level.biome.BiomeGenerationSettings gen_settings = biome_holder.value().getGenerationSettings();
                boolean found = false;
                for (net.minecraft.core.HolderSet<net.minecraft.world.level.levelgen.placement.PlacedFeature> step_features : gen_settings.features()) {
                    for (net.minecraft.core.Holder<net.minecraft.world.level.levelgen.placement.PlacedFeature> pf : step_features) {
                        if (pf.unwrapKey().map(k -> k.location().toString()).orElse("").contains("tanshugetrees")) {
                            found = true;
                            if (Core.debug_log) System.out.println("[THT-DEBUG] Found tanshugetrees feature in biome: " + pf.unwrapKey().get().location());
                        }
                    }
                }
                if (!found) {
                    if (Core.debug_log) System.out.println("[THT-DEBUG] WARNING: No tanshugetrees features found in biome at spawn! Biome modifier NOT applied!");
                    if (Core.debug_log) System.out.println("[THT-DEBUG] Biome: " + biome_holder.unwrapKey().map(k -> k.location().toString()).orElse("unknown"));
                    if (Core.debug_log) System.out.println("[THT-DEBUG] Total feature steps: " + gen_settings.features().size());
                    int total = 0;
                    for (net.minecraft.core.HolderSet<net.minecraft.world.level.levelgen.placement.PlacedFeature> step_features : gen_settings.features()) {
                        total += step_features.size();
                    }
                    if (Core.debug_log) System.out.println("[THT-DEBUG] Total features in biome: " + total);
                }
            } catch (Exception e) {
                if (Core.debug_log) System.out.println("[THT-DEBUG] Error checking biome features: " + e);
            }

            Core.restart(level_server, true, false);

        }

        @SubscribeEvent
        public static void eventWorldStopping (ServerStoppingEvent event) {

            first_player_joined = false;
            // [LMax Fix V3] 优雅关闭专属线程池
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
        private static final java.util.concurrent.ExecutorService TREE_GEN_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(
            TREE_GEN_THREADS,
            r -> {
                Thread t = new Thread(r, "THT-TreeGen");
                t.setDaemon(true);
                return t;
            }
        );

        // [LMax Fix] Region 级别扫描锁：确保 TreeLocation 扫描完成后才跑 TreePlacer
        private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Void>> region_scans
            = new java.util.concurrent.ConcurrentHashMap<>();



        @SubscribeEvent
        public static void eventChunkLoaded (ChunkEvent.Load event) {
            // [LMax Fix V34] 绝对防御：拦截客户端事件！
            if (event.getLevel().isClientSide()) return;

            net.minecraft.server.level.ServerLevel level_server = (net.minecraft.server.level.ServerLevel) event.getLevel();
            String dimension = GameUtils.Space.getDimensionID(level_server).replace(":", "-");
            net.minecraft.world.level.ChunkPos chunk_pos = event.getChunk().getPos();
            net.minecraft.world.level.chunk.ChunkGenerator generator = level_server.getChunkSource().getGenerator();

            // [LMax Fix V37] 延迟 100 Tick (5 秒) 后在后台线程执行种树！
            // 5 秒后区块加载风暴结束，异步读取绝对不会死锁，且绝不阻塞世界生成！
            Core.DelayedWork.create(true, 100, () -> {
                // [LMax Fix V38] 裸new Thread→TREE_GEN_EXECUTOR.submit
                // 原实现每区块一个线程，2400区块=2400并发线程把12核饿死（主线程stall 10.5s根因）
                // submit进固定池(4-16线程)由无界队列调度，线程数恒定，任务被排队消化
                // [长期记忆: 004] 先A后B的A2：消灭线程风暴
                TREE_GEN_EXECUTOR.submit(() -> {
                    try {
                        // [LMax Fix V38] contains+add两步非原子(check-then-act竞态)→add()原子check-and-add
                        // Set.add()返回true=新增成功(本次处理)，false=已存在(跳过)，彻底消除窗口
                        if (processed_chunks.add(chunk_pos)) {
                            tannyjung.tanshugetrees_handcode.systems.world_gen.TreeLocation.start(level_server, dimension, chunk_pos);
                            tannyjung.tanshugetrees_handcode.systems.world_gen.TreePlacer.start(level_server, level_server, generator, dimension, chunk_pos);

                            // 种完树后，在主线程手动发包！
                            level_server.getServer().execute(() -> {
                                try {
                                    net.minecraft.world.level.chunk.LevelChunk lc = level_server.getChunk(chunk_pos.x, chunk_pos.z);
                                    if (lc != null && !lc.isEmpty()) {
                                        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet =
                                            new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                                                lc, level_server.getLightEngine(), null, null);
                                        for (net.minecraft.server.level.ServerPlayer player : level_server.players()) {
                                            if (player.distanceToSqr(chunk_pos.getWorldPosition().getX(), player.getY(), chunk_pos.getWorldPosition().getZ()) < 1024) {
                                                player.connection.send(packet);
                                            }
                                        }
                                    }
                                } catch (Exception e) { e.printStackTrace(); }
                            });
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                });
            });
        }
            
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
            if (event.phase == TickEvent.Phase.START) return;

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