package com.aitest.execution;

import com.aitest.common.Problem;
import java.util.List;
import java.util.Map;

public final class Values {
    private Values() { }
    public static String text(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key); return value == null ? fallback : value.toString();
    }
    public static int integer(Map<String, Object> data, String key, int fallback, int min, int max) {
        Object value = data.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < min || n.longValue() > max)
            throw Problem.invalid(key + " 必须为 " + min + "–" + max + " 的整数");
        return n.intValue();
    }
    public static boolean bool(Map<String, Object> data, String key, boolean fallback) {
        Object value = data.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean b)) throw Problem.invalid(key + " 必须为布尔值");
        return b;
    }
    @SuppressWarnings("unchecked") public static Map<String, Object> map(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String))) throw Problem.invalid("需要 JSON 对象");
        return (Map<String, Object>) map;
    }
    public static List<Map<String, Object>> objects(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw Problem.invalid("需要 JSON 数组");
        return list.stream().map(Values::map).toList();
    }
}
