import tannyjung.tanshugetrees_core.outside.ConfigToml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

// [U5-B2] scratch 层 harness v2 (不进生产 build): 对实盘 config.txt 副本跑 ConfigToml 全链验证.
// v2 变更: 1) 头 60 行目检移至 PowerShell 文件层读取(控制台显示层乱码与判定解耦)
//          2) 新增 Test 4 幂等(双 repair 字节稳定, 控制循环写零漂移)
//          3) 新增文件层中文保真校验(读文件判定, 不依赖控制台显示)
// live 路径硬编码: 测试工具非生产代码, 实例迁移即改.
public class TestConfigToml {

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

    // 测试侧独立模板默认解析 (与 ConfigToml.parseTemplate 同语义, 交叉验证)
    static Map<String, String> templateDefaults (String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() == true || trimmed.startsWith("#") == true) { continue; }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) { continue; }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (value.startsWith("'") == true && value.endsWith("'") == true && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return out;
    }

    // 全量对账: expect = override(用户改值) 或 defaults; 逐键打印 MISMATCH
    static void diffAgainstDefaults (Map<String, String> values, Map<String, String> defaults, Map<String, String> overrides, String label) {
        int mismatch = 0;
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            String expect = overrides.containsKey(entry.getKey()) == true ? overrides.get(entry.getKey()) : entry.getValue();
            if (expect.equals(values.get(entry.getKey())) == false) {
                mismatch++;
                System.out.println("  MISMATCH " + entry.getKey() + " expect=" + expect + " got=" + values.get(entry.getKey()));
            }
        }
        check(label, mismatch == 0);
    }

    public static void main (String[] args) throws Exception {
        Path scratch = Path.of(".scratch/test");
        Path live_txt = Path.of("E:/MC/.minecraft/versions/TEST 1.20.1-Forge_47.4.10/config/tanshugetrees/config.txt");
        String template = Files.readString(Path.of(".scratch/config_template.toml")).replace("\r\n", "\n");
        Map<String, String> defaults = templateDefaults(template);
        System.out.println("template defaults: " + defaults.size() + " keys (expect 79)");
        check("template key count = 79", defaults.size() == 79);

        // ---- Test 1: 迁移 (实盘 txt -> toml), 全量对账 ----
        wipe(scratch);
        Files.copy(live_txt, scratch.resolve("config.txt"));
        ConfigToml.repair(scratch.resolve("config.toml").toString(), template);
        check("migrate: config.txt.migrated exists", Files.exists(scratch.resolve("config.txt.migrated")));
        Map<String, String> values = ConfigToml.getValues(scratch.resolve("config.toml").toString());
        check("migrate: toml key count = 79", values.size() == 79);
        diffAgainstDefaults(values, defaults, Map.of(), "migrate: all 79 = defaults (live userEdit=0, plainDecimal fix)");

        // ---- Test 2: 幻影改值 (bool + string) 再 repair: 用户值保持 + 其余零漂移 ----
        String txt = Files.readString(scratch.resolve("config.toml"));
        txt = txt.replace("tree_location = true", "tree_location = false");
        txt = txt.replace("pregen_task_priority = 'fifo'", "pregen_task_priority = 'nearest'");
        Files.writeString(scratch.resolve("config.toml"), txt);
        ConfigToml.repair(scratch.resolve("config.toml").toString(), template);
        Map<String, String> values2 = ConfigToml.getValues(scratch.resolve("config.toml").toString());
        diffAgainstDefaults(values2, defaults, Map.of("tree_location", "false", "pregen_task_priority", "nearest"), "phantom: 2 user values kept + 77 zero drift");

        // ---- Test 3: 冷启动 (无 txt 无 toml, 纯模板起步) ----
        wipe(scratch);
        ConfigToml.repair(scratch.resolve("config.toml").toString(), template);
        Map<String, String> values3 = ConfigToml.getValues(scratch.resolve("config.toml").toString());
        check("cold: key count = 79", values3.size() == 79);
        diffAgainstDefaults(values3, defaults, Map.of(), "cold: all 79 = defaults");

        // ---- 文件层中文保真 (显示层乱码与判定解耦: 校验读文件本身) ----
        String produced = Files.readString(scratch.resolve("config.toml"));
        check("file: bilingual header (主配置)", produced.contains("# Tan's Huge Trees - Main Config / 主配置"));
        check("file: bilingual section (树生成器)", produced.contains("# ===== Tree Generator / 树生成器 ====="));
        check("file: bilingual desc (落叶层)", produced.contains("# 在地面与水面生成落叶层(总开关)"));
        check("file: LF only (no CR)", produced.indexOf('\r') < 0);

        // ---- Test 4: 幂等 (同输入再 repair, 字节稳定 = 控制循环写零漂移) ----
        byte[] once = Files.readAllBytes(scratch.resolve("config.toml"));
        ConfigToml.repair(scratch.resolve("config.toml").toString(), template);
        byte[] twice = Files.readAllBytes(scratch.resolve("config.toml"));
        check("idempotent: byte-identical after re-repair", Arrays.equals(once, twice));

        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}