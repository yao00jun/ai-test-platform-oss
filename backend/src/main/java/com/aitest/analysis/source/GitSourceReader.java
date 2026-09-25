package com.aitest.analysis.source;

import com.aitest.analysis.SourceDiagnostic;
import com.aitest.common.Problem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;

@Component
public final class GitSourceReader {
    private final SourceCollector collector;
    private final Path temporaryRoot;
    private final String allowedRoots;
    private final long repositoryLimit;
    private final Duration timeout;
    public GitSourceReader(SourceCollector collector, @Value("${aitest.storage-root}") String storage,
                           @Value("${aitest.local-file-roots:}") String allowedRoots,
                           @Value("${aitest.analysis.git.max-repository-bytes:536870912}") long repositoryLimit,
                           @Value("${aitest.analysis.git.timeout-seconds:120}") long timeout) {
        this.collector = collector; this.temporaryRoot = Path.of(storage).toAbsolutePath().normalize().resolve(".source-git"); this.allowedRoots = allowedRoots;
        this.repositoryLimit = Math.clamp(repositoryLimit, 1024 * 1024, 2L * 1024 * 1024 * 1024); this.timeout = Duration.ofSeconds(Math.clamp(timeout, 5, 600));
    }
    public record RepositoryRead(SourceCollector.CollectionResult head, SourceCollector.CollectionResult baseline) { }
    public RepositoryRead read(String kind, String location, String headRef, String baselineRef, SourceBudget budget, Runnable checkpoint) {
        Path temporary = null;
        try {
            boolean remote = SourcePaths.remote(location);
            Path repository;
            if (remote) { Files.createDirectories(temporaryRoot); temporary = Files.createTempDirectory(temporaryRoot, "git-"); repository = temporary; }
            else repository = SourcePaths.readable(location, allowedRoots);
            if (!Files.isDirectory(repository)) throw Problem.invalid("Git 仓库路径须指向目录");
            if (remote) GitCommands.text(repository, List.of("init", "--bare", "--quiet"), timeout, checkpoint);
            String head = resolve(repository, remote ? location : null, blank(headRef, "HEAD"), checkpoint);
            SourceCollector.CollectionResult current = collect(repository, kind, location, head, budget, checkpoint);
            SourceCollector.CollectionResult baseline = null;
            if (baselineRef != null && !baselineRef.isBlank()) {
                String base = resolve(repository, remote ? location : null, baselineRef, checkpoint);
                baseline = collect(repository, "BASELINE", location, base, budget, checkpoint);
            }
            return new RepositoryRead(current, baseline);
        } catch (IOException failure) { throw Problem.invalid("无法创建临时 Git 读取空间或访问仓库目录"); }
        finally { if (temporary != null) removeTemporary(temporary); }
    }
    private String resolve(Path repository, String url, String ref, Runnable checkpoint) {
        SourcePaths.validateRef(ref);
        String target = ref;
        if (url != null) {
            int ancestry = ref.length(); for (char marker : new char[]{'~', '^'}) { int index = ref.indexOf(marker); if (index >= 0) ancestry = Math.min(ancestry, index); }
            String fetchRef = ref.substring(0, ancestry), suffix = ref.substring(ancestry);
            Runnable bounded = () -> { checkpoint.run(); enforceDiskLimit(repository); };
            fetch(repository, url, fetchRef, bounded);
            target = "FETCH_HEAD" + suffix;
        }
        String revision = GitCommands.text(repository, List.of("rev-parse", "--verify", "--end-of-options", target + "^{commit}"), timeout, checkpoint);
        if (!revision.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw Problem.invalid("无法解析为固定 Git 提交");
        return revision;
    }
    private void fetch(Path repository, String url, String ref, Runnable checkpoint) {
        try {
            GitCommands.text(repository, List.of("fetch", "--quiet", "--no-tags", "--no-recurse-submodules", "--filter=blob:none", "--depth=50", "--", url, ref), timeout, checkpoint);
        } catch (Problem failure) {
            String message = Objects.toString(failure.getMessage(), "").toLowerCase(Locale.ROOT);
            if (!"GIT_READ_FAILED".equals(failure.code()) || !message.contains("shallow capabilities")) throw failure;
            // Dumb HTTP servers cannot advertise shallow capabilities. Retry without the
            // optimization; reset the failed shallow negotiation first because Git can
            // leave state that makes a second fetch wait for smart protocol negotiation.
            resetBareRepository(repository, checkpoint);
            List<String> arguments = new ArrayList<>(List.of("fetch", "--quiet", "--no-tags", "--no-recurse-submodules", "--", url));
            // Dumb HTTP exposes the advertised default branch but not shallow/ref
            // negotiation. Let Git select that branch for the fallback; explicit refs
            // already use the optimized path and retain their exact revision check.
            if (!"HEAD".equals(ref)) arguments.add(ref);
            GitCommands.text(repository, arguments, timeout, checkpoint);
        }
    }
    private void resetBareRepository(Path repository, Runnable checkpoint) {
        try (var children = Files.list(repository)) {
            for (Path child : children.toList()) {
                deleteTree(child);
                checkpoint.run();
            }
        } catch (IOException failure) {
            throw Problem.invalid("无法重置 Git 临时仓库");
        }
        GitCommands.text(repository, List.of("init", "--bare", "--quiet"), timeout, checkpoint);
    }
    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    private SourceCollector.CollectionResult collect(Path repository, String kind, String location, String revision, SourceBudget budget, Runnable checkpoint) throws IOException {
        byte[] tree = GitCommands.run(repository, List.of("ls-tree", "-r", "-l", "-z", revision), new byte[0], 16 * 1024 * 1024, timeout, checkpoint);
        List<SourceDiagnostic> diagnostics = new ArrayList<>(); List<SourceFile> files = new ArrayList<>(); List<Blob> selected = new ArrayList<>();
        long bytes = 0;
        for (String record : new String(tree, StandardCharsets.UTF_8).split("\0")) {
            checkpoint.run();
            int tab = record.indexOf('\t'); if (tab < 0) continue;
            String[] metadata = record.substring(0, tab).strip().split("\\s+");
            String path = record.substring(tab + 1);
            if (metadata.length != 4) throw Problem.invalid("Git 文件清单格式无法识别");
            if (metadata[0].equals("160000")) { diagnostics.add(SourceDiagnostic.warning("GIT_SUBMODULE", kind, path, "未递归读取 Git 子模块，请将子模块作为单独源码输入")); continue; }
            if (!SourceCollector.supported(kind, path)) continue;
            if (metadata[0].equals("120000")) { diagnostics.add(SourceDiagnostic.warning("EXTERNAL_LINK", kind, path, "Git 符号链接未作为源文件读取")); continue; }
            if (path.length() > 2048 || path.chars().anyMatch(Character::isISOControl) || path.startsWith("/") || Arrays.asList(path.split("/")).contains("..")) { diagnostics.add(SourceDiagnostic.warning("SOURCE_PATH_INVALID", kind, "", "源码文件路径无法安全展示，未读取")); continue; }
            if (path.split("/").length > 51) { diagnostics.add(SourceDiagnostic.warning("SOURCE_DEPTH_LIMIT", kind, path, "目录深度超过 50 层，分析不完整")); continue; }
            long size = Long.parseLong(metadata[3]);
            if (size > budget.maxFileBytes()) { diagnostics.add(SourceDiagnostic.warning("FILE_TOO_LARGE", kind, path, "Git 文件超过单文件上限，未读取")); continue; }
            if (selected.size() >= budget.remainingFiles() || bytes + size > budget.remainingBytes()) { diagnostics.add(SourceDiagnostic.warning("SOURCE_LIMIT", kind, path, "已达到整个快照的文件数或字节上限，未读取")); continue; }
            selected.add(new Blob(path, metadata[2], (int) size)); bytes += size;
        }
        if (!selected.isEmpty()) {
            String input = String.join("\n", selected.stream().map(Blob::oid).toList()) + "\n";
            byte[] output = GitCommands.run(repository, List.of("cat-file", "--batch"), input.getBytes(StandardCharsets.US_ASCII), Math.toIntExact(bytes + selected.size() * 128L + 1024), timeout, checkpoint);
            try (ByteArrayInputStream stream = new ByteArrayInputStream(output)) {
                for (Blob blob : selected) {
                    checkpoint.run();
                    String header = header(stream);
                    if (!header.equals(blob.oid() + " blob " + blob.size())) throw Problem.invalid("Git 对象内容与文件清单不一致");
                    byte[] content = stream.readNBytes(blob.size());
                    if (content.length != blob.size() || stream.read() != '\n') throw Problem.invalid("Git 源文件读取不完整");
                    collector.accept(kind, blob.path(), content, files, diagnostics, budget);
                }
            }
        }
        if (files.isEmpty() && diagnostics.isEmpty()) diagnostics.add(SourceDiagnostic.warning("NO_SOURCE_FILES", kind, "", "此 Git 版本没有可分析的源码文件"));
        files.sort(Comparator.comparing(SourceFile::path));
        return new SourceCollector.CollectionResult(List.copyOf(files), List.copyOf(diagnostics), location, revision);
    }
    private void enforceDiskLimit(Path repository) {
        try {
            long[] size = {0};
            Files.walkFileTree(repository, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    size[0] += attrs.size();
                    if (size[0] > repositoryLimit) throw new Problem(422, "GIT_REPOSITORY_LIMIT", "Git 仓库存储超过分析上限，请先在本地检出需要的代码");
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                    if (failure instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                    throw failure;
                }
            });
        } catch (IOException failure) { throw Problem.invalid("无法检查 Git 临时仓库存储大小"); }
    }
    private void removeTemporary(Path repository) {
        Path resolved = repository.toAbsolutePath().normalize();
        if (!resolved.getParent().equals(temporaryRoot) || !resolved.getFileName().toString().startsWith("git-")) throw new IllegalStateException("Invalid Git temporary directory");
        try {
            Files.walkFileTree(resolved, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException { if (failure != null) throw failure; Files.deleteIfExists(directory); return FileVisitResult.CONTINUE; }
            });
        } catch (IOException failure) { org.slf4j.LoggerFactory.getLogger(GitSourceReader.class).warn("Git temporary directory cleanup failed; retained for maintenance: {}", resolved.getFileName()); }
    }
    private static String header(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int next;
        while ((next = input.read()) != '\n') { if (next < 0 || bytes.size() > 128) throw Problem.invalid("Git 对象头无法读取"); bytes.write(next); }
        return bytes.toString(StandardCharsets.US_ASCII);
    }
    private static String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private record Blob(String path, String oid, int size) { }
}
