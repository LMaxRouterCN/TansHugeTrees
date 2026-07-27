


        

          
package tannyjung.tanshugetrees_handcode.systems;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import tannyjung.tanshugetrees_core.Core;
import tannyjung.tanshugetrees_core.game.GameUtils;
import tannyjung.tanshugetrees_core.outside.CacheManager;
import tannyjung.tanshugetrees_core.outside.FileManager;
import tannyjung.tanshugetrees_core.outside.OutsideUtils;
import tannyjung.tanshugetrees_handcode.Handcode;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.*;

public class Caches {

    public static class TreeShape {

        // [LMax Fix V13] 添加 synchronized 保证多线程下文件加载的安全，防止 CacheManager 内部非线程安全导致的死锁
        // [LMax Fix V15] 废除类级 synchronized，改为 per-key 锁。
        // 原代码 synchronized 静态方法导致所有区块生成线程串行化，
        // 在出生点区块生成时（~441个区块并发）造成极端锁竞争，世界生成卡死在0%。
        private static final java.util.concurrent.ConcurrentHashMap<String, Object> shape_locks = new java.util.concurrent.ConcurrentHashMap<>();

        private static void getTreeShape (String id) {
            // 快速路径：已缓存则直接返回（无锁）
            if (CacheManager.DataShort.getArray("tree_shape_size").containsKey(id)) {
                return;
            }

            // per-key 锁：只有加载同一个形状文件的线程才互相等待，不同形状文件可以并发加载
            Object lock = shape_locks.computeIfAbsent(id, k -> new Object());
            synchronized (lock) {
                // 双重检查：可能在等锁期间已被其他线程加载
                if (CacheManager.DataShort.getArray("tree_shape_size").containsKey(id)) {
                    return;
                }

                short[] data_size = null;
                int[] data_block_count = null;
                short[] data_shape = null;

                // Get Data
                {
                    String path = "";

                    // Get path
                    {
                        try {
                            String[] split = id.split("\\|");
                            path = Core.path_config + "/dev/temporary/" + split[0] + "/" + split[1];
                        } catch (Exception exception) {
                            OutsideUtils.exception(new Exception(), exception, "");
                            return;
                        }
                    }

                    ByteBuffer buffer = FileManager.readBIN(path);

                    if (buffer.remaining() > 0) {
                        // Size
                        {
                            int count = 6;
                            data_size = new short[count];

                            for (int number = 0; number < count; number++) {
                                data_size[number] = buffer.getShort();
                            }
                        }

                        // Block Count
                        {
                            int count = 6;
                            data_block_count = new int[count];

                            for (int number = 0; number < count; number++) {
                                data_block_count[number] = buffer.getInt();
                            }
                        }

                        // Shape
                        {
                            ShortBuffer buffer_convert = buffer.asShortBuffer();
                            data_shape = new short[buffer_convert.remaining()];
                            buffer_convert.get(data_shape);
                        }
                    }
                }

                if (data_size == null) data_size = new short[0];
                if (data_block_count == null) data_block_count = new int[0];
                if (data_shape == null) data_shape = new short[0];

                CacheManager.DataShort.setArray("tree_shape_size", id, data_size);
                CacheManager.DataInt.setArray("tree_shape_block_count", id, data_block_count);
                CacheManager.DataShort.setArray("tree_shape_data", id, data_shape);
            }
            // 加载完成后移除锁对象，防止 shape_locks 无限增长
            shape_locks.remove(id);
        }
        public static short[] getTreeShapeSize (String id) {
            // [Poker Agent Fix] 优化延迟加载逻辑，减少对 CacheManager 的重复调用
            Map<String, short[]> cache = CacheManager.DataShort.getArray("tree_shape_size");
            short[] data = cache.get(id);
            if (data == null) {
                getTreeShape(id);
                data = cache.getOrDefault(id, new short[0]);
            }
            return data;
        }

        public static int[] getTreeShapeBlockCount (String id) {
            // [Poker Agent Fix] 优化延迟加载逻辑，减少对 CacheManager 的重复调用
            Map<String, int[]> cache = CacheManager.DataInt.getArray("tree_shape_block_count");
            int[] data = cache.get(id);
            if (data == null) {
                getTreeShape(id);
                data = cache.getOrDefault(id, new int[0]);
            }
            return data;
        }

        public static short[] getTreeShapeData (String id) {
            // [Poker Agent Fix] 优化延迟加载逻辑，减少对 CacheManager 的重复调用
            Map<String, short[]> cache = CacheManager.DataShort.getArray("tree_shape_data");
            short[] data = cache.get(id);
            if (data == null) {
                getTreeShape(id);
                data = cache.getOrDefault(id, new short[0]);
            }
            return data;
        }

    }

    public static class TreeSettings {

        // [执行代号22 - 任务 4.1] 引入 BlockState 缓存，避免每次种树都重新解析文本，解决 TPS 尖峰
        private static final Map<String, Map<Short, BlockState>> blockStateCache = new java.util.concurrent.ConcurrentHashMap<>();

        // [LMax Fix V15] 废除类级 synchronized，改为 per-key 锁（同 TreeShape 修复）
        private static final java.util.concurrent.ConcurrentHashMap<String, Object> settings_locks = new java.util.concurrent.ConcurrentHashMap<>();

        private static void get (String id) {

            // 快速路径：已缓存则直接返回（无锁）
            if (CacheManager.DataText.getMap("tree_settings_normal").containsKey(id)) {
                return;
            }

            Object lock = settings_locks.computeIfAbsent(id, k -> new Object());
            synchronized (lock) {
                // 双重检查
                if (CacheManager.DataText.getMap("tree_settings_normal").containsKey(id)) {
                    return;
                }

                // [执行代号22 - 任务 4.1] 数据更新时清除旧的 BlockState 缓存
                blockStateCache.remove(id);

                Map<String, String> data_normal = new HashMap<>();
                Map<String, String> data_block = new HashMap<>();
                Map<String, String> data_function = new HashMap<>();
                Set<Short> data_keep = new HashSet<>();
                short[] data_leaves_type = new short[2];

                // Get Data
                {
                    String[] split = null;
                    String key = "";
                    String value = "";
                    byte leaves_type = 0;

                    // [执行代号22 - 任务 4.1] 捕获文件不存在的异常，防止 NPE 打断流程并确保后续缓存空数据
                    List<String> lines = null;
                    try {
                        lines = new ArrayList<>(java.util.Arrays.asList(FileManager.readTXT(Core.path_config + "/dev/temporary/" + id + ".txt")));
                    } catch (Exception e) {
                        lines = new ArrayList<>();
                    }
                    if (lines == null) lines = new ArrayList<>();

                    for (String scan : lines) {
                        if (scan.isEmpty() == false) {
                            try {
                                split = scan.split(" = ");
                                key = split[0];
                                value = split[1];
                            } catch (Exception exception) {
                                OutsideUtils.exception(new Exception(), exception, "");
                                break;
                            }

                            if (key.startsWith("Block ") == true) {
                                {
                                    key = key.substring("Block ### ".length());

                                    if (value.endsWith(" keep") == true) {
                                        value = value.substring(0, value.length() - " keep".length());
                                        data_keep.add(Short.parseShort(key));
                                    }

                                    data_block.put(key, value);

                                    if (key.startsWith("120") == true) {
                                        if (value.endsWith("]") == true) {
                                            value = value.substring(0, value.indexOf("["));
                                        }

                                        leaves_type = Byte.parseByte(key.substring("120".length()));

                                        if (Handcode.Config.deciduous_leaves_list.contains(value) == true) {
                                            data_leaves_type[leaves_type] = 1;
                                        } else if (Handcode.Config.coniferous_leaves_list.contains(value) == true) {
                                            data_leaves_type[leaves_type] = 2;
                                        }
                                    }
                                }
                            } else if (key.startsWith("Function ") == true) {
                                key = key.substring("Function ## ".length());
                                data_function.put(key, value);
                            } else {
                                data_normal.put(key, value);
                            }
                        }
                    }
                }

                // [执行代号22 - 任务 4.1] 无论文件是否存在，都缓存结果，彻底解决缓存穿透
                CacheManager.DataText.setMap("tree_settings_normal", id, data_normal);
                CacheManager.DataText.setMap("tree_settings_block", id, data_block);
                CacheManager.DataText.setMap("tree_settings_function", id, data_function);
                CacheManager.DataShort.setSet("tree_settings_keep", id, data_keep);
                CacheManager.DataShort.setArray("tree_settings_leaves_type", id, data_leaves_type);
            }
            settings_locks.remove(id);
        }

        public static Map<String, String> getNormal (String id) {
            Map<String, String> data = CacheManager.DataText.getMap("tree_settings_normal").get(id);
            if (data == null) {
                get(id);
                data = CacheManager.DataText.getMap("tree_settings_normal").get(id);
                if (data == null) {
                    data = new HashMap<>();
                }
            }
            return data;
        }

        public static Map<Short, BlockState> getBlock (ServerLevel level_server, String id) {
            // [执行代号22 - 任务 4.1] 命中缓存直接返回，避免重复解析 BlockState
            Map<Short, BlockState> cachedState = blockStateCache.get(id);
            if (cachedState != null) {
                return cachedState;
            }

            Map<String, String> data = CacheManager.DataText.getMap("tree_settings_block").get(id);
            if (data == null) {
                get(id);
                data = CacheManager.DataText.getMap("tree_settings_block").get(id);
                if (data == null) {
                    data = new HashMap<>();
                }
            }

            Map<Short, BlockState> convert = new HashMap<>();
            for (Map.Entry<String, String> entry : data.entrySet()) {
                convert.put(Short.parseShort(entry.getKey()), GameUtils.Tile.fromText(level_server, entry.getValue()));
            }

            blockStateCache.put(id, convert);
            return convert;
        }

        public static Map<Short, String> getFunction (String id) {
            Map<String, String> data = CacheManager.DataText.getMap("tree_settings_function").get(id);
            if (data == null) {
                get(id);
                data = CacheManager.DataText.getMap("tree_settings_function").get(id);
                if (data == null) {
                    data = new HashMap<>();
                }
            }

            Map<Short, String> convert = new HashMap<>();
            for (Map.Entry<String, String> entry : data.entrySet()) {
                convert.put(Short.parseShort(entry.getKey()), entry.getValue());
            }
            return convert;
        }

        public static Set<Short> getKeep (String id) {
            Set<Short> data = CacheManager.DataShort.getSet("tree_settings_keep").get(id);
            if (data == null) {
                get(id);
                data = CacheManager.DataShort.getSet("tree_settings_keep").get(id);
                if (data == null) {
                    data = new HashSet<>();
                }
            }
            return data;
        }

        public static short[] getLeavesType (String id) {
            short[] data = CacheManager.DataShort.getArray("tree_settings_leaves_type").get(id);
            if (data == null) {
                get(id);
                data = CacheManager.DataShort.getArray("tree_settings_leaves_type").get(id);
                if (data == null) {
                    data = new short[0];
                }
            }
            return data;
        }

    }

}