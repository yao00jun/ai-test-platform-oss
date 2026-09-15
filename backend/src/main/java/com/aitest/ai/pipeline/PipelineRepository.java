package com.aitest.ai.pipeline;

import com.aitest.asset.AssetRepository;
import com.aitest.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class PipelineRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final AssetRepository projects;
    public PipelineRepository(JdbcTemplate jdbc, JsonCodec json, AssetRepository projects) { this.jdbc = jdbc; this.json = json; this.projects = projects; }
    public Map<String, Object> get(String project, String id) {
        projects.project(project);
        var rows = jdbc.queryForList("SELECT * FROM ai_pipeline_record WHERE id=? AND project_id=?", id, project);
        if (rows.isEmpty()) throw Problem.missing();
        var row = rows.getFirst(); Map<String, Object> value = new LinkedHashMap<>();
        for (var field : Map.of("id", "id", "projectId", "project_id", "jobId", "job_id", "conversationId", "conversation_id", "status", "status", "currentStage", "current_stage", "runId", "run_id", "error", "error").entrySet()) value.put(field.getKey(), row.get(field.getValue()));
        value.put("config", json.map(Objects.toString(row.get("config_snapshot"), "{}"))); value.put("assetIds", json.tree(row.get("asset_ids").toString()));
        value.put("revision", row.get("revision").toString()); value.put("progress", row.get("progress")); value.put("createdAt", time(row.get("created_at"))); value.put("updatedAt", time(row.get("updated_at")));
        List<Map<String, Object>> attempts = jdbc.query("SELECT * FROM ai_pipeline_step WHERE pipeline_id=? ORDER BY stage,attempt", (rs, n) -> {
            Map<String, Object> step = new LinkedHashMap<>(); step.put("id", rs.getString("id")); step.put("stage", rs.getString("stage")); step.put("status", rs.getString("status")); step.put("attempt", rs.getInt("attempt")); step.put("jobId", rs.getString("job_id"));
            step.put("input", json.map(rs.getString("input_snapshot"))); step.put("output", json.map(rs.getString("output_snapshot"))); step.put("error", rs.getString("error")); step.put("startedAt", rs.getTimestamp("started_at").toInstant()); step.put("completedAt", rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()); return step;
        }, id);
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>(); attempts.forEach(step -> latest.put(step.get("stage").toString(), step));
        value.put("steps", List.copyOf(latest.values())); value.put("attempts", attempts);
        Map<String, Object> execution = new LinkedHashMap<>();
        for (var step : attempts) if ("S6".equals(step.get("stage"))) {
            var output = com.aitest.execution.Values.map(step.get("output"));
            if (output.containsKey("runId") && !Objects.equals(output.get("runId"), execution.get("runId"))) {
                // Durable plan/history fields survive, but these belong to one run only.
                List.of("runJobId", "executionPlanId", "runSummary", "diagnosisJobId", "diagnosis").forEach(execution::remove);
            }
            if (output.containsKey("execution")) execution.remove("reason");
            execution.putAll(output);
        }
        value.put("execution", execution); return value;
    }
    public List<Map<String, Object>> list(String project) {
        projects.project(project);
        return jdbc.queryForList("SELECT id,status,current_stage,progress,created_at,updated_at FROM ai_pipeline_record WHERE project_id=? ORDER BY created_at DESC LIMIT 100", project).stream().map(row -> Map.<String, Object>of("id", row.get("id"), "status", row.get("status"), "currentStage", Objects.toString(row.get("current_stage"), ""), "progress", row.get("progress"), "createdAt", time(row.get("created_at")), "updatedAt", time(row.get("updated_at")))).toList();
    }
    public Map<String, Object> batch(String pipeline, String stage, String key) {
        var rows = jdbc.queryForList("SELECT output_snapshot FROM ai_pipeline_batch WHERE pipeline_id=? AND stage=? AND source_key=?", pipeline, stage, key);
        return rows.isEmpty() ? null : json.map(rows.getFirst().get("output_snapshot").toString());
    }
    public void saveBatch(String pipeline, String stage, String key, Object input, Map<String, Object> output, List<String> ids) {
        jdbc.update("INSERT INTO ai_pipeline_batch(id,pipeline_id,stage,source_key,input_snapshot,output_snapshot,asset_ids,created_at) VALUES(?,?,?,?,?,?,?,?)", Ids.newId(), pipeline, stage, key, json.write(input), json.write(output), json.write(ids), now());
    }
    public void lock(String project) { projects.lockProject(project); }
    public void requireActive(String project, String id, String jobId) {
        lock(project);
        // A job checkpoint may already have established a REPEATABLE READ snapshot.
        // The durable cancellation fence must use a current read after the project lock.
        var rows = jdbc.queryForList("SELECT status,job_id FROM ai_pipeline_record WHERE id=? AND project_id=? FOR UPDATE", id, project);
        if (rows.isEmpty()) throw Problem.missing();
        var pipeline = rows.getFirst();
        if (!"RUNNING".equals(pipeline.get("status")) || !jobId.equals(pipeline.get("job_id")))
            throw new java.util.concurrent.CancellationException("流水线已停止或此阶段已被后续尝试替代");
    }
    public JdbcTemplate jdbc() { return jdbc; }
    public static Timestamp now() { return Timestamp.from(Instant.now()); }
    private static Instant time(Object value) { return value instanceof Timestamp timestamp ? timestamp.toInstant() : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC); }
}
