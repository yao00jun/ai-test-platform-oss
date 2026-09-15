package com.aitest.engine.sql;

import com.aitest.asset.Asset;
import com.aitest.common.Problem;
import com.aitest.common.JsonCodec;
import com.aitest.execution.*;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

@Component
public final class SqlStepExecutor {
    private final DynamicDataSourceManager pools;
    private final SqlParameters parameters;
    private final SqlPolicyValidator policy;
    private final VariableResolver resolver;
    private final AssertionEvaluator assertions;
    private final EvidenceRedactor redactor;
    private final JsonCodec json;
    public SqlStepExecutor(DynamicDataSourceManager pools, SqlParameters parameters, SqlPolicyValidator policy, VariableResolver resolver, AssertionEvaluator assertions, EvidenceRedactor redactor, JsonCodec json) {
        this.pools = pools; this.parameters = parameters; this.policy = policy; this.resolver = resolver; this.assertions = assertions; this.redactor = redactor; this.json = json;
    }
    public StepResult execute(Asset source, Map<String, Object> spec, ExecutionContext context, boolean environmentAllowsWrite) {
        long start = System.nanoTime(); Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> bindings = new LinkedHashMap<>(context.variables());
        try {
            context.checkpoint();
            bindings.putAll(Values.map(resolver.resolve(spec.get("parameters"), context.variables())));
            SqlParameters.Prepared sql = parameters.bind(Values.text(spec, "sql", ""), bindings);
            boolean allowWrite = environmentAllowsWrite && !Values.bool(source.data(), "safeMode", true) && Values.bool(spec, "allowWrite", false);
            boolean write = policy.validate(sql.sql(), allowWrite).write();
            request.put("sql", sql.sql()); request.put("parameters", sql.values()); request.put("databaseSourceId", source.id());
            int maxRows = Values.integer(spec, "maxRows", 1000, 1, 10000);
            boolean dryRun = Values.bool(spec, "dryRun", true);
            try (var lease = pools.borrow(source)) {
                Connection connection = lease.connection(); connection.setReadOnly(!write); connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(sql.sql())) {
                    statement.setQueryTimeout(Values.integer(spec, "timeoutSeconds", 30, 1, 300)); statement.setMaxRows(maxRows + 1);
                    for (int i = 0; i < sql.values().size(); i++) statement.setObject(i + 1, sql.values().get(i));
                    boolean query = statement.execute();
                    Map<String, Object> actual = new LinkedHashMap<>(); List<Map<String, Object>> rows = new ArrayList<>(); int affected = 0;
                    if (query) try (ResultSet result = statement.getResultSet()) {
                        ResultSetMetaData metadata = result.getMetaData();
                        while (result.next()) {
                            if (rows.size() == maxRows) throw Problem.invalid("SQL 查询超过 " + maxRows + " 行，请缩小范围");
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                                String label = metadata.getColumnLabel(column);
                                if (row.containsKey(label)) throw Problem.invalid("SQL 返回列名重复，请显式指定别名: " + label);
                                Object value = result.getObject(column);
                                if (value instanceof Blob blob) { if (blob.length() > 65536) throw Problem.invalid("SQL 二进制字段过大"); value = Base64.getEncoder().encodeToString(blob.getBytes(1, (int) blob.length())); }
                                else if (value instanceof byte[] bytes) value = Base64.getEncoder().encodeToString(bytes);
                                else if (value instanceof java.sql.Date || value instanceof java.sql.Time || value instanceof java.sql.Timestamp || value instanceof java.time.temporal.TemporalAccessor) value = value.toString();
                                row.put(label, value);
                            }
                            rows.add(row);
                        }
                    } else affected = statement.getUpdateCount();
                    if (affected > Values.integer(spec, "maxAffectedRows", 1000, 1, 10000)) throw Problem.invalid("写入影响行数超过上限，事务已回滚");
                    actual.put("rows", rows); actual.put("rowCount", rows.size()); actual.put("affectedRows", affected);
                    actual.put("dryRun", dryRun); actual.put("durationMs", elapsed(start));
                    List<AssertionResult> checks = assertions.evaluate(Values.objects(spec.get("assertions")), actual, bindings);
                    Map<String, Object> exports = new LinkedHashMap<>();
                    for (var export : Values.map(spec.get("exports")).entrySet()) {
                        String column = Objects.toString(export.getValue(), "");
                        if (rows.isEmpty() || !rows.getFirst().containsKey(column)) throw Problem.invalid("SQL 导出字段不存在: " + column);
                        exports.put(export.getKey(), rows.getFirst().get(column));
                    }
                    context.checkpoint(); boolean passed = checks.stream().allMatch(AssertionResult::passed);
                    if (!write || dryRun || !passed) connection.rollback(); else connection.commit();
                    actual.put("committed", write && !dryRun && passed);
                    if (passed) context.publish(exports);
                    return safe(new StepResult(passed ? "PASSED" : "FAILED", elapsed(start), request, actual, checks, exports, List.of(), passed ? null : "SQL 断言失败"), bindings);
                } catch (Exception error) { connection.rollback(); throw error; }
            }
        } catch (CancellationException e) { throw e; }
        catch (Exception error) {
            String message = error instanceof Problem ? error.getMessage() : error instanceof SQLTimeoutException ? "SQL 执行超时" : "SQL 执行失败（" + error.getClass().getSimpleName() + "）";
            return safe(StepResult.error(message, elapsed(start), request), bindings);
        }
    }
    private StepResult safe(StepResult result, Map<String, Object> bindings) { return json.convert(redactor.redact(json.tree(json.write(result)), bindings), StepResult.class); }
    private long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
}
