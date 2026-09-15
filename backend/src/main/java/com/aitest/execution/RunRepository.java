package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class RunRepository {
    private final JdbcTemplate jdbc; private final JsonCodec json; private final SecretProtector secrets;
    private final AssetRepository assets; private final AssetSecrets assetSecrets; private final EvidenceRedactor redactor;
    public RunRepository(JdbcTemplate jdbc, JsonCodec json, SecretProtector secrets, AssetRepository assets, AssetSecrets assetSecrets, EvidenceRedactor redactor) {
        this.jdbc = jdbc; this.json = json; this.secrets = secrets; this.assets = assets; this.assetSecrets = assetSecrets; this.redactor = redactor;
    }
    public boolean exists(String projectId, String id) { return !jdbc.queryForList("SELECT id FROM test_run WHERE id=? AND project_id=?", id, projectId).isEmpty(); }
    public void create(String projectId, String id, String jobId, RunDefinition definition) {
        Map<String, Asset> sanitized = new LinkedHashMap<>(); definition.graph().assets().forEach((key, value) -> sanitized.put(key, assetSecrets.redact(value)));
        var snapshot = new AssetGraph(definition.graph().rootId(), definition.graph().environmentId(), sanitized, definition.graph().sourceEvidence());
        jdbc.update("INSERT INTO test_run(id,project_id,asset_id,job_id,name,status,snapshot,private_snapshot,summary,created_at) VALUES(?,?,?,?,?,'QUEUED',?,?,?,?)", id, projectId, definition.graph().rootId(), jobId, definition.graph().root().name(), json.write(snapshot), secrets.encrypt(json.write(definition)), "{}", Timestamp.from(Instant.now()));
        List<Object[]> rows = new ArrayList<>();
        for (var item : definition.items()) rows.add(new Object[]{item.id(), id, item.assetId(), item.name(), item.type().name(), item.position(), item.rowIndex(), json.write(redactor.redact(item.variables(), item.variables())), item.mode().equals("MANUAL") || item.type() == AssetType.FUNCTIONAL_CASE ? "MANUAL_PENDING" : "QUEUED"});
        jdbc.batchUpdate("INSERT INTO test_run_item(id,run_id,asset_id,name,asset_type,position,row_index,variables,status) VALUES(?,?,?,?,?,?,?,?,?)", rows);
    }
    public RunDefinition definition(String projectId, String id) {
        assets.project(projectId);
        var rows = jdbc.queryForList("SELECT private_snapshot FROM test_run WHERE id=? AND project_id=?", id, projectId);
        if (rows.isEmpty()) throw Problem.missing(); return json.read(secrets.decrypt(rows.getFirst().get("private_snapshot").toString()), RunDefinition.class);
    }
    public Map<String, Object> get(String projectId, String id) {
        assets.project(projectId);
        var rows = jdbc.queryForList("SELECT id,project_id,asset_id,job_id,name,status,snapshot,summary,created_at,started_at,completed_at FROM test_run WHERE id=? AND project_id=?", id, projectId);
        if (rows.isEmpty()) throw Problem.missing();
        Map<String, Object> result = normalize(rows.getFirst()); result.put("snapshot", json.tree(rows.getFirst().get("snapshot").toString())); result.put("summary", summary(id));
        List<Map<String, Object>> items = jdbc.queryForList("SELECT * FROM test_run_item WHERE run_id=? ORDER BY position,row_index", id).stream().map(this::normalize).toList();
        var steps = jdbc.queryForList("SELECT s.* FROM test_step_result s JOIN test_run_item i ON s.run_item_id=i.id WHERE i.run_id=? ORDER BY i.position,s.position", id);
        Map<String, List<Map<String, Object>>> byItem = new HashMap<>();
        for (var step : steps) { Map<String, Object> value = normalize(step); value.put("result", json.tree(step.get("result").toString())); byItem.computeIfAbsent(step.get("run_item_id").toString(), ignored -> new ArrayList<>()).add(value); }
        for (var item : items) { item.put("variables", json.tree(item.get("variables").toString())); item.put("steps", byItem.getOrDefault(item.get("id").toString(), List.of())); }
        result.put("items", items); return result;
    }
    public Map<String, Object> list(String projectId, int offset, int limit) {
        assets.project(projectId); if (offset < 0 || limit < 1 || limit > 200) throw Problem.invalid("分页参数无效");
        var rows = jdbc.queryForList("SELECT id,project_id,asset_id,job_id,name,status,summary,created_at,started_at,completed_at FROM test_run WHERE project_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?", projectId, limit, offset);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : rows) { var value = normalize(row); value.put("summary", summary(row.get("id").toString())); result.add(value); }
        return Map.of("items", result, "total", jdbc.queryForObject("SELECT COUNT(*) FROM test_run WHERE project_id=?", Long.class, projectId));
    }
    public void started(String id) { jdbc.update("UPDATE test_run SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED'", Timestamp.from(Instant.now()), id); }
    public void itemStarted(String id) { jdbc.update("UPDATE test_run_item SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED'", Timestamp.from(Instant.now()), id); }
    public void step(String itemId, Asset asset, int position, StepResult result, Map<String, Object> variables) {
        Object safe = redactor.redact(json.tree(json.write(result)), variables);
        jdbc.update("INSERT INTO test_step_result(id,run_item_id,asset_id,name,engine,status,position,result,created_at) VALUES(?,?,?,?,?,?,?,?,?)", Ids.newId(), itemId, asset.id(), asset.name(), asset.type().name(), result.status(), position, json.write(safe), Timestamp.from(Instant.now()));
    }
    public void itemFinished(String id, String status, long duration, String error) { jdbc.update("UPDATE test_run_item SET status=?,duration_ms=?,error=?,completed_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')", status, duration, error, Timestamp.from(Instant.now()), id); }
    public Map<String, Object> summary(String id) {
        Map<String, Long> counts = new LinkedHashMap<>(); long total = 0;
        for (var row : jdbc.queryForList("SELECT status,COUNT(*) AS total FROM test_run_item WHERE run_id=? GROUP BY status", id)) {
            long count = ((Number) row.get("total")).longValue(); counts.put(row.get("status").toString(), count); total += count;
        }
        return Map.of("total", total, "counts", counts, "caseCount", jdbc.queryForObject("SELECT COUNT(DISTINCT asset_id) FROM test_run_item WHERE run_id=?", Long.class, id), "dataRows", jdbc.queryForObject("SELECT COUNT(*) FROM test_run_item WHERE run_id=? AND row_index IS NOT NULL", Long.class, id));
    }
    @Transactional public void finish(String id, String forcedStatus) {
        var owner = jdbc.queryForList("SELECT project_id FROM test_run WHERE id=?", String.class, id);
        if (owner.isEmpty()) throw Problem.missing();
        String projectId = owner.getFirst();
        // Also lock deleted projects: completing an in-flight run still needs an audit.
        var project = jdbc.queryForMap("SELECT id,name FROM project WHERE id=? FOR UPDATE", projectId);
        var run = jdbc.queryForMap("SELECT name FROM test_run WHERE id=? FOR UPDATE", id);
        if (forcedStatus != null) jdbc.update("UPDATE test_run_item SET status=?,completed_at=? WHERE run_id=? AND status IN ('QUEUED','RUNNING')", forcedStatus, Timestamp.from(Instant.now()), id);
        // Locking reads see the last committed item values even if the caller already
        // opened a repeatable-read snapshot before acquiring the project lock.
        var items = jdbc.queryForList("SELECT asset_id,row_index,status FROM test_run_item WHERE run_id=? FOR UPDATE", id);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (var item : items) counts.merge(item.get("status").toString(), 1L, Long::sum);
        Map<String, Object> summary = Map.of("total", items.size(), "counts", counts, "caseCount", items.stream().map(item -> item.get("asset_id")).distinct().count(), "dataRows", items.stream().filter(item -> item.get("row_index") != null).count());
        String status = forcedStatus == null ? aggregate(Values.map(summary.get("counts"))) : forcedStatus;
        boolean complete = forcedStatus != null || Collections.disjoint(counts.keySet(), Set.of("QUEUED", "RUNNING", "MANUAL_PENDING"));
        Instant completed = Instant.now();
        jdbc.update("UPDATE test_run SET status=?,summary=?,completed_at=? WHERE id=?", status, json.write(summary), complete ? Timestamp.from(completed) : null, id);
        if (complete && jdbc.queryForList("SELECT seq FROM run_completion_event WHERE run_id=? FOR UPDATE", id).isEmpty()) {
            boolean failed = Set.of("FAILED", "ERROR", "BLOCKED", "CANCELLED", "INTERRUPTED").contains(status);
            var report = Map.of("projectId", projectId, "projectName", project.get("name"), "runId", id, "planName", run.get("name"), "status", status, "summary", summary, "completedAt", completed);
            jdbc.update("INSERT INTO run_completion_event(project_id,run_id,failed,report,created_at) VALUES(?,?,?,?,?)", projectId, id, failed, json.write(report), Timestamp.from(completed));
        }
    }
    @Transactional public Map<String, Object> manual(String projectId, String runId, String itemId, String baseVersion, String status, String notes) {
        assets.lockProject(projectId);
        var rows = jdbc.queryForList("SELECT i.* FROM test_run_item i JOIN test_run r ON i.run_id=r.id WHERE i.id=? AND r.id=? AND r.project_id=? FOR UPDATE", itemId, runId, projectId);
        if (rows.isEmpty()) throw Problem.missing(); var row = rows.getFirst();
        if (!Set.of("PASSED", "FAILED", "BLOCKED", "SKIPPED").contains(status)) throw Problem.invalid("人工结果状态无效");
        if (!row.get("manual_version").toString().equals(baseVersion)) throw Problem.conflict("人工执行记录已更新，请重新载入");
        if (!row.get("status").equals("MANUAL_PENDING") && jdbc.queryForObject("SELECT COUNT(*) FROM manual_result_event WHERE run_item_id=?", Long.class, itemId) == 0) throw Problem.invalid("此运行项不是人工执行项目");
        if (notes != null && notes.length() > 20000) throw Problem.invalid("执行备注最多 20000 字符");
        long next = Long.parseLong(baseVersion) + 1;
        jdbc.update("UPDATE test_run_item SET status=?,notes=?,manual_version=?,completed_at=? WHERE id=?", status, notes, next, Timestamp.from(Instant.now()), itemId);
        jdbc.update("INSERT INTO manual_result_event(id,run_item_id,version,status,notes,created_at) VALUES(?,?,?,?,?,?)", Ids.newId(), itemId, next, status, notes, Timestamp.from(Instant.now()));
        finish(runId, null); return get(projectId, runId);
    }
    private String aggregate(Map<String, Object> counts) {
        for (String status : List.of("RUNNING", "QUEUED", "ERROR", "FAILED", "INTERRUPTED", "CANCELLED", "BLOCKED", "MANUAL_PENDING")) if (counts.containsKey(status)) return status.equals("ERROR") ? "FAILED" : status;
        return counts.containsKey("PASSED") ? "PASSED" : "SKIPPED";
    }
    private Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            StringBuilder name = new StringBuilder(); boolean upper = false;
            for (char c : key.toCharArray()) { if (c == '_') upper = true; else { name.append(upper ? Character.toUpperCase(c) : c); upper = false; } }
            if (value instanceof Timestamp timestamp) value = timestamp.toInstant();
            if (value instanceof java.time.LocalDateTime local) value = local.toInstant(java.time.ZoneOffset.UTC);
            if (key.equals("manual_version")) value = value.toString();
            result.put(name.toString(), value);
        });
        return result;
    }
}
