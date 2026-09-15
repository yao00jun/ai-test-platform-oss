package com.aitest.analysis.schema;

import com.aitest.analysis.SourceDiagnostic;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.create.table.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Reads schema metadata only. No datasource or SQL executor is involved. */
@Component
public final class SqlSchemaParser {
    public Map<String, Object> parse(String path, String ddl, List<SourceDiagnostic> diagnostics) {
        List<Map<String, Object>> tables = new ArrayList<>();
        int ordinal = 0;
        for (String fragment : statements(ddl)) {
            ordinal++;
            try {
                var statement = CCJSqlParserUtil.parse(fragment, parser -> parser.withUnsupportedStatements(true).withTimeOut(5000));
                if (!(statement instanceof CreateTable table)) {
                    if (statement == null || !Set.of("SetStatement", "UseStatement", "Comment", "Insert", "Commit", "StartTransaction", "CreateSchema").contains(statement.getClass().getSimpleName()))
                        diagnostics.add(SourceDiagnostic.warning("UNSUPPORTED_DDL", "DDL", path, "第 " + ordinal + " 条语句没有纳入建表元数据：" + (statement == null ? "解析失败" : statement.getClass().getSimpleName())));
                    continue;
                }
                List<Map<String, Object>> columns = new ArrayList<>(), indexes = new ArrayList<>();
                for (ColumnDefinition column : optional(table.getColumnDefinitions())) {
                    String specs = String.join(" ", optional(column.getColumnSpecs()));
                    var definition = new LinkedHashMap<String, Object>();
                    definition.put("name", unquote(column.getColumnName())); definition.put("type", column.getColDataType().toString());
                    definition.put("nullable", !specs.toUpperCase(Locale.ROOT).matches("(?s).*(NOT\\s+NULL|PRIMARY\\s+KEY).*"));
                    definition.put("primaryKey", specs.toUpperCase(Locale.ROOT).contains("PRIMARY KEY")); definition.put("unique", specs.toUpperCase(Locale.ROOT).contains("UNIQUE"));
                    definition.put("enumValues", column.getColDataType().getDataType().equalsIgnoreCase("ENUM") ? optional(column.getColDataType().getArgumentsStringList()).stream().map(SqlSchemaParser::unquote).toList() : List.of());
                    definition.put("definition", column.toString()); definition.put("specifications", specs); columns.add(definition);
                }
                for (Index index : optional(table.getIndexes())) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("name", Objects.toString(index.getName(), "")); value.put("type", Objects.toString(index.getType(), "")); value.put("columns", optional(index.getColumnsNames()).stream().map(SqlSchemaParser::unquote).toList()); value.put("definition", index.toString());
                    if (index instanceof ForeignKeyIndex foreign) { value.put("referenceTable", foreign.getTable().getFullyQualifiedName()); value.put("referenceColumns", optional(foreign.getReferencedColumnNames()).stream().map(SqlSchemaParser::unquote).toList()); }
                    indexes.add(value);
                }
                for (Map<String, Object> index : indexes) {
                    String type = index.get("type").toString().toUpperCase(Locale.ROOT);
                    List<?> keys = (List<?>) index.get("columns");
                    for (Map<String, Object> column : columns) if (keys.contains(column.get("name"))) {
                        if (type.contains("PRIMARY")) { column.put("primaryKey", true); column.put("nullable", false); }
                        if (type.contains("UNIQUE") && keys.size() == 1) column.put("unique", true);
                    }
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("name", unquote(table.getTable().getName())); value.put("schema", Objects.toString(table.getTable().getSchemaName(), "")); value.put("columns", columns); value.put("indexes", indexes);
                value.put("constraints", optional(table.getTableElements()).stream().filter(element -> !(element instanceof ColumnDefinition)).map(Object::toString).toList());
                value.put("options", optional(table.getTableOptionsStrings())); value.put("sourcePath", path); value.put("statementIndex", ordinal); value.put("evidenceLevel", "DDL");
                if (table.getSelect() != null || table.getLikeTable() != null) diagnostics.add(SourceDiagnostic.warning("INFERRED_TABLE_SCHEMA", "DDL", path, "CREATE TABLE AS/LIKE 需要来源表或查询结果，未推断缺失列"));
                tables.add(value);
            } catch (Exception invalid) { diagnostics.add(new SourceDiagnostic("ERROR", "DDL_PARSE_ERROR", "DDL", path, 0, "第 " + ordinal + " 条 DDL 不能完整解析：" + invalid.getClass().getSimpleName())); }
        }
        if (tables.isEmpty() && !ddl.isBlank()) diagnostics.add(SourceDiagnostic.warning("NO_DDL_TABLES", "DDL", path, "没有提取到明确的 CREATE TABLE 定义"));
        return Map.of("tables", tables);
    }
    /** Delimit only outside SQL literals/comments; a malformed statement cannot erase earlier tables. */
    private static List<String> statements(String sql) {
        List<String> result = new ArrayList<>(); int start = 0; boolean content = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (current == '#' || sql.startsWith("--", index) && (index + 2 == sql.length() || Character.isWhitespace(sql.charAt(index + 2)))) {
                int end = sql.indexOf('\n', index); index = end < 0 ? sql.length() : end; continue;
            }
            if (sql.startsWith("/*", index)) { int end = sql.indexOf("*/", index + 2); index = end < 0 ? sql.length() : end + 1; continue; }
            if (current == '\'' || current == '"' || current == '`') {
                content = true;
                for (index++; index < sql.length(); index++) {
                    if (sql.charAt(index) == '\\') index++;
                    else if (sql.charAt(index) == current) { if (index + 1 < sql.length() && sql.charAt(index + 1) == current) index++; else break; }
                }
                continue;
            }
            if (current == ';') { if (content) result.add(sql.substring(start, index + 1)); start = index + 1; content = false; }
            else if (!Character.isWhitespace(current)) content = true;
        }
        if (content && start < sql.length()) result.add(sql.substring(start));
        return result;
    }
    private static <T> List<T> optional(List<T> value) { return value == null ? List.of() : value; }
    public static String unquote(String value) {
        if (value == null || value.length() < 2) return value;
        char quote = value.charAt(0);
        if ((quote == '`' || quote == '\'' || quote == '"') && value.charAt(value.length() - 1) == quote) return value.substring(1, value.length() - 1).replace("" + quote + quote, "" + quote);
        return value;
    }
}
