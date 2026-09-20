package tannyjung.tanshugetrees_core.outside;

import java.util.concurrent.ConcurrentHashMap;

import tannyjung.tanshugetrees_core.Core;

import java.util.*;

public class CacheManager {

    public static String clear () {

        int size = 0;
        size = size + DataLogic.clear();
        size = size + DataText.clear();
        size = size + DataShort.clear();
        size = size + DataInt.clear();

        if (size < 1024) {

            return size + " B";

        } else if (size < 1048576) {

            return OutsideUtils.Mathematics.shorterDouble((double) size / 1024.0, 2) + " KB";

        } else {

            return OutsideUtils.Mathematics.shorterDouble((double) size / 1048576.0, 2) + " MB";

        }

    }

    public static class DataLogic {

        private static final Map<String, Map<String, Boolean>> normal = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, boolean[]>> array = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, List<Boolean>>> list = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, Map<String, Boolean>>> map = new ConcurrentHashMap<>();

        private static int clear () {

            int size = 0;

            // Normal
            {


                for (Map.Entry<String, Map<String, Boolean>> entry : normal.entrySet()) {

                    size = size + entry.getValue().size();

                }

                normal.clear();


            }

            // Array
            {


                for (Map.Entry<String, Map<String, boolean[]>> entry1 : array.entrySet()) {

                    for (Map.Entry<String, boolean[]> entry2 : entry1.getValue().entrySet()) {

                        size = size + entry2.getValue().length;

                    }

                }

                array.clear();


            }

            // List
            {


                for (Map.Entry<String, Map<String, List<Boolean>>> entry1 : list.entrySet()) {

                    for (Map.Entry<String, List<Boolean>> entry2 : entry1.getValue().entrySet()) {

                        size = size + entry2.getValue().size();

                    }

                }

                list.clear();


            }

            // Map
            {


                for (Map.Entry<String, Map<String, Map<String, Boolean>>> entry1 : map.entrySet()) {

                    for (Map.Entry<String, Map<String, Boolean>> entry2 : entry1.getValue().entrySet()) {

                        size = size + entry2.getValue().size();

                    }

                }

                map.clear();


            }

            return size;

        }

        public static boolean existNormal (String name, String key) {


            return normal.computeIfAbsent(name, k -> new ConcurrentHashMap<>()).containsKey(key) == true;


        }

        public static Map<String, Boolean> getNormal (String name) {


            return normal.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setNormal (String name, String key, boolean value) {


            if (key == null) {

                normal.put(name, new ConcurrentHashMap<>());

            } else {

                normal.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, boolean[]> getArray (String name) {


            return array.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setArray (String name, String key, boolean[] value) {


            if (key == null) {

                array.put(name, new ConcurrentHashMap<>());

            } else {

                array.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, List<Boolean>> getList (String name) {


            return list.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setList (String name, String key, List<Boolean> value) {


            if (key == null) {

                list.put(name, new ConcurrentHashMap<>());

            } else {

                list.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, Map<String, Boolean>> getMap (String name) {


            return map.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setMap (String name, String key, Map<String, Boolean> value) {


            if (key == null) {

                map.put(name, new ConcurrentHashMap<>());

            } else {

                map.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

    }

    public static class DataText {

        private static final Map<String, Map<String, String>> normal = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, String[]>> array = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, Set<String>>> set = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, List<String>>> list = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, Map<String, String>>> map = new ConcurrentHashMap<>();

        private static int clear () {

            int size = 0;

            // Normal
            {


                for (Map.Entry<String, Map<String, String>> entry1 : normal.entrySet()) {

                    for (Map.Entry<String, String> entry2 : entry1.getValue().entrySet()) {

                        size = size + (entry2.getValue().length() * Character.BYTES);

                    }

                }

                normal.clear();


            }

            // Array
            {


                for (Map.Entry<String, Map<String, String[]>> entry1 : array.entrySet()) {

                    for (Map.Entry<String, String[]> entry2 : entry1.getValue().entrySet()) {

                        for (String scan : entry2.getValue()) {

                            size = size + (scan.length() * Character.BYTES);

                        }

                    }

                }

                array.clear();


            }

            // Set
            {


                for (Map.Entry<String, Map<String, Set<String>>> entry1 : set.entrySet()) {

                    for (Map.Entry<String, Set<String>> entry2 : entry1.getValue().entrySet()) {

                        for (String scan : entry2.getValue()) {

                            size = size + (scan.length() * Character.BYTES);

                        }

                    }

                }

                set.clear();


            }

            // List
            {


                for (Map.Entry<String, Map<String, List<String>>> entry1 : list.entrySet()) {

                    for (Map.Entry<String, List<String>> entry2 : entry1.getValue().entrySet()) {

                        for (String scan : entry2.getValue()) {

                            size = size + (scan.length() * Character.BYTES);

                        }

                    }

                }

                list.clear();


            }

            // Map
            {


                for (Map.Entry<String, Map<String, Map<String, String>>> entry1 : map.entrySet()) {

                    for (Map.Entry<String, Map<String, String>> entry2 : entry1.getValue().entrySet()) {

                        for (Map.Entry<String, String> entry3 : entry2.getValue().entrySet()) {

                            size = size + (entry3.getValue().length() * Character.BYTES);

                        }

                    }

                }

                map.clear();


            }

            return size;

        }

        public static boolean existNormal (String name, String key) {


            return normal.computeIfAbsent(name, k -> new ConcurrentHashMap<>()).containsKey(key) == true;


        }

        public static Map<String, String> getNormal (String name) {


            return normal.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setNormal (String name, String key, String value) {


            if (key == null) {

                normal.put(name, new ConcurrentHashMap<>());

            } else {

                normal.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, String[]> getArray (String name) {


            return array.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setArray (String name, String key, String[] value) {


            if (key == null) {

                array.put(name, new ConcurrentHashMap<>());

            } else {

                array.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, Set<String>> getSet (String name) {


            return set.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setSet (String name, String key, Set<String> value) {


            if (key == null) {

                set.put(name, new ConcurrentHashMap<>());

            } else {

                set.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, List<String>> getList (String name) {


            return list.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setList (String name, String key, List<String> value) {


            if (key == null) {

                list.put(name, new ConcurrentHashMap<>());

            } else {

                list.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        

          
        public static Map<String, Map<String, String>> getMap (String name) {


            // 修复：使用 computeIfAbsent 替代 getOrDefault，确保返回的是持久化 Map 而非一次性空 Map
            return map.computeIfAbsent(name, create -> new ConcurrentHashMap<>());


        }

        public static void setMap (String name, String key, Map<String, String> value) {


            if (key == null) {

                map.put(name, new ConcurrentHashMap<>());

            } else {

                map.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

    }

    public static class DataShort {

        private static final Map<String, Map<String, Short>> normal = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, short[]>> array = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, Set<Short>>> set = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, List<Short>>> list = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, Map<String, Short>>> map = new ConcurrentHashMap<>();

        private static int clear () {

            int size = 0;

            // Normal
            {


                for (Map.Entry<String, Map<String, Short>> entry : normal.entrySet()) {

                    size = size + (entry.getValue().size() * Short.BYTES);

                }

                normal.clear();


            }

            // Array
            {


                for (Map.Entry<String, Map<String, short[]>> entry1 : array.entrySet()) {

                    for (Map.Entry<String, short[]> entry2 : entry1.getValue().entrySet()) {

                        size = size + (entry2.getValue().length * Short.BYTES);

                    }

                }

                array.clear();


            }

            // Set
            {


                for (Map.Entry<String, Map<String, Set<Short>>> entry1 : set.entrySet()) {

                    for (Map.Entry<String, Set<Short>> entry2 : entry1.getValue().entrySet()) {

                        size = size + (entry2.getValue().size() * Short.BYTES);

                    }

                }

                set.clear();


            }

            // List
            {


                for (Map.Entry<String, Map<String, List<Short>>> entry1 : list.entrySet()) {

                    for (Map.Entry<String, List<Short>> entry2 : entry1.getValue().entrySet()) {

                        size = size + (entry2.getValue().size() * Short.BYTES);

                    }

                }

                list.clear();


            }

            // Map
            {


                for (Map.Entry<String, Map<String, Map<String, Short>>> entry1 : map.entrySet()) {

                    for (Map.Entry<String, Map<String, Short>> entry2 : entry1.getValue().entrySet()) {

                        size = size + (entry2.getValue().size() * Short.BYTES);

                    }

                }

                map.clear();


            }

            return size;

        }

        public static boolean existNormal (String name) {


            return normal.containsKey(name);


        }

        public static Map<String, Short> getNormal (String name) {


            return normal.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setNormal (String name, String key, short value) {


            if (key == null) {

                normal.put(name, new ConcurrentHashMap<>());

            } else {

                normal.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, short[]> getArray (String name) {


            return array.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setArray (String name, String key, short[] value) {


            if (key == null) {

                array.put(name, new ConcurrentHashMap<>());

            } else {

                array.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, Set<Short>> getSet (String name) {


            return set.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setSet (String name, String key, Set<Short> value) {


            if (key == null) {

                set.put(name, new ConcurrentHashMap<>());

            } else {

                set.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, List<Short>> getList (String name) {


            return list.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setList (String name, String key, List<Short> value) {


            if (key == null) {

                list.put(name, new ConcurrentHashMap<>());

            } else {

                list.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, Map<String, Short>> getMap (String name) {


            return map.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setMap (String name, String key, Map<String, Short> value) {


            if (key == null) {

                map.put(name, new ConcurrentHashMap<>());

            } else {

                map.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

    }

    public static class DataInt {

        private static final Map<String, Map<String, Integer>> normal = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, int[]>> array = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, Set<Integer>>> set = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, List<Integer>>> list = new ConcurrentHashMap<>();
        private static final Map<String, Map<String, Map<String, Integer>>> map = new ConcurrentHashMap<>();

        private static int clear () {

            int size = 0;

            // Normal
            {


                for (Map.Entry<String, Map<String, Integer>> entry : normal.entrySet()) {

                    size = size + (entry.getValue().size() * Integer.BYTES);

                }

                normal.clear();


            }

            // Array
            {


                for (Map.Entry<String, Map<String, int[]>> entry1 : array.entrySet()) {

                    for (Map.Entry<String, int[]> entry2 : entry1.getValue().entrySet()) {

                        size = size + (entry2.getValue().length * Integer.BYTES);

                    }

                }

                array.clear();


            }

            // Set
            {


                for (Map.Entry<String, Map<String, Set<Integer>>> entry1 : set.entrySet()) {

                    for (Map.Entry<String, Set<Integer>> entry2 : entry1.getValue().entrySet()) {

                        size = size + (entry2.getValue().size() * Integer.BYTES);

                    }

                }

                set.clear();


            }

            // List
            {


                for (Map.Entry<String, Map<String, List<Integer>>> entry1 : list.entrySet()) {

                    for (Map.Entry<String, List<Integer>> entry2 : entry1.getValue().entrySet()) {

                        size = size + (entry2.getValue().size() * Integer.BYTES);

                    }

                }

                list.clear();


            }

            // Map
            {


                for (Map.Entry<String, Map<String, Map<String, Integer>>> entry1 : map.entrySet()) {

                    for (Map.Entry<String, Map<String, Integer>> entry2 : entry1.getValue().entrySet()) {

                        size = size + (entry2.getValue().size() * Integer.BYTES);

                    }

                }

                map.clear();


            }

            return size;

        }

        public static boolean existNormal (String name) {


            return normal.containsKey(name);


        }

        public static Map<String, Integer> getNormal (String name) {


            return normal.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setNormal (String name, String key, int value) {


            if (key == null) {

                normal.put(name, new ConcurrentHashMap<>());

            } else {

                normal.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, int[]> getArray (String name) {


            return array.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setArray (String name, String key, int[] value) {


            if (key == null) {

                array.put(name, new ConcurrentHashMap<>());

            } else {

                array.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, Set<Integer>> getSet (String name) {


            return set.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setSet (String name, String key, Set<Integer> value) {


            if (key == null) {

                set.put(name, new ConcurrentHashMap<>());

            } else {

                set.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, List<Integer>> getList (String name) {


            return list.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setList (String name, String key, List<Integer> value) {


            if (key == null) {

                list.put(name, new ConcurrentHashMap<>());

            } else {

                list.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

        public static Map<String, Map<String, Integer>> getMap (String name) {


            return map.computeIfAbsent(name, k -> new ConcurrentHashMap<>());


        }

        public static void setMap (String name, String key, Map<String, Integer> value) {


            if (key == null) {

                map.put(name, new ConcurrentHashMap<>());

            } else {

                map.computeIfAbsent(name, create -> new ConcurrentHashMap<>()).put(key, value);

            }


        }

    }

    public static String[] getFunction (String path) {

        String[] data = DataText.getArray("functions").get(path);

        if (data == null) {

            data = FileManager.readTXT(Core.path_config + "/dev/temporary/" + path + ".txt");
            DataText.setArray("functions", path, data);

        }

        return data;

    }

    // [刀Q] 字典注册锁(记忆140修法甲): 串行化"读文件→分配id→写文件→写内存"整个注册序列
    // 热路径(缓存命中)不经过此锁, 零开销; 注册仅冷启动缓存miss时发生
    private static final Object dictionary_lock = new Object();

    public static String getDictionary (String key, boolean is_number) {

        String get = DataText.getNormal("dictionary").get(key);

        if (get == null) {
            // [刀Q] 甲(记忆140): 注册序列原子化。根因: 441区块生成线程并发注册时
            // "读文件→行数+1→append"三步非原子, 两线程抢同一id→同id双行入档
            // →读侧id→name按文件首匹配跨变体互换(E4吞树62棵)
            // 热路径(缓存命中)不进此锁零开销; 注册是冷启动稀有事件
            synchronized (dictionary_lock) {

                // 双重检查: 等锁期间可能已被其他线程注册, 命中则跳过注册
                get = DataText.getNormal("dictionary").get(key);

                if (get == null) {

                    // Write New
                    {

                        String value_id = "";
                        String value_text = "";
                        // [刀Q] 甲: 供下方"max+1"分配的统计变量(见for循环内统计块)
                        long max_id = 0;
                        String path = Core.path_world_mod + "/dictionary.txt";
                        String[] data = FileManager.readTXT(path);

                        for (String scan : data) {
                            // [刀Q] 甲: 单遍扫描顺带统计最大id, 供下方"max+1"分配(未命中路径循环无break完整执行)
                            // 非数字id行(历史脏数据)防御跳过, 不参与统计
                            {
                                int separator_dictionary = scan.indexOf("|");

                                if (separator_dictionary > 0) {

                                    try {
                                        max_id = Math.max(max_id, Long.parseLong(scan.substring(0, separator_dictionary).trim()));
                                    } catch (NumberFormatException exception_dictionary) {
                                        // id非数字的历史脏数据行, 不参与统计
                                    }

                                }

                            }

                            if (is_number == true) {

                                if (scan.startsWith(key + "|") == true) {

                                    value_id = key;
                                    value_text = scan.substring(scan.indexOf("|") + 1);
                                    break;

                                }

                            } else {

                                if (scan.endsWith("|" + key) == true) {

                                    value_id = scan.substring(0, scan.indexOf("|"));
                                    value_text = key;
                                    break;

                                }

                            }

                        }

                        if (value_id.isEmpty() == true && value_text.isEmpty() == true) {

                            if (is_number == false) {

                                value_text = key;

                            }

                            if (value_text.isEmpty() == false) {

                                // [刀Q] 甲: id分配废除"行数+1"改为"最大id+1"——
                                // 行数+1在字典存在历史dup/空洞(行数≠最大id)时会复用已占id, 造成二次碰撞
                                value_id = String.valueOf(max_id + 1);
                                FileManager.writeTXT(path, value_id + "|" + value_text + "\n", true);

                            }

                        }

                        // [刀Q] 空串守卫: is_number=true且short查无此人时value_text为空,
                        // 原代码会把 ""→"" 垃圾对写入内存字典(每次未知short污染一次)
                        if (value_text.isEmpty() == false) {
                            DataText.setNormal("dictionary", value_id, value_text);
                            DataText.setNormal("dictionary", value_text, value_id);
                        }

                        if (is_number == true) {

                            get = value_text;

                        } else {

                            get = value_id;

                        }

                    }

                }
            }

        }

        return get;

    }

}
