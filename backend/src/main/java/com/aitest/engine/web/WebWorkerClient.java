package com.aitest.engine.web;

import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.execution.*;
import com.aitest.storage.FileStorageService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Bounded disposable workers isolate browser deadlocks from the API and from other runs. */
@Component
public final class WebWorkerClient {
    private final FileStorageService files;
    private final JsonCodec json;
    private final VariableResolver variables;
    private final Semaphore slots;
    private final Path browserPath;
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    public WebWorkerClient(FileStorageService files, JsonCodec json, VariableResolver variables,
                           @Value("${aitest.execution.browser-workers:2}") int workers,
                           @Value("${aitest.execution.browser-path:${AI_TEST_BROWSER_PATH:../.tools/playwright-1.62.0}}") String browserPath) {
        this.files = files; this.json = json; this.variables = variables;
        slots = new Semaphore(Math.clamp(workers, 1, 8), true); this.browserPath = Path.of(browserPath).toAbsolutePath().normalize();
    }
    public String execute(Asset scenario, AssetGraph graph, ExecutionContext context, BiConsumer<Asset, StepResult> record) {
        boolean acquired = false; Path directory = null; Process process = null;
        try {
            while (!(acquired = slots.tryAcquire(100, TimeUnit.MILLISECONDS))) context.checkpoint();
            context.checkpoint();
            Path workers = files.root().resolve(".workers"); Files.createDirectories(workers); directory = Files.createTempDirectory(workers, "browser-");
            String key = WorkerProtocol.newKey(); WorkerProtocol protocol = new WorkerProtocol(key);
            Map<String, Asset> steps = new LinkedHashMap<>();
            for (Asset step : graph.children(scenario.id())) if (step.type() == AssetType.UI_STEP) {
                Map<String, Object> data = ExecutionConfiguration.data(step);
                if (Values.text(data, "action", "").equals("upload")) {
                    List<Map<String, Object>> uploads = new ArrayList<>();
                    for (Object fileId : (List<?>) data.getOrDefault("fileIds", List.of())) {
                        var file = files.get(scenario.projectId(), fileId.toString()); uploads.add(Map.of("path", file.path().toString(), "name", file.name(), "mediaType", file.mediaType()));
                    }
                    data.put("_uploads", uploads);
                }
                steps.put(step.id(), AssetSecrets.withData(step, data));
            }
            String baseUrl = variables.text(Values.text(scenario.data(), "baseUrl", ""), context.variables());
            if (baseUrl.isBlank() && graph.environment() != null) baseUrl = variables.text(Values.text(graph.environment().data(), "webUrl", ""), context.variables());
            Map<String, String> headers = new LinkedHashMap<>();
            if (graph.environment() != null) Values.map(variables.resolve(graph.environment().data().get("headers"), context.variables())).forEach((name, value) -> headers.put(name, Objects.toString(value, "")));
            var request = new WorkerProtocol.Request(AssetSecrets.withData(scenario, ExecutionConfiguration.data(scenario)), List.copyOf(steps.values()), context.variables(), headers, baseUrl, key);
            process = launch(directory); processes.add(process);
            try (OutputStream stdin = process.getOutputStream()) { stdin.write(json.write(request).getBytes(StandardCharsets.UTF_8)); }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Values.integer(scenario.data(), "timeoutMs", 120000, 1000, 3600000));
            Path eventFile = directory.resolve("events.ndjson"); Set<String> recorded = new HashSet<>();
            long offset = 0; ByteArrayOutputStream pending = new ByteArrayOutputStream();
            while (true) {
                context.checkpoint();
                offset = drain(eventFile, offset, pending, directory, protocol, scenario.projectId(), steps, recorded, context, record);
                if (!process.isAlive()) break;
                if (System.nanoTime() > deadline) {
                    kill(process);
                    StepResult timeout = StepResult.error("UI 场景超过运行超时，工作进程已回收", 0, Map.of()); record.accept(scenario, timeout); return "ERROR";
                }
                process.waitFor(100, TimeUnit.MILLISECONDS);
            }
            drain(eventFile, offset, pending, directory, protocol, scenario.projectId(), steps, recorded, context, record);
            Path completed = directory.resolve("done.json");
            if (!Files.isRegularFile(completed)) throw new Problem(500, "BROWSER_WORKER_EXIT", "浏览器工作进程意外退出，已回收相关进程");
            Map<String, Object> done = json.map(Files.readString(completed));
            if (done.containsKey("error")) record.accept(scenario, new StepResult(done.get("status").toString(), 0, Map.of(), Map.of(), List.of(), Map.of(), List.of(), done.get("error").toString()));
            else if (recorded.size() != steps.size()) throw new Problem(500, "BROWSER_EVIDENCE_INCOMPLETE", "浏览器步骤证据不完整");
            return done.get("status").toString();
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException("浏览器运行已中断"); }
        catch (IOException error) { throw new Problem(500, "BROWSER_WORKER_IO", "浏览器工作进程或证据文件不可用，请检查 Java、浏览器和磁盘配置"); }
        finally {
            if (process != null) { kill(process); processes.remove(process); }
            if (directory != null) cleanup(directory);
            if (acquired) slots.release();
        }
    }
    private long drain(Path eventFile, long offset, ByteArrayOutputStream pending, Path directory, WorkerProtocol protocol,
                       String project, Map<String, Asset> steps, Set<String> recorded, ExecutionContext context, BiConsumer<Asset, StepResult> record) throws IOException {
        if (!Files.isRegularFile(eventFile)) return offset;
        try (RandomAccessFile input = new RandomAccessFile(eventFile.toFile(), "r")) {
            input.seek(offset); int value;
            while ((value = input.read()) != -1) {
                offset++;
                if (value != '\n') { pending.write(value); if (pending.size() > 4 * 1024 * 1024) throw new IOException("Worker event exceeds limit"); continue; }
                String line = pending.toString(StandardCharsets.UTF_8); pending.reset();
                WorkerProtocol.Event event = json.read(protocol.decode(line), WorkerProtocol.Event.class);
                Asset step = steps.get(event.assetId()); if (step == null || !recorded.add(step.id())) throw new IOException("Invalid worker event identity");
                List<String> ids = new ArrayList<>();
                for (String filename : event.result().artifactIds()) {
                    Path artifact = directory.resolve(filename).normalize();
                    if (!artifact.startsWith(directory) || Files.isSymbolicLink(artifact) || !Files.isRegularFile(artifact) || Files.size(artifact) > FileStorageService.MAX_BYTES) throw new IOException("Invalid worker artifact");
                    String mediaType = filename.endsWith(".png") ? "image/png" : filename.endsWith(".json") ? "application/json" : filename.endsWith(".zip") ? "application/zip" : "application/octet-stream";
                    ids.add(files.save(project, filename, mediaType, Files.readAllBytes(artifact)).id());
                }
                StepResult result = event.result(); context.publish(event.exports());
                record.accept(step, new StepResult(result.status(), result.durationMs(), result.request(), result.actual(), result.assertions(), result.exports(), ids, result.error()));
            }
        }
        return offset;
    }
    private Process launch(Path directory) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(java));
        if (!classpath.contains(File.pathSeparator) && classpath.endsWith(".jar") && Files.isRegularFile(Path.of(classpath))) command.addAll(List.of("-jar", Path.of(classpath).toAbsolutePath().toString()));
        else {
            Path args = directory.resolve("classpath.args");
            Files.writeString(args, "-cp\n\"" + classpath.replace("\\", "/").replace("\"", "\\\"") + "\"\ncom.aitest.AiTestApplication\n", StandardCharsets.UTF_8);
            command.add("@" + args);
        }
        command.addAll(List.of("--browser-worker", directory.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(name -> name.startsWith("AI_TEST_"));
        WorkerOwnerGuard.attach(builder);
        builder.environment().put("PLAYWRIGHT_BROWSERS_PATH", browserPath.toString());
        builder.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"); return builder.start();
    }
    private void kill(Process process) {
        List<ProcessHandle> children = process.descendants().toList();
        children.reversed().forEach(ProcessHandle::destroyForcibly); if (process.isAlive()) process.destroyForcibly();
        try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }
    private void cleanup(Path directory) {
        try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        catch (IOException failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Browser temporary evidence cleanup failed: {}", directory.getFileName()); }
    }
    @PreDestroy void close() { processes.forEach(this::kill); }
}
