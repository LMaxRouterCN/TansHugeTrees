import tannyjung.tanshugetrees_core.outside.ConfigToml;
import tannyjung.tanshugetrees_core.outside.DebugLogToml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

// [U6] DebugLogToml 全链 harness (照 U5 TestConfigToml 模式: scratch 层, 不进生产 build).
// 判定不依赖控制台中文显示 (U5 判例: 显示层乱码与判定解耦), 断言走返回值 + 文件字节层.
// 用例: T1 冷启动首建 / T2 json 迁移保值+桥 / T3 注释+缺键永生 / T4 坏文件不碰盘 / T5 ensure 幂等 / T6 模板对账.
// live 路径硬编码: 测试工具非生产代码, 实例迁移即改 (U5 同款声明).
public class TestDebugLogToml {

    static int pass = 0;
    static int fail = 0;

    static void check (String name, boolean ok) {
        if (ok == true) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    static void wipe (Path dir) throws Exception {
        if (Files.exists(dir) == true) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (Exception exception) { throw new RuntimeException(exception); }
                });
            }
        }
        Files.createDirectories(dir);
    }

    static String sha256 (Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) { out.append(String.format("%02x", b)); }
        return out.toString();
    }

    // 期望表构造 helper (varargs: key, value, key, value ...)
    static Map<String, String> expect (String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) { out.put(pairs[i], pairs[i + 1]); }
        return out;
    }

    // 逐键断言 + 总数断言 (错则打出具体键, 不吞错)
    static void checkMap (String tag, Map<String, String> actual, Map<String, String> expected) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String key = entry.getKey();
            String want = entry.getValue();
            check(tag + " " + key + "==" + want, want.equals(actual.get(key)));
        }
        check(tag + " size==" + expected.size(), actual.size() == expected.size());
    }

    // max live 实况 json (R1 取证: 10 键 7 true / 3 false, 缺 log_queue_depth)
    static final String JSON_LIVE = """
            {
              "debug_log_print": false,
              "log_deferred_queue": false,
              "log_placer_start": true,
              "log_place_calculate": true,
              "log_pending_blocks": true,
              "log_tree_location": true,
              "log_event_center": true,
              "log_world_gen_step": false,
              "log_queue_overflow": true,
              "watchdog_enabled": true
            }
            """;

    // 全默认期望 (T1/T4 共用): 12 false + threshold 50 + dump true
    static Map<String, String> defaults () {
        return expect(
            "debug_log_print", "false",
            "log_deferred_queue", "false",
            "log_placer_start", "false",
            "log_place_calculate", "false",
            "log_pending_blocks", "false",
            "log_tree_location", "false",
            "log_event_center", "false",
            "log_world_gen_step", "false",
            "log_queue_overflow", "false",
            "log_queue_depth", "false",
            "watchdog_enabled", "false",
            "watchdog_threshold_ms", "50",
            "watchdog_dump_all_threads", "true"
        );
    }

    public static void main (String[] args) throws Exception {

        Path root = Path.of(".scratch", "test_debuglog");
        Path toml = root.resolve("lmax-debuglog.toml");

        // ===== T1: 冷启动 (双缺) -> 模板首建 13 键全默认 =====
        wipe(root);
        Map<String, String> v1 = DebugLogToml.load(toml.toString(), null);
        check("T1 file created", Files.isRegularFile(toml));
        checkMap("T1", v1, defaults());

        // ===== T2: json 一次性迁移 (7true 保值 + 桥覆盖两键 + 缺键默认 + json 保档改名) =====
        wipe(root);
        Path json = root.resolve("lmax-debuglog.json");
        Files.writeString(json, JSON_LIVE, StandardCharsets.UTF_8);
        Map<String, String> bridge = new LinkedHashMap<>();
        bridge.put("watchdog_threshold_ms", "120");       // 模拟用户自定义阈值 (默认 50)
        bridge.put("watchdog_dump_all_threads", "false"); // 模拟用户关 dump (默认 true)
        Map<String, String> v2 = DebugLogToml.load(toml.toString(), bridge);
        checkMap("T2", v2, expect(
            "debug_log_print", "false",
            "log_deferred_queue", "false",
            "log_placer_start", "true",      // json 保值
            "log_place_calculate", "true",   // json 保值
            "log_pending_blocks", "true",    // json 保值
            "log_tree_location", "true",     // json 保值
            "log_event_center", "true",      // json 保值
            "log_world_gen_step", "false",
            "log_queue_overflow", "true",    // json 保值
            "log_queue_depth", "false",      // json 缺键 -> 默认
            "watchdog_enabled", "true",      // json 保值
            "watchdog_threshold_ms", "120",  // 迁移桥值
            "watchdog_dump_all_threads", "false" // 迁移桥值
        ));
        check("T2 legacy json gone", Files.isRegularFile(json) == false);
        check("T2 .json.migrated exists", Files.isRegularFile(root.resolve("lmax-debuglog.json.migrated")));
        check("T2 migrated content intact", Files.readString(root.resolve("lmax-debuglog.json.migrated"), StandardCharsets.UTF_8).equals(JSON_LIVE));

        // ===== T3: 注释 + 删键 -> 永生 (文件字节级零写盘) =====
        // 模拟用户编辑: 头部插注释 + 删 log_event_center 键行
        String edited = "# MY NOTE: max 注释永生测试 [U6]\n" + Files.readString(toml, StandardCharsets.UTF_8);
        edited = edited.replace("log_event_center = true\n", "");
        Files.writeString(toml, edited, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(toml);
        Map<String, String> v3 = DebugLogToml.load(toml.toString(), null);
        check("T3 missing key falls back default", "false".equals(v3.get("log_event_center")));
        check("T3 other keys kept", "true".equals(v3.get("log_placer_start")));
        check("T3 file byte-identical (never rewritten)", Arrays.equals(before, Files.readAllBytes(toml)));
        check("T3 comment survived", new String(Files.readAllBytes(toml), StandardCharsets.UTF_8).contains("注释永生测试"));

        // ===== T4: 坏文件 -> 全默认 + 绝不碰盘 =====
        Files.writeString(toml, "broken = = @@@\n[[[ not toml", StandardCharsets.UTF_8);
        byte[] bad = Files.readAllBytes(toml);
        Map<String, String> v4 = DebugLogToml.load(toml.toString(), null);
        checkMap("T4", v4, defaults());
        check("T4 file byte-identical (untouched)", Arrays.equals(bad, Files.readAllBytes(toml)));

        // ===== T5: ensureFromTemplate 幂等 (文件存在 = 零接触, mtime+hash 双证) =====
        long mtime = Files.getLastModifiedTime(toml).toMillis();
        String hash = sha256(toml);
        ConfigToml.ensureFromTemplate(toml.toString(), DebugLogToml.TEMPLATE, null);
        check("T5 hash stable", hash.equals(sha256(toml)));
        check("T5 mtime stable", Files.getLastModifiedTime(toml).toMillis() == mtime);

        // ===== T6: 模板对账 (Java TEMPLATE vs .scratch 基准, EOL 归一 + 剥 BOM) =====
        String fileTemplate = Files.readString(Path.of(".scratch", "lmax-debuglog_template.toml"), StandardCharsets.UTF_8);
        if (fileTemplate.startsWith("\uFEFF") == true) { fileTemplate = fileTemplate.substring(1); }
        fileTemplate = fileTemplate.replace("\r\n", "\n");
        String javaTemplate = DebugLogToml.TEMPLATE.replace("\r\n", "\n");
        check("T6 template byte-identical (EOL-normalized)", fileTemplate.equals(javaTemplate));

        System.out.println("RESULT pass=" + pass + " fail=" + fail + " total=" + (pass + fail));
        if (fail > 0) { System.exit(1); }
    }
}