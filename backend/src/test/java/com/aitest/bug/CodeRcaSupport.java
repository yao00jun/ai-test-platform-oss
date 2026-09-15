package com.aitest.bug;

import com.aitest.ai.*;
import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.execution.*;
import com.aitest.job.*;
import com.aitest.support.ModelFixtureServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

abstract class CodeRcaSupport extends ExchangeHttpTest {
    @TempDir Path temporary;
    @Autowired JobService jobs;
    @Autowired ModelSettingsService settings;
    @Autowired ExecutionCoordinator coordinator;
    @Autowired FailureEvidenceReader reader;
    static final String SOURCE = "package shop;\nclass OrderService {\n  String apiKey = \"source-secret-4717\";\n  String refund(String id) {\n    if (id == null) throw new IllegalArgumentException(\"missing id\");\n    return id.trim();\n  }\n}\n";
    static final String STACK = "java.lang.IllegalArgumentException: missing id\n\tat shop.OrderService.refund(OrderService.java:5)\n";
    static final String PATCH = "--- a/OrderService.java\n+++ b/OrderService.java\n@@ -4,3 +4,3 @@\n   String refund(String id) {\n-    if (id == null) throw new IllegalArgumentException(\"missing id\");\n+    if (id == null) return \"pending review\";\n     return id.trim();\n";
    record Captured(String id, Path directory) { }
    protected Captured source(String project, boolean baseline, boolean duplicate) throws Exception {
        Path directory = Files.createDirectories(temporary.resolve(UUID.randomUUID().toString()));
        Files.createDirectory(directory.resolve("other"));
        Files.writeString(directory.resolve("other/OrderService.java"), SOURCE.replace("package shop;", duplicate ? "package shop;" : "package unrelated;").replace("missing id", "other class"));
        Files.writeString(directory.resolve("OrderService.java"), SOURCE.replace("throw new IllegalArgumentException(\"missing id\")", "return \"old acceptance\""));
        if (baseline) { git(directory, "init", "-q"); git(directory, "add", "."); git(directory, "commit", "-qm", "baseline"); }
        Files.writeString(directory.resolve("OrderService.java"), SOURCE);
        if (baseline) { git(directory, "add", "."); git(directory, "commit", "-qm", "head"); }
        Map<String, Object> input = new LinkedHashMap<>(Map.of("backendRepoPath", directory.toString(), "idempotencyKey", UUID.randomUUID().toString()));
        if (baseline) { input.put("backendRef", "HEAD"); input.put("baselineRef", "HEAD~1"); }
        var response = request("POST", "/api/projects/" + project + "/source-analyses", input);
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        var accepted = object(response); assertThat(terminal(project, accepted.get("jobId").toString()).status()).isEqualTo("SUCCEEDED");
        return new Captured(accepted.get("analysisId").toString(), directory);
    }
    private void git(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=RCA fixture", "-c", "user.email=fixture@localhost", "-c", "commit.gpgSign=false")); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isZero();
    }
    protected String failure(String project, String snapshot, String stack) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", request -> { byte[] body = stack.getBytes(StandardCharsets.UTF_8); request.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8"); request.sendResponseHeaders(500, body.length); request.getResponseBody().write(body); request.close(); }); target.start();
        try {
            Map<String, Object> data = new LinkedHashMap<>(Map.of("path", "http://127.0.0.1:" + target.getAddress().getPort() + "/", "assertions", List.of(Map.of("type", "status", "expected", 200))));
            if (snapshot != null) data.put("sourceSnapshotId", snapshot);
            Asset api = assets.create(project, AssetType.API_CASE, null, "退款 HTTP 500", data, "MANUAL");
            Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "源码诊断计划", Map.of("diagnoseFailures", false), "MANUAL");
            assets.create(project, AssetType.PLAN_ITEM, plan.id(), "退款调用", Map.of("targetId", api.id()), "MANUAL");
            var accepted = coordinator.submit(project, new ExecutionCoordinator.Request(plan.id(), null, null, UUID.randomUUID().toString()));
            assertThat(terminal(project, accepted.jobId()).status()).isEqualTo("SUCCEEDED");
            return accepted.runId();
        } finally { target.stop(0); }
    }
    protected Job terminal(String project, String id) { await().atMost(Duration.ofSeconds(45)).until(() -> jobs.get(project, id).terminal()); return jobs.get(project, id); }
    protected void configure(ModelFixtureServer model) { settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30)); }
    protected Map<String, Object> code(String root, String path, String patch) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("formatVersion", "aitest.code-rca/v1"); value.put("root_cause", root); value.put("affected_code_path", path); value.put("suggested_fix", patch); value.put("is_regression", null); value.put("confidence", 0.7); return value;
    }
    protected Map<String, Object> diagnosis(Map<String, Object> code) {
        var value = new LinkedHashMap<String, Object>(Map.of("title", "退款参数异常", "severity", "MAJOR", "reproduceSteps", "调用退款接口", "expectedResult", "返回参数错误", "actualResult", "HTTP 500", "rootCauseAnalysis", "待核实的异常处理", "fixSuggestion", "根据固定源码核对"));
        if (code != null) value.put("codeDiagnosis", code); return value;
    }
    protected Map<String, Object> diagnose(String project, String run) throws Exception {
        var response = request("POST", "/api/projects/" + project + "/runs/" + run + "/diagnose", Map.of("idempotencyKey", UUID.randomUUID().toString()));
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        return object(response);
    }
    protected Asset diagnosedBug(String project, String run, ModelFixtureServer model, Map<String, Object> code) throws Exception {
        configure(model); model.enqueue(json.write(diagnosis(code))); var accepted = diagnose(project, run); Job job = terminal(project, accepted.get("jobId").toString());
        assertThat(job.status()).as(job.error()).isEqualTo("SUCCEEDED");
        return assets.list(project, AssetType.BUG, null, "", 0, 100).items().getFirst();
    }
}
