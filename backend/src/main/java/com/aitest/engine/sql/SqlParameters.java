package com.aitest.engine.sql;

import com.aitest.common.Problem;
import com.aitest.execution.VariableResolver;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

@Component
public final class SqlParameters {
    private final VariableResolver variables;
    public SqlParameters(VariableResolver variables) { this.variables = variables; }
    public Prepared bind(String sql, Map<String, Object> values) {
        List<Object> bindings = new ArrayList<>();
        return new Prepared(transform(sql, expression -> {
            Object value = variables.resolve(expression, values);
            if (value instanceof Map<?, ?> || value instanceof Collection<?>) throw Problem.invalid("SQL 参数必须为标量值");
            bindings.add(value); return "?";
        }), bindings);
    }
    public String parameterize(String sql) { return transform(sql, expression -> "?"); }
    public List<String> expressions(String sql) {
        List<String> values = new ArrayList<>();
        transform(sql, expression -> { values.add(expression); return "?"; });
        return List.copyOf(values);
    }
    private String transform(String sql, java.util.function.Function<String, String> bind) {
        if (sql == null || sql.isBlank() || sql.length() > 100000) throw Problem.invalid("SQL 必填，最多 100000 字符");
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int start = i++; StringBuilder inner = new StringBuilder(); boolean closed = false;
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    if (next == c) {
                        if (i < sql.length() && sql.charAt(i) == c) { inner.append(c); i++; }
                        else { closed = true; break; }
                    } else if (next == '\\' && i < sql.length()) inner.append(next).append(sql.charAt(i++));
                    else inner.append(next);
                }
                if (!closed) throw Problem.invalid("SQL 引号未闭合");
                if (inner.toString().contains("${")) {
                    if (c != '\'') throw Problem.invalid("表名和列名不能使用运行变量");
                    output.append(bind.apply(inner.toString()));
                } else output.append(sql, start, i);
            } else if (i + 1 < sql.length() && sql.startsWith("--", i) || c == '#') {
                int end = sql.indexOf('\n', i); if (end < 0) end = sql.length(); output.append(sql, i, end); i = end;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2); if (end < 0) throw Problem.invalid("SQL 注释未闭合");
                if (sql.startsWith("/*!", i)) throw Problem.invalid("不支持 MySQL 可执行注释");
                output.append(sql, i, end + 2); i = end + 2;
            } else if (sql.startsWith("${", i)) {
                int end = sql.indexOf('}', i + 2); if (end < 0) throw Problem.invalid("SQL 变量未闭合");
                output.append(bind.apply(sql.substring(i, end + 1))); i = end + 1;
            } else if (c == ':' && (i == 0 || sql.charAt(i - 1) != ':') && i + 1 < sql.length() && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                int end = i + 2;
                while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) end++;
                output.append(bind.apply("${" + sql.substring(i + 1, end) + "}")); i = end;
            } else { output.append(c); i++; }
        }
        return output.toString();
    }
    public record Prepared(String sql, List<Object> values) { }
}
