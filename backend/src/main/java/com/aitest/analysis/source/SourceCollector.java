package com.aitest.analysis.source;

import com.aitest.analysis.SourceDiagnostic;
import com.aitest.common.Problem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Component
public final class SourceCollector {
    static final Set<String> EXCLUDED = Set.of(".git", ".svn", "node_modules", "target", "dist", "build", "coverage", ".idea", ".gradle", ".runtime", ".tools", "vendor", ".next");
    private final String roots;
    private final int maxFiles;
    private final long maxFileBytes, maxBytes;
    public SourceCollector(@Value("${aitest.local-file-roots:}") String roots,
                           @Value("${aitest.analysis.max-files:5000}") int maxFiles,
                           @Value("${aitest.analysis.max-file-bytes:2097152}") long maxFileBytes,
                           @Value("${aitest.analysis.max-total-bytes:67108864}") long maxBytes) {
        this.roots = roots; this.maxFiles = Math.clamp(maxFiles, 1, 20000);
        this.maxFileBytes = Math.clamp(maxFileBytes, 1024, 8 * 1024 * 1024);
        this.maxBytes = Math.clamp(maxBytes, this.maxFileBytes, 256 * 1024 * 1024);
    }
    public CollectionResult local(String kind, String location, Runnable checkpoint) {
        return local(kind, location, budget(), checkpoint);
    }
    public SourceBudget budget() { return new SourceBudget(maxFiles, maxFileBytes, maxBytes); }
    public CollectionResult local(String kind, String location, SourceBudget budget, Runnable checkpoint) {
        if (location == null || location.isBlank()) return new CollectionResult(List.of(), List.of(), "", "LOCAL");
        try {
            Path root = SourcePaths.readable(location, roots);
            List<SourceFile> files = new ArrayList<>(); List<SourceDiagnostic> diagnostics = new ArrayList<>();
            if (kind.equals("DDL")) {
                if (!Files.isRegularFile(root) || !root.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql")) throw Problem.invalid("SQL 路径须指向 .sql 普通文件");
                collect(kind, root, root.getFileName().toString(), files, diagnostics, budget, checkpoint);
            } else {
                if (!Files.isDirectory(root)) throw Problem.invalid("源码路径须指向目录");
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 50, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                        checkpoint.run();
                        if (!directory.equals(root) && (EXCLUDED.contains(directory.getFileName().toString().toLowerCase(Locale.ROOT)) || directory.getFileName().toString().startsWith("."))) return FileVisitResult.SKIP_SUBTREE;
                        if (!directory.toRealPath().startsWith(root)) { diagnostics.add(SourceDiagnostic.warning("EXTERNAL_LINK", kind, relative(root, directory), "目录链接超出所选源码根，未读取")); return FileVisitResult.SKIP_SUBTREE; }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        checkpoint.run();
                        if (attrs.isDirectory()) { diagnostics.add(SourceDiagnostic.warning("SOURCE_DEPTH_LIMIT", kind, relative(root, file), "目录深度超过 50 层，分析不完整")); return FileVisitResult.CONTINUE; }
                        if (attrs.isSymbolicLink()) { diagnostics.add(SourceDiagnostic.warning("EXTERNAL_LINK", kind, relative(root, file), "源码链接未跟随读取")); return FileVisitResult.CONTINUE; }
                        if (!supported(kind, relative(root, file))) return FileVisitResult.CONTINUE;
                        if (!attrs.isRegularFile() || !file.toRealPath().startsWith(root)) { diagnostics.add(SourceDiagnostic.warning("EXTERNAL_LINK", kind, relative(root, file), "非普通文件或目录外链接，未读取")); return FileVisitResult.CONTINUE; }
                        if (budget.remainingFiles() == 0 || budget.remainingBytes() == 0) { diagnostics.add(SourceDiagnostic.warning("SOURCE_LIMIT", kind, "", "已达到文件数或总字节上限，分析不完整")); return FileVisitResult.TERMINATE; }
                        collect(kind, file, relative(root, file), files, diagnostics, budget, checkpoint);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        diagnostics.add(SourceDiagnostic.warning("FILE_UNREADABLE", kind, relative(root, file), "无法读取文件或目录")); return FileVisitResult.CONTINUE;
                    }
                });
            }
            files.sort(Comparator.comparing(SourceFile::path));
            if (files.isEmpty() && diagnostics.isEmpty()) diagnostics.add(SourceDiagnostic.warning("NO_SOURCE_FILES", kind, "", "目录没有可分析的源码文件"));
            return new CollectionResult(List.copyOf(files), List.copyOf(diagnostics), root.toString(), "LOCAL");
        } catch (IOException | InvalidPathException failure) { throw Problem.invalid("无法读取源码路径，请检查目录、文件与读取权限"); }
    }
    private void collect(String kind, Path path, String relative, List<SourceFile> files, List<SourceDiagnostic> diagnostics, SourceBudget budget, Runnable checkpoint) throws IOException {
        checkpoint.run();
        if (Files.size(path) > maxFileBytes) { diagnostics.add(SourceDiagnostic.warning("FILE_TOO_LARGE", kind, relative, "文件超过单文件大小限制，未读取")); return; }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes((int) maxFileBytes + 1); }
        accept(kind, relative, bytes, files, diagnostics, budget);
    }
    public void accept(String kind, String relative, byte[] bytes, List<SourceFile> files, List<SourceDiagnostic> diagnostics, SourceBudget budget) {
        if (!budget.fits(bytes.length)) { diagnostics.add(SourceDiagnostic.warning("SOURCE_LIMIT", kind, relative, "文件或源码总字节超过限制，未读取")); return; }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            if (text.indexOf('\0') >= 0) { diagnostics.add(SourceDiagnostic.warning("BINARY_SOURCE", kind, relative, "文件包含二进制内容，未解析")); return; }
            files.add(new SourceFile(kind, relative, SourceFile.hash(bytes), bytes.length, text)); budget.include(bytes.length);
        } catch (CharacterCodingException invalid) { diagnostics.add(SourceDiagnostic.warning("SOURCE_ENCODING", kind, relative, "源码不是有效 UTF-8，请转换编码后重试")); }
    }
    public static boolean supported(String kind, String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (Arrays.stream(normalized.split("/")).anyMatch(part -> part.startsWith(".") || part.equals("public") || EXCLUDED.contains(part))) return false;
        if (normalized.endsWith(".min.js")) return false;
        return switch (kind) {
            case "BACKEND", "BASELINE" -> normalized.endsWith(".java") || normalized.endsWith(".xml");
            case "FRONTEND" -> normalized.endsWith(".vue") || normalized.endsWith(".tsx") || normalized.endsWith(".jsx") || normalized.endsWith(".js") || normalized.endsWith(".ts") || normalized.endsWith(".html");
            case "DDL" -> normalized.endsWith(".sql");
            default -> false;
        };
    }
    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
    public record CollectionResult(List<SourceFile> files, List<SourceDiagnostic> diagnostics, String location, String revision) { }
}
