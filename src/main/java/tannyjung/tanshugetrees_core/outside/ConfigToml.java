package tannyjung.tanshugetrees_core.outside;

// [U5 Stage1] config.txt -> config.toml 革命: 机制层 (纯 night-config + JDK, 零 tannyjung 内部依赖,
// 以支持脱离 Minecraft/gradle 独立编译与 harness 验证; 日志走 System.err 前缀, 不引 OutsideUtils).
//
// 架构 (混合读写, 二者不对称是特性):
//   读 = night-config TomlParser 解析用户文件 (健壮: 吃得住用户手写的各种合法 TOML 变体),
//        归一为 Map<String,String> 保真字符串 (Long 直接 toString; Double 经 BigDecimal.toPlainString
//        防 0.0001 -> "1.0E-4" 科学计数法变形; Boolean/String 原样; List 防御性 join).
//   写 = 模板驱动 (骨架锁定: 键顺序/分组注释/双语文案全部来自模板文本, 写回只在 key 行填值,
//        规避 night-config writer 的重排/注释丢失不确定性, diff 恒干净, 双语注释恒在).
//   值类型形态由模板声明 (模板即 TOML 语法本身):
//        'literal'      -> string 形态 (斜杠列表/表达式/方块列表/自由文本)
//        true / false   -> bool 形态
//        100 / -5       -> int 形态
//        1.0 / 0.75     -> float 形态
//   用户值写入时跟随模板形态; 形态校验失败 (如 int 位出现非数字) 防御回退模板默认值并警告,
//   保证 render 产物永远可被 night-config 重新 parse (写后自动回读自检, 自愈闭环).
//
// 迁移语义 (一次性, 判例 174 教训=解析不测会崩, 故迁移器为独立干净实现不复用 ConfigClassic 脏结构):
//   旧 config.txt 存在且未迁移 -> 老格式 parse (含 "| Default is [ v ]" 对齐语义, 与实盘迁移预演一致):
//   用户值 != 旧默认 -> 保用户值; 否则 -> 取新模板默认. 旧文件改名 <name>.txt.migrated 保档不删.
//   值优先级: 现存 toml 用户值 > txt 用户改值 > 模板默认.
//
// 原子性: 写 tmp + Files.move(ATOMIC_MOVE, REPLACE_EXISTING), 降级 REPLACE_EXISTING 平台兜底.

import com.electronwill.nightconfig.toml.TomlParser;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConfigToml {

    private static final String TAG = "[THT-ConfigToml] ";

    // ============ 读侧 (公共 API, 契约 = 旧 ConfigClassic.getValues: Map<String,String>) ============

    /**
     * 读 TOML 用户配置并归一为字符串 Map. 文件不存在 -> 空 Map (与旧 getValues 同语义,
     * 下游 Boolean.parseBoolean(null)=false 等行为 parity). 解析失败 -> 抛出上层决定
     * (repair 路径会捕获走重建, getValues 路径让调用方看到异常而不是静默吞掉半份配置).
     */
    public static Map<String, String> getValues (String path_toml) {

        Map<String, String> out = new LinkedHashMap<>();
        Path path = Path.of(path_toml);

        if (Files.isRegularFile(path) == false) {
            return out;
        }

        for (Map.Entry<String, Object> entry : readTomlFile(path).entrySet()) {
            String normalized = normalize(entry.getValue());
            if (normalized != null) {
                out.put(entry.getKey(), normalized);
            }
        }

        return out;
    }

    /**
     * 完整 lifecycle (替代 ConfigClassic.repair 的语义):
     *   1. txt 在场且未迁移 -> 迁移 (用户改值保值, 其余取模板默认), txt 改名 .txt.migrated
     *   2. toml 在场且可解析 -> 现值作为基线; 缺键补模板默认; 未知键丢弃 (模板骨架刷新)
     *   3. toml 不存在 / 解析失败 -> 模板默认起步 (+ 迁移值覆盖)
     *   4. 模板驱动渲染 + 原子写 + 回读自检 (失败 -> 回写纯净模板, 保证永远有合法文件)
     */
    public static void repair (String path_toml, String template_toml) {

        Path path = Path.of(path_toml);
        Template template = parseTemplate(template_toml.replace("\r\n", "\n")); // [U5-B2] CRLF 归一: Java 文本块继承源码 EOL, 统一 LF 防产物 EOL 混血
        Map<String, String> merged = new LinkedHashMap<>(template.defaults); // 起步 = 模板默认

        // --- 步骤 1: 旧 txt 迁移 (一次性) ---
        Path legacy_txt = path.resolveSibling(path.getFileName().toString().replaceAll("\\.toml$", "") + ".txt");
        Path legacy_mark = legacy_txt.resolveSibling(legacy_txt.getFileName() + ".migrated");

        if (Files.isRegularFile(legacy_txt) == true && Files.exists(legacy_mark) == false) {

            Map<String, String> user_edits = parseLegacyUserEdits(legacy_txt);

            // 值优先级: 现存 toml 用户值 > txt 用户改值 > 模板默认 -> txt 改值只填还没被 toml 接管的键
            for (Map.Entry<String, String> edit : user_edits.entrySet()) {
                String key = edit.getKey();
                if (template.defaults.containsKey(key) == true) {
                    merged.put(key, edit.getValue());
                }
            }

            try {
                // 保档不删: 迁移完成后改名, 既是标记也是用户数据备份
                Files.move(legacy_txt, legacy_mark, StandardCopyOption.REPLACE_EXISTING);
                System.out.println(TAG + "migrated legacy config: " + legacy_txt.getFileName() + " -> "
                        + legacy_mark.getFileName() + " (user edits kept: " + user_edits.size() + ")");
            } catch (Exception exception) {
                // 改名失败 (文件被占用等) -> 迁移值已并入, 下轮会再尝试; 不阻塞配置生成
                System.err.println(TAG + "legacy rename failed, will retry next run: " + exception);
            }
        }

        // --- 步骤 2: 现存 toml 基线 (覆盖迁移值 = toml 侧优先) ---
        if (Files.isRegularFile(path) == true) {
            try {
                Map<String, String> current = getValues(path_toml);
                for (String key : template.defaults.keySet()) {
                    if (current.containsKey(key) == true) {
                        merged.put(key, current.get(key));
                    }
                }
            } catch (Exception exception) {
                // 解析失败: 模板默认起步 (迁移值已在 merged 里, 不丢), 警告可见
                System.err.println(TAG + "existing toml unparseable, rebuilding from template: " + exception);
            }
        }

        // --- 步骤 3: 模板驱动渲染 + 原子写 ---
        List<String> warnings = new ArrayList<>();
        String rendered = render(template, merged, warnings);
        atomicWrite(path, rendered);

        for (String warning : warnings) {
            System.err.println(TAG + "value form fallback to default: " + warning);
        }

        // --- 步骤 4: 回读自检 (防假绿: 写出来的文件必须能被 night-config 重新 parse) ---
        try {
            readTomlFile(path);
        } catch (Exception exception) {
            System.err.println(TAG + "self-check FAILED, falling back to pristine template: " + exception);
            atomicWrite(path, render(template, new LinkedHashMap<>(template.defaults), warnings));
        }
    }

    // ============ 内部机制 ============

    /** night-config 解析 (读侧唯一入口). 剥 BOM (用户编辑器可能写入, 键名防污染). */
    private static Map<String, Object> readTomlFile (Path path) {

        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new RuntimeException("read failed: " + path, exception);
        }

        if (text.startsWith("\uFEFF") == true) {
            text = text.substring(1);
        }

        return new TomlParser().parse(text).valueMap();
    }

    /** Object -> String 保真归一 (防科学计数法变形, 防半份配置). */
    private static String normalize (Object value) {

        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Boolean bool) {
            return bool.toString();
        }
        if (value instanceof Integer integer) {
            return integer.toString();
        }
        if (value instanceof Long long_value) {
            return long_value.toString();
        }
        if (value instanceof Double double_value) {
            // Double.toString(0.0001) = "1.0E-4" -> BigDecimal.toPlainString() 保 "0.0001"
            return plainDecimal(double_value.toString()); // [U5-B2] E branch -> plainDecimal
        }
        if (value instanceof Float float_value) {
            return plainDecimal(float_value.toString()); // [U5-B2]
        }
        if (value instanceof List<?> list) {
            // 模板不使用 TOML 数组; 防御: 用户手写数组时 join 为旧斜杠语义而不是丢弃
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                String normalized = normalize(item);
                if (normalized != null) {
                    parts.add(normalized);
                }
            }
            return String.join(" / ", parts);
        }

        return String.valueOf(value);
    }

    // [U5-B2] Double/Float literal fidelity: no E -> keep raw (1.0 stays 1.0, never collapses to 1);
    //         with E -> plain expand + strip trailing zeros (1.0E-4 -> 0.0001). Idempotent fixed point.
    private static String plainDecimal (String raw) {
        if (raw.indexOf('E') < 0 && raw.indexOf('e') < 0) {
            return raw;
        }
        return new BigDecimal(raw).stripTrailingZeros().toPlainString();
    }

    /** 模板结构: defaults 保序 (渲染顺序即模板文本顺序), forms 键 -> 值形态. */
    private static final class Template {
        final Map<String, String> defaults = new LinkedHashMap<>();
        final Map<String, String> forms = new LinkedHashMap<>();
        final List<String> raw_lines = new ArrayList<>(); // 模板原始行表: render 骨架 (顺序即文本顺序)
    }

    /**
     * 逐行提取模板 (键顺序 = 文本顺序; 默认值/形态 = 键行值的 TOML 语法本身).
     * 注释行/空行/分组线不进结构 —— 它们由 render 逐行透传, 模板文本即渲染骨架.
     */
    private static Template parseTemplate (String template_text) {

        Template template = new Template();

        template.raw_lines.addAll(List.of(template_text.split("\n", -1)));

        for (String line : template.raw_lines) {

            String trimmed = line.trim();
            if (trimmed.startsWith("#") == true || trimmed.isEmpty() == true) {
                continue;
            }

            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue; // 分组线等非键行
            }

            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (key.isEmpty() == true || value.isEmpty() == true) {
                continue;
            }

            String form;
            String default_value;

            if (value.startsWith("'") == true && value.endsWith("'") == true && value.length() >= 2) {
                form = "string";
                default_value = value.substring(1, value.length() - 1);
            } else if (value.equals("true") == true || value.equals("false") == true) {
                form = "bool";
                default_value = value;
            } else if (value.matches("-?\\d+") == true) {
                form = "int";
                default_value = value;
            } else {
                form = "float"; // 其余裸值按浮点 (模板写作约定)
                default_value = value;
            }

            template.defaults.put(key, default_value);
            template.forms.put(key, form);
        }

        return template;
    }

    /**
     * 老格式 txt -> 用户改值表 (只返回 值 != 旧默认 的键; 独立干净实现, 含 U4 判例 174 运行时守卫语义:
     * 竖线注释行不吸作配置项). "| Default is [ v1 ] [ v2 ]" 行按位置对齐其前面连续键组.
     */
    private static Map<String, String> parseLegacyUserEdits (Path legacy_txt) {

        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> defaults = new LinkedHashMap<>();
        List<String> pending_keys = new ArrayList<>();

        List<String> lines;
        try {
            lines = Files.readAllLines(legacy_txt, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            System.err.println(TAG + "legacy read failed, skip migration: " + exception);
            return values;
        }

        for (String line : lines) {

            if (line.startsWith("| Default is ") == true) {
                // "[ v1 ] [ v2 ] ..." 逐对提取, 与前组键按位对齐 (老 ConfigClassic.repair 语义)
                List<String> extracted = new ArrayList<>();
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("\\[ ([^\\]]*) \\]").matcher(line.substring("| Default is ".length()));
                while (matcher.find() == true) {
                    extracted.add(matcher.group(1));
                }
                for (int index = 0; index < extracted.size() && index < pending_keys.size(); index++) {
                    defaults.put(pending_keys.get(index), extracted.get(index));
                }
                pending_keys.clear();

            } else if (line.isBlank() == false && line.startsWith("|") == false && line.contains(" = ") == true) {
                // [U4 判例174运行时守卫] 竖线注释行不吸作配置项; 键行入值表并等待默认对齐
                int eq = line.indexOf(" = ");
                String key = line.substring(0, eq).trim();
                values.put(key, line.substring(eq + 3).trim());
                pending_keys.add(key);
            }
        }

        // 只保留用户真实改过的键 (值 != 旧默认); 未知键由 repair 侧模板过滤
        Map<String, String> user_edits = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String old_default = defaults.get(entry.getKey());
            if (old_default == null || old_default.equals(entry.getValue()) == false) {
                user_edits.put(entry.getKey(), entry.getValue());
            }
        }

        return user_edits;
    }

    /** 模板驱动渲染: 模板行透传, 键行填 merged 值 (形态跟随模板声明). */
    private static String render (Template template, Map<String, String> merged, List<String> warnings) {

        StringBuilder out = new StringBuilder(template_text_length_hint(merged.size()));

        for (String line : template.raw_lines) {

            String trimmed = line.trim();
            String replacement = null;

            if (trimmed.startsWith("#") == false && trimmed.isEmpty() == false) {
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    String key = trimmed.substring(0, eq).trim();
                    if (template.defaults.containsKey(key) == true) {
                        replacement = line.substring(0, line.indexOf(key)) + key + " = "
                                + formatValue(key, merged.get(key), template, warnings);
                    }
                }
            }

            out.append(replacement != null ? replacement : line).append("\n");
        }

        return out.toString();
    }

    /** 估算缓冲区初始容量 (小优化, 避免渲染期 StringBuilder 反复扩容). */
    private static int template_text_length_hint (int key_count) {
        return key_count * 96 + 2048;
    }

    /**
     * 值格式化: 形态跟随模板. 校验失败 -> 回退模板默认并记警告 (保证产物永远可被重新 parse).
     */
    private static String formatValue (String key, String value, Template template, List<String> warnings) {

        String form = template.forms.get(key);
        String fallback = template.defaults.get(key);

        if (value == null || value.isEmpty() == true) {
            return literalOf(fallback, form);
        }
        // [U5-B2] Value containing newline = illegal single-line key-value in TOML, fall back to default (prevents unparseable output)
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) { warnings.add(key + " <newline> form=" + form); return literalOf(fallback, form); }

        if ("string".equals(form) == true) {
            // 单引号字面量零转义 (max 转义红线); 值含单引号时降级双引号转义 (用户乱写防御)
            if (value.contains("'") == false) {
                return "'" + value + "'";
            }
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if ("bool".equals(form) == true) {
            if (value.equals("true") == true || value.equals("false") == true) {
                return value;
            }
        } else if ("int".equals(form) == true) {
            if (value.matches("-?\\d+") == true) {
                return value;
            }
        } else if ("float".equals(form) == true) {
            if (value.matches("-?\\d+(\\.\\d+)?") == true) {
                return value;
            }
        }

        warnings.add(key + " = " + value + " (form " + form + ", default " + fallback + ")");
        return literalOf(fallback, form);
    }

    /** 默认值按形态回写 (string -> 单引号; 裸形态原样). */
    private static String literalOf (String default_value, String form) {
        if ("string".equals(form) == true) {
            return "'" + default_value + "'";
        }
        return default_value;
    }

    /** 原子写: tmp + ATOMIC_MOVE (降级普通 move), 显式 UTF-8 无 BOM. */
    private static void atomicWrite (Path path, String content) {

        try {
            Files.createDirectories(path.getParent());
        } catch (Exception exception) {
            throw new RuntimeException("mkdirs failed: " + path.getParent(), exception);
        }

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");

        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException move_unsupported) {
                // 平台/文件系统不支持原子移动 -> 降级为普通替换移动 (内容已完整落盘, 语义等价)
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new RuntimeException("atomic write failed: " + path, exception);
        }
    }
}