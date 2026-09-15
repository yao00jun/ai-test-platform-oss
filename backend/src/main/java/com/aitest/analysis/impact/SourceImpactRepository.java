package com.aitest.analysis.impact;

import com.aitest.analysis.SourceSnapshotRepository;
import com.aitest.asset.AssetRepository;
import com.aitest.common.*;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Repository
public class SourceImpactRepository {
    private final AssetRepository assets; private final JsonCodec json; private final SecretProtector secrets; private final SourceSnapshotRepository sources;
    public SourceImpactRepository(AssetRepository assets, JsonCodec json, SecretProtector secrets, SourceSnapshotRepository sources) { this.assets = assets; this.json = json; this.secrets = secrets; this.sources = sources; }
    public Map<String, Object> get(String project, String id) {
        var row = row(project, id); var value = metadata(row);
        value.put("result", row.get("result_cipher") == null ? Map.of() : json.tree(secrets.decrypt(row.get("result_cipher").toString()))); return value;
    }
    public Map<String, Object> metadata(String project, String id) { return metadata(row(project, id)); }
    public Map<String, Object> result(String project, String id) {
        var row = row(project, id);
        if (!"READY".equals(row.get("status"))) throw new Problem(409, "SOURCE_IMPACT_NOT_READY", "影响分析尚未完成");
        return json.map(secrets.decrypt(row.get("result_cipher").toString()));
    }
    public Map<String, Object> list(String project, String source, int offset, int limit) {
        sources.get(project, source);
        if (offset < 0 || limit < 1 || limit > 100) throw Problem.invalid("影响分析分页无效");
        var rows = assets.jdbc().queryForList("SELECT i.*,j.status AS job_status,j.error AS job_error FROM source_impact i JOIN job_task j ON j.id=i.job_id WHERE i.project_id=? AND i.source_snapshot_id=? ORDER BY i.created_at DESC,i.id LIMIT ? OFFSET ?", project, source, limit, offset);
        return Map.of("items", rows.stream().map(this::metadata).toList(), "total", assets.jdbc().queryForObject("SELECT COUNT(*) FROM source_impact WHERE project_id=? AND source_snapshot_id=?", Long.class, project, source));
    }
    public String prepare(String project, String source, String baseline, Map<String, Object> result) {
        Object safe = sources.redact(project, source, result);
        if (baseline != null && !baseline.isBlank()) safe = sources.redact(project, baseline, safe);
        return secrets.encrypt(json.write(safe));
    }
    public void save(String project, String id, String cipher) {
        int updated = assets.jdbc().update("UPDATE source_impact SET result_cipher=?,status='READY',completed_at=? WHERE id=? AND project_id=? AND status='PENDING'", cipher, Timestamp.from(Instant.now()), id, project);
        if (updated != 1) throw new Problem(409, "SOURCE_IMPACT_IMMUTABLE", "已完成的影响报告不能覆盖");
    }
    private Map<String, Object> row(String project, String id) {
        assets.project(project);
        var rows = assets.jdbc().queryForList("SELECT i.*,j.status AS job_status,j.error AS job_error FROM source_impact i JOIN job_task j ON j.id=i.job_id WHERE i.id=? AND i.project_id=?", id, project);
        if (rows.isEmpty()) throw Problem.missing(); return rows.getFirst();
    }
    private Map<String, Object> metadata(Map<String, Object> row) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", row.get("id")); value.put("projectId", row.get("project_id")); value.put("sourceSnapshotId", row.get("source_snapshot_id")); value.put("baselineSnapshotId", row.get("baseline_snapshot_id")); value.put("jobId", row.get("job_id"));
        value.put("status", "PENDING".equals(row.get("status")) ? row.get("job_status") : row.get("status")); value.put("error", row.get("job_error"));
        value.put("createdAt", time(row.get("created_at"))); value.put("completedAt", time(row.get("completed_at"))); return value;
    }
    private static Instant time(Object value) { return value == null ? null : value instanceof Timestamp timestamp ? timestamp.toInstant() : ((LocalDateTime) value).toInstant(ZoneOffset.UTC); }
}
