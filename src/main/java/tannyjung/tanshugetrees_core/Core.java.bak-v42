package tannyjung.tanshugetrees_core;

import java.io.File;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DeferredRegister;
import tannyjung.tanshugetrees_core.game.GameUtils;
import tannyjung.tanshugetrees_core.game.world_gen.FeatureAreaDirt;
import tannyjung.tanshugetrees_core.game.world_gen.FeatureAreaGrass;
import tannyjung.tanshugetrees_core.game.world_gen.WorldGenStepBeforePlants;
import tannyjung.tanshugetrees_core.game.world_gen.WorldGenStepLast;
import tannyjung.tanshugetrees_core.outside.CacheManager;
import tannyjung.tanshugetrees_core.outside.ConfigClassic;
import tannyjung.tanshugetrees_core.outside.CustomPackOrganizing;
import tannyjung.tanshugetrees_core.outside.FileManager;
import tannyjung.tanshugetrees_core.outside.TXTFunction;
import tannyjung.tanshugetrees_handcode.Handcode;
import tannyjung.tanshugetrees_handcode.systems.Loops;

public class Core {

    public static net.minecraft.server.MinecraftServer currentServer = null;

    /*

    Use replace-all tool to replace these words, without "___" by the way. Note that you need to enable match cases and match words as well.

    (1.20.1)
    ___ForgeData___
    (1.21.1) (1.21.8)
    ___NeoForgeData___

    */
    
    public static String mod_name = "";
    public static String mod_id = "";
    public static String mod_id_big = "";
    public static String mod_id_short = "";
    
    public static int data_structure_version_core = 0;
    public static String data_structure_version_mod = "";
    public static String data_structure_version_pack = "";
    public static String main_pack_type = "";
    public static String main_pack_type_original = "";
    public static String github_pack = "";
    public static String wiki = "";
    public static boolean have_world_data_cleaner = false;
    
    public static Logger logger = null;
    public static boolean global_locking = false;
    public static String path_game = FMLPaths.GAMEDIR.get().toString();
    public static String path_config = null; // 延迟初始化，等 mod_id 赋值后再计算
    public static String path_world_core = null;
    public static String path_world_mod = null;
    public static final ExecutorService thread_main = Executors.newFixedThreadPool(1, name -> { Thread thread = new Thread(name); thread.setName(mod_name); return thread; });

    public static boolean auto_check_update = false;
    public static boolean wip_version = false;
    public static boolean developer_mode = false;

    // [LMax] 调试日志开关，读取 config/tanshugetrees/lmax-debuglog.json
    public static boolean debug_log = false;

    public static void start (IEventBus bus) {

        // [LMax Fix] 确保 logger 在使用前已初始化
        if (logger == null) {
            logger = LogManager.getLogger(mod_name);
        }

        Handcode.start();
        // [LMax Fix V2] 必须在 Handcode.start() 赋值 mod_id 之后才能计算路径！
        // 否则 mod_id 为空字符串，导致路径变成 config/ 而不是 config/tanshugetrees/
        path_config = path_game + "/config/" + mod_id;
        path_world_core = path_game + "/saves";
        path_world_mod = path_game + "/saves";
        main_pack_type_original = main_pack_type;
        Registry.start(bus);
        DataMigration.run(false);
        restart(null, true, true);

        // [LMax] 读取调试日志开关
        // [LMax] 读取调试日志开关
        loadDebugLogConfig();

    }

    // [LMax] 从 config/tanshugetrees/lmax-debuglog.json 读取调试日志开关
    private static void loadDebugLogConfig() {
        try {
            java.io.File file = new java.io.File(path_config + "/lmax-debuglog.json");
            if (file.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                if (content.contains("\"debug_log_print\"") && content.contains("true")) {
                    debug_log = true;
                    System.out.println("[LMax] Debug log enabled");
                }
            }
        } catch (Exception e) {
            System.err.println("[LMax] Failed to load debug log config: " + e.getMessage());
        }
    }
    public static void restart (ServerLevel level_server, boolean message, boolean config) {

        Runnable runnable = () -> {

            // Start Message
            {

                if (message == true && config == true) {

                    if (level_server != null) {

                        GameUtils.Misc.sendChatMessage(level_server, "Restarting the mod... / gray");

                    }

                }

            }

            String cache_size = "";

            if (config == true) {

                cache_size = CacheManager.clear();
                repairConfig(level_server);

            }

            // End Message
            {

                if (message == true && config == true) {

                    CustomPackOrganizing.Error.sendMessage(level_server);

                    if (level_server != null) {

                        GameUtils.Misc.sendChatMessage(level_server, "Restarted and cleared main caches about " + cache_size + " / gray");

                    }

                }

            }

        };

        if (level_server == null) {

            runnable.run();

        } else {

            thread_main.submit(() -> {

                GlobalLocking.test();
                GlobalLocking.lock();

                DelayedWork.create(true, 20, () -> {

                    runnable.run();
                    GameUtils.Score.create(level_server, mod_id_big);

                    GlobalLocking.unlock();

                });

            });

        }

    }

    private static void repairConfig (ServerLevel level_server) {

        FileManager.createEmptyFile(Core.path_config + "/custom_packs", true);

        // Main Config
        {

            Handcode.Config.repair("""
                    ----------------------------------------------------------------------------------------------------
                    Main Pack
                    ----------------------------------------------------------------------------------------------------
                    
                    auto_check_update = true
                    | Check for new update from GitHub every time the world starts
                    
                    wip_version = false
                    | Use development version of the pack instead of release version. Not recommended for game play, as it's still in development, it might unstable. Sometimes it needed development version of the mod.
                    
                    """, """
                    
                    developer_mode = false
                    | Enable some features for debugging such as detailed error messages, info overlay in-game, etc.
                    
                    ----------------------------------------------------------------------------------------------------
                    """);

            Map<String, String> data = ConfigClassic.getValues(path_config + "/config.txt");
            Handcode.Config.apply(data);

            auto_check_update = Boolean.parseBoolean(data.get("auto_check_update"));
            wip_version = Boolean.parseBoolean(data.get("wip_version"));
            developer_mode = Boolean.parseBoolean(data.get("developer_mode"));

            if (wip_version == true) {

                main_pack_type = "WIP";

            } else {

                main_pack_type = main_pack_type_original;

            }

        }

        Handcode.repairData(level_server);

    }

    public static class Registry {

        public static Map<String, Supplier<Feature<?>>> features = new HashMap<>();

        public static void start (IEventBus bus) {

            features.put("world_gen_before_plants", WorldGenStepBeforePlants::new);
            features.put("world_gen_last", WorldGenStepLast::new);
            features.put("area_grass", FeatureAreaGrass::new);
            features.put("area_dirt", FeatureAreaDirt::new);

            // Feature
            {

                DeferredRegister<Feature<?>> deferred = DeferredRegister.create(Registries.FEATURE, mod_id);

                for (Map.Entry<String, Supplier<Feature<?>>> entry : features.entrySet()) {

                    deferred.register(entry.getKey(), entry.getValue());

                }

                deferred.register(bus);
                features.clear();

            }

        }

    }
    
    public static class GlobalLocking {

        // [LMax Fix] Global lock removed to prevent deadlocks in async chunk generation (e.g., Distant Horizons).
        public static void lock () { }
        public static void unlock () { }
        public static void test () { }

    }

    public static class DelayedWork {

        private static final Collection<AbstractMap.SimpleEntry<Runnable, Integer>> delayed_works = new ConcurrentLinkedQueue<>();
        private static final ScheduledExecutorService thread_delay = Executors.newScheduledThreadPool(1);

        public static void create (boolean async, int tick, Runnable work) {

            if (async == true) {

                thread_delay.schedule(work, tick * 50L, TimeUnit.MILLISECONDS);

            } else {

                delayed_works.add(new AbstractMap.SimpleEntry<>(work, tick));

            }

        }

        public static void runTick () {

            for (AbstractMap.SimpleEntry<Runnable, Integer> work : delayed_works) {

                work.setValue(work.getValue() - 1);

                if (work.getValue() == 0) {

                    work.getKey().run();
                    delayed_works.remove(work);

                }

            }

        }

    }

    public static class Loop {

        private static int second = 0;
        private static int minute = 0;

        public static void loopTick (LevelAccessor level_accessor, ServerLevel level_server) {

            Loops.tick(level_accessor, level_server);
            second = second + 1;

            if (second > 20) {

                second = 0;
                loopSecond(level_accessor, level_server);

            }

        }

        private static void loopSecond (LevelAccessor level_accessor, ServerLevel level_server) {

            // Developer Mode
            {

                if (developer_mode == true) {

                    for (Entity entity : GameUtils.Mob.getAtEverywhere(level_server, "", mod_id_big)) {

                        GameUtils.Misc.spawnParticle(level_server, entity.position(), 0, 0, 0, 0, 1, "minecraft:end_rod");

                    }

                }

            }

            TXTFunction.loop(level_server);
            Loops.second(level_accessor, level_server);
            minute = minute + 1;

            if (minute > 60) {

                minute = 0;
                loopMinute(level_accessor, level_server);

            }

        }

        private static void loopMinute (LevelAccessor level_accessor, ServerLevel level_server) {

            Loops.minute(level_accessor, level_server);

        }

    }

    public static class DataMigration {

        public static void run(boolean is_world) {

            if (is_world == false) {

                String path = path_config + "/dev/version.txt";
                File test_exist = new File(path_config);
                String version = "";

                // Get Version
                {

                    if (test_exist.exists() == true) {

                        for (String scan : FileManager.readTXT(path)) {

                            version = scan;

                        }

                    } else {

                        version = "not found";

                    }

                }

                if (version.equals("not found") == false) {

                    Handcode.DataMigration.runConfig(version);

                }

                FileManager.writeTXT(path, data_structure_version_mod, false);

            } else {

                String path = path_world_mod + "/version.txt";
                File test_exist = new File(path_world_mod);
                String version = "";

                // Get Version
                {

                    if (test_exist.exists() == true) {

                        for (String scan : FileManager.readTXT(path)) {

                            version = scan;

                        }

                    } else {

                        version = "not found";

                    }

                }

                if (version.equals("not found") == false) {

                    Handcode.DataMigration.runWorld(version);

                }

                FileManager.writeTXT(path, data_structure_version_mod, false);

            }

        }

    }

}