package com.aitest.execution;

import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public final class VariableResolver {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^{}]+)}");
    private final JsonCodec json;
    public VariableResolver(JsonCodec json) { this.json = json; }
    public Object resolve(Object input, Map<String, Object> variables) { return resolve(input, variables, new LinkedHashSet<>(), 0); }
    public String text(Object input, Map<String, Object> variables) {
        Object value = resolve(input, variables);
        return value == null ? "" : value instanceof Map<?, ?> || value instanceof List<?> ? json.write(value) : value.toString();
    }
    private Object resolve(Object input, Map<String, Object> variables, Set<String> path, int depth) {
        if (depth > 32) throw Problem.invalid("变量嵌套超过 32 层");
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(key.toString(), resolve(value, variables, path, depth + 1))); return result;
        }
        if (input instanceof List<?> list) return list.stream().map(v -> resolve(v, variables, path, depth + 1)).toList();
        if (!(input instanceof String text)) return input;
        Matcher matcher = VARIABLE.matcher(text);
        if (matcher.matches()) return value(matcher.group(1), variables, path, depth);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object replacement = value(matcher.group(1), variables, path, depth);
            String rendered = replacement == null ? "" : replacement instanceof Map<?, ?> || replacement instanceof List<?> ? json.write(replacement) : replacement.toString();
            matcher.appendReplacement(result, Matcher.quoteReplacement(rendered));
        }
        return matcher.appendTail(result).toString();
    }
    private Object value(String name, Map<String, Object> variables, Set<String> path, int depth) {
        if (name.equals("__timestamp()")) return Instant.now().toEpochMilli();
        if (name.equals("__now()")) return Instant.now().toString();
        if (name.equals("__uuid()")) return UUID.randomUUID().toString();
        if (!path.add(name)) throw Problem.invalid("变量循环引用: " + name);
        try {
            Object value;
            if (variables.containsKey(name)) value = variables.get(name);
            else {
                value = variables;
                for (String part : name.split("\\.")) {
                    if (!(value instanceof Map<?, ?> map) || !map.containsKey(part)) throw Problem.invalid("缺少运行变量: " + name);
                    value = map.get(part);
                }
            }
            return resolve(value, variables, path, depth + 1);
        } finally { path.remove(name); }
    }
    public Set<String> references(Object input) {
        Set<String> result = new LinkedHashSet<>(); collect(input, result); return result;
    }
    private void collect(Object input, Set<String> result) {
        if (input instanceof String text) {
            Matcher matcher = VARIABLE.matcher(text);
            while (matcher.find()) if (!Set.of("__timestamp()", "__now()", "__uuid()").contains(matcher.group(1))) result.add(matcher.group(1));
        } else if (input instanceof Map<?, ?> map) map.values().forEach(v -> collect(v, result));
        else if (input instanceof List<?> list) list.forEach(v -> collect(v, result));
    }
}
