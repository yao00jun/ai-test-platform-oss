package com.aitest.ai;
import org.junit.jupiter.api.Tag;

import com.aitest.ai.pipeline.PipelineService;
import com.aitest.asset.*;
import com.aitest.execution.RunRepository;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import com.aitest.support.ModelFixtureServer;
import com.sun.net.httpserver.HttpServer;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Tag("slow")
class PipelinePlanRecoveryIT extends ExchangeHttpTest {
    private static final int BROWSER_SCENARIO_TIMEOUT_MS = 120_000;

    @Autowired ModelSettingsService settings;
    @Autowired PipelineService pipelines;
    @Autowired RunRepository runs;
    @Autowired JobService jobs;

    @Test void recoveringApiEvidenceAddsNewRootsToTheExistingHumanEditedPlan() throws Exception {
        try (var model = model()) {
            String project = project().id(); Asset requirement = requirement(project);
            model.enqueue(analysis(requirement)); model.enqueue(functional());
            String id = submit(project, requirement, null, List.of(), false);
            var before = terminal(project, id);
            Asset plan = assets.get(project, output(before).get("planId").toString());
            Asset edited = assets.update(project, plan.id(), plan.version(), "人工整理的计划", Map.of("description", "保留人工说明", "concurrency", 1), null, "MANUAL");
            List<Asset> originalItems = assets.children(project, plan.id());
            Asset definition = assets.create(project, AssetType.API_DEFINITION, null, "退款接口", Map.of("method", "GET", "path", "/refund"), "IMPORT");
            model.enqueue(api(definition));

            pipelines.resume(id, new PipelineService.Resume(project, "S3", "supply-api", null, List.of(definition.id()), null, null, false));
            var recovered = terminal(project, id);
            Asset generated = assets.list(project, AssetType.API_CASE, null, "", 0, 10).items().getFirst();
            assertThat(assets.children(project, plan.id())).extracting(item -> item.data().get("targetId")).contains(generated.id());
            assertThat(assets.children(project, plan.id())).containsAll(originalItems);
            assertThat(assets.get(project, plan.id())).isEqualTo(edited);
            assertThat(output(recovered)).containsEntry("planId", plan.id()).containsEntry("execution", "NOT_REQUESTED");
            assertThat(recovered.get("runId")).isNull();
            assertThat(assets.list(project, AssetType.TEST_PLAN, null, "", 0, 10).items()).containsExactly(edited);
            assertThat(model.requests).hasSize(3);
        }
    }

    @Test void recoveringUiAfterASavedRunWaitsForExplicitContinuationAndDoesNotReplayOldHttp() throws Exception {
        AtomicInteger savedHttp = new AtomicInteger(), recoveredWeb = new AtomicInteger();
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/saved", exchange -> { savedHttp.incrementAndGet(); exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8)); exchange.close(); });
        site.createContext("/recovered", exchange -> { recoveredWeb.incrementAndGet(); byte[] html = "<html><body>退款完成</body></html>".getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8"); exchange.sendResponseHeaders(200, html.length); exchange.getResponseBody().write(html); exchange.close(); });
        site.start();
        try (var model = model()) {
            String project = project().id(), baseUrl = "http://127.0.0.1:" + site.getAddress().getPort();
            Asset requirement = requirement(project);
            Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "恢复执行环境", Map.of("baseUrl", baseUrl), "MANUAL");
            Asset definition = assets.create(project, AssetType.API_DEFINITION, null, "原有 HTTP", Map.of("method", "GET", "path", "/saved"), "IMPORT");
            model.enqueue(analysis(requirement)); model.enqueue(functional()); model.enqueue(api(definition));
            String id = submit(project, requirement, environment.id(), List.of(definition.id()), true);
            var before = terminal(project, id);
            String savedRun = before.get("runId").toString();
            Map<String, Object> historicalRun = runs.get(project, savedRun);
            assertThat(savedHttp).hasValue(1);
            Asset plan = assets.get(project, output(before).get("planId").toString());
            Asset editedPlan = assets.update(project, plan.id(), plan.version(), "人工编辑的执行计划", Map.of("description", "保留人工备注", "concurrency", 1), null, "MANUAL");
            Asset oldApi = assets.list(project, AssetType.API_CASE, null, "", 0, 10).items().getFirst();
            Asset apiItem = assets.children(project, plan.id()).stream().filter(item -> oldApi.id().equals(item.data().get("targetId"))).findFirst().orElseThrow();
            Asset editedItem = assets.update(project, apiItem.id(), apiItem.version(), "人工重命名的 HTTP 项", Map.of("variables", Map.of("manualMarker", "keep")), null, "MANUAL");
            Asset removedItem = assets.children(project, plan.id()).stream().filter(item -> !item.id().equals(apiItem.id())).findFirst().orElseThrow();
            assets.delete(project, removedItem.id(), removedItem.version());
            Asset manual = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "人工补充项", Map.of(), "MANUAL");
            Asset manualItem = assets.create(project, AssetType.PLAN_ITEM, plan.id(), "人工补充计划项", Map.of("targetId", manual.id(), "executionMode", "MANUAL"), "MANUAL");
            Asset recording = assets.create(project, AssetType.UI_SCENARIO, null, "真实录制", Map.of("baseUrl", baseUrl), "IMPORT");
            assets.create(project, AssetType.UI_STEP, recording.id(), "导航证据", Map.of("action", "navigate", "url", "/recovered"), "IMPORT");
            model.enqueue(json.write(Map.of("changes", List.of(
                    add("UI_SCENARIO", "web", null, "恢复的 UI", Map.of("baseUrl", baseUrl, "timeoutMs", BROWSER_SCENARIO_TIMEOUT_MS)),
                    add("UI_STEP", "navigate", "@web", "打开退款页", Map.of("action", "navigate", "url", "/recovered", "timeoutMs", 30_000))))));

            pipelines.resume(id, new PipelineService.Resume(project, "S5", "supply-ui", null, null, null, List.of(recording.id()), null));
            var recovered = terminal(project, id);
            Asset web = assets.list(project, AssetType.UI_SCENARIO, null, "", 0, 10).items().stream().filter(asset -> !asset.id().equals(recording.id())).findFirst().orElseThrow();
            assertThat(assets.children(project, plan.id())).extracting(item -> item.data().get("targetId")).contains(web.id());
            assertThat(step(recovered).get("status")).isEqualTo("BLOCKED");
            assertThat(recovered.get("status")).isEqualTo("COMPLETED_WITH_GAPS");
            assertThat(output(recovered)).containsEntry("execution", "PENDING_NEW_ASSETS").containsEntry("requiresExecutionResume", true);
            assertThat(output(recovered).get("pendingAssetIds")).isEqualTo(List.of(web.id()));
            assertThat(map(recovered.get("execution"))).isEqualTo(output(recovered));
            assertThat(recovered.get("runId")).isEqualTo(savedRun);
            assertThat(runs.get(project, savedRun)).isEqualTo(historicalRun);
            assertThat(assets.get(project, plan.id())).isEqualTo(editedPlan);
            assertThat(assets.children(project, plan.id())).contains(editedItem, manualItem).noneMatch(item -> item.id().equals(removedItem.id()));
            assertThat(savedHttp).hasValue(1); assertThat(recoveredWeb).hasValue(0);

            // Inherited execute=true cannot turn an ordinary stage retry into another run.
            pipelines.resume(id, new PipelineService.Resume(project, "S6", "inspect-gap", null, null, null, null, null));
            var stillPending = terminal(project, id);
            assertThat(step(stillPending).get("status")).isEqualTo("BLOCKED");
            assertThat(savedHttp).hasValue(1); assertThat(recoveredWeb).hasValue(0);

            var request = new PipelineService.Resume(project, "S6", "execute-new", null, null, null, null, true);
            pipelines.resume(id, request);
            // A real browser run has the same 120-second scenario budget as production;
            // this functional wait also covers queue handoff, persistence and reconciliation.
            var executed = terminal(project, id, Duration.ofMillis(BROWSER_SCENARIO_TIMEOUT_MS).plusSeconds(30));
            assertThat(step(executed).get("status")).isEqualTo("COMPLETED");
            assertThat(executed.get("runId")).isNotEqualTo(savedRun);
            assertThat(output(executed).get("runIds")).isEqualTo(List.of(savedRun, executed.get("runId")));
            assertThat((List<?>) output(executed).get("pendingAssetIds")).isEmpty();
            assertThat(output(executed).get("planId")).isEqualTo(plan.id());
            assertThat(runs.get(project, savedRun)).isEqualTo(historicalRun);
            assertThat(assets.get(project, plan.id())).isEqualTo(editedPlan);
            assertThat(assets.children(project, plan.id())).contains(editedItem, manualItem).noneMatch(item -> item.id().equals(removedItem.id()));
            var continuedRun = runs.get(project, executed.get("runId").toString());
            assertThat(continuedRun).containsEntry("status", "PASSED");
            assertThat(objects(continuedRun.get("items"))).singleElement().satisfies(item -> assertThat(item.get("assetId")).isEqualTo(web.id()));
            pipelines.resume(id, request);
            assertThat(savedHttp).hasValue(1); assertThat(recoveredWeb).hasValue(1);
            assertThat(model.requests).hasSize(4);
        } finally { site.stop(0); }
    }

    @Test void recoveringRootsCannotExtendAConfirmedPlanAndReportsTheMissingMembership() throws Exception {
        try (var model = model()) {
            String project = project().id(); Asset requirement = requirement(project);
            model.enqueue(analysis(requirement)); model.enqueue(functional());
            String id = submit(project, requirement, null, List.of(), false);
            var before = terminal(project, id);
            Asset plan = assets.get(project, output(before).get("planId").toString());
            Asset confirmed = assets.update(project, plan.id(), plan.version(), null, Map.of(), true, "MANUAL");
            List<Asset> originalItems = assets.children(project, plan.id());
            Asset definition = assets.create(project, AssetType.API_DEFINITION, null, "退款接口", Map.of("method", "GET", "path", "/refund"), "IMPORT");
            model.enqueue(api(definition));
            pipelines.resume(id, new PipelineService.Resume(project, "S3", "protected-plan", null, List.of(definition.id()), null, null, false));
            var recovered = terminal(project, id);
            Asset generated = assets.list(project, AssetType.API_CASE, null, "", 0, 10).items().getFirst();
            assertThat(step(recovered).get("status")).isEqualTo("BLOCKED");
            assertThat(output(recovered).get("pendingPlanAssetIds")).isEqualTo(List.of(generated.id()));
            assertThat(assets.get(project, plan.id())).isEqualTo(confirmed);
            assertThat(assets.children(project, plan.id())).containsExactlyElementsOf(originalItems);
        }
    }

    @Test void aMissingExecutionEnvironmentDoesNotLeaveRecoveredRootsOutOfTheSavedPlan() throws Exception {
        try (var model = model()) {
            String project = project().id(); Asset requirement = requirement(project);
            model.enqueue(analysis(requirement)); model.enqueue(functional());
            String id = submit(project, requirement, null, List.of(), false);
            String plan = output(terminal(project, id)).get("planId").toString();
            Asset definition = assets.create(project, AssetType.API_DEFINITION, null, "待配置环境的退款接口", Map.of("method", "GET", "path", "/refund"), "IMPORT");
            model.enqueue(api(definition));
            pipelines.resume(id, new PipelineService.Resume(project, "S3", "recover-before-environment", null, List.of(definition.id()), null, null, true));
            var recovered = terminal(project, id);
            Asset api = assets.list(project, AssetType.API_CASE, null, "", 0, 10).items().getFirst();
            assertThat(assets.children(project, plan)).extracting(item -> item.data().get("targetId")).contains(api.id());
            assertThat(step(recovered).get("status")).isEqualTo("BLOCKED");
            assertThat(output(recovered)).containsEntry("execution", "ENVIRONMENT_REQUIRED");
            assertThat(recovered.get("runId")).isNull();
        }
    }

    private ModelFixtureServer model() throws Exception { var model = new ModelFixtureServer(); settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30)); return model; }
    private Asset requirement(String project) { return assets.create(project, AssetType.REQUIREMENT, null, "退款需求", Map.of("content", "# 退款\n退款应满足订单状态。"), "MANUAL"); }
    private String analysis(Asset requirement) { return json.write(Map.of("changes", List.of(Map.of("operation", "MODIFY", "targetType", "REQUIREMENT", "targetId", requirement.id(), "baseVersion", requirement.version(), "data", Map.of("analysis", Map.of("rules", List.of("校验订单状态"))))))); }
    private String functional() { return "featureCaseStart\n## 人工退款\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 提交退款 | 成功 |\nfeatureCaseEnd"; }
    private String api(Asset definition) { return json.write(Map.of("changes", List.of(add("API_CASE", "api", null, definition.name(), Map.of("apiDefinitionId", definition.id(), "method", "GET", "path", definition.data().get("path"), "assertions", List.of(Map.of("type", "status", "expected", 200))))))); }
    private Map<String, Object> add(String type, String key, String parent, String name, Map<String, Object> data) { Map<String, Object> result = new LinkedHashMap<>(Map.of("operation", "ADD", "targetType", type, "localKey", key, "name", name, "data", data)); result.put("parentId", parent); return result; }
    private String submit(String project, Asset requirement, String environment, List<String> definitions, boolean execute) { return pipelines.submit(new PipelineService.Request(project, List.of(requirement.id()), definitions, environment, List.of(), List.of(), execute, "initial")).get("pipelineId").toString(); }
    private Map<String, Object> terminal(String project, String id) {
        return terminal(project, id, Duration.ofSeconds(45));
    }
    private Map<String, Object> terminal(String project, String id, Duration timeout) {
        try {
            await().atMost(timeout).until(() -> Set.of("COMPLETED", "COMPLETED_WITH_GAPS", "FAILED", "CANCELLED", "INTERRUPTED").contains(pipelines.get(project, id).get("status")));
        } catch (ConditionTimeoutException failure) {
            captureTimeout(project, id, timeout, failure);
            throw failure;
        }
        var result = pipelines.get(project, id);
        assertThat(result.get("status")).as("Pipeline error: %s", result.get("error")).isIn("COMPLETED", "COMPLETED_WITH_GAPS");
        return result;
    }
    private void captureTimeout(String project, String id, Duration timeout, RuntimeException failure) {
        try {
            var pipeline = pipelines.get(project, id);
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("at", Instant.now()); state.put("waitBudgetMs", timeout.toMillis()); state.put("pipeline", pipeline);
            state.put("job", jobs.get(project, pipeline.get("jobId").toString()));
            if (pipeline.get("runId") != null) state.put("run", runs.get(project, pipeline.get("runId").toString()));
            Path directory = Files.createDirectories(Path.of("../.runtime/acceptance"));
            Path path = directory.resolve("pipeline-plan-recovery-" + id + "-timeout.json");
            Files.writeString(path, json.write(state));
            failure.addSuppressed(new IllegalStateException("Pipeline timeout evidence: " + path.toAbsolutePath().normalize()));
        } catch (Exception diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
    }
    private Map<String, Object> step(Map<String, Object> pipeline) { return objects(pipeline.get("steps")).stream().filter(step -> step.get("stage").equals("S6")).findFirst().orElseThrow(); }
    private Map<String, Object> output(Map<String, Object> pipeline) { return map(step(pipeline).get("output")); }
}
