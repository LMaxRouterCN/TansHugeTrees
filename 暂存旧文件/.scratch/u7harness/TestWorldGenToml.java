import tannyjung.tanshugetrees_core.outside.ConfigDynamicToml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

// [U7 harness] world_gen TOML 革命验收台.
// 教训: 源文件目录与运行时 work 目录分离 (v1 自杀事故: work=.scratch/u7test 与源同目录,
//       main 开头 deleteRecursively(work) 把自己删了). 本版: 源在 .scratch/u7harness, work 照旧.
// A 组: 真实环境端到端 (txt 迁移 verbatim 对账 + resolve 三层合并对账 + ensure 零接触).
// B 组: 合成矩阵 (LOCK 平移 / INCOMPATIBLE / 用户覆盖 / 删行回落 / 陈旧表 / 坏文件 / 旧格式表键).
// 纪律: 期望值 = 独立实现 (旧 ConfigDynamic.read()/write() 语义复刻), 与被测代码零共享逻辑;
//       生产盘 (E 盘 txt + pack) 全程只读; 全部写操作落 .scratch (零生产污染).
// 实例路径硬编码 = 测试环境事实, harness 专用非生产代码.
public class TestWorldGenToml {

    static int pass = 0;
    static int fail = 0;
    static final List<String> failures = new ArrayList<>();

    public static void main (String[] arguments) throws Exception {

        Path work = Path.of(".scratch/u7test");
        if (Files.exists(work)) deleteRecursively(work);
        Files.createDirectories(work);

        LinkedHashMap<String, String> defaults = specDefaults();

        // ================= A: 真实环境端到端 =================

        System.out.println("--- A: real environment end-to-end ---");

        Path real_txt = Path.of("E:/MC/.minecraft/versions/TEST 1.20.1-Forge_47.4.10/config/tanshugetrees/config_world_gen.txt");
        Path real_pack = Path.of("E:/MC/.minecraft/versions/TEST 1.20.1-Forge_47.4.10/config/tanshugetrees/dev/temporary/world_gen");

        Path toml_a = work.resolve("config_world_gen.toml");
        Path txt_a = work.resolve("config_world_gen.txt");
        Files.copy(real_txt, txt_a, StandardCopyOption.REPLACE_EXISTING);

        // A1-A2: 一次性迁移 + 保档
        boolean migrated = ConfigDynamicToml.migrateLegacy(toml_a, txt_a, defaults);
        check("A1 migrateLegacy 返回 true", migrated);
        check("A2 txt 改名 .migrated 保档", Files.isRegularFile(txt_a.resolveSibling("config_world_gen.txt.migrated")));

        // A3-A4: verbatim 对账 (期望 = 旧 read() 语义独立解析, 不做 none 归一)
        LinkedHashMap<String, LinkedHashMap<String, String>> parsed = ConfigDynamicToml.parseToml(toml_a);
        Map<String, Map<String, String>> expect_verbatim = legacyExpect(txt_a.resolveSibling("config_world_gen.txt.migrated"));
        System.out.println("  [INFO] txt entries (independent parse) = " + expect_verbatim.size() + ", toml tables = " + parsed.size());
        diffMaps("A4 迁移 verbatim 值对账", expect_verbatim, toPlain(parsed));

        // A5-A6: 三层合并对账 (期望 = pack 独立扫描 + spec 补缺 + none 归一; 实例 0 locked = txt 快照 == pack 值, 两边应全等)
        Map<String, Map<String, String>> resolved = ConfigDynamicToml.resolve(toml_a, real_pack, defaults);
        Map<String, Map<String, String>> expect_resolved = packExpect(real_pack, defaults);
        diffMaps("A6 resolve 三层合并对账", expect_resolved, resolved);

        // A7: 文件为真门 = 对已存在文件零接触
        String hash_before = md5(toml_a);
        ConfigDynamicToml.ensureFromPack(toml_a, real_pack, defaults);
        check("A7 ensureFromPack 已存在 = 零接触 (md5 不变)", hash_before.equals(md5(toml_a)));

        // A8: 值域抽查 (polaris = 值最杂条目; v2 勘误: path_settings 实值含 presets/ 前缀, recon1 L1059)
        Map<String, String> polaris = resolved.get("#main/#vanilla/variants/polaris");
        check("A8 polaris 在缓存", polaris != null);
        if (polaris != null) {
            check("A8 polaris biome = grove", "minecraft:grove".equals(polaris.get("biome")));
            check("A8 polaris group_size = 3 <> 5", "3 <> 5".equals(polaris.get("group_size")));
            check("A8 polaris dead_tree_level = auto_pine", "auto_pine".equals(polaris.get("dead_tree_level")));
            check("A8 polaris path_settings 井号路径 (含 presets/ 前缀)", "presets/#main/#variants/polaris_settings".equals(polaris.get("path_settings")));
        }

        // ================= B: 合成矩阵 =================

        runMatrixB(work, defaults);

        // ================= 总结 =================

        System.out.println();
        System.out.println("=== RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
        for (String failure : failures) System.out.println("  FAIL-Detail: " + failure);
    }

    // ============ B 组: 合成矩阵 (每 case 独立目录, resolve 不写盘可反复) ============

    static void runMatrixB (Path work, Map<String, String> defaults) throws Exception {

        System.out.println("--- B: synthetic matrix ---");

        Path root = work.resolve("matrix");

        // B1 迁移语义: 未 LOCK 平移 / LOCK 平移 / INCOMPATIBLE 丢弃 / spec 字段补全
        {
            Path dir = Files.createDirectories(root.resolve("b1"));
            Path txt = dir.resolve("config_test.txt");
            Files.writeString(txt, "[] #main > #global > locked_tree\nenable = true\nrarity = 42\n\n[LOCK] #main > #global > manual_tree\nenable = false\nrarity = 7\n\n[INCOMPATIBLE] #main > #global > dead_tree\nrarity = 99\n");
            Path toml = dir.resolve("config_test.toml");
            boolean migrated = ConfigDynamicToml.migrateLegacy(toml, txt, defaults);
            LinkedHashMap<String, LinkedHashMap<String, String>> parsed = ConfigDynamicToml.parseToml(toml);
            check("B1 迁移发生", migrated);
            check("B1 未 LOCK 条目值平移 (rarity=42)", "42".equals(field(parsed, "#main/#global/locked_tree", "rarity")));
            check("B1 LOCK 条目值平移 (rarity=7)", "7".equals(field(parsed, "#main/#global/manual_tree", "rarity")));
            check("B1 INCOMPATIBLE 条目丢弃", !parsed.containsKey("#main/#global/dead_tree"));
            check("B1 条目 spec 字段补全 (14 字段)", parsed.get("#main/#global/locked_tree").size() == defaults.size());
        }

        // B2 建盘: pack 快照渲染一次 / INCOMPATIBLE 不进文件 / 缺字段 spec 补
        {
            Path dir = Files.createDirectories(root.resolve("b2"));
            Path pack = makePack(dir);
            Path toml = dir.resolve("config_test.toml");
            ConfigDynamicToml.ensureFromPack(toml, pack, defaults);
            LinkedHashMap<String, LinkedHashMap<String, String>> parsed = ConfigDynamicToml.parseToml(toml);
            check("B2 建盘 tree1 表在场", parsed.containsKey("tree1"));
            check("B2 pack 值渲染 (rarity=50)", "50".equals(field(parsed, "tree1", "rarity")));
            check("B2 INCOMPATIBLE 不进文件", !parsed.containsKey("bad/bad"));
            check("B2 缺字段 spec 补 (min_distance=0)", "0".equals(field(parsed, "tree1", "min_distance")));
        }

        // B3 用户覆盖: TOML 值最高优先, 未覆盖字段保持 pack 值
        {
            Path dir = Files.createDirectories(root.resolve("b3"));
            Path pack = makePack(dir);
            Path toml = dir.resolve("config_test.toml");
            Files.writeString(toml, "[tree1]\nrarity = '99'\n");
            Map<String, Map<String, String>> resolved = ConfigDynamicToml.resolve(toml, pack, defaults);
            check("B3 用户覆盖 pack (rarity 50->99)", "99".equals(field(resolved, "tree1", "rarity")));
            check("B3 未覆盖字段保持 pack 值 (biome)", "#test:plains".equals(field(resolved, "tree1", "biome")));
            check("B3 pack 其余条目在场 (tree2 rarity=30)", "30".equals(field(resolved, "tree2", "rarity")));
        }

        // B4 删行回落: TOML 缺字段 = pack 值接管
        {
            Path dir = Files.createDirectories(root.resolve("b4"));
            Path pack = makePack(dir);
            Path toml = dir.resolve("config_test.toml");
            Files.writeString(toml, "[tree1]\nrarity = '50'\n");
            Map<String, Map<String, String>> resolved = ConfigDynamicToml.resolve(toml, pack, defaults);
            check("B4 删行回落 pack (biome)", "#test:plains".equals(field(resolved, "tree1", "biome")));
        }

        // B5 陈旧表: pack 无此条目 = 不进缓存 (幽灵树防线)
        {
            Path dir = Files.createDirectories(root.resolve("b5"));
            Path pack = makePack(dir);
            Path toml = dir.resolve("config_test.toml");
            Files.writeString(toml, "[ghost]\nrarity = '1'\n[tree1]\nrarity = '50'\n");
            Map<String, Map<String, String>> resolved = ConfigDynamicToml.resolve(toml, pack, defaults);
            check("B5 陈旧表不进缓存", !resolved.containsKey("ghost"));
            check("B5 陈旧表不影响其余条目", "50".equals(field(resolved, "tree1", "rarity")));
        }

        // B6 坏 toml: 不碰盘 + 降级 pack 值运行
        {
            Path dir = Files.createDirectories(root.resolve("b6"));
            Path pack = makePack(dir);
            Path toml = dir.resolve("config_test.toml");
            Files.writeString(toml, "garbage = [[\nnot toml at all\n");
            String hash_before = md5(toml);
            Map<String, Map<String, String>> resolved = ConfigDynamicToml.resolve(toml, pack, defaults);
            check("B6 坏 toml 不碰盘 (md5 不变)", hash_before.equals(md5(toml)));
            check("B6 坏 toml 降级 pack 值运行 (tree1 rarity=50)", "50".equals(field(resolved, "tree1", "rarity")));
        }

        // B7 旧格式表键: 用户沿用旧 txt 头 " > " 分隔仍命中
        {
            Path dir = Files.createDirectories(root.resolve("b7"));
            Path toml = dir.resolve("config_test.toml");
            Files.writeString(toml, "['#main > #global > legacy_tree']\nrarity = '55'\n");
            LinkedHashMap<String, LinkedHashMap<String, String>> parsed = ConfigDynamicToml.parseToml(toml);
            check("B7 旧格式表键归一为 '/'", parsed.containsKey("#main/#global/legacy_tree"));
        }

        // B8 INCOMPATIBLE 运行时: spec 默认硬注入, 不吃 pack 值
        {
            Path dir = Files.createDirectories(root.resolve("b8"));
            Path pack = makePack(dir);
            Path toml = dir.resolve("config_test.toml");
            Files.writeString(toml, "[tree1]\nrarity = '50'\n");
            Map<String, Map<String, String>> resolved = ConfigDynamicToml.resolve(toml, pack, defaults);
            check("B8 INCOMPATIBLE 条目进缓存 (enable=false)", "false".equals(field(resolved, "bad/bad", "enable")));
            check("B8 INCOMPATIBLE 不吃 pack 值 (rarity=spec 0, pack 99)", "0".equals(field(resolved, "bad/bad", "rarity")));
        }
    }

    // ============ 期望值独立实现 (与被测代码零共享逻辑) ============

    /** spec 默认值 (Handcode:68-97 文本块忠实复制, 14 字段顺序一致). */
    static LinkedHashMap<String, String> specDefaults () {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.put("enable", "false");
        out.put("spawn_type", "none");
        out.put("biome", "none");
        out.put("ground_block", "none");
        out.put("rarity", "0");
        out.put("min_distance", "0");
        out.put("group_size", "1 <> 1");
        out.put("dead_tree_chance", "0.0");
        out.put("dead_tree_level", "auto");
        out.put("start_height_offset", "0 <> 0");
        out.put("rotation", "random");
        out.put("mirrored", "random");
        out.put("path_storage", "none");
        out.put("path_settings", "none");
        return out;
    }

    /** 旧 read() 语义独立复刻 (verbatim, 不做 none 归一; INCOMPATIBLE 整组丢弃). */
    static Map<String, Map<String, String>> legacyExpect (Path legacy_txt) throws Exception {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        boolean skip = true;
        Map<String, String> current = null;
        for (String line : Files.readAllLines(legacy_txt, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) {
                if (line.startsWith("[INCOMPATIBLE] ")) { skip = true; current = null; continue; }
                int close = line.indexOf("]");
                String id = line.substring(close + 2).replace(" > ", "/");
                current = out.computeIfAbsent(id, create -> new LinkedHashMap<>());
                skip = false;
            } else if (skip == false && current != null && line.contains(" = ")) {
                int eq = line.indexOf(" = ");
                current.put(line.substring(0, eq), line.substring(eq + 3));
            }
        }
        return out;
    }

    /** pack 独立扫描期望: walk + " = " split + none 归一 + spec 补缺 (与被测 resolve 契约独立对账). */
    static Map<String, Map<String, String>> packExpect (Path pack_root, Map<String, String> defaults) throws Exception {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(pack_root)) { stream.filter(Files::isRegularFile).forEach(files::add); }
        files.sort(Comparator.comparing(path -> pack_root.relativize(path).toString()));
        for (Path file : files) {
            String id = pack_root.relativize(file).toString().replace("\\", "/");
            if (id.endsWith(".txt")) id = id.substring(0, id.length() - 4);
            if (id.contains("[INCOMPATIBLE] ")) {
                out.put(id.replace("[INCOMPATIBLE] ", ""), new LinkedHashMap<>(defaults)); // spec 默认硬注入
            } else {
                Map<String, String> fields = new LinkedHashMap<>();
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isEmpty() == false && line.contains(" = ")) {
                        int eq = line.indexOf(" = ");
                        String value = line.substring(eq + 3);
                        fields.put(line.substring(0, eq), "none".equals(value) ? "" : value);
                    }
                }
                Map<String, String> merged = new LinkedHashMap<>();
                for (Map.Entry<String, String> spec : defaults.entrySet()) {
                    merged.put(spec.getKey(), fields.containsKey(spec.getKey()) ? fields.get(spec.getKey()) : spec.getValue());
                }
                out.put(id, merged);
            }
        }
        // 统一 none 归一 (含 spec 默认值; 与被测 resolve 的单点归一语义对齐)
        for (Map<String, String> fields : out.values()) {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if ("none".equals(field.getValue())) field.setValue("");
            }
        }
        return out;
    }

    // ============ 工具 ============

    /** 合成 pack: tree1 (rarity=50, biome) / tree2 / [INCOMPATIBLE] bad (rarity=99, 不该被消费). */
    static Path makePack (Path parent) throws Exception {
        Path pack = parent.resolve("pack");
        Files.createDirectories(pack.resolve("[INCOMPATIBLE] bad"));
        Files.writeString(pack.resolve("tree1.txt"), "rarity = 50\nbiome = #test:plains\n");
        Files.writeString(pack.resolve("tree2.txt"), "rarity = 30\n");
        Files.writeString(pack.resolve("[INCOMPATIBLE] bad/bad.txt"), "rarity = 99\n");
        return pack;
    }

    static String field (Map<String, ? extends Map<String, String>> data, String id, String key) {
        Map<String, String> entry = data.get(id);
        return entry == null ? null : entry.get(key);
    }

    static void check (String name, boolean condition) {
        if (condition) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; failures.add(name); System.out.println("  [FAIL] " + name); }
    }

    /** 逐条目逐字段对账 (条目集 + 值级, 差异样本最多打印 8 条). */
    static void diffMaps (String tag, Map<String, Map<String, String>> expect, Map<String, Map<String, String>> actual) {
        Set<String> only_expect = new TreeSet<>(expect.keySet()); only_expect.removeAll(actual.keySet());
        Set<String> only_actual = new TreeSet<>(actual.keySet()); only_actual.removeAll(expect.keySet());
        if (only_expect.isEmpty() && only_actual.isEmpty()) {
            check(tag + ": 条目集一致 (" + expect.size() + " 条)", true);
        } else {
            check(tag + ": 条目集不一致 (仅期望 " + only_expect.size() + " / 仅实测 " + only_actual.size()
                    + "; 样本 仅期望[" + String.join(",", head(only_expect)) + "] 仅实测[" + String.join(",", head(only_actual)) + "])", false);
        }
        int value_diffs = 0;
        List<String> samples = new ArrayList<>();
        for (String id : new TreeSet<>(expect.keySet())) {
            if (actual.containsKey(id) == false) continue;
            for (Map.Entry<String, String> field : expect.get(id).entrySet()) {
                String actual_value = actual.get(id).get(field.getKey());
                if (actual_value == null || actual_value.equals(field.getValue()) == false) {
                    value_diffs++;
                    if (samples.size() < 8) samples.add(id + "." + field.getKey() + " 期望[" + field.getValue() + "] 实测[" + actual_value + "]");
                }
            }
        }
        check(tag + ": 值级对账 差异 " + value_diffs + " 处" + (samples.isEmpty() ? "" : "; " + String.join(" ; ", samples)), value_diffs == 0);
    }

    static List<String> head (Set<String> set) {
        return new ArrayList<>(set).subList(0, Math.min(3, set.size()));
    }

    /** 泛型适配: LinkedHashMap 内层具体类型 -> Map (Java 泛型不变性). */
    static Map<String, Map<String, String>> toPlain (LinkedHashMap<String, LinkedHashMap<String, String>> source) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, String>> entry : source.entrySet()) out.put(entry.getKey(), entry.getValue());
        return out;
    }

    static String md5 (Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static void deleteRecursively (Path dir) throws Exception {
        if (Files.exists(dir) == false) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); } catch (Exception exception) { throw new RuntimeException(exception); }
            });
        }
    }
}