package tannyjung.tanshugetrees_core.outside;

// [U7] world_gen TOML 革命: 机制层 (纯 night-config + JDK, 零 tannyjung 内部依赖 — 与 ConfigToml [U5/U6]
// 同纪律: harness 可脱离 Minecraft/gradle 独立编译打靶; 原子写/值归一复用 ConfigToml 包内实现 = 单一事实源).
// (max 五裁决 [长期记忆: 209]: U7 即时开工 + 75 条中文文案授权)
//
// 数据形状 (与 ConfigToml 平面表的差异): 两层 Map<条目ID, Map<字段, 值>> = 旧 ConfigDynamic 缓存契约.
//   表 = 条目, quoted-key 形如 ['#main/#global/name'] ("/" = 旧 txt 头 " > " 漂亮分隔的内部形态, 与
//   path_storage 值风格一致, TreeLocation:755 get(id) 直接命中);
//   值 = 单引号字面量字符串 (零类型翻译: 消费端 Double.parseDouble/equals(String) 全吃字符串, 旧缓存同形态).
//
// 生命周期 (ConfigDynamic.reorganize 编排三步):
//   migrateLegacy : 老 txt verbatim -> TOML 一次性 + .txt.migrated 保档 (U6 同款);
//   ensureFromPack: TOML 缺失 -> pack 快照渲染一次 (文件为真: 之后永不重写, 用户注释永生);
//   resolve       : spec 默认 < pack 文件值 < TOML 用户值 (字段级三层合并) -> 旧缓存契约形态.
//
// 语义革命 (LOCK 退役): 旧系统每轮全量重渲染, 用户改值须手动 [LOCK] 防碾; 新系统文件为真, 用户值天然
//   最高优先, 删表/删行 = 回落 pack = 天然重置手势 (头部双语注释自文档).
// 防线: pack 已删条目的陈旧 TOML 表不进缓存 (TreeLocation:222 遍历消费 = 幽灵树扫描防线);
//   INCOMPATIBLE 条目 = spec 默认硬注入不吃用户覆盖 (旧 read() skip=true 同语义, 文件面不再欺骗);
//   pack 扫描异常 -> 降级 TOML 条目集 (最后一次已知快照, 优于旧系统全空死树).

import com.electronwill.nightconfig.toml.TomlParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class ConfigDynamicToml {

    private static final String TAG = "[THT-ConfigDynamicToml] ";

    // pack 目录名内嵌前缀 = 不兼容标记 (旧 ConfigDynamic write()/read() L142/L253 同字面量)
    private static final String INCOMPATIBLE_PREFIX = "[INCOMPATIBLE] ";

    // ============ 公共 API (全部收 Path 参数 = harness 可独立打靶; 计算与调度解耦) ============

    /**
     * 一次性迁移: 老 txt 在场且无 .migrated 标记 -> verbatim 渲染 TOML + 原子写 + txt 改名保档.
     * [LOCK] 条目值原样平移 (其语义 = 用户冻结值, 文件为真天然继承); [INCOMPATIBLE] 条目丢弃
     * (旧系统文件面显示 pack 值 / 运行时却强制 spec 默认 = 欺骗性不一致, 新系统让它们从文件面消失).
     * 返回 true = 本轮发生了迁移 (供 reorganize 日志).
     */
    public static boolean migrateLegacy (Path path_toml, Path path_legacy_txt, Map<String, String> default_values) {

        Path legacy_mark = path_legacy_txt.resolveSibling(path_legacy_txt.getFileName() + ".migrated");

        if (Files.isRegularFile(path_legacy_txt) == false || Files.exists(legacy_mark) == true) {
            return false;
        }

        // 已有带内容的 TOML (上轮迁移后改名失败的补试路径): 不重渲染 (保护用户可能已做的编辑), 只补改名
        if (Files.isRegularFile(path_toml) == true) {
            try {
                if (parseToml(path_toml).isEmpty() == false) {
                    try {
                        Files.move(path_legacy_txt, legacy_mark, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println(TAG + "legacy rename retry ok: " + legacy_mark.getFileName());
                    } catch (Exception exception) {
                        System.err.println(TAG + "legacy rename still failing: " + exception);
                    }
                    return true;
                }
            } catch (Exception ignored) {
                // TOML 解析失败 -> 走下方重渲染 (以 txt 为准重建)
            }
        }

        LinkedHashMap<String, LinkedHashMap<String, String>> entries = parseLegacyTxt(path_legacy_txt);

        ConfigToml.atomicWrite(path_toml, renderToml(entries, default_values));

        // 回读自检 (防假绿: 产物必须能被 night-config 重新 parse). 失败 -> 头部占位 + 不改名,
        // 下轮 txt 仍在场且占位文件空表 -> 走完整重渲染路径, 自愈闭环.
        boolean healthy = true;
        try {
            parseToml(path_toml);
        } catch (Exception exception) {
            System.err.println(TAG + "migration self-check FAILED, writing header-only placeholder: " + exception);
            ConfigToml.atomicWrite(path_toml, renderToml(new LinkedHashMap<>(), default_values));
            healthy = false;
        }

        if (healthy == true) {
            try {
                Files.move(path_legacy_txt, legacy_mark, StandardCopyOption.REPLACE_EXISTING);
                System.out.println(TAG + "migrated legacy config: " + path_legacy_txt.getFileName() + " -> "
                        + legacy_mark.getFileName() + " (entries kept: " + entries.size() + ")");
            } catch (Exception exception) {
                // 改名失败 (文件占用等): TOML 已原子写入, 下轮走上方"只补改名"路径, 不重复渲染
                System.err.println(TAG + "legacy rename failed, will retry next run: " + exception);
            }
        }

        return true;
    }

    /** 首次建盘 (文件为真门): TOML 缺失 -> 以 pack 当前快照 + spec 默认渲染一次; 已存在 = 零接触. */
    public static void ensureFromPack (Path path_toml, Path pack_root, Map<String, String> default_values) {

        if (Files.isRegularFile(path_toml) == true) return;

        LinkedHashMap<String, LinkedHashMap<String, String>> entries = new LinkedHashMap<>();
        try {
            entries = scanPack(pack_root);
        } catch (Exception exception) {
            System.err.println(TAG + "initial render pack scan failed, header-only file: " + exception);
        }

        // INCOMPATIBLE 条目不进初始文件 (与迁移处置一致: 文件面纯净, 运行时由 pack 目录前缀识别)
        LinkedHashMap<String, LinkedHashMap<String, String>> renderable = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, String>> entry : entries.entrySet()) {
            if (entry.getKey().contains(INCOMPATIBLE_PREFIX) == false) renderable.put(entry.getKey(), entry.getValue());
        }

        ConfigToml.atomicWrite(path_toml, renderToml(renderable, default_values));

        try {
            parseToml(path_toml);
        } catch (Exception exception) {
            System.err.println(TAG + "ensure self-check FAILED, rewriting header-only: " + exception);
            ConfigToml.atomicWrite(path_toml, renderToml(new LinkedHashMap<>(), default_values));
        }
    }

    /**
     * 三层合并 + 缓存契约归一: 条目集 = pack 现存条目; 值优先级 spec 默认 < pack 文件值 < TOML 用户表值 (字段级).
     * 输出 = 旧 getData 缓存契约: 条目ID "/" 分隔, 值 "none" -> "" (旧 read()/convertFileToDataMap 归一语义,
     * 收敛到此单点), 每条目含 spec 全集字段.
     * INCOMPATIBLE 条目 (pack 目录名内嵌前缀): spec 默认硬注入, 不吃 TOML 覆盖 (旧 read() skip=true 同语义).
     * 陈旧 TOML 表 (pack 无此条目): 不进缓存 (幽灵树防线, TreeLocation:222 遍历消费).
     * pack 扫描异常 (非缺目录): 降级为 TOML 条目集 (最后一次已知快照, spec 补缺) — 优于旧系统全空死树.
     */
    public static Map<String, Map<String, String>> resolve (Path path_toml, Path pack_root, Map<String, String> default_values) {

        LinkedHashMap<String, LinkedHashMap<String, String>> user = new LinkedHashMap<>();
        try {
            user = parseToml(path_toml);
        } catch (Exception exception) {
            // 用户文件坏: 不碰盘, 全 pack 值运行 (用户可自行修复, 下轮再试解析)
            System.err.println(TAG + "toml unparseable, running on pack values (file NOT touched): " + exception);
        }

        LinkedHashMap<String, LinkedHashMap<String, String>> pack = null;
        try {
            pack = scanPack(pack_root);
        } catch (Exception exception) {
            System.err.println(TAG + "pack scan failed, degrading to toml entry set: " + exception);
        }

        LinkedHashMap<String, Map<String, String>> out = new LinkedHashMap<>();

        if (pack == null) {

            // 降级: TOML 条目即条目集, spec 补缺字段
            for (Map.Entry<String, LinkedHashMap<String, String>> entry : user.entrySet()) {
                LinkedHashMap<String, String> merged = new LinkedHashMap<>();
                for (Map.Entry<String, String> spec : default_values.entrySet()) {
                    merged.put(spec.getKey(), entry.getValue().containsKey(spec.getKey()) == true
                            ? entry.getValue().get(spec.getKey()) : spec.getValue());
                }
                out.put(entry.getKey(), merged);
            }

        } else {

            for (Map.Entry<String, LinkedHashMap<String, String>> entry : pack.entrySet()) {

                String id = entry.getKey();

                if (id.contains(INCOMPATIBLE_PREFIX) == true) {
                    // INCOMPATIBLE: spec 默认硬注入 (含 enable=false 杀树), 不吃用户覆盖 — 旧运行时语义纯净化
                    id = id.replace(INCOMPATIBLE_PREFIX, "");
                    out.put(id, new LinkedHashMap<>(default_values));
                    continue;
                }

                LinkedHashMap<String, String> merged = new LinkedHashMap<>();
                for (Map.Entry<String, String> spec : default_values.entrySet()) {
                    merged.put(spec.getKey(), entry.getValue().containsKey(spec.getKey()) == true
                            ? entry.getValue().get(spec.getKey()) : spec.getValue());
                }

                LinkedHashMap<String, String> user_table = user.get(id);
                if (user_table != null) {
                    for (Map.Entry<String, String> field : user_table.entrySet()) {
                        if (default_values.containsKey(field.getKey()) == true) {
                            merged.put(field.getKey(), field.getValue()); // 字段级: 用户值最高优先
                        }
                    }
                }

                out.put(id, merged);
            }
        }

        // 缓存契约归一 (单点): "none" -> "" (旧 read() L275 / convertFileToDataMap L396 语义收敛)
        for (Map<String, String> fields : out.values()) {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if ("none".equals(field.getValue()) == true) field.setValue("");
            }
        }

        return out;
    }

    /**
     * TOML -> 两层 Map (公共: harness 直打). 顶层 quoted-key 表 = 条目; 表内键值归一为字符串
     * (ConfigToml.normalize 同规则: Double 防科学计数法变形等). 非表顶层键/表内嵌套值 (用户乱写) 忽略.
     * 表键 " > " -> "/" 归一 (旧 read() L264 同款: 用户沿用旧 txt 头格式仍命中).
     * 值不做 "none" 归一 — 收敛在 resolve 单点.
     */
    public static LinkedHashMap<String, LinkedHashMap<String, String>> parseToml (Path path_toml) {

        String text;
        try {
            text = Files.readString(path_toml, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new RuntimeException("read failed: " + path_toml, exception);
        }

        if (text.startsWith("﻿") == true) text = text.substring(1); // BOM 剥 (ConfigToml.readTomlFile 同纪律)

        Map<String, Object> root = new TomlParser().parse(text).valueMap();

        LinkedHashMap<String, LinkedHashMap<String, String>> out = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : root.entrySet()) {

            Map<String, Object> fields = asTableMap(entry.getValue());
            if (fields == null) continue; // 顶层标量 (用户乱写) 忽略 — 本文件面只有表

            LinkedHashMap<String, String> converted = new LinkedHashMap<>();

            for (Map.Entry<String, Object> field : fields.entrySet()) {

                if (asTableMap(field.getValue()) != null) continue; // 表内嵌套 (用户乱写) 忽略

                String normalized = ConfigToml.normalize(field.getValue());

                if (normalized != null) converted.put(field.getKey(), normalized);
            }

            String id = entry.getKey().replace(" > ", "/"); // 旧格式头兼容
            if (id.isEmpty() == false) out.put(id, converted);
        }

        return out;
    }

    /**
     * 渲染: 头部 (双语语义 + 14 字段双语说明常量) + 每条目一个 quoted-key 表, spec 字段全渲染
     * (条目缺值回落 spec 默认). 值 = 单引号字面量 (含 ' 或换行时降级双引号转义 — 防御, 产物恒可重解析);
     * 只渲染 spec 字段 (条目内未知字段丢弃, 与旧 write() 重渲染丢弃行为一致; 迁移后用户新加的未知字段
     * 由文件为真永久保留, 缓存侧过滤).
     */
    public static String renderToml (LinkedHashMap<String, LinkedHashMap<String, String>> entries, Map<String, String> default_values) {

        StringBuilder out = new StringBuilder(entries.size() * 18 * 64 + 4096);

        out.append("# Tan's Huge Trees - World Generation Config | 世界生成配置\n");
        out.append("# [EN] Generated once from the tree pack; after that this file is NEVER rewritten - your edits and comments persist forever.\n");
        out.append("# [EN] Each table = one tree. Values here override the pack. Delete a whole table = that tree falls back to pack defaults.\n");
        out.append("# [EN] Delete a single key line = that field falls back to the pack value (pack updates then apply to it).\n");
        out.append("# [EN] Delete the whole file = regenerate everything from the current pack snapshot.\n");
        out.append("# [EN] New trees from pack updates apply automatically without touching this file.\n");
        out.append("# [EN] Entries marked incompatible by the pack are not listed; they stay disabled by pack design.\n");
        out.append("# [EN] Apply changes with a world restart (or /TansHugeTrees restart).\n");
        out.append("# [中] 本文件由树包首次生成, 之后永不被重写 — 你的修改与注释永生.\n");
        out.append("# [中] 每个表 = 一棵树: 这里的值覆盖主包默认; 删除整个表 = 该树回落主包默认值.\n");
        out.append("# [中] 删除单行 = 该字段回落主包值 (主包更新对该字段重新生效).\n");
        out.append("# [中] 删除整个文件 = 按当前主包快照重新生成.\n");
        out.append("# [中] 主包更新新增的树自动生效, 无需改动本文件.\n");
        out.append("# [中] 主包标记为不兼容的条目不在此列出, 按包设计保持禁用.\n");
        out.append("# [中] 修改后重启世界生效 (或 /TansHugeTrees restart).\n");
        out.append("\n");
        out.append("# ---- Field Reference | 字段说明 ----\n");

        for (Map.Entry<String, String[]> description : DESCRIPTIONS.entrySet()) {
            if (default_values.containsKey(description.getKey()) == false) continue; // 只列 spec 内字段
            out.append("# [EN] ").append(description.getKey()).append(" : ").append(description.getValue()[0]).append("\n");
            out.append("# [中] ").append(description.getKey()).append(" : ").append(description.getValue()[1]).append("\n");
        }

        for (Map.Entry<String, LinkedHashMap<String, String>> entry : entries.entrySet()) {

            String id = entry.getKey();
            if (id.isEmpty() == true || id.contains(INCOMPATIBLE_PREFIX) == true) continue; // 防御

            out.append("\n[").append(literal(id)).append("]\n");

            for (Map.Entry<String, String> spec : default_values.entrySet()) {
                String value = entry.getValue().get(spec.getKey());
                if (value == null) value = spec.getValue();
                out.append(spec.getKey()).append(" = ").append(literal(value)).append("\n");
            }
        }

        out.append("\n");
        return out.toString();
    }

    // ============ 内部机制 ============

    /**
     * 老 txt verbatim 解析 (旧 ConfigDynamic.read() L235 语义移植):
     * 行首 "[" = 条目头 ([INCOMPATIBLE] 前缀条目整组丢弃; [LOCK]/[] 均取当前值 — LOCK 概念退役, 值平移;
     * 头部说明区在首个条目头之前, 天然不吸); " = " 行 = 字段值 (verbatim 不 trim 不归一 — 文件面保真).
     */
    private static LinkedHashMap<String, LinkedHashMap<String, String>> parseLegacyTxt (Path legacy_txt) {

        LinkedHashMap<String, LinkedHashMap<String, String>> out = new LinkedHashMap<>();

        List<String> lines;
        try {
            lines = Files.readAllLines(legacy_txt, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            System.err.println(TAG + "legacy read failed, skip migration: " + exception);
            return out;
        }

        boolean skip_entry = true;
        LinkedHashMap<String, String> current = null;

        for (String line : lines) {

            if (line.isEmpty() == true) continue;

            if (line.startsWith("[") == true) {

                if (line.startsWith(INCOMPATIBLE_PREFIX) == true) {
                    skip_entry = true;
                    current = null;
                    continue;
                }

                int close = line.indexOf("]");
                if (close < 0 || close + 2 > line.length()) continue; // 畸形头防御 (旧代码此处会越界)

                String id = line.substring(close + 2).replace(" > ", "/");
                if (id.isEmpty() == true) continue;

                current = out.computeIfAbsent(id, create -> new LinkedHashMap<>());
                skip_entry = false;

            } else if (skip_entry == false && current != null && line.contains(" = ") == true) {

                int eq = line.indexOf(" = ");
                current.put(line.substring(0, eq), line.substring(eq + 3));
            }
        }

        return out;
    }

    /**
     * pack 目录树扫描 (旧 write() L91 walk + L139 relativize 语义移植):
     * 每个文件 = 一个条目; id = 相对路径 ("/" 分隔, .txt 后缀剥除 — 旧 replace 全串替换的保守修正).
     * INCOMPATIBLE 条目: 键保留前缀 (resolve 识别用), 值不解析 (旧运行时语义 = 强制 spec 默认).
     * 排序 = id 字典序 (产物确定性; 旧 Files.walk 顺序未定义).
     */
    private static LinkedHashMap<String, LinkedHashMap<String, String>> scanPack (Path pack_root) throws Exception {

        LinkedHashMap<String, LinkedHashMap<String, String>> out = new LinkedHashMap<>();
        if (Files.isDirectory(pack_root) == false) return out;

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(pack_root)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }

        files.sort(Comparator.comparing((Path source) -> pack_root.relativize(source).toString()));

        for (Path source : files) {

            String id = pack_root.relativize(source).toString().replace("\\", "/");
            if (id.endsWith(".txt") == true) id = id.substring(0, id.length() - 4);

            if (id.contains(INCOMPATIBLE_PREFIX) == true) {
                out.put(id, new LinkedHashMap<>()); // 值空 + 前缀在键 = INCOMPATIBLE 哨兵
                continue;
            }

            out.put(id, parseFlatTxt(source));
        }

        return out;
    }

    /**
     * pack 条目 .txt -> 字段表 (" = " split, 与 OutsideUtils.convertFileToDataMap L381 同语义;
     * 独立实现 = 保持本类 harness 纯度. "none" 归一不在本层 — 收敛在 resolve 单点).
     */
    private static LinkedHashMap<String, String> parseFlatTxt (Path file) {

        LinkedHashMap<String, String> out = new LinkedHashMap<>();

        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty() == false && line.contains(" = ") == true) {
                    int eq = line.indexOf(" = ");
                    out.put(line.substring(0, eq), line.substring(eq + 3));
                }
            }
        } catch (Exception exception) {
            System.err.println(TAG + "pack entry read failed (fields -> spec defaults): " + file + " - " + exception);
        }

        return out;
    }

    /** night-config 表对象 -> Map (valueMap() 可能给出 Map 或 Config, 双形态兼容). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asTableMap (Object value) {

        if (value instanceof Map) return (Map<String, Object>) value;

        if (value instanceof com.electronwill.nightconfig.core.Config config) {
            return config.valueMap();
        }

        return null;
    }

    /** 单引号字面量 (零转义); 值含 ' 或换行 -> 降级双引号转义 (用户乱写防御, 产物恒可重解析). */
    private static String literal (String value) {

        if (value.indexOf("'") < 0 && value.indexOf("\n") < 0 && value.indexOf("\r") < 0) {
            return "'" + value + "'";
        }

        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    // 14 字段双语说明 (文案授权: max 27 深夜五裁决第 3 条 [长期记忆: 209]; 与 Handcode spec 运行契约解耦,
    // 纯自维护文案; EN = 旧 txt 头原文, CN = 授权翻译)
    private static final LinkedHashMap<String, String[]> DESCRIPTIONS = buildDescriptions();

    private static LinkedHashMap<String, String[]> buildDescriptions () {

        LinkedHashMap<String, String[]> out = new LinkedHashMap<>();

        out.put("enable", new String[]{
                "Enable world generation for that tree by set to [ true ] or disable by [ false ].",
                "启用该树的世界生成, 设 [ true ] 启用 / [ false ] 禁用."});

        out.put("spawn_type", new String[]{
                "Set how that tree spawn in the world. Set to [ normal ] for spawn by biome like normal. Set to [ waterside ] for only spawn if detects water biomes nearby, but their group can't be spawned in water biomes. Set to [ landside ] for only spawn if detects water biomes nearby, but their group can be spawned in water biomes. Set to [ shoreline ] for only spawn if detects water biomes nearby, but their group can be spawned in both their biomes and water biomes.",
                "该树的生成方式: [ normal ]=常规按群系生成; [ waterside ]=仅检测到附近水系群系时生成, 但树组不会出现在水系群系内; [ landside ]=仅检测到附近水系群系时生成, 树组可出现在水系群系内; [ shoreline ]=检测到附近水系群系时生成, 树组可出现在自身群系与水系群系."});

        out.put("biome", new String[]{
                "Change biome of that tree can place on. Supported both IDs and tags. These config supported multiple conditions, use [ / ] for [ OR ], use [ , ] for [ AND ]. For example, a tree that spawn in 2 type of biomes. One is biomes tagged as forest, but not birch forest. Other one is taiga forest. It will be [ #minecraft:is_forest, !minecraft:birch_forest / minecraft:taiga ].",
                "该树可生成的群系, 支持ID与标签, 复合条件 [ / ]=或, [ , ]=与. 示例: 森林标签但排除桦木森林, 或针叶林 → [ #minecraft:is_forest, !minecraft:birch_forest / minecraft:taiga ]."});

        out.put("ground_block", new String[]{
                "Change ground block of that tree can place on. Supported both IDs and tags. These config supported multiple conditions, use [ / ] for [ OR ], use [ , ] for [ AND ]. For example, a tree that spawn on 2 type of blocks. One is any of blocks tagged as dirt but not grass. Other one is sand block. It will be [ #minecraft:dirt, !minecraft:grass / minecraft:sand ]. Important note, it may not works with trees that one side farther than 32 blocks.",
                "该树可立足的方块, 支持ID与标签, 复合条件同上. 示例: [ #minecraft:dirt, !minecraft:grass / minecraft:sand ]. 注意: 树体单侧超出32格时可能失效."});

        out.put("rarity", new String[]{
                "Change how common of that tree. Lower means rarer. Only supported number between 0 and 100 (can be non-integer number).",
                "该树的常见度, 数值越低越稀有, 仅支持 0 到 100 之间的数字 (可为小数)."});

        out.put("min_distance", new String[]{
                "Change distance of trees in the same species. This is distance in block with Y position ignored. Can be any number, but higher number may slow down region pre-location time.",
                "同树种最小间距 (方块距离, 忽略Y轴). 可为任意数, 数值过大可能拖慢区域预定位耗时."});

        out.put("group_size", new String[]{
                "Spawn addition trees of the same species of it around the area. To use this, set min and max count of trees per group that upper than 1. For example, min 1 and max 5, will be [ 1 <> 5 ]. Be careful to use this, as it can affect scan time. This config also change the way other config options work. Rarity will be how common of the group. Min distance is between trees, not between groups. Waterside config will only detect once at spawn location of the group.",
                "在周围区域成组生成同种树: 设定每组的数量区间, 如 [ 1 <> 5 ]. 注意影响扫描耗时; 该项会改变其他选项的作用方式: rarity 变为组的常见度, min_distance 变为树间 (而非组间) 距离, 水系检测只在组生成点执行一次."});

        out.put("dead_tree_chance", new String[]{
                "Set how common of that tree to spawn as a dead tree. Note that the trees can still be dead trees when spawn in unviable ecology, such as land trees in water.",
                "该树生成为枯树的概率. 注: 生态不适宜时 (如陆地树落在水里) 也会成为枯树."});

        out.put("dead_tree_level", new String[]{
                "Randomly select style of dead trees to make it looks more variety. This config will be randomly select a number from the list, or use \"auto\" and \"auto_pine\" for automatic selection. Only supported numbers 1XX/2XX/3XX with sub numbers 10/20/30/40/50/60/70/80/90 and 11/21/31/41/51. Set to 1XX for normal dead trees, 2XX and 3XX for coarse woody debris style but with and without roots. For sub numbers 10/20/30/40/50 is no leaves, no sprig, no twig, no limb, and no branch. With random decay 10-50%. For 11/21/31/41/51 is the same as previous but no random decay. For 60/70 is only trunk with random length 50-100% and hollowed. For 80/90 is only trunk with random length 0-50% and hollowed.",
                "随机选择枯树样式以增加多样性: 从列表随机取数, 或填 \"auto\" / \"auto_pine\" 自动选择. 仅支持 1XX/2XX/3XX 及子号 10 到 90 与 11/21/31/41/51: 1XX=普通枯树, 2XX/3XX=粗木质残骸风格 (带/不带树根); 子号 10/20/30/40/50 依次为 无叶/无嫩枝/无细枝/无大枝/无树枝, 附带 10-50% 随机腐朽; 11/21/31/41/51 同上但无随机腐朽; 60/70=仅树干 (随机长度 50-100%, 中空); 80/90=仅树干 (随机长度 0-50%, 中空)."});

        out.put("start_height_offset", new String[]{
                "Randomly spawn that tree with custom height from the ground. To use this, set min and max height. For example, lowest -10 highest +10, will be [ -10 <> 10 ].",
                "相对地面随机偏移生成高度: 设区间, 如最低 -10 最高 +10 → [ -10 <> 10 ]."});

        out.put("rotation", new String[]{
                "Set rotation of that tree. For random direction, use [ random ]. For specific direction, use [ north ], [ west ], [ east ], or [ south ]. Only supported one value per tree.",
                "树的朝向: [ random ]=随机, 或 [ north ] / [ west ] / [ east ] / [ south ] 指定, 每树一值."});

        out.put("mirrored", new String[]{
                "Set mirror effect for that tree. For random value, use [ random ]. For specific value with 50% random, use [ random_x ] or [ random_z ]. Use [ off ] to disable mirror effect.",
                "镜像效果: [ random ]=随机; [ random_x ] / [ random_z ]=对应轴 50% 概率镜像; [ off ]=禁用."});

        out.put("path_storage", new String[]{
                "The part of tree shapes that will be used by that tree",
                "该树使用的树形数据 (storage) 路径."});

        out.put("path_settings", new String[]{
                "The path of tree settings that will be applied into that tree",
                "应用到该树的树设置 (settings) 路径."});

        return out;
    }
}