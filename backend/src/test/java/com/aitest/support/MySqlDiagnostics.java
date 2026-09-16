package com.aitest.support;

import com.aitest.common.JsonCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/** Read-only diagnostics for the disposable bootstrap database, never a business database. */
public final class MySqlDiagnostics implements AutoCloseable {
    private final Connection connection;
    private final String schema;

    public MySqlDiagnostics(IsolatedApplication app) throws Exception {
        schema = app.jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (schema == null || !schema.matches("ai_test_acceptance_[a-f0-9]{32}"))
            throw new IllegalArgumentException("Performance diagnostics require a disposable bootstrap schema");
        Path runtime = app.root.resolve(".runtime/mysql");
        var config = new JsonCodec().map(Files.readString(runtime.resolve("connection.json")));
        Properties admin = new Properties();
        for (String line : Files.readAllLines(runtime.resolve("admin.cnf"))) {
            int split = line.indexOf('=');
            if (split > 0) admin.setProperty(line.substring(0, split).strip(), line.substring(split + 1).strip());
        }
        connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + config.get("port") + "/mysql",
                admin.getProperty("user"), admin.getProperty("password"));
    }

    public Map<String, Object> configuration() throws Exception {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery("""
                SELECT VERSION() AS mysqlVersion, @@innodb_flush_log_at_trx_commit AS flushAtCommit,
                @@sync_binlog AS syncBinlog, @@log_bin AS binlogEnabled,
                @@innodb_buffer_pool_size AS bufferPoolBytes, @@datadir AS dataDirectory
                """)) {
            rs.next(); var result = new LinkedHashMap<String, Object>();
            for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++)
                result.put(rs.getMetaData().getColumnLabel(column), rs.getObject(column));
            return result;
        }
    }

    public Snapshot snapshot() throws Exception {
        Set<Long> connections = new TreeSet<>(); Map<String, Long> counters = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.PROCESSLIST_ID, s.VARIABLE_NAME, s.VARIABLE_VALUE
                FROM performance_schema.status_by_thread s
                JOIN performance_schema.threads t ON t.THREAD_ID=s.THREAD_ID
                WHERE t.PROCESSLIST_DB=? AND s.VARIABLE_NAME IN
                ('Com_select','Com_insert','Com_update','Com_delete','Com_commit','Com_rollback',
                 'Handler_commit','Bytes_received','Bytes_sent')
                """)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) { connections.add(rs.getLong(1)); counters.merge(rs.getString(2), rs.getLong(3), Long::sum); }
            }
        }
        // status_by_thread intentionally omits Com_* counters in MySQL. Statement
        // event classes give exact counts without the bounded SQL-digest registry.
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.EVENT_NAME,SUM(s.COUNT_STAR) AS executions
                FROM performance_schema.events_statements_summary_by_thread_by_event_name s
                JOIN performance_schema.threads t ON t.THREAD_ID=s.THREAD_ID
                WHERE t.PROCESSLIST_DB=? AND s.EVENT_NAME IN
                ('statement/sql/select','statement/sql/insert','statement/sql/update',
                 'statement/sql/delete','statement/sql/commit','statement/sql/rollback')
                GROUP BY s.EVENT_NAME
                """)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) counters.put("SQL_" + rs.getString(1).substring("statement/sql/".length()), rs.getLong(2));
            }
        }
        Map<String, Long> writes = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT OBJECT_NAME,COUNT_INSERT,COUNT_UPDATE,COUNT_DELETE,COUNT_FETCH
                FROM performance_schema.table_io_waits_summary_by_table WHERE OBJECT_SCHEMA=?
                """)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) for (int column = 2; column <= 5; column++)
                    writes.put(rs.getString(1) + "." + rs.getMetaData().getColumnLabel(column), rs.getLong(column));
            }
        }
        return new Snapshot(connections, counters, writes);
    }

    public List<Map<String, Object>> explain(String sql, Object... arguments) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN " + sql.replace("$schema", "`" + schema + "`"))) {
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            try (var rs = statement.executeQuery()) {
                var rows = new ArrayList<Map<String, Object>>();
                while (rs.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    public static Map<String, Object> difference(Snapshot before, Snapshot after) {
        return Map.of("stableConnections", before.connections.equals(after.connections),
                "connectionsBefore", before.connections.size(), "connectionsAfter", after.connections.size(),
                "sessionCounters", delta(before.counters, after.counters), "tableOperations", delta(before.writes, after.writes));
    }

    private static Map<String, Long> delta(Map<String, Long> before, Map<String, Long> after) {
        var result = new TreeMap<String, Long>();
        after.forEach((key, value) -> { long amount = value - before.getOrDefault(key, 0L); if (amount != 0) result.put(key, amount); });
        return result;
    }

    public record Snapshot(Set<Long> connections, Map<String, Long> counters, Map<String, Long> writes) { }
    @Override public void close() throws Exception { connection.close(); }
}
