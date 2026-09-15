package com.aitest.analysis.schema;

import com.aitest.common.Problem;
import com.aitest.engine.sql.*;
import com.aitest.execution.Values;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.schema.*;
import net.sf.jsqlparser.statement.select.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Resolves SELECT identifiers against captured schema with lexical query scopes, without opening a connection. */
@Component
public final class SchemaQueryValidator {
    private final SqlParameters parameters;
    private final SqlPolicyValidator policy;
    public SchemaQueryValidator(SqlParameters parameters, SqlPolicyValidator policy) { this.parameters = parameters; this.policy = policy; }

    public Map<String, Object> validate(Map<String, Object> data, List<Map<String, Object>> tables) {
        String sql = Values.text(data, "sql", "");
        var parsed = policy.validate(parameters.parameterize(sql), false);
        if (!(parsed.statement() instanceof Select select)) throw invalid("源码约束生成只支持 SELECT");
        Analysis analysis = new Analysis(tables);
        List<String> columns = analysis.select(select, null, Map.of(), 0);
        Set<String> names = new LinkedHashSet<>();
        for (String expression : parameters.expressions(sql)) {
            var matcher = java.util.regex.Pattern.compile("\\$\\{([^{}]+)}").matcher(expression);
            while (matcher.find()) names.add(matcher.group(1));
        }
        if (analysis.parameterCount != parameters.expressions(sql).size()) throw invalid("SQL 参数必须使用 :名称 或 ${名称}，不能使用未绑定的问号");
        for (Object value : Values.map(data.get("exports")).values()) if (!(value instanceof String name) || !columns.contains(name)) throw invalid("结果变量映射引用了不存在的返回列");
        for (var assertion : Values.objects(data.get("assertions"))) if ("field".equals(Values.text(assertion, "type", assertion.containsKey("field") ? "field" : "jsonpath"))) {
            String column = Values.text(assertion, "path", Values.text(assertion, "jsonpath", Values.text(assertion, "field", "")));
            if (!columns.contains(column)) throw invalid("SQL 断言引用了不存在的返回列：" + column);
        }
        return Map.of("tables", List.copyOf(analysis.usedTables), "columns", List.copyOf(analysis.usedColumns), "outputColumns", columns, "parameters", List.copyOf(names));
    }

    private static final class Analysis {
        final List<Map<String, Object>> tables;
        final Set<String> usedTables = new LinkedHashSet<>(), usedColumns = new LinkedHashSet<>();
        int parameterCount;
        Analysis(List<Map<String, Object>> tables) { this.tables = tables; }
        List<String> select(Select select, Scope outer, Map<String, List<String>> inherited, int depth) {
            if (depth > 24) throw invalid("查询嵌套超过 24 层");
            // These fields belong to Select itself, including UNION and parenthesized queries.
            // Parsing a dialect extension is not evidence that we checked its identifiers.
            if (select.getFetch() != null || select.getLimitBy() != null || !optional(select.getInterpolate()).isEmpty()
                    || select.getForClause() != null || select.getOption() != null || select.getIsolation() != null
                    || select.getMySqlProcedureAnalyse() != null || select.getForMode() != null || select.getForUpdate() != null
                    || select.getWait() != null || select.getPivot() != null || select.getUnPivot() != null)
                throw invalid("离线源码校验不支持此查询扩展子句，请使用显式 SELECT/ORDER BY/LIMIT");
            for (var order : optional(select.getOrderByElements())) if (order.getWithFill() != null)
                throw invalid("离线源码校验暂不支持 ORDER BY WITH FILL");
            pagination(select);
            Map<String, List<String>> ctes = new LinkedHashMap<>(inherited);
            for (WithItem<?> item : optional(select.getWithItemsList())) {
                if (item.isRecursive() || item.getSelect() == null) throw invalid("离线校验暂不支持递归或非查询 CTE");
                List<String> output = select(item.getSelect(), outer, ctes, depth + 1);
                if (item.getWithItemList() != null && !item.getWithItemList().isEmpty()) {
                    if (item.getWithItemList().size() != output.size()) throw invalid("CTE 列声明数量与查询不一致");
                    output = item.getWithItemList().stream().map(column -> unquote(column.toString())).toList();
                    unique(output);
                }
                String name = key(item.getAliasName());
                if (ctes.putIfAbsent(name, output) != null) throw invalid("CTE 名称重复");
            }
            if (select instanceof ParenthesedSelect wrapped) {
                List<String> output = select(wrapped.getSelect(), outer, ctes, depth + 1);
                Scope result = new Scope(outer); result.add("result", "", output);
                for (var order : optional(wrapped.getOrderByElements())) expression(order.getExpression(), result, Set.of(), ctes, depth);
                return output;
            }
            if (select instanceof SetOperationList union) {
                List<String> first = null;
                for (Select part : union.getSelects()) {
                    List<String> output = select(part, outer, ctes, depth + 1);
                    if (first == null) first = output;
                    else if (output.size() != first.size()) throw invalid("集合查询返回列数量不一致");
                }
                if (first == null) throw invalid("集合查询为空");
                Scope result = new Scope(outer); result.add("result", "", first);
                for (var order : optional(union.getOrderByElements())) expression(order.getExpression(), result, Set.of(), ctes, depth);
                return first;
            }
            if (!(select instanceof PlainSelect plain)) throw invalid("离线校验不支持此查询结构：" + select.getClass().getSimpleName());
            if (!optional(plain.getWindowDefinitions()).isEmpty() || !optional(plain.getLateralViews()).isEmpty() || plain.getOracleHierarchical() != null) throw invalid("命名窗口、侧向视图或层级查询需要另行提供可验证证据");
            if (plain.getTop() != null || plain.getFirst() != null || plain.getSkip() != null || plain.getSampleClause() != null
                    || plain.getOptimizeFor() != null || plain.getPreferringClause() != null || !optional(plain.getSettings()).isEmpty()
                    || plain.getKsqlWindow() != null || plain.getEmitMode() != PlainSelect.EmitMode.NONE || plain.getForXmlPath() != null
                    || plain.getBigQuerySelectQualifier() != null || plain.getDistinct() != null && !optional(plain.getDistinct().getOnSelectItems()).isEmpty())
                throw invalid("离线源码校验不支持此 SELECT 修饰子句");
            Scope scope = new Scope(outer);
            from(plain.getFromItem(), scope, ctes, depth);
            for (Join join : optional(plain.getJoins())) {
                if (join.isNatural() || !optional(join.getUsingColumns()).isEmpty()) throw invalid("离线校验请使用显式 JOIN ON 表达连接列");
                from(join.getRightItem(), scope, ctes, depth);
                for (Expression on : optional(join.getOnExpressions())) expression(on, scope, Set.of(), ctes, depth);
            }
            List<String> output = new ArrayList<>();
            for (SelectItem<?> item : plain.getSelectItems()) {
                Expression expression = item.getExpression();
                if (expression instanceof AllTableColumns star) output.addAll(scope.table(star.getTable().getFullyQualifiedName()).columns());
                else if (expression instanceof AllColumns star) {
                    if (star.getExceptColumns() != null || !optional(star.getReplaceExpressions()).isEmpty()) throw invalid("离线校验暂不支持星号 EXCEPT/REPLACE");
                    if (scope.tables.isEmpty()) throw invalid("星号查询没有表来源");
                    scope.tables.values().forEach(table -> output.addAll(table.columns()));
                } else {
                    expression(expression, scope, Set.of(), ctes, depth);
                    output.add(item.getAlias() != null ? unquote(item.getAlias().getName()) : expression instanceof Column column ? unquote(column.getColumnName()) : expression.toString());
                }
            }
            unique(output);
            expression(plain.getWhere(), scope, Set.of(), ctes, depth);
            expression(plain.getPreWhere(), scope, Set.of(), ctes, depth);
            Set<String> aliases = new HashSet<>(); output.forEach(value -> aliases.add(key(value)));
            expression(plain.getHaving(), scope, aliases, ctes, depth);
            expression(plain.getQualify(), scope, aliases, ctes, depth);
            if (plain.getGroupBy() != null) {
                for (Expression group : optional(plain.getGroupBy().getGroupByExpressionList())) expression(group, scope, aliases, ctes, depth);
                for (var grouping : optional(plain.getGroupBy().getGroupingSets())) for (Expression group : grouping) expression(group, scope, aliases, ctes, depth);
            }
            for (var order : optional(plain.getOrderByElements())) expression(order.getExpression(), scope, aliases, ctes, depth);
            return List.copyOf(output);
        }
        void pagination(Select select) {
            if (select.getLimit() != null) {
                Limit limit = select.getLimit();
                if (limit.isLimitAll() || limit.isLimitNull() || !optional(limit.getByExpressions()).isEmpty()) throw invalid("分页只接受普通 LIMIT");
                count(limit.getRowCount()); count(limit.getOffset());
            }
            if (select.getOffset() != null) count(select.getOffset().getOffset());
        }
        void count(Expression value) {
            if (value == null) return;
            if (value instanceof JdbcParameter) { parameterCount++; return; }
            if (value instanceof LongValue number && number.getBigIntegerValue().signum() >= 0) return;
            throw invalid("LIMIT/OFFSET 只接受非负整数或绑定参数");
        }
        void from(FromItem item, Scope scope, Map<String, List<String>> ctes, int depth) {
            if (item == null) return;
            if (item.getPivot() != null || item.getUnPivot() != null) throw invalid("离线校验暂不支持 PIVOT");
            if (item instanceof Table table) {
                String name = unquote(table.getName()), qualified = table.getSchemaName() == null ? name : unquote(table.getSchemaName()) + "." + name;
                List<String> columns = ctes.get(key(qualified)); String origin = "";
                if (columns == null) {
                    var matched = tables.stream().filter(candidate -> name.equalsIgnoreCase(Values.text(candidate, "name", "")) && (table.getSchemaName() == null || unquote(table.getSchemaName()).equalsIgnoreCase(Values.text(candidate, "schema", "")))).toList();
                    if (matched.size() != 1) throw invalid("表不存在或来源有歧义：" + qualified);
                    var found = matched.getFirst(); columns = Values.objects(found.get("columns")).stream().map(column -> column.get("name").toString()).toList();
                    if (columns.isEmpty()) throw invalid("表没有完整列定义：" + qualified);
                    origin = qualified; usedTables.add(qualified);
                }
                scope.add(table.getAlias() == null ? qualified : table.getAlias().getName(), origin, columns);
            } else if (item instanceof ParenthesedSelect nested) {
                if (nested.getAlias() == null) throw invalid("派生表需要明确别名");
                scope.add(nested.getAlias().getName(), "", select(nested, scope.outer, ctes, depth + 1));
            } else throw invalid("离线校验不支持此表来源：" + item.getClass().getSimpleName());
        }
        void expression(Expression expression, Scope scope, Set<String> aliases, Map<String, List<String>> ctes, int depth) {
            if (expression == null) return;
            expression.accept(new ExpressionVisitorAdapter<Void>() {
                @Override public <S> Void visit(Column column, S context) {
                    String name = unquote(column.getColumnName()), qualifier = column.getTable() == null ? "" : column.getTable().getFullyQualifiedName();
                    if (qualifier.isBlank() && aliases.contains(key(name))) return null;
                    TableColumns table = qualifier.isBlank() ? scope.column(name) : scope.table(qualifier);
                    if (table.columns().stream().noneMatch(value -> value.equalsIgnoreCase(name))) throw invalid("列不存在：" + column);
                    if (!table.origin().isBlank()) usedColumns.add(table.origin() + "." + name);
                    return null;
                }
                @Override public <S> Void visit(JdbcParameter parameter, S context) { parameterCount++; return null; }
                @Override public <S> Void visit(ParenthesedSelect query, S context) { select(query, scope, ctes, depth + 1); return null; }
                @Override public <S> Void visit(Select query, S context) { select(query, scope, ctes, depth + 1); return null; }
            }, null);
        }
    }
    private record TableColumns(String origin, List<String> columns) { }
    private static final class Scope {
        final Scope outer;
        final Map<String, TableColumns> tables = new LinkedHashMap<>();
        Scope(Scope outer) { this.outer = outer; }
        void add(String name, String origin, List<String> columns) {
            if (tables.putIfAbsent(key(name), new TableColumns(origin, columns)) != null) throw invalid("表别名重复：" + name);
        }
        TableColumns table(String name) {
            TableColumns found = tables.get(key(name));
            if (found == null && outer != null) return outer.table(name);
            if (found == null) throw invalid("表别名不存在：" + name);
            return found;
        }
        TableColumns column(String name) {
            var matches = tables.values().stream().filter(table -> table.columns().stream().anyMatch(column -> column.equalsIgnoreCase(name))).toList();
            if (matches.isEmpty() && outer != null) return outer.column(name);
            if (matches.size() != 1) throw invalid("未限定的列不存在或有歧义：" + name);
            return matches.getFirst();
        }
    }
    private static void unique(List<String> columns) { if (columns.stream().map(SchemaQueryValidator::key).distinct().count() != columns.size()) throw invalid("返回列名重复，请给每列明确且唯一的别名"); }
    private static String unquote(String value) { return SqlSchemaParser.unquote(value); }
    private static String key(String value) { return unquote(value).toLowerCase(Locale.ROOT); }
    private static <T> Collection<T> optional(Collection<T> values) { return values == null ? List.of() : values; }
    private static Problem invalid(String message) { return new Problem(422, "SOURCE_QUERY_INVALID", message); }
}
