package com.aitest.analysis;

import com.aitest.analysis.source.SourceFile;
import com.aitest.asset.AssetRepository;
import com.aitest.common.*;
import com.aitest.execution.Values;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class SourceSnapshotRepository {
    private final JdbcTemplate jdbc;
    private final AssetRepository assets;
    private final JsonCodec json;
    private final SecretProtector secrets;
    public SourceSnapshotRepository(JdbcTemplate jdbc, AssetRepository assets, JsonCodec json, SecretProtector secrets) {
        this.jdbc = jdbc; this.assets = assets; this.json = json; this.secrets = secrets;
    }
    public Map<String, Object> get(String project, String id) {
        Map<String, Object> row = row(project, id);
        Map<String, Object> output = metadata(row);
        Map<String, Object> input = json.map(secrets.decrypt(row.get("input_cipher").toString()));
        input.remove("ddlText");
        output.put("configuration", input);
        output.put("diagnostics", json.tree(row.get("diagnostics").toString()));
        output.put("result", row.get("result_cipher") == null ? Map.of() : SourceRedactor.redact(json.tree(secrets.decrypt(row.get("result_cipher").toString())), redactions(row)));
        return output;
    }
    public Map<String, Object> list(String project, int offset, int limit) {
        assets.project(project); pagination(offset, limit);
        var rows = jdbc.queryForList("SELECT s.*,j.status AS job_status,j.error AS job_error FROM source_snapshot s JOIN job_task j ON j.id=s.job_id WHERE s.project_id=? ORDER BY s.created_at DESC,s.id LIMIT ? OFFSET ?", project, limit, offset);
        return Map.of("items", rows.stream().map(this::metadata).toList(), "total", jdbc.queryForObject("SELECT COUNT(*) FROM source_snapshot WHERE project_id=?", Long.class, project));
    }
    private Map<String, Object> metadata(Map<String, Object> row) {
        String status = row.get("status").toString();
        if (status.equals("PENDING")) status = row.get("job_status").toString();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", row.get("id")); output.put("projectId", row.get("project_id")); output.put("projectVersion", row.get("project_version").toString()); output.put("jobId", row.get("job_id")); output.put("status", status);
        output.put("fileCount", row.get("file_count")); output.put("totalBytes", row.get("total_bytes")); output.put("manifestHash", row.get("manifest_hash"));
        output.put("createdAt", instant(row.get("created_at"))); output.put("completedAt", instant(row.get("completed_at"))); output.put("error", row.get("job_error"));
        return output;
    }
    public Map<String, Object> input(String project, String id) { return json.map(secrets.decrypt(row(project, id).get("input_cipher").toString())); }
    public Map<String, Object> result(String project, String id) {
        Map<String, Object> row = ready(project, id);
        return json.map(secrets.decrypt(row.get("result_cipher").toString()));
    }
    /** Internal immutable bytes for analysis; public/model callers must use redacted accessors. */
    public List<SourceFile> contents(String project, String id, String kind) {
        ready(project, id);
        return jdbc.query("SELECT path,sha256,byte_size,content_cipher FROM source_snapshot_file WHERE snapshot_id=? AND kind=? ORDER BY path", (rs, n) ->
                new SourceFile(kind, rs.getString("path"), rs.getString("sha256"), rs.getLong("byte_size"), secrets.decrypt(rs.getString("content_cipher"))), id, kind);
    }
    public Object redact(String project, String id, Object value) { return SourceRedactor.redact(value, redactions(ready(project, id))); }
    public Map<String, Object> binding(String project, String id) {
        var row = ready(project, id);
        return Map.of("sourceSnapshotId", id, "manifestHash", row.get("manifest_hash"), "status", row.get("status"));
    }
    public Map<String, Object> files(String project, String id, int offset, int limit) {
        ready(project, id); pagination(offset, limit);
        return Map.of("items", jdbc.queryForList("SELECT kind,path,sha256,byte_size AS bytes FROM source_snapshot_file WHERE snapshot_id=? ORDER BY kind,path LIMIT ? OFFSET ?", id, limit, offset),
                "total", jdbc.queryForObject("SELECT COUNT(*) FROM source_snapshot_file WHERE snapshot_id=?", Long.class, id));
    }
    public Map<String, Object> excerpt(String project, String id, String kind, String path, int from, int to) {
        Map<String, Object> row = ready(project, id);
        if (from < 1 || to < from || to - from >= 200) throw Problem.invalid("每次读取 1–200 行源码，行号从 1 开始");
        SourceFile source = file(project, id, kind, path);
        String[] lines = source.content().split("\\R", -1);
        if (from > lines.length) throw Problem.invalid("行号超出源码范围");
        int last = Math.min(to, lines.length);
        String content = String.join("\n", Arrays.copyOfRange(lines, from - 1, last));
        if (content.length() > 200_000) throw Problem.invalid("所选源码片段过长，请缩小行范围");
        return Map.of("snapshotId", id, "kind", kind, "path", path, "sha256", source.sha256(), "from", from, "to", last, "totalLines", lines.length,
                "content", SourceRedactor.redact(content, redactions(row)), "evidenceLevel", "SOURCE_SNAPSHOT");
    }
    public SourceFile file(String project, String id, String kind, String path) {
        ready(project, id);
        if (!Set.of("BACKEND", "BASELINE", "FRONTEND", "DDL").contains(kind) || path == null || path.length() > 2048 || path.contains("\\") || path.startsWith("/") || Arrays.asList(path.split("/")).contains("..")) throw Problem.invalid("源码标识无效");
        var rows = jdbc.queryForList("SELECT * FROM source_snapshot_file WHERE snapshot_id=? AND kind=? AND path_hash=? AND path=?", id, kind, SourceFile.hash(path), path);
        if (rows.isEmpty()) throw Problem.missing();
        var file = rows.getFirst();
        return new SourceFile(kind, path, file.get("sha256").toString(), ((Number) file.get("byte_size")).longValue(), secrets.decrypt(file.get("content_cipher").toString()));
    }
    /** Encryption and serialization run before acquiring the final job/project transaction. */
    public Prepared prepare(String id, List<SourceFile> files, Map<String, Object> result, List<SourceDiagnostic> diagnostics) {
        Set<String> redactions = SourceRedactor.literals(files.stream().map(SourceFile::content).toList());
        String manifest = SourceFile.hash(json.write(files.stream().map(file -> Map.of("kind", file.kind(), "path", file.path(), "sha256", file.sha256())).toList()));
        List<Object[]> rows = files.stream().map(file -> new Object[]{id, file.kind(), file.path(), SourceFile.hash(file.path()), file.sha256(), file.bytes(), secrets.encrypt(file.content())}).toList();
        return new Prepared(id, rows, diagnostics.stream().anyMatch(diagnostic -> !diagnostic.severity().equals("INFO")) ? "PARTIAL" : "READY",
                secrets.encrypt(json.write(result)), secrets.encrypt(json.write(redactions)), json.write(SourceRedactor.redact(json.tree(json.write(diagnostics)), redactions)),
                manifest, files.stream().mapToLong(SourceFile::bytes).sum());
    }
    public void save(String project, Prepared data) {
        int updated = jdbc.update("UPDATE source_snapshot SET status=?,result_cipher=?,secrets_cipher=?,diagnostics=?,manifest_hash=?,file_count=?,total_bytes=?,completed_at=? WHERE id=? AND project_id=? AND status='PENDING'",
                data.status(), data.resultCipher(), data.secretsCipher(), data.diagnostics(), data.manifest(), data.files().size(), data.totalBytes(), Timestamp.from(Instant.now()), data.id(), project);
        if (updated != 1) throw new Problem(409, "SOURCE_ALREADY_CAPTURED", "此源码快照已完成，不能覆盖历史内容");
        jdbc.batchUpdate("INSERT INTO source_snapshot_file(snapshot_id,kind,path,path_hash,sha256,byte_size,content_cipher) VALUES(?,?,?,?,?,?,?)", data.files());
    }
    public record Prepared(String id, List<Object[]> files, String status, String resultCipher, String secretsCipher, String diagnostics, String manifest, long totalBytes) { }
    private Map<String, Object> ready(String project, String id) {
        Map<String, Object> row = row(project, id);
        if (!Set.of("READY", "PARTIAL").contains(row.get("status").toString())) throw new Problem(409, "SOURCE_SNAPSHOT_NOT_READY", "源码快照尚未完成采集，不能作为生成或执行依据");
        return row;
    }
    private Map<String, Object> row(String project, String id) {
        assets.project(project);
        var rows = jdbc.queryForList("SELECT s.*,j.status AS job_status,j.error AS job_error FROM source_snapshot s JOIN job_task j ON j.id=s.job_id WHERE s.id=? AND s.project_id=?", id, project);
        if (rows.isEmpty()) throw Problem.missing(); return rows.getFirst();
    }
    private Set<String> redactions(Map<String, Object> row) {
        if (row.get("secrets_cipher") == null) return Set.of();
        Object value = json.tree(secrets.decrypt(row.get("secrets_cipher").toString()));
        return value instanceof List<?> list ? new HashSet<>(list.stream().map(Object::toString).toList()) : Set.of();
    }
    private static void pagination(int offset, int limit) { if (offset < 0 || limit < 1 || limit > 500) throw Problem.invalid("limit 需要为 1–500，offset 不能为负"); }
    private static Instant instant(Object value) {
        if (value == null) return null;
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }
}
