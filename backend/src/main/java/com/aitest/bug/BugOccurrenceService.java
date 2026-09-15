package com.aitest.bug;

import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.execution.Values;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class BugOccurrenceService {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final AssetService assets;
    private final AssetRepository repository;
    public BugOccurrenceService(JdbcTemplate jdbc, JsonCodec json, AssetService assets, AssetRepository repository) { this.jdbc = jdbc; this.json = json; this.assets = assets; this.repository = repository; }
    public Map<String, Object> existing(String project, FailureEvidenceReader.Failure failure) {
        var rows = jdbc.queryForList("SELECT id,bug_id,status FROM bug_failure_occurrence WHERE project_id=? AND run_id=? AND run_item_id=? AND failure_key=?", project, failure.runId(), failure.itemId(), failure.key());
        return rows.isEmpty() ? null : result(rows.getFirst().get("id").toString(), rows.getFirst().get("bug_id").toString(), rows.getFirst().get("status").toString(), true);
    }
    public boolean known(String project, String fingerprint) { return !jdbc.queryForList("SELECT bug_id FROM bug_failure_registry WHERE project_id=? AND fingerprint=?", project, fingerprint).isEmpty(); }

    /** Called inside a job's short atomic section. The project lock also serializes human deletion. */
    @Transactional
    public Map<String, Object> record(String project, String jobId, FailureEvidenceReader.Failure failure, Map<String, Object> diagnosis, String modelStamp, String promptVersion) {
        repository.lockProject(project);
        var prior = existing(project, failure); if (prior != null) return prior;
        var registry = jdbc.queryForList("SELECT bug_id FROM bug_failure_registry WHERE project_id=? AND fingerprint=? FOR UPDATE", project, failure.fingerprint());
        Timestamp now = Timestamp.from(Instant.now()); String bugId, status;
        if (registry.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("severity", diagnosis.get("severity")); data.put("status", "OPEN"); data.put("rootCauseAnalysis", diagnosis.get("rootCauseAnalysis")); data.put("suggestion", diagnosis.get("fixSuggestion"));
            data.put("codeDiagnosis", diagnosis.getOrDefault("codeDiagnosis", Map.of()));
            var sourceBindings = Values.objects(Values.map(failure.evidence().get("sourceEvidence")).get("bindings"));
            if (sourceBindings.size() == 1) data.put("sourceSnapshotId", sourceBindings.getFirst().get("sourceSnapshotId"));
            Map<String, Object> result = Values.map(failure.evidence().get("result"));
            data.put("reproduceSteps", "运行：" + failure.runId() + "\n用例：" + failure.caseName() + "\n" + json.write(result.getOrDefault("request", Map.of())));
            data.put("actualResult", json.write(result.getOrDefault("actual", result.getOrDefault("error", ""))));
            Object checks = failure.evidence().get("failedAssertions");
            data.put("expectedResult", checks instanceof List<?> list && !list.isEmpty() ? json.write(checks) : "原始运行未提供机器断言；请结合需求和人工执行记录确认预期。");
            try { assets.get(project, failure.caseId()); data.put("associatedCaseId", failure.caseId()); }
            catch (Problem missing) { if (missing.status() != 404) throw missing; }
            data.put("runId", failure.runId()); data.put("fingerprint", failure.fingerprint()); data.put("attachments", result.getOrDefault("artifactIds", List.of()));
            bugId = assets.create(project, AssetType.BUG, null, diagnosis.get("title").toString(), data, "AI").id(); status = "CREATED";
            jdbc.update("INSERT INTO bug_failure_registry(project_id,fingerprint,bug_id,first_seen,last_seen,occurrence_count) VALUES(?,?,?,?,?,0)", project, failure.fingerprint(), bugId, now, now);
        } else {
            bugId = registry.getFirst().get("bug_id").toString();
            var deleted = jdbc.queryForObject("SELECT deleted FROM asset WHERE id=? AND project_id=?", Boolean.class, bugId, project);
            status = Boolean.TRUE.equals(deleted) ? "SUPPRESSED_DELETED" : "RECURRENCE";
            // No update to bug_issue or asset: even version and timestamps are human-owned here.
        }
        String id = Ids.newId();
        jdbc.update("INSERT INTO bug_failure_occurrence(id,project_id,run_id,run_item_id,failure_key,fingerprint,bug_id,job_id,status,evidence,diagnosis,model_stamp,prompt_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, project, failure.runId(), failure.itemId(), failure.key(), failure.fingerprint(), bugId, jobId, status, json.write(failure.evidence()), json.write(diagnosis), modelStamp, promptVersion, now);
        jdbc.update("UPDATE bug_failure_registry SET last_seen=?,occurrence_count=occurrence_count+1 WHERE project_id=? AND fingerprint=?", now, project, failure.fingerprint());
        return result(id, bugId, status, false);
    }
    public Map<String, Object> forBug(String project, String bugId) {
        if (assets.get(project, bugId).type() != AssetType.BUG) throw Problem.invalid("目标不是缺陷");
        var items = list("project_id=? AND bug_id=?", project, bugId);
        return Map.of("items", items, "occurrenceCount", items.size());
    }
    public Map<String, Object> forRun(String project, String runId) {
        repository.project(project);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM test_run WHERE id=? AND project_id=?", Long.class, runId, project) == 0) throw Problem.missing();
        return Map.of("items", list("project_id=? AND run_id=?", project, runId));
    }
    private List<Map<String, Object>> list(String predicate, Object... arguments) {
        return jdbc.query("SELECT id,run_id,run_item_id,bug_id,job_id,status,evidence,diagnosis,model_stamp,prompt_version,created_at FROM bug_failure_occurrence WHERE " + predicate + " ORDER BY created_at,id", (rs, n) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", rs.getString("id")); value.put("runId", rs.getString("run_id")); value.put("runItemId", rs.getString("run_item_id")); value.put("bugId", rs.getString("bug_id")); value.put("jobId", rs.getString("job_id")); value.put("status", rs.getString("status"));
            value.put("evidence", json.tree(rs.getString("evidence"))); value.put("diagnosis", json.tree(rs.getString("diagnosis"))); value.put("modelStamp", rs.getString("model_stamp")); value.put("promptVersion", rs.getString("prompt_version")); value.put("createdAt", rs.getTimestamp("created_at").toInstant()); return value;
        }, arguments);
    }
    private Map<String, Object> result(String id, String bugId, String status, boolean repeated) { return Map.of("occurrenceId", id, "bugId", bugId, "status", status, "idempotent", repeated); }
}
