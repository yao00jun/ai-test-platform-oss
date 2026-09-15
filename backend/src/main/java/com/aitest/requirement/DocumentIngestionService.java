package com.aitest.requirement;

import com.aitest.asset.Asset;
import com.aitest.asset.AssetRepository;
import com.aitest.asset.AssetService;
import com.aitest.asset.AssetType;
import com.aitest.common.Ids;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.storage.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Service
public class DocumentIngestionService {
    private final DocumentParser parser;
    private final FileStorageService files;
    private final AssetService assets;
    private final AssetRepository repository;
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final String roots;
    public DocumentIngestionService(DocumentParser parser, FileStorageService files, AssetService assets, AssetRepository repository, JdbcTemplate jdbc, JsonCodec json, @Value("${aitest.local-file-roots:}") String roots) {
        this.parser = parser; this.files = files; this.assets = assets; this.repository = repository; this.jdbc = jdbc; this.json = json; this.roots = roots;
    }
    @Transactional
    public Asset ingest(String projectId, String name, byte[] bytes, String sourcePath) {
        return ingest(projectId, name, bytes, sourcePath, null);
    }
    @Transactional
    public Asset ingest(String projectId, String name, byte[] bytes, String sourcePath, String idempotencyKey) {
        String fingerprint = fingerprint(Map.of("mode", "UPLOAD", "name", name, "bytes", hash(bytes), "sourcePath", sourcePath == null ? "" : sourcePath));
        repository.lockProject(projectId);
        Asset previous = replay(projectId, idempotencyKey, fingerprint);
        if (previous != null) return previous;
        Asset result = persist(projectId, name, bytes, sourcePath);
        remember(projectId, idempotencyKey, fingerprint, result);
        return result;
    }
    private Asset persist(String projectId, String name, byte[] bytes, String sourcePath) {
        var parsed = parser.parse(name, bytes);
        var file = files.save(projectId, name, "application/octet-stream", bytes);
        Asset asset = assets.create(projectId, AssetType.REQUIREMENT, null, name, Map.of("content", parsed.content(), "fileId", file.id(), "sourcePath", sourcePath == null ? "" : sourcePath, "sections", json.tree(json.write(parsed.sections()))), "IMPORT");
        for (var section : parsed.sections()) jdbc.update("INSERT INTO requirement_chunk(id,requirement_id,chunk_index,title,start_offset,content) VALUES(?,?,?,?,?,?)", Ids.newId(), asset.id(), section.index(), section.title(), section.offset(), section.text());
        return asset;
    }
    @Transactional
    public Asset read(String projectId, String path, String text, String name) {
        return read(projectId, path, text, name, null);
    }
    @Transactional
    public Asset read(String projectId, String path, String text, String name, String idempotencyKey) {
        boolean fromPath = path != null && !path.isBlank();
        String documentName = name == null || name.isBlank() ? "粘贴需求.md" : name;
        if (!documentName.toLowerCase(Locale.ROOT).endsWith(".md")) documentName += ".md";
        String fingerprint = fromPath ? fingerprint(Map.of("mode", "PATH", "path", path.strip()))
                : fingerprint(Map.of("mode", "TEXT", "name", documentName, "text", text == null ? "" : text));
        repository.lockProject(projectId);
        // An acknowledged read is immutable input: retrying its key must not reread a changed file.
        Asset previous = replay(projectId, idempotencyKey, fingerprint);
        if (previous != null) return previous;
        Asset result = fromPath ? readPath(projectId, path.strip()) : readText(projectId, text, documentName);
        remember(projectId, idempotencyKey, fingerprint, result);
        return result;
    }
    private Asset readPath(String projectId, String path) {
        if (path != null && !path.isBlank()) {
            try {
                Path requested = Path.of(path);
                if (!requested.isAbsolute()) throw Problem.invalid("本地文档必须提供绝对路径");
                Path real = requested.toRealPath();
                boolean allowed = roots.isBlank();
                for (String root : roots.split(";")) if (!root.isBlank() && real.startsWith(Path.of(root).toRealPath())) allowed = true;
                if (!allowed) throw new Problem(403, "PATH_NOT_ALLOWED", "文件不在配置的可读目录中");
                if (!Files.isRegularFile(real) || Files.size(real) > FileStorageService.MAX_BYTES) throw Problem.invalid("路径必须指向 32 MB 以内的普通文件");
                return persist(projectId, real.getFileName().toString(), Files.readAllBytes(real), real.toString());
            } catch (IOException | InvalidPathException e) { throw Problem.invalid("无法读取指定路径，请检查路径、文件和读取权限"); }
        }
        throw Problem.invalid("请输入文档绝对路径");
    }
    private Asset readText(String projectId, String text, String documentName) {
        if (text == null || text.isBlank()) throw Problem.invalid("请输入文档路径或需求正文");
        return persist(projectId, documentName, text.getBytes(StandardCharsets.UTF_8), "");
    }
    private Asset replay(String projectId, String key, String fingerprint) {
        if (key == null) return null;
        if (key.isBlank() || key.length() > 160) throw Problem.invalid("文档提交的 idempotencyKey 必须为 1–160 个字符");
        var existing = jdbc.queryForList("SELECT request_hash,requirement_id FROM document_submission WHERE project_id=? AND idempotency_key=? FOR UPDATE", projectId, key);
        if (existing.isEmpty()) return null;
        var row = existing.getFirst();
        if (!fingerprint.equals(row.get("request_hash"))) throw new Problem(409, "IDEMPOTENCY_CONFLICT", "此文档幂等键已用于其他输入");
        try { return assets.get(projectId, row.get("requirement_id").toString()); }
        catch (Problem missing) {
            if (missing.status() == 404) throw new Problem(410, "DOCUMENT_RESULT_DELETED", "此前导入的需求已删除；如需重新导入，请开始新的提交");
            throw missing;
        }
    }
    private void remember(String projectId, String key, String fingerprint, Asset result) {
        if (key != null) jdbc.update("INSERT INTO document_submission(project_id,idempotency_key,request_hash,requirement_id,created_at) VALUES(?,?,?,?,?)", projectId, key, fingerprint, result.id(), Timestamp.from(Instant.now()));
    }
    private String fingerprint(Map<String, Object> input) { return hash(json.write(new java.util.TreeMap<>(input)).getBytes(StandardCharsets.UTF_8)); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
