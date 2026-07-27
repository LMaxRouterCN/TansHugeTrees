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

    public static String getDictionary (String key, boolean is_number) {

        String get = DataText.getNormal("dictionary").get(key);

        if (get == null) {

            // Write New
            {

                String value_id = "";
                String value_text = "";
                String path = Core.path_world_mod + "/dictionary.txt";
                String[] data = FileManager.readTXT(path);

                for (String scan : data) {

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

                        value_id = String.valueOf(data.length + 1);
                        FileManager.writeTXT(path, value_id + "|" + value_text + "\n", true);

                    }

                }

                DataText.setNormal("dictionary", value_id, value_text);
                DataText.setNormal("dictionary", value_text, value_id);

                if (is_number == true) {

                    get = value_text;

                } else {

                    get = value_id;

                }

            }

        }

        return get;

    }

}
