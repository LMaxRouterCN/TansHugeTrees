package tannyjung.tanshugetrees_core.outside;

// [U7] world_gen TOML 革命: 编排层瘦身 (原 51286B 全量重渲染写门退役).
// 旧职责: read(txt) -> 全 pack 扫描 -> write() 逐条目重渲染 -> writeTXT 覆盖 (每轮碾掉用户未 LOCK 的编辑).
// 新职责: spec 解析 -> 一次性迁移 -> 文件为真建盘 -> 三层合并 -> 缓存注入. 机制细节全部在 ConfigDynamicToml.
//
// 值层语义: spec 默认 < pack 文件值 < TOML 用户值 (字段级). LOCK 概念整体退役
// (旧: 用户须手动 [LOCK] 防重置; 新: 文件为真, 用户值天然最高优先, 删行/删表 = 回落 pack = 重置手势).
//
// 缓存契约不变 (消费端 TreeLocation/TreePlacer 零改动):
//   Map<条目ID ("/"分隔, 旧 read() L264 形态), Map<字段, 值字符串>>; 值 "none" -> "" 归一
//   (旧 L275 语义, 收敛至 ConfigDynamicToml.resolve 单点); 旧 read() 每条目注入的 "lock" 伪键
//   不再存在 (全库唯一消费者 = 已退役的 write(), recon6 实证).

import tannyjung.tanshugetrees_core.Core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConfigDynamic {

    // 签名不变 (Handcode:68 调用点零改动); options = Handcode spec 文本块 (默认值单一事实源, 原作区不碰).
    public static void reorganize (String name, String scan_at, String options) {

        // [U7] spec 解析: 只取默认值表 (描述文案 = ConfigDynamicToml 内置双语常量, 与运行契约解耦;
        // 旧代码同时解析描述用于渲染 txt 头部 — 渲染已死, 描述职责平移).
        // 防御加固: 空行/注释行跳过; limit 2 防值内 " = " 误劈 (旧代码 split[1] 无守卫).
        // 注: Handcode 文本块经 Java text-block 缩进剥离后行首无缩进, trim() 为纵深防御.
        LinkedHashMap<String, String> default_values = new LinkedHashMap<>();

        for (String scan : options.split("\n")) {

            if (scan.startsWith("# ") == true || scan.isEmpty() == true) continue;

            String[] split = scan.split(" = ", 2);

            if (split.length == 2) default_values.put(split[0].trim(), split[1].trim());
        }

        Path path_toml = Path.of(Core.path_config + "/config_" + name + ".toml");
        Path path_legacy = Path.of(Core.path_config + "/config_" + name + ".txt");
        Path path_pack = Path.of(Core.path_config + "/dev/temporary/" + scan_at);

        // 1. 一次性迁移: 老 txt verbatim -> TOML (含 .txt.migrated 保档; INCOMPATIBLE 条目丢弃).
        //    磁盘错误不致命 (捕获继续, resolve 以 pack 值运行, 下轮重试) — 与旧 FileManager.writeTXT 吞异常同级容错.
        boolean migrated = false;

        try {
            migrated = ConfigDynamicToml.migrateLegacy(path_toml, path_legacy, default_values);
        } catch (Exception exception) {
            System.err.println("[THT-ConfigDynamic] migration failed (will retry next run): " + exception);
        }

        // 2. 文件为真建盘门: TOML 缺失 -> pack 快照渲染一次; 已存在 (含刚迁移) = 零接触. 同级容错.
        try {
            ConfigDynamicToml.ensureFromPack(path_toml, path_pack, default_values);
        } catch (Exception exception) {
            System.err.println("[THT-ConfigDynamic] initial file creation failed: " + exception);
        }

        // 3. 三层合并 -> 旧缓存契约形态, 注入 CacheManager (setMap 形态与旧 Apply 段一致).
        //    resolve 内部自吞解析/扫描异常, 此处无文件 IO, 不需要外层包裹.
        Map<String, Map<String, String>> data = ConfigDynamicToml.resolve(path_toml, path_pack, default_values);

        for (Map.Entry<String, Map<String, String>> entry : data.entrySet()) {
            CacheManager.DataText.setMap("config_" + name, entry.getKey(), entry.getValue());
        }

        // [THT-DEBUG] 运行观测 (空缓存 = 冷启动零树事故类别, 保留可观测性)
        System.out.println("[THT-DEBUG] ConfigDynamic.reorganize: resolved " + data.size() + " entries for config_" + name
                + (migrated == true ? " (first-run migration done)" : ""));

        if (data.isEmpty() == true) {
            Core.logger.warn("[THT-DEBUG] ConfigDynamic.reorganize() - empty data for name: " + name);
        }
    }

    // 消费端唯一入口 (TreeLocation:222/755/806, TreePlacer:1659): CacheManager 缓存读, 契约零变化.
    public static Map<String, Map<String, String>> getData (String name) {
        return CacheManager.DataText.getMap("config_" + name);
    }
}