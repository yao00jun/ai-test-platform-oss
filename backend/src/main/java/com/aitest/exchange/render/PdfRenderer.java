package com.aitest.exchange.render;

import com.aitest.common.Problem;
import com.aitest.storage.FileStorageService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public final class PdfRenderer {
    private final FileStorageService files;
    private final Path browsers;
    private final Semaphore capacity = new Semaphore(1, true);
    private final Set<Process> active = ConcurrentHashMap.newKeySet();
    public PdfRenderer(FileStorageService files, @Value("${aitest.execution.browser-path:${AI_TEST_BROWSER_PATH:../.tools/playwright-1.62.0}}") String browsers) { this.files = files; this.browsers = Path.of(browsers).toAbsolutePath().normalize(); }
    public byte[] render(String html) {
        byte[] content = html.getBytes(StandardCharsets.UTF_8);
        if (content.length > 32 * 1024 * 1024) throw new Problem(422, "REPORT_TOO_LARGE", "文档超过 PDF 渲染的 32 MB 限制，请下载完整 HTML/证据包或缩小资产范围");
        Path directory = null; Process process = null; boolean acquired = false;
        try {
            acquired = capacity.tryAcquire(15, TimeUnit.SECONDS);
            if (!acquired) throw new Problem(503, "REPORT_BUSY", "当前 PDF 正在生成，请稍后重试或先下载 HTML");
            Path root = files.root().resolve(".render"); Files.createDirectories(root); directory = Files.createTempDirectory(root, "pdf-");
            Files.write(directory.resolve("document.html"), content);
            String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            List<String> command = new ArrayList<>(List.of(java));
            if (!classpath.contains(File.pathSeparator) && classpath.endsWith(".jar")) command.addAll(List.of("-jar", Path.of(classpath).toAbsolutePath().toString()));
            else {
                Path arguments = directory.resolve("classpath.args");
                Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "/").replace("\"", "\\\"") + "\"\ncom.aitest.AiTestApplication\n", StandardCharsets.UTF_8); command.add("@" + arguments);
            }
            command.addAll(List.of("--pdf-worker", directory.toString()));
            ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().keySet().removeIf(name -> name.startsWith("AI_TEST_"));
            com.aitest.common.WorkerOwnerGuard.attach(builder);
            builder.environment().put("PLAYWRIGHT_BROWSERS_PATH", browsers.toString()); builder.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            process = builder.start(); active.add(process); process.getOutputStream().close();
            if (!process.waitFor(45, TimeUnit.SECONDS)) throw new Problem(504, "REPORT_TIMEOUT", "PDF 渲染超过 45 秒，浏览器已回收；可以下载 HTML 或缩小范围");
            Path pdf = directory.resolve("document.pdf");
            if (process.exitValue() != 0 || !Files.isRegularFile(pdf)) throw new Problem(503, "PDF_RENDER_FAILED", "PDF 渲染不可用，请检查 Chromium 安装；HTML 与 JSON 导出仍可使用");
            if (Files.size(pdf) > FileStorageService.MAX_BYTES) throw new Problem(422, "REPORT_TOO_LARGE", "生成的 PDF 超过 32 MB，请下载 HTML/证据包或缩小范围");
            return Files.readAllBytes(pdf);
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new Problem(409, "REPORT_CANCELLED", "PDF 生成已中断"); }
        catch (IOException failure) { throw new Problem(500, "REPORT_IO_FAILED", "报告临时目录、Java 或浏览器不可用，请检查运行环境"); }
        finally {
            if (process != null) { kill(process); active.remove(process); }
            if (directory != null) try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
            catch (IOException cleanupFailure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("PDF temporary directory cleanup failed: {}", directory.getFileName()); }
            if (acquired) capacity.release();
        }
    }
    private static void kill(Process process) {
        var children = process.descendants().toList(); children.reversed().forEach(ProcessHandle::destroyForcibly); if (process.isAlive()) process.destroyForcibly();
        try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }
    @PreDestroy void close() { active.forEach(PdfRenderer::kill); }
}
