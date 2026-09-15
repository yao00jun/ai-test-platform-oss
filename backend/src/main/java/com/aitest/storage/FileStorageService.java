package com.aitest.storage;

import com.aitest.asset.AssetRepository;
import com.aitest.common.Ids;
import com.aitest.common.Problem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class FileStorageService {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private final Path root;
    private final JdbcTemplate jdbc;
    private final AssetRepository projects;
    public FileStorageService(@Value("${aitest.storage-root}") String root, JdbcTemplate jdbc, AssetRepository projects) throws IOException {
        this.root = Path.of(root).toAbsolutePath().normalize(); this.jdbc = jdbc; this.projects = projects; Files.createDirectories(this.root);
    }
    public StoredFile save(String projectId, String name, String mediaType, byte[] bytes) {
        projects.project(projectId);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw Problem.invalid("文件大小必须为 1 字节至 32 MB");
        String id = Ids.newId();
        String relative = "files/" + projectId + "/" + id;
        Path target = resolve(relative);
        String safeName = name == null ? "document" : name.replaceAll("[\\\\/\\p{Cntrl}]", "_");
        if (safeName.length() > 255) safeName = safeName.substring(safeName.length() - 255);
        try {
            Files.createDirectories(target.getParent()); Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            jdbc.update("INSERT INTO artifact_file(id,project_id,original_name,storage_path,media_type,sha256,byte_size,created_at) VALUES(?,?,?,?,?,?,?,?)", id, projectId, safeName, relative, mediaType, checksum, bytes.length, Timestamp.from(Instant.now()));
            if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) try { Files.deleteIfExists(target); } catch (IOException e) { org.slf4j.LoggerFactory.getLogger(FileStorageService.class).warn("Orphan file cleanup failed for {}", id); }
                }
            });
            return new StoredFile(id, projectId, safeName, mediaType, checksum, bytes.length, target);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            try { Files.deleteIfExists(target); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            if (e instanceof Problem p) throw p;
            throw new Problem(500, "FILE_WRITE_FAILED", "文件保存失败，请检查受管目录和磁盘空间");
        }
    }
    public StoredFile get(String projectId, String id) {
        projects.project(projectId);
        var rows = jdbc.queryForList("SELECT * FROM artifact_file WHERE id=? AND project_id=?", id, projectId);
        if (rows.isEmpty()) throw Problem.missing();
        var row = rows.getFirst(); Path path = resolve(row.get("storage_path").toString());
        if (!Files.isRegularFile(path)) throw new Problem(404, "FILE_MISSING", "附件文件已缺失");
        return new StoredFile(id, projectId, row.get("original_name").toString(), row.get("media_type").toString(), row.get("sha256").toString(), ((Number) row.get("byte_size")).longValue(), path);
    }
    public Path root() { return root; }
    private Path resolve(String relative) {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw Problem.invalid("非法文件路径");
        return path;
    }
    public record StoredFile(String id, String projectId, String name, String mediaType, String sha256, long size, Path path) { }
}
