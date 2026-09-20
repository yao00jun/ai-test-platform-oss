package com.aitest.engine.web;

import com.aitest.asset.Asset;
import com.aitest.common.*;
import com.aitest.execution.*;
import com.microsoft.playwright.*;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Process entry point. The main thread owns every Playwright object until it is closed. */
public final class PlaywrightWorker {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]).toAbsolutePath().normalize(); JsonCodec json = new JsonCodec();
        byte[] input = System.in.readNBytes(16 * 1024 * 1024 + 1);
        if (input.length > 16 * 1024 * 1024) throw new IllegalArgumentException("Browser input limit exceeded");
        WorkerProtocol.Request request = json.read(new String(input, StandardCharsets.UTF_8), WorkerProtocol.Request.class);
        WorkerProtocol protocol = new WorkerProtocol(request.key());
        Map<String, Object> variables = new LinkedHashMap<>(request.variables()); WebEvidence evidence = new WebEvidence(json, variables);
        evidence.register(request.headers());
        List<Object> console = new ArrayList<>(), network = new ArrayList<>(); String outcome = "PASSED";
        try (BufferedWriter events = Files.newBufferedWriter(directory.resolve("events.ndjson")); Playwright playwright = Playwright.create()) {
            Map<String, Object> options = request.scenario().data();
            BrowserType type = switch (Values.text(options, "browser", "CHROMIUM")) { case "FIREFOX" -> playwright.firefox(); case "WEBKIT" -> playwright.webkit(); default -> playwright.chromium(); };
            if (!Files.isRegularFile(Path.of(type.executablePath()))) throw new Problem(409, "BROWSER_NOT_INSTALLED", "浏览器未安装，请运行 scripts/aitest.ps1 install-browsers 并选择对应浏览器");
            try (Browser browser = type.launch(new BrowserType.LaunchOptions().setHeadless(Values.bool(options, "headless", true)).setTimeout(30000));
                 BrowserContext browserContext = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(true)
                         .setViewportSize(Values.integer(options, "viewportWidth", 1440, 320, 7680), Values.integer(options, "viewportHeight", 900, 200, 4320))
                         .setIgnoreHTTPSErrors(Values.bool(options, "ignoreHttpsErrors", false)).setExtraHTTPHeaders(request.headers()))) {
                browserContext.onPage(page -> observe(page, console, network));
                Page first = browserContext.newPage();
                WebStepExecutor executor = new WebStepExecutor(first, directory, request.baseUrl(), evidence);
                browserContext.tracing().start(new Tracing.StartOptions().setScreenshots(false).setSnapshots(false).setSources(false));
                VariableResolver resolver = new VariableResolver(json); boolean stopped = false;
                for (Asset step : request.steps()) {
                    StepResult result;
                    if (stopped) result = new StepResult("SKIPPED", 0, Map.of(), Map.of(), List.of(), Map.of(), List.of(), "前序 UI 步骤失败");
                    else {
                        result = executeStep(step, browserContext, executor, resolver, variables, evidence, directory, json, console, network);
                        if (!result.successful()) {
                            outcome = result.status(); stopped = !Values.bool(options, "continueOnFailure", false);
                        }
                    }
                    Map<String, Object> rawExports = result.exports();
                    StepResult sanitized = json.convert(evidence.scrub(json.tree(json.write(result))), StepResult.class);
                    events.write(protocol.encode(json.write(new WorkerProtocol.Event(step.id(), sanitized, rawExports)))); events.newLine(); events.flush();
                }
                browserContext.tracing().stop();
            }
            Files.writeString(directory.resolve("done.json"), json.write(Map.of("status", request.steps().isEmpty() ? "BLOCKED" : outcome)), StandardCharsets.UTF_8);
        } catch (Throwable failure) {
            String message = failure instanceof Problem ? failure.getMessage() : "浏览器工作进程失败（" + failure.getClass().getSimpleName() + "）";
            Files.writeString(directory.resolve("done.json"), json.write(Map.of("status", failure instanceof Problem ? "BLOCKED" : "ERROR", "error", evidence.scrub(message))), StandardCharsets.UTF_8);
        }
    }
    private static StepResult executeStep(Asset step, BrowserContext browser, WebStepExecutor executor, VariableResolver resolver,
                                          Map<String, Object> variables, WebEvidence evidence, Path directory, JsonCodec json,
                                          List<Object> console, List<Object> network) {
        long started = System.nanoTime();
        Map<String, Object> spec = step.data();
        List<String> warnings = new ArrayList<>();
        boolean tracing = false;
        try { browser.tracing().startChunk(new Tracing.StartChunkOptions().setTitle(step.name())); tracing = true; }
        catch (RuntimeException failure) { warnings.add("追踪启动失败（" + failure.getClass().getSimpleName() + "）"); }
        StepResult result;
        try {
            spec = Values.map(resolver.resolve(step.data(), variables));
            result = executor.execute(step.id(), spec, variables);
        } catch (RuntimeException failure) {
            String message = failure instanceof Problem ? failure.getMessage() : "UI 步骤准备失败（" + failure.getClass().getSimpleName() + "）";
            result = StepResult.error(message, (System.nanoTime() - started) / 1_000_000, step.data());
        }
        evidence.register(variables);
        evidence.register(result.exports());
        List<String> artifacts = new ArrayList<>(result.artifactIds());
        if (!result.successful()) {
            try { artifacts.add(executor.screenshot(executor.page(spec), step.id() + "-failure.png")); }
            catch (RuntimeException failure) { warnings.add("失败截图不可用（" + failure.getClass().getSimpleName() + "）"); }
        }
        if (tracing) try {
            if (result.successful()) browser.tracing().stopChunk();
            else {
                Path raw = directory.resolve(step.id() + "-raw.zip"), trace = directory.resolve(step.id() + "-trace.zip");
                browser.tracing().stopChunk(new Tracing.StopChunkOptions().setPath(raw));
                evidence.sanitizeTrace(raw, trace); artifacts.add(trace.getFileName().toString());
            }
        } catch (RuntimeException | java.io.IOException failure) { warnings.add("步骤追踪不可用（" + failure.getClass().getSimpleName() + "）"); }
        if (!result.successful()) try {
            String filename = step.id() + "-evidence.json";
            Files.writeString(directory.resolve(filename), json.write(evidence.scrub(Map.of("console", console, "network", network, "warnings", warnings))), StandardCharsets.UTF_8);
            artifacts.add(filename);
        } catch (RuntimeException | java.io.IOException failure) { warnings.add("网络与控制台证据不可用（" + failure.getClass().getSimpleName() + "）"); }
        Map<String, Object> actual = new LinkedHashMap<>(result.actual());
        if (!warnings.isEmpty()) actual.put("evidenceWarnings", warnings);
        return new StepResult(result.status(), result.durationMs(), result.request(), actual, result.assertions(), result.exports(), artifacts, result.error());
    }
    private static void observe(Page page, List<Object> console, List<Object> network) {
        page.onConsoleMessage(message -> { if (console.size() < 1000) console.add(Map.of("type", message.type(), "text", truncate(message.text()))); });
        page.onPageError(message -> { if (console.size() < 1000) console.add(Map.of("type", "pageerror", "text", truncate(message))); });
        page.onResponse(response -> { if (network.size() < 1000) network.add(Map.of("url", truncate(response.url()), "method", response.request().method(), "status", response.status())); });
        page.onRequestFailed(request -> { if (network.size() < 1000) network.add(Map.of("url", truncate(request.url()), "method", request.method(), "failure", truncate(request.failure()))); });
    }
    private static String truncate(String text) { return text == null ? "" : text.substring(0, Math.min(4000, text.length())); }
}
