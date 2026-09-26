package tannyjung.tanshugetrees_core.outside;

// [U6 Stage1] 诊断面板 lmax-debuglog: json -> toml 革命 (owner: Core.loadDebugLogConfig 唯一调用)
// 设计语义 "文件为真" (与主 config "模板为真" 的 ConfigToml.repair 刻意不同):
//   - 现存 toml 永不重写 -> 用户注释绝对永生 (max 诉求)
//   - 缺键 = 内存默认兜底, 不回写; parse 失败 = 全默认 + stderr, 也不碰盘
//   - 唯一写盘 = 文件缺失: ensureFromTemplate (模板渲染 + 原子写 + 回读自检)
// 一次性迁移: lmax-debuglog.json 存在且 toml 缺失 -> flat 行解析取现值(容错) + 桥值 ->
//   建盘 -> json 改名 .json.migrated 保档 (U5 .migrated 判例)
// 零依赖: 仅 ConfigToml(night-config) + JDK; 手搓 flat json 解析不拉 Gson, harness 可端到端打.

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

public class DebugLogToml {

    private static final String TAG = "[LMax-DebugLog] ";

    // [U6] 13 键双语模板 (12 boolean + 1 long), 唯一事实源; 默认全 false (watchdog_dump_all_threads 例外 true).
    // 字节对账基准: .scratch/lmax-debuglog_template.toml (harness T6).
    public static final String TEMPLATE = """
            # Tan's Huge Trees - Debug Log & Watchdog Config / 调试日志与看门狗配置
            # [U6] This file is NEVER rewritten by the mod once created: your comments survive every restart.
            #      Missing keys fall back to defaults. To regenerate with the latest template, delete this file.
            # [U6] 本文件创建后永远不会被模组重写: 你写的注释在每次重启后都会原样保留.
            #      缺失的键自动使用默认值. 想用最新模板重新生成? 删除本文件再启动即可.
            # All changes take effect after a restart. / 所有修改需重启生效.

            # Master switch: when true, ALL module switches below count as ON (module keys OR into it).
            # 主开关: 为 true 时下方所有模块开关视为开启(模块键以 OR 关系叠加, 可单独开启).
            debug_log_print = false

            # ===== Module log switches / 模块日志开关 =====
            # Effective value = debug_log_print || module key. / 有效值 = 主开关 OR 模块键.

            log_deferred_queue = false
            # DeferredQueue task lifecycle. / DeferredQueue 任务生命周期.

            log_placer_start = false
            # TreePlacer.start entry, empty data, timing. / TreePlacer.start 入口/空数据/耗时.

            log_place_calculate = false
            # placeCalculate, detection, checkpoints. / placeCalculate 检测与检查点.

            log_pending_blocks = false
            # PendingBlocks add/place/placeForced. / PendingBlocks add/place/placeForced.

            log_tree_location = false
            # TreeLocation scan/write/read. / TreeLocation 扫描/写入/读取.

            log_event_center = false
            # Biome feature checks. / 生物群系 feature 检查.

            log_world_gen_step = false
            # WorldGenStepBeforePlants. / 世界生成步骤 (WorldGenStepBeforePlants).

            log_queue_overflow = false
            # DeferredQueue overflow eviction. / DeferredQueue 溢出驱逐.

            log_queue_depth = false
            # DeferredQueue depth gauge at budget exhaustion. / DeferredQueue 深度仪表 (预算耗尽打点).

            # ===== Watchdog / 看门狗 =====
            watchdog_enabled = false
            # Master switch of the server-thread stall watchdog. / 看门狗(服务器线程冻结监控)总开关.

            watchdog_threshold_ms = 50
            # Trigger threshold in milliseconds; a normal tick is 50ms. Raise if too sensitive. / 触发阈值(毫秒), 正常 tick 为 50ms, 误报频繁时调高.

            watchdog_dump_all_threads = true
            # Dump all threads' stacks when a stall persists past milestones (1s/5s/20s/...). / 冻结持续过里程碑(1s/5s/20s/...)时 dump 全部线程堆栈.
            """;

    /**
     * [U6] 三分支加载 (Core.loadDebugLogConfig 唯一调用点).
     * @param path_toml  lmax-debuglog.toml 路径 (主体名不变, 仅扩展名随格式)
     * @param bridge     迁移桥值(主 config 的 watchdog 两键; Core 在主 config 瘦身渲染前读取传入)
     * @return 13 键归一化字符串表 (缺键/坏文件 = 默认值)
     */
    public static Map<String, String> load (String path_toml, Map<String, String> bridge) {

        Path toml = Path.of(path_toml);
        Path legacy_json = toml.resolveSibling("lmax-debuglog.json");
        Map<String, String> defaults = ConfigToml.templateDefaults(TEMPLATE);

        // --- 分支 1: 现存 toml = 文件为真, 只读不写 ---
        if (Files.isRegularFile(toml) == true) {
            return readMerged(path_toml, defaults);
        }

        // --- 分支 2/3: toml 缺失 -> 组装 overrides (json 现值 + 桥值) -> 建盘 ---
        Map<String, String> overrides = new LinkedHashMap<>();
        if (Files.isRegularFile(legacy_json) == true) {
            overrides.putAll(parseJsonFlat(legacy_json));
        }
        if (bridge != null) {
            overrides.putAll(bridge); // 桥值优先于 json (json 本不含 watchdog 两键)
        }

        ConfigToml.ensureFromTemplate(path_toml, TEMPLATE, overrides);

        // json 保档改名 (U5 .migrated 判例: 值已并入, 改名失败不阻塞, 下轮重试)
        if (Files.isRegularFile(legacy_json) == true) {
            try {
                Files.move(legacy_json, legacy_json.resolveSibling(legacy_json.getFileName() + ".migrated"), StandardCopyOption.REPLACE_EXISTING);
                System.out.println(TAG + "migrated legacy debug log config: lmax-debuglog.json -> lmax-debuglog.json.migrated (values kept: " + overrides.size() + ")");
            } catch (Exception exception) {
                System.err.println(TAG + "legacy json rename failed, will retry next run: " + exception);
            }
        }

        return readMerged(path_toml, defaults);
    }

    /** 读链统一 getValues + 缺键默认合并 (读侧唯一入口 = 与主 config 同一条读链, U5 判例). */
    private static Map<String, String> readMerged (String path_toml, Map<String, String> defaults) {

        try {
            Map<String, String> current = ConfigToml.getValues(path_toml);
            Map<String, String> merged = new LinkedHashMap<>(defaults);
            for (String key : defaults.keySet()) {
                if (current.containsKey(key) == true) {
                    merged.put(key, current.get(key));
                }
            }
            return merged;
        } catch (Exception exception) {
            // parse 失败 = 全默认 + stderr, 绝不碰盘 (注释永生的坏文件分支)
            System.err.println(TAG + "lmax-debuglog unparseable, using defaults (file untouched): " + exception);
            return new LinkedHashMap<>(defaults);
        }
    }

    /** [U6] 手搓 flat json 行解析 (json 为本模组模板产物的扁平布尔表; 容错: 坏行跳过, 整体异常 = 空表). */
    private static Map<String, String> parseJsonFlat (Path path_json) {

        Map<String, String> result = new LinkedHashMap<>();
        try {
            String text = Files.readString(path_json, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF") == true) {
                text = text.substring(1); // BOM 剥离 (U5 判例)
            }
            for (String raw : text.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty() == true || line.startsWith("{") || line.startsWith("}")) continue;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (key.startsWith("\"") == true && key.length() >= 2) {
                    key = key.replace("\"", "").trim();
                }
                if (value.endsWith(",") == true) {
                    value = value.substring(0, value.length() - 1).trim();
                }
                if (value.startsWith("\"") == true && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                if (key.isEmpty() == false && value.isEmpty() == false) {
                    result.put(key, value);
                }
            }
        } catch (Exception exception) {
            System.err.println(TAG + "legacy json unreadable, skipping its values: " + exception);
        }
        return result;
    }
}