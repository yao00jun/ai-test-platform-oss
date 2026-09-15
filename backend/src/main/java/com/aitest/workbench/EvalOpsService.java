package com.aitest.workbench;

import com.aitest.asset.AssetRepository;
import com.aitest.common.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Measured usage, terminal generation jobs, frozen run items and independent human judgements. */
@Service
public class EvalOpsService {
    private final JdbcTemplate jdbc;
    private final AssetRepository assets;
    public EvalOpsService(JdbcTemplate jdbc, AssetRepository assets) { this.jdbc = jdbc; this.assets = assets; }
    public record Window(Instant from, Instant to) { }
    public static Window window(String from, String to) {
        try {
            Instant start = from == null || from.isBlank() ? Instant.EPOCH : Instant.parse(from);
            Instant end = to == null || to.isBlank() ? Instant.now().truncatedTo(ChronoUnit.MILLIS) : Instant.parse(to);
            if (start.isBefore(Instant.EPOCH) || !start.isBefore(end) || end.isAfter(Instant.parse("9999-01-01T00:00:00Z"))) throw new IllegalArgumentException();
            return new Window(start, end);
        } catch (RuntimeException invalid) { throw Problem.invalid("统计范围需要 ISO 时间，且 1970 年起始时间 < 结束时间"); }
    }
    private record Query(String where, Object[] args) { }
    private Query invocations(String project, Window window, String model, String version) {
        String where = "v.project_id=? AND v.started_at>=? AND v.started_at<?";
        List<Object> args = new ArrayList<>(List.of(project, Timestamp.from(window.from()), Timestamp.from(window.to())));
        if (model != null && !model.isBlank()) { if (model.length() > 200) throw Problem.invalid("模型名称过长"); where += " AND v.model_name=?"; args.add(model); }
        if (version != null && !version.isBlank()) { if (version.length() > 100) throw Problem.invalid("模型配置版本过长"); where += " AND v.model_version=?"; args.add(version); }
        return new Query(where, args.toArray());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> summary(String project, Window window) {
        assets.project(project);
        Query calls = invocations(project, window, null, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "aitest.evalops/v1"); result.put("projectId", project); result.put("from", window.from()); result.put("to", window.to()); result.put("capturedAt", Instant.now());
        result.put("usage", usage(calls)); result.put("generation", generation(calls));
        result.put("execution", execution(project, window)); result.put("rca", rca(project, window));
        var models = jdbc.queryForList("SELECT DISTINCT v.model_name,v.model_version FROM ai_model_invocation v WHERE " + calls.where + " ORDER BY v.model_name,v.model_version", calls.args);
        List<Map<String, Object>> grouped = new ArrayList<>();
        for (var model : models) {
            Query selected = invocations(project, window, model.get("model_name").toString(), model.get("model_version").toString());
            Map<String, Object> row = usage(selected);
            row.put("modelName", model.get("model_name")); row.put("modelVersion", model.get("model_version")); row.put("generation", generation(selected)); grouped.add(row);
        }
        result.put("byModel", grouped);
        return result;
    }
    private Map<String, Object> usage(Query query) {
        var raw = jdbc.queryForMap("""
                SELECT COUNT(*) AS invocations,
                  COALESCE(SUM(v.status='SUCCEEDED'),0) AS succeeded,
                  COALESCE(SUM(v.status='FAILED'),0) AS failed,
                  COALESCE(SUM(v.status='CANCELLED'),0) AS cancelled,
                  COALESCE(SUM(v.status='INTERRUPTED' OR (v.status='RUNNING' AND j.status NOT IN ('QUEUED','RUNNING'))),0) AS interrupted,
                  COALESCE(SUM(v.status='RUNNING' AND j.status IN ('QUEUED','RUNNING')),0) AS active,
                  COALESCE(SUM(v.http_attempts),0) AS http_attempts,
                  COALESCE(SUM(v.usage_reported),0) AS usage_reported,
                  SUM(v.prompt_tokens) AS prompt_tokens,SUM(v.completion_tokens) AS completion_tokens,SUM(v.total_tokens) AS total_tokens,
                  COALESCE(SUM(v.estimated_cost IS NOT NULL),0) AS priced_invocations,
                  COUNT(v.duration_ms) AS duration_samples,AVG(v.duration_ms) AS average_duration_ms
                FROM ai_model_invocation v JOIN job_task j ON j.id=v.job_id WHERE
                """ + query.where, query.args);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("invocations", "succeeded", "failed", "cancelled", "interrupted", "active", "http_attempts", "usage_reported", "prompt_tokens", "completion_tokens", "total_tokens", "priced_invocations", "duration_samples")) out.put(camel(key), raw.get(key) == null ? null : ((Number) raw.get(key)).longValue());
        out.put("usageMissing", number(raw, "invocations") - number(raw, "usage_reported"));
        out.put("averageDurationMs", decimal(raw.get("average_duration_ms")));
        out.put("costByCurrency", jdbc.query("SELECT v.currency,COUNT(*) AS samples,SUM(v.estimated_cost) AS cost FROM ai_model_invocation v WHERE " + query.where + " AND v.estimated_cost IS NOT NULL GROUP BY v.currency ORDER BY v.currency",
                (rs, n) -> Map.of("currency", rs.getString("currency"), "invocations", rs.getLong("samples"), "estimatedCost", rs.getBigDecimal("cost")), query.args));
        return out;
    }
    private Map<String, Object> generation(Query calls) {
        // A successful HTTP response can still be invalid JSON, fail grounding or lose a CAS race.
        // Pipeline BLOCKED is a completed control task, not a validated generation.
        var raw = jdbc.queryForMap("""
                SELECT COUNT(*) AS attempts,
                  COALESCE(SUM(j.status='SUCCEEDED' AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(j.result,'$.status')),'')<>'BLOCKED'),0) AS valid,
                  COALESCE(SUM(j.status='FAILED'),0) AS invalid,
                  COALESCE(SUM(j.status='SUCCEEDED' AND JSON_UNQUOTE(JSON_EXTRACT(j.result,'$.status'))='BLOCKED'),0) AS blocked,
                  COALESCE(SUM(j.status='CANCELLED'),0) AS cancelled,
                  COALESCE(SUM(j.status='INTERRUPTED'),0) AS interrupted,
                  COALESCE(SUM(j.status IN ('QUEUED','RUNNING')),0) AS active
                FROM job_task j WHERE EXISTS (SELECT 1 FROM ai_model_invocation v WHERE v.job_id=j.id AND
                """ + calls.where + ")", calls.args);
        Map<String, Object> out = new LinkedHashMap<>(); raw.forEach((key, value) -> out.put(camel(key), ((Number) value).longValue()));
        long finished = number(raw, "valid") + number(raw, "invalid");
        out.put("finished", finished); out.put("validRatePercent", percent(number(raw, "valid"), finished));
        out.put("denominator", "SUCCEEDED_OR_FAILED_JOBS_WITH_MEASURED_MODEL_CALLS");
        return out;
    }
    private Map<String, Object> execution(String project, Window window) {
        var raw = jdbc.queryForMap("""
                SELECT COUNT(*) AS ai_items,
                  COALESCE(SUM(i.status IN ('PASSED','FAILED','ERROR')),0) AS executed,
                  COALESCE(SUM(i.status='PASSED'),0) AS passed,
                  COALESCE(SUM(i.status='FAILED'),0) AS failed,
                  COALESCE(SUM(i.status='ERROR'),0) AS errors,
                  COALESCE(SUM(i.status='BLOCKED'),0) AS blocked,
                  COALESCE(SUM(i.status='SKIPPED'),0) AS skipped,
                  COALESCE(SUM(i.status IN ('QUEUED','RUNNING','MANUAL_PENDING')),0) AS pending,
                  COALESCE(SUM(i.status IN ('CANCELLED','INTERRUPTED')),0) AS interrupted
                FROM test_run_item i JOIN test_run r ON r.id=i.run_id
                WHERE r.project_id=? AND r.created_at>=? AND r.created_at<?
                AND JSON_UNQUOTE(JSON_EXTRACT(r.snapshot,CONCAT('$.assets."',i.asset_id,'".source')))='AI'
                """, project, Timestamp.from(window.from()), Timestamp.from(window.to()));
        Map<String, Object> out = new LinkedHashMap<>(); raw.forEach((key, value) -> out.put(camel(key), ((Number) value).longValue()));
        out.put("passRatePercent", percent(number(raw, "passed"), number(raw, "executed"))); out.put("denominator", "FROZEN_AI_ITEMS_PASSED_FAILED_ERROR");
        return out;
    }
    private Map<String, Object> rca(String project, Window window) {
        var raw = jdbc.queryForMap("""
                SELECT COUNT(*) AS judged_versions,
                  COALESCE(SUM(verdict<>'UNREVIEWED'),0) AS evaluated_versions,
                  COALESCE(SUM(verdict='UNREVIEWED'),0) AS cleared_versions,
                  COALESCE(SUM(verdict='CORRECT'),0) AS correct,
                  COALESCE(SUM(verdict='PARTIAL'),0) AS partial,
                  COALESCE(SUM(verdict='INCORRECT'),0) AS incorrect
                FROM (SELECT e.*,ROW_NUMBER() OVER(PARTITION BY bug_id,diagnosis_hash ORDER BY created_at DESC,id DESC) AS ordinal
                      FROM bug_rca_evaluation e WHERE project_id=? AND source='MANUAL' AND created_at<?) latest
                WHERE ordinal=1 AND created_at>=?
                """, project, Timestamp.from(window.to()), Timestamp.from(window.from()));
        Map<String, Object> out = new LinkedHashMap<>(); raw.forEach((key, value) -> out.put(camel(key), ((Number) value).longValue()));
        out.put("confirmedRegressionBugs", jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT e.*,ROW_NUMBER() OVER(PARTITION BY bug_id ORDER BY created_at DESC,id DESC) AS ordinal
                  FROM bug_rca_evaluation e WHERE project_id=? AND source='MANUAL' AND created_at<?) latest
                WHERE ordinal=1 AND regression='CONFIRMED' AND created_at>=?
                """, Long.class, project, Timestamp.from(window.to()), Timestamp.from(window.from())));
        out.put("correctRatePercent", percent(number(raw, "correct"), number(raw, "evaluated_versions")));
        out.put("regressionInterceptionRatePercent", null);
        out.put("denominator", "LATEST_HUMAN_VERDICT_PER_DIAGNOSIS_DIGEST");
        return out;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(String project, Window window, String model, String version, int offset, int limit) {
        assets.project(project); if (offset < 0 || limit < 1 || limit > 100) throw Problem.invalid("调用记录分页范围为 1–100");
        Query query = invocations(project, window, model, version);
        List<Object> args = new ArrayList<>(Arrays.asList(query.args)); args.add(limit); args.add(offset);
        var rows = jdbc.queryForList("SELECT v.*,j.status AS job_status FROM ai_model_invocation v JOIN job_task j ON j.id=v.job_id WHERE " + query.where + " ORDER BY v.started_at DESC,v.id DESC LIMIT ? OFFSET ?", args.toArray());
        List<Map<String, Object>> items = rows.stream().map(raw -> {
            Map<String, Object> out = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                if (value instanceof Timestamp stamp) value = stamp.toInstant();
                if (value instanceof LocalDateTime time) value = time.toInstant(ZoneOffset.UTC);
                if (key.equals("pricing_version") && value != null) value = value.toString();
                out.put(camel(key), value);
            });
            if ("RUNNING".equals(out.get("status")) && !Set.of("QUEUED", "RUNNING").contains(out.get("jobStatus"))) out.put("status", "INTERRUPTED");
            return out;
        }).toList();
        return Map.of("items", items, "total", jdbc.queryForObject("SELECT COUNT(*) FROM ai_model_invocation v WHERE " + query.where, Long.class, query.args));
    }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static Object decimal(Object value) { return value == null ? null : new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP); }
    private static BigDecimal percent(long count, long total) { return total == 0 ? null : BigDecimal.valueOf(count * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP); }
    private static String camel(String key) {
        StringBuilder out = new StringBuilder(); boolean upper = false;
        for (char c : key.toCharArray()) { if (c == '_') upper = true; else { out.append(upper ? Character.toUpperCase(c) : c); upper = false; } }
        return out.toString();
    }
}
