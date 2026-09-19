package com.aitest.engine.web;
import org.junit.jupiter.api.Tag;

import com.aitest.asset.*;
import com.aitest.common.JsonCodec;
import com.aitest.execution.*;
import com.aitest.job.JobService;
import com.aitest.storage.FileStorageService;
import com.aitest.support.MySqlIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Tag("slow")
class BrowserBoundaryRegressionIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired ExecutionCoordinator coordinator;
    @Autowired RunRepository runs;
    @Autowired JobService jobs;
    @Autowired FileStorageService files;
    @Autowired JsonCodec json;

    @Test void extractedTokensAndLiteralHeaderCredentialsAreScrubbedFromActualAttachments() throws Exception {
        String project = assets.createProject("Browser secret boundaries " + UUID.randomUUID(), Map.of()).id();
        try (Site site = new Site()) {
            Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "HTTP headers", Map.of("baseUrl", site.url(), "webUrl", site.url(), "headers", Map.of("Authorization", "Bearer Header-secret-981")), "MANUAL");
            Asset scenario = scenario(project, site.url(), false);
            step(project, scenario, Map.of("action", "navigate", "url", "/secret"));
            step(project, scenario, Map.of("action", "extract", "selector", "#token", "attribute", "value", "saveAs", "accessToken"));
            step(project, scenario, Map.of("action", "assertText", "selector", "#status", "expected", "${accessToken}", "timeoutMs", 300));
            Map<String, Object> run = run(project, scenario, environment.id());
            assertThat(run.get("status")).isEqualTo("FAILED");
            assertThat(site.authorization.get()).isEqualTo("Bearer Header-secret-981");
            assertThat(json.write(run)).doesNotContain("Extracted-secret-742", "Header-secret-981");
            var results = steps(run);
            assertThat(results).hasSize(3);
            List<?> artifacts = (List<?>) Values.map(results.getLast().get("result")).get("artifactIds");
            boolean sawTrace = false, sawConsole = false;
            for (Object id : artifacts) {
                var artifact = files.get(project, id.toString());
                if (artifact.name().endsWith(".zip")) {
                    sawTrace = true;
                    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(artifact.path()))) {
                        while (zip.getNextEntry() != null) assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).doesNotContain("Extracted-secret-742", "Header-secret-981");
                    }
                }
                if (artifact.name().endsWith("-evidence.json")) {
                    sawConsole = true;
                    String evidence = Files.readString(artifact.path());
                    assertThat(evidence).contains("console", "••••••••").doesNotContain("Extracted-secret-742", "Header-secret-981");
                }
            }
            assertThat(sawTrace).isTrue(); assertThat(sawConsole).isTrue();
        }
    }

    @Test void variableFailuresBelongToTheExactStepAndRespectBothContinuationModes() throws Exception {
        String project = assets.createProject("Browser missing variables " + UUID.randomUUID(), Map.of()).id();
        try (Site site = new Site()) {
            for (boolean continuing : List.of(false, true)) {
                Asset scenario = scenario(project, site.url(), continuing);
                Asset first = step(project, scenario, Map.of("action", "navigate", "url", "/"));
                Asset invalid = step(project, scenario, Map.of("action", "fill", "selector", "#field", "value", "${missingInput}"));
                Asset last = step(project, scenario, Map.of("action", "assertText", "selector", "#status", "expected", "ready"));
                var results = steps(run(project, scenario, null));
                assertThat(results).extracting(row -> row.get("assetId")).containsExactly(first.id(), invalid.id(), last.id());
                assertThat(results).extracting(row -> row.get("status")).containsExactly("PASSED", "ERROR", continuing ? "PASSED" : "SKIPPED");
                assertThat(Values.map(results.get(1).get("result")).get("error").toString()).contains("missingInput");
            }
        }
    }

    @Test void popupLoadUsesTheRemainingStepTimeout() throws Exception {
        String project = assets.createProject("Browser popup deadline " + UUID.randomUUID(), Map.of()).id();
        try (Site site = new Site()) {
            Asset scenario = scenario(project, site.url(), false);
            step(project, scenario, Map.of("action", "navigate", "url", "/"));
            Asset popup = step(project, scenario, Map.of("action", "popup", "selector", "#popup", "saveAs", "detail", "timeoutMs", 1500));
            var results = steps(run(project, scenario, null));
            assertThat(results).hasSize(2);
            assertThat(results.getLast()).containsEntry("assetId", popup.id()).containsEntry("status", "FAILED");
            assertThat(((Number) Values.map(results.getLast().get("result")).get("durationMs")).longValue()).isLessThan(2500);
        }
    }

    private Asset scenario(String project, String url, boolean continuing) {
        return assets.create(project, AssetType.UI_SCENARIO, null, "Boundary " + UUID.randomUUID(), Map.of("baseUrl", url, "continueOnFailure", continuing), "MANUAL");
    }
    private Asset step(String project, Asset scenario, Map<String, Object> data) {
        return assets.create(project, AssetType.UI_STEP, scenario.id(), data.get("action").toString(), data, "MANUAL");
    }
    private Map<String, Object> run(String project, Asset scenario, String environment) {
        var submission = coordinator.submit(project, new ExecutionCoordinator.Request(scenario.id(), environment, null, UUID.randomUUID().toString()));
        await().atMost(Duration.ofSeconds(70)).until(() -> jobs.get(project, submission.jobId()).terminal());
        return runs.get(project, submission.runId());
    }
    private List<Map<String, Object>> steps(Map<String, Object> run) { return Values.objects(Values.objects(run.get("items")).getFirst().get("steps")); }

    private static final class Site implements AutoCloseable {
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private Site() throws Exception {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/delayed.js")) {
                    try { Thread.sleep(4000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                String body = switch (path) {
                    case "/delayed.js" -> "void 0;";
                    case "/slow-popup" -> "<h1>detail</h1><script src='/delayed.js'></script>";
                    case "/secret" -> {
                        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        yield "<input type='hidden' id='token' value='Extracted-secret-742'><p id='status'>ready</p><script>console.log('" + authorization.get() + "')</script>";
                    }
                    default -> "<input id='field'><p id='status'>ready</p><button id='popup' onclick=\"setTimeout(()=>window.open('/slow-popup'),150)\">open</button>";
                };
                byte[] content = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", path.endsWith(".js") ? "text/javascript" : "text/html; charset=utf-8");
                try { exchange.sendResponseHeaders(200, content.length); exchange.getResponseBody().write(content); }
                finally { exchange.close(); }
            });
            server.start();
        }
        private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
