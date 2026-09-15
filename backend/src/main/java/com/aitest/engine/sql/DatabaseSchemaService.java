package com.aitest.engine.sql;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.util.*;

@Service
public final class DatabaseSchemaService {
    private final AssetService assets;
    private final DynamicDataSourceManager pools;
    private final SqlParameters parameters;
    private final SqlPolicyValidator policy;
    public DatabaseSchemaService(AssetService assets, DynamicDataSourceManager pools, SqlParameters parameters, SqlPolicyValidator policy) { this.assets = assets; this.pools = pools; this.parameters = parameters; this.policy = policy; }
    public Map<String, Object> schema(String project, String id) {
        Asset source = source(project, id);
        try (var lease = pools.borrow(source)) {
            Connection connection = lease.connection(); connection.setNetworkTimeout(Runnable::run, 10000);
            DatabaseMetaData metadata = connection.getMetaData(); List<Map<String, Object>> tables = new ArrayList<>();
            try (ResultSet table = metadata.getTables(connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE", "VIEW"})) {
                while (table.next()) {
                    if (tables.size() >= 500) throw Problem.invalid("Schema 超过 500 张表，请使用业务库范围连接");
                    String name = table.getString("TABLE_NAME"), schema = table.getString("TABLE_SCHEM");
                    List<Map<String, Object>> columns = new ArrayList<>(), references = new ArrayList<>(); List<String> primaryKeys = new ArrayList<>();
                    try (ResultSet column = metadata.getColumns(connection.getCatalog(), schema, name, "%")) {
                        while (column.next()) {
                            if (columns.size() >= 2000) throw Problem.invalid("数据表列数超过 2000，请缩小 Schema 范围");
                            columns.add(Map.of("name", column.getString("COLUMN_NAME"), "type", column.getString("TYPE_NAME"), "nullable", column.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, "size", column.getInt("COLUMN_SIZE"), "remarks", Objects.toString(column.getString("REMARKS"), "")));
                        }
                    }
                    try (ResultSet keys = metadata.getPrimaryKeys(connection.getCatalog(), schema, name)) { while (keys.next()) primaryKeys.add(keys.getString("COLUMN_NAME")); }
                    try (ResultSet keys = metadata.getImportedKeys(connection.getCatalog(), schema, name)) { while (keys.next()) references.add(Map.of("column", keys.getString("FKCOLUMN_NAME"), "targetTable", keys.getString("PKTABLE_NAME"), "targetColumn", keys.getString("PKCOLUMN_NAME"))); }
                    tables.add(Map.of("name", name, "columns", columns, "primaryKeys", primaryKeys, "references", references));
                }
            }
            return Map.of("databaseSourceId", id, "version", source.version(), "tables", tables);
        } catch (SQLException error) { throw new Problem(502, "DATABASE_CONNECTION_FAILED", "读取业务数据库结构失败，请检查连接和权限"); }
    }
    /** EXPLAIN validates live identifiers and types without running the SELECT or any writes. */
    public Map<String, Object> validateReadQuery(String project, String id, String sql) {
        Asset source = source(project, id);
        String prepared = parameters.parameterize(sql); policy.validate(prepared, false);
        try (var lease = pools.borrow(source)) {
            Connection connection = lease.connection(); connection.setNetworkTimeout(Runnable::run, 10000); connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement("EXPLAIN " + prepared)) {
                statement.setQueryTimeout(5); statement.setMaxRows(100);
                int count = statement.getParameterMetaData().getParameterCount();
                for (int index = 1; index <= count; index++) statement.setObject(index, null);
                try (ResultSet ignored = statement.executeQuery()) { return Map.of("valid", true, "databaseSourceId", id, "sourceVersion", source.version(), "parameterCount", count); }
            }
        } catch (SQLException error) { throw new Problem(422, "SCHEMA_QUERY_INVALID", "业务数据库不能解析此查询，请检查表名、列名和 SELECT 语法（SQLState " + Objects.toString(error.getSQLState(), "unknown") + "）"); }
    }
    private Asset source(String project, String id) { Asset source = assets.getInternal(project, id); if (source.type() != AssetType.DATABASE_SOURCE) throw Problem.invalid("目标不是业务数据源"); return source; }
}
