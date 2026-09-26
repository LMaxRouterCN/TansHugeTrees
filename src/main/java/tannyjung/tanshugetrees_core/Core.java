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
import tannyjung.tanshugetrees_core.outside.ConfigToml; // [U5 Stage1]
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

    // [LMax] 调试日志/看门狗开关, 读取 config/tanshugetrees/lmax-debuglog.toml [U6]
    public static boolean debug_log = false;

    // [LMax Fix V50.3 刀M] [长期记忆: 113/114] 看门狗总开关(默认 false = 生产静默) [U6] lmax-debuglog.toml 三键集中
    public static boolean watchdog_enabled = false;

        // [LMax V42] 模块级日志有效开关（已预计算 = debug_log || 对应模块键），调用点单布尔判断零开销
        // [长期记忆: 015] 键控日志体系: lmax-debuglog.toml 中 9 个模块键独立控制各自子系统 [U6]
        public static boolean log_deferred_queue = false;  // DeferredQueue 任务生命周期
        public static boolean log_placer_start = false;    // TreePlacer.start 入口/空数据/耗时
        public static boolean log_place_calculate = false; // placeCalculate/检测/检查点
        public static boolean log_pending_blocks = false;  // PendingBlocks add/place/placeForced
        public static boolean log_tree_location = false;   // TreeLocation 扫描/写入/读取
        public static boolean log_event_center = false;    // 生物群系 feature 检查
        public static boolean log_world_gen_step = false;  // WorldGenStepBeforePlants
        public static boolean log_queue_overflow = false;  // DeferredQueue 溢出驱逐
    // [LMax Fix V54 刀S] [长期记忆: 149] 队列深度仪表: 预算耗尽处打点(深度/t/d/mode, counter vs size() 双报兼漂移探测)
    public static boolean log_queue_depth = false;  // DeferredQueue 深度仪表(预算耗尽打点)

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

        // [U6] 调用点上移: 须在 restart()(主 config 模板瘦身渲染)之前,
        // 迁移桥才能读到旧主 config 里的 watchdog 两键用户值(渲染后该两键不再存在)
        loadDebugLogConfig();
        restart(null, true, true);

        // [LMax Fix V54 刀S] [长期记忆: 149] 预算三键热重载: WatchService 事件驱动监听 config.toml(零轮询),
        // 只热应用 budget_mode/budget_ms/budget_expr 三键, 其余键改动需重启(边界清晰). 详见 WatchConfigReload.
        WatchConfigReload.start();

    }

    // [LMax] 调试日志/看门狗配置加载 [U6]: lmax-debuglog.json -> lmax-debuglog.toml (文件为真语义)
    // 现存 toml 永不重写(用户注释永生); 缺键=内存默认不回写; parse 失败=全默认+stderr, 均不碰盘.
    // 唯一写盘 = 文件缺失: 首建 或 json 一次性迁移(旧 json 保档改名 .json.migrated).
    // watchdog 三键集中此地(原 enabled 本就在此 + 主 config 两键迁入): 主 config 只留游戏语义.
    // [U6] 调用点已上移至 restart() 之前: 迁移桥必须在旧 79 键主 config 被模板瘦身渲染前读
    // watchdog 两键, 否则用户自定义值会被渲染蒸发(一般用户保值; live 实例两键恰为默认, 零携带).
    private static void loadDebugLogConfig() {

        // [U6] 迁移桥: watchdog 两键最后一次从主 config 读取(读的是渲染前的旧文件)
        java.util.Map<String, String> bridge = new java.util.HashMap<>();
        try {
            java.util.Map<String, String> main_config = tannyjung.tanshugetrees_core.outside.ConfigToml.getValues(path_config + "/config.toml");
            if (main_config.containsKey("watchdog_threshold_ms")) bridge.put("watchdog_threshold_ms", main_config.get("watchdog_threshold_ms"));
            if (main_config.containsKey("watchdog_dump_all_threads")) bridge.put("watchdog_dump_all_threads", main_config.get("watchdog_dump_all_threads"));
        } catch (Exception ignored) {
            // 主 config 缺/坏: 桥空, 两键走模板默认 (50 / true)
        }

        java.util.Map<String, String> values;
        try {
            values = tannyjung.tanshugetrees_core.outside.DebugLogToml.load(path_config + "/lmax-debuglog.toml", bridge);
        } catch (Exception exception) {
            // 诊断面板任何意外不炸启动: 空 map -> 全默认 (沿袭旧 catch 语义)
            values = new java.util.HashMap<>();
        }

        try {
            debug_log = Boolean.parseBoolean(values.getOrDefault("debug_log_print", "false"));
            // 有效值预计算: 主开关 OR 模块键 [LMax V42 语义原样, 调用点单布尔判断零开销]
            log_deferred_queue  = debug_log || Boolean.parseBoolean(values.getOrDefault("log_deferred_queue", "false"));
            log_placer_start    = debug_log || Boolean.parseBoolean(values.getOrDefault("log_placer_start", "false"));
            log_place_calculate = debug_log || Boolean.parseBoolean(values.getOrDefault("log_place_calculate", "false"));
            log_pending_blocks  = debug_log || Boolean.parseBoolean(values.getOrDefault("log_pending_blocks", "false"));
            log_tree_location   = debug_log || Boolean.parseBoolean(values.getOrDefault("log_tree_location", "false"));
            log_event_center    = debug_log || Boolean.parseBoolean(values.getOrDefault("log_event_center", "false"));
            log_world_gen_step  = debug_log || Boolean.parseBoolean(values.getOrDefault("log_world_gen_step", "false"));
            log_queue_overflow  = debug_log || Boolean.parseBoolean(values.getOrDefault("log_queue_overflow", "false"));
            log_queue_depth     = debug_log || Boolean.parseBoolean(values.getOrDefault("log_queue_depth", "false"));

            // [U6] watchdog 三键: threshold/dump 直接喂 Watchdog 静态(Handcode 两中转静态已退役)
            tannyjung.tanshugetrees_handcode.debug.Watchdog.thresholdMs = Long.parseLong(values.getOrDefault("watchdog_threshold_ms", "50"));
            tannyjung.tanshugetrees_handcode.debug.Watchdog.dumpAllThreadsEnabled = Boolean.parseBoolean(values.getOrDefault("watchdog_dump_all_threads", "true"));
            watchdog_enabled = Boolean.parseBoolean(values.getOrDefault("watchdog_enabled", "false"));

            // [LMax Fix V50.3 刀M] [长期记忆: 113/114] 启动门原位: enabled 后启动(首 tick 才武装, 上移无时序影响)
            if (watchdog_enabled) {
                tannyjung.tanshugetrees_handcode.debug.Watchdog.start();
            }

            System.out.println("[LMax] Debug log config loaded (lmax-debuglog.toml): master=" + debug_log + ", watchdog=" + watchdog_enabled + ", threshold=" + values.getOrDefault("watchdog_threshold_ms", "50") + "ms, modules(on)=" + (log_deferred_queue?"deferred_queue,":"") + (log_placer_start?"placer_start,":"") + (log_place_calculate?"place_calculate,":"") + (log_pending_blocks?"pending_blocks,":"") + (log_tree_location?"tree_location,":"") + (log_event_center?"event_center,":"") + (log_world_gen_step?"world_gen_step,":"") + (log_queue_overflow?"queue_overflow,":"") + (log_queue_depth?"queue_depth":""));
        } catch (Exception e) {
            // 解析失败: 全 false 兜底(含 debug_log), 不让坏配置炸启动 [LMax V42 原语义]
            debug_log = false;
            log_deferred_queue = false;
            log_placer_start = false;
            log_place_calculate = false;
            log_pending_blocks = false;
            log_tree_location = false;
            log_event_center = false;
            log_world_gen_step = false;
            log_queue_overflow = false;
            log_queue_depth = false;
            watchdog_enabled = false;
            System.err.println("[LMax] Failed to load debug log config (all switches off): " + e.getMessage());
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

            // [U5 Stage1] 79-key bilingual template lives in Handcode.Config.TEMPLATE_TOML; start/end assembly retired.
            Handcode.Config.repair();

            Map<String, String> data = ConfigToml.getValues(path_config + "/config.toml"); // [U5 Stage1] night-config read chain, Map contract parity
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
