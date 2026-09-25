package com.aitest.execution;

import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.jayway.jsonpath.JsonPath;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import com.google.re2j.Pattern;

@Component
public final class AssertionEvaluator {
    private static final Set<String> TYPES = Set.of("status_code", "status", "header", "jsonpath", "text", "response_time", "schema", "row_count", "field", "affected_rows");
    private static final Set<String> OPERATORS = Set.of("eq", "ne", "contains", "not_contains", "gt", "gte", "lt", "lte", "exists", "not_exists", "matches");
    private static final Set<String> KEYS = Set.of("type", "operator", "path", "jsonpath", "field", "expected", "row");
    private final JsonCodec json;
    private final VariableResolver resolver;
    public AssertionEvaluator(JsonCodec json, VariableResolver resolver) { this.json = json; this.resolver = resolver; }
    public void validate(List<Map<String, Object>> specs) {
        for (Map<String, Object> spec : specs) {
            for (String key : spec.keySet()) if (!KEYS.contains(key)) throw Problem.invalid("断言不支持字段: " + key);
            String type = Values.text(spec, "type", spec.containsKey("field") ? "field" : "jsonpath");
            if (!TYPES.contains(type)) throw Problem.invalid("不支持的断言类型: " + type);
            String operator = Values.text(spec, "operator", "eq");
            if (!OPERATORS.contains(operator)) throw Problem.invalid("不支持的断言运算符: " + operator);
            if (type.equals("jsonpath")) try { JsonPath.compile(Values.text(spec, "path", Values.text(spec, "jsonpath", "$"))); }
                catch (Exception e) { throw Problem.invalid("断言 JSONPath 无效"); }
            if (operator.equals("matches") && !resolver.references(spec.get("expected")).isEmpty()) continue;
            if (operator.equals("matches")) try { Pattern.compile(Objects.toString(spec.get("expected"), "")); }
                catch (Exception e) { throw Problem.invalid("断言正则表达式无效"); }
        }
    }
    public List<AssertionResult> evaluate(List<Map<String, Object>> specs, Map<String, Object> response, Map<String, Object> variables) {
        validate(specs); List<AssertionResult> results = new ArrayList<>();
        for (Map<String, Object> spec : specs) {
            String type = Values.text(spec, "type", spec.containsKey("field") ? "field" : "jsonpath");
            String path = Values.text(spec, "path", Values.text(spec, "jsonpath", Values.text(spec, "field", "")));
            String operator = Values.text(spec, "operator", type.equals("response_time") ? "lte" : "eq");
            Object expected = resolver.resolve(spec.get("expected"), variables), actual = null;
            boolean passed; String message = "";
            try {
                actual = switch (type) {
                    case "status_code", "status" -> response.get("status");
                    case "header" -> Values.map(response.get("headers")).entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(path)).findFirst().map(Map.Entry::getValue).map(v -> v instanceof List<?> l ? String.join(",", l.stream().map(Object::toString).toList()) : v).orElse(null);
                    case "jsonpath" -> JsonPath.read(Objects.toString(response.get("body"), "null"), path.isBlank() ? "$" : path);
                    case "text", "schema" -> response.get("body");
                    case "response_time" -> response.get("durationMs");
                    case "row_count" -> response.get("rowCount");
                    case "affected_rows" -> response.get("affectedRows");
                    case "field" -> sqlField(response, path, Values.integer(spec, "row", 0, 0, 10000));
                    default -> throw Problem.invalid("断言类型无效");
                };
                if (type.equals("schema")) {
                    var errors = new SchemaValidator().errors(json.tree(Objects.toString(actual, "null")), Values.map(expected));
                    passed = errors.isEmpty(); message = String.join("; ", errors);
                } else passed = compare(actual, expected, operator);
            } catch (com.jayway.jsonpath.PathNotFoundException absent) { passed = operator.equals("not_exists"); message = passed ? "" : "路径不存在"; }
            catch (RuntimeException error) { passed = false; message = error instanceof Problem ? error.getMessage() : "无法读取或比较实际值"; }
            results.add(new AssertionResult(type, path, operator, expected, actual, passed, passed ? "通过" : message.isBlank() ? "实际结果不满足预期" : message));
        }
        return results;
    }
    private Object sqlField(Map<String, Object> response, String field, int index) {
        var rows = Values.objects(response.get("rows")); if (index >= rows.size()) return null;
        return rows.get(index).get(field);
    }
    private boolean compare(Object actual, Object expected, String operator) {
        boolean equal = Objects.equals(actual, expected);
        if (actual instanceof Number || expected instanceof Number) try { equal = new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected.toString())) == 0; } catch (Exception ignored) { }
        return switch (operator) {
            case "eq" -> equal; case "ne" -> !equal; case "exists" -> actual != null; case "not_exists" -> actual == null;
            case "contains" -> actual != null && (actual instanceof Collection<?> c ? c.contains(expected) : actual.toString().contains(Objects.toString(expected, "")));
            case "not_contains" -> actual == null || !actual.toString().contains(Objects.toString(expected, ""));
            case "gt" -> number(actual).compareTo(number(expected)) > 0; case "gte" -> number(actual).compareTo(number(expected)) >= 0;
            case "lt" -> number(actual).compareTo(number(expected)) < 0; case "lte" -> number(actual).compareTo(number(expected)) <= 0;
            case "matches" -> actual != null && actual.toString().length() <= 100000 && Objects.toString(expected, "").length() <= 500 && Pattern.compile(expected.toString()).matcher(actual.toString()).find();
            default -> false;
        };
    }
    private BigDecimal number(Object value) { return new BigDecimal(Objects.toString(value, "NaN")); }
}
