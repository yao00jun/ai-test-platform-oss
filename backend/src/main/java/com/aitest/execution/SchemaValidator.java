package com.aitest.execution;

import java.math.BigDecimal;
import java.util.*;

/** Structural OpenAPI/JSON Schema checks used by response assertions; external refs are never fetched. */
public final class SchemaValidator {
    public List<String> errors(Object value, Map<String, Object> schema) {
        List<String> errors = new ArrayList<>(); check(value, schema, schema, "$", errors, 0); return errors;
    }
    private void check(Object value, Map<String, Object> schema, Map<String, Object> root, String path, List<String> errors, int depth) {
        if (depth > 40) { errors.add(path + ": Schema 递归过深"); return; }
        if (schema.containsKey("$ref")) {
            String ref = schema.get("$ref").toString(); Object target = root;
            if (!ref.startsWith("#/")) { errors.add(path + ": Schema 引用未解析"); return; }
            for (String part : ref.substring(2).split("/")) target = target instanceof Map<?, ?> m ? m.get(part.replace("~1", "/").replace("~0", "~")) : null;
            if (!(target instanceof Map<?, ?>)) { errors.add(path + ": Schema 引用不存在"); return; }
            check(value, Values.map(target), root, path, errors, depth + 1); return;
        }
        if (value == null && Boolean.TRUE.equals(schema.get("nullable"))) return;
        if (schema.get("allOf") instanceof List<?> all) for (Object item : all) check(value, Values.map(item), root, path, errors, depth + 1);
        for (String union : List.of("anyOf", "oneOf")) if (schema.get(union) instanceof List<?> options) {
            int matches = 0;
            for (Object option : options) { List<String> trial = new ArrayList<>(); check(value, Values.map(option), root, path, trial, depth + 1); if (trial.isEmpty()) matches++; }
            if (matches == 0 || union.equals("oneOf") && matches != 1) errors.add(path + ": 不满足 " + union);
        }
        Object type = schema.get("type");
        if (type != null && !(type instanceof List<?> types ? types.stream().anyMatch(t -> matches(value, t.toString())) : matches(value, type.toString()))) {
            errors.add(path + ": 类型应为 " + type); return;
        }
        if (schema.get("enum") instanceof List<?> choices && choices.stream().noneMatch(c -> Objects.equals(c, value))) errors.add(path + ": 不在枚举范围");
        if (schema.containsKey("const") && !Objects.equals(schema.get("const"), value)) errors.add(path + ": 常量不匹配");
        if (value instanceof Map<?, ?> map) {
            if (schema.get("required") instanceof List<?> required) for (Object key : required) if (!map.containsKey(key)) errors.add(path + "." + key + ": 必填字段缺失");
            Map<String, Object> properties = Values.map(schema.get("properties"));
            for (var entry : map.entrySet()) {
                if (properties.containsKey(entry.getKey())) check(entry.getValue(), Values.map(properties.get(entry.getKey())), root, path + "." + entry.getKey(), errors, depth + 1);
                else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) errors.add(path + "." + entry.getKey() + ": 未定义字段");
            }
        }
        if (value instanceof List<?> list) {
            bounds(list.size(), schema, "minItems", "maxItems", path, errors);
            if (schema.get("items") instanceof Map<?, ?> item) for (int i = 0; i < list.size(); i++) check(list.get(i), Values.map(item), root, path + "[" + i + "]", errors, depth + 1);
        }
        if (value instanceof String text) {
            bounds(text.codePointCount(0, text.length()), schema, "minLength", "maxLength", path, errors);
            if (schema.get("pattern") instanceof String pattern) {
                if (pattern.length() > 500 || text.length() > 100000 || !com.google.re2j.Pattern.compile(pattern).matcher(text).find()) errors.add(path + ": 不满足格式");
            }
        }
        if (value instanceof Number number) bounds(number.doubleValue(), schema, "minimum", "maximum", path, errors);
    }
    private boolean matches(Object value, String type) {
        return switch (type) {
            case "null" -> value == null; case "object" -> value instanceof Map; case "array" -> value instanceof List;
            case "string" -> value instanceof String; case "boolean" -> value instanceof Boolean; case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n && new BigDecimal(n.toString()).stripTrailingZeros().scale() <= 0;
            default -> false;
        };
    }
    private void bounds(double value, Map<String, Object> schema, String min, String max, String path, List<String> errors) {
        if (schema.get(min) instanceof Number lower && value < lower.doubleValue()) errors.add(path + ": 小于 " + min);
        if (schema.get(max) instanceof Number upper && value > upper.doubleValue()) errors.add(path + ": 大于 " + max);
    }
}
