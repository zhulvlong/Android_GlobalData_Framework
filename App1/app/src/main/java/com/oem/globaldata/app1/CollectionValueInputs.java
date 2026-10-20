package com.oem.globaldata.app1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** 集合页面共享输入规则；DataKey 和写入调用仍在各独立 Activity 中明确列出。 */
final class CollectionValueInputs {
    private CollectionValueInputs() {}

    /** 类型选择顺序与 XML 中 collection_value_types 完全一致。 */
    static String typeName(int index) {
        String[] names = {"String", "Integer", "Long", "Float", "Double", "Boolean"};
        return names[index];
    }

    /** 初始按钮使用可读的业务样例；修改按钮读取用户编辑的文本。 */
    static String sample(String kind, int type) {
        String[] list = {"设备A,设备B", "404,500", "4294967296,8589934592",
                "0.25,0.75", "23.625,-0.0", "true,false"};
        String[] map = {"theme=light\nowner=App1", "worker=3\nsync=5",
                "upload=4294967296\ncache=8589934592", "wifi=0.25\nbluetooth=0.75",
                "sensorA=23.625\nsensorB=-0.0", "wifi=true\nbluetooth=false"};
        String[] set = {"wifi,bluetooth", "1,2", "4294967296,8589934592",
                "0.25,0.75", "23.625,-0.0", "true,false"};
        return ("List".equals(kind) ? list : "Map".equals(kind) ? map : set)[type];
    }

    static Boolean booleanValue(String input) {
        if ("true".equals(input)) return true;
        if ("false".equals(input)) return false;
        throw new IllegalArgumentException("Boolean 只能输入 true 或 false");
    }

    static <T> List<T> list(String input, Function<String, T> parse) {
        List<T> result = new ArrayList<>();
        for (String part : input.split(",", -1)) {
            String token = part.trim();
            if (token.isEmpty()) throw new IllegalArgumentException("List 不允许空元素");
            result.add(parse.apply(token));
        }
        return result;
    }

    static <T> Map<String, T> map(String input, Function<String, T> parse) {
        Map<String, T> result = new LinkedHashMap<>();
        for (String line : input.split("\n")) {
            if (line.trim().isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1)
                throw new IllegalArgumentException("Map 每行必须是非空 key=value");
            String key = line.substring(0, separator).trim();
            String raw = line.substring(separator + 1).trim();
            if (key.isEmpty() || raw.isEmpty() || result.containsKey(key))
                throw new IllegalArgumentException("Map 的 key/value 不可为空，key 不可重复");
            result.put(key, parse.apply(raw));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Map 至少输入一项");
        return result;
    }

    static <T> Set<T> set(String input, Function<String, T> parse) {
        Set<T> result = new LinkedHashSet<>();
        // 空输入代表存在的空 Set，不等于 Key 尚未写入。
        if (input.trim().isEmpty()) return result;
        for (String part : input.split(",", -1)) {
            String token = part.trim();
            if (token.isEmpty()) throw new IllegalArgumentException("Set 不允许空元素");
            T value = parse.apply(token);
            if (!result.add(value)) throw new IllegalArgumentException("Set 不允许重复元素：" + token);
        }
        return result;
    }
}
