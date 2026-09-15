package com.aitest.ai;

import com.aitest.ai.pipeline.PipelineService;
import com.aitest.asset.*;
import com.aitest.execution.RunRepository;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.support.ModelFixtureServer;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

class PipelinePlanBoundaryIT extends ExchangeHttpTest {
    @Autowired ModelSettingsService settings;
    @Autowired PipelineService pipelines;
    @Autowired RunRepository runs;
    @MockitoSpyBean AssetRepository repository;

    @Test void confirmationCommittedAfterTheJobCheckpointPreventsNewPlanChildren() throws Exception {
        CountDownLatch beforeProject = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var model = model()) {
            String project = project().id(); Asset requirement = requirement(project);
            model.enqueue(analysis(requirement)); model.enqueue(functional());
            String id = submit(project, requirement, null, List.of(), false);
            Asset plan = assets.get(project, output(terminal(project, id)).get("planId").toString());
            List<Asset> originalItems = assets.children(project, plan.id());
            Asset definition = definition(project, "/recovered");
            model.enqueue(api(definition));
            pauseS6BeforeProjectLock(project, beforeProject, release);
            pipelines.resume(id, new PipelineService.Resume(project, "S3", "recover-api", null, List.of(definition.id()), null, null, false));
            assertThat(beforeProject.await(15, TimeUnit.SECONDS)).as("S6 must pause after its checkpoint").isTrue();
            Asset confirmed;
            try { confirmed = assets.update(project, plan.id(), plan.version(), null, Map.of(), true, "MANUAL"); }
            finally { release.countDown(); }
            var after = terminal(project, id);
            assertThat(output(after)).containsEntry("execution", "PLAN_UPDATE_REQUIRED");
            assertThat(assets.get(project, plan.id())).isEqualTo(confirmed);
            assertThat(assets.children(project, plan.id())).containsExactlyElementsOf(originalItems);
            assertThat(after.get("runId")).isNull();
        } finally { release.countDown(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"REMOVE", "RETARGET", "OVERRIDE"})
    void continuationCannotCopyPlanItemsChangedAfterItsJobCheckpoint(String change) throws Exception {
        AtomicInteger savedCalls = new AtomicInteger(), pendingCalls = new AtomicInteger();
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/saved", exchange -> { savedCalls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        site.createContext("/pending", exchange -> { pendingCalls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        site.start();
        CountDownLatch beforeProject = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var model = model()) {
            String project = project().id(); Asset requirement = requirement(project);
            Asset environment = environment(project, site), original = definition(project, "/saved");
            model.enqueue(analysis(requirement)); model.enqueue(functional()); model.enqueue(api(original));
            String id = submit(project, requirement, environment.id(), List.of(original.id()), true);
            var before = terminal(project, id);
            String savedRun = before.get("runId").toString(), planId = output(before).get("planId").toString();
            Map<String, Object> historical = runs.get(project, savedRun);
            Asset recording = uiEvidence(project, site, "/pending");
            model.enqueue(ui(site, "/pending"));
            pipelines.resume(id, new PipelineService.Resume(project, "S5", "recover-pending", null, null, null, List.of(recording.id()), null));
            var pending = terminal(project, id);
            assertThat(output(pending)).containsEntry("execution", "PENDING_NEW_ASSETS");
            String target = ((List<?>) output(pending).get("pendingAssetIds")).getFirst().toString();
            Asset item = assets.children(project, planId).stream().filter(candidate -> target.equals(candidate.data().get("targetId"))).findFirst().orElseThrow();
            Asset alternative = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "人工替代项", Map.of(), "MANUAL");
            Asset plan = assets.get(project, planId);
            pauseS6BeforeProjectLock(project, beforeProject, release);
            pipelines.resume(id, new PipelineService.Resume(project, "S6", "continue-with-boundary", null, null, null, null, true));
            assertThat(beforeProject.await(15, TimeUnit.SECONDS)).as("Continuation must pause after its checkpoint").isTrue();
            try {
                if (change.equals("REMOVE")) assets.delete(project, item.id(), item.version());
                else if (change.equals("RETARGET")) assets.update(project, item.id(), item.version(), null, Map.of("targetId", alternative.id(), "executionMode", "MANUAL"), null, "MANUAL");
                else {
                    assets.update(project, plan.id(), plan.version(), "人工新计划名称", Map.of("description", "采用最新说明"), null, "MANUAL");
                    assets.update(project, item.id(), item.version(), "人工新计划项", Map.of("variables", Map.of("humanMarker", "latest")), null, "MANUAL");
                }
            } finally { release.countDown(); }
            var blocked = terminal(project, id);
            assertThat(output(blocked)).containsEntry("execution", "PLAN_UPDATE_REQUIRED");
            assertThat(blocked.get("runId")).isEqualTo(savedRun);
            assertThat(assets.list(project, AssetType.TEST_PLAN, null, "", 0, 10).items()).hasSize(1);
            assertThat(savedCalls).hasValue(1); assertThat(pendingCalls).hasValue(0);
            assertThat(runs.get(project, savedRun)).isEqualTo(historical);
            if (change.equals("REMOVE")) assertThat(assets.children(project, planId)).noneMatch(current -> current.id().equals(item.id()));
            if (change.equals("RETARGET")) assertThat(assets.get(project, item.id()).data()).containsEntry("targetId", alternative.id());
            if (change.equals("OVERRIDE")) {
                pipelines.resume(id, new PipelineService.Resume(project, "S6", "continue-current-view", null, null, null, null, true));
                var executed = terminal(project, id);
                String continuation = output(executed).get("executionPlanId").toString();
                assertThat(assets.get(project, continuation).name()).startsWith("人工新计划名称");
                assertThat(assets.get(project, continuation).data()).containsEntry("description", "采用最新说明");
                assertThat(assets.children(project, continuation)).singleElement().satisfies(current -> {
                    assertThat(current.name()).isEqualTo("人工新计划项");
                    assertThat(current.data().get("variables")).isEqualTo(Map.of("humanMarker", "latest"));
                });
                assertThat(pendingCalls).hasValue(1); assertThat(savedCalls).hasValue(1);
            }
        } finally { release.countDown(); site.stop(0); }
    }

    @Test void continuationDoesNotResurrectDiagnosisFromAnEarlierRun() throws Exception {
        AtomicInteger failedCalls = new AtomicInteger(), nextCalls = new AtomicInteger();
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/failed", exchange -> { failedCalls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        site.createContext("/next", exchange -> {
            nextCalls.incrementAndGet(); running.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("continuation HTTP barrier timed out");
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        site.start();
        try (var model = model()) {
            String project = project().id(); Asset requirement = requirement(project);
            Asset environment = environment(project, site), original = definition(project, "/failed");
            model.enqueue(analysis(requirement)); model.enqueue(functional()); model.enqueue(api(original));
            model.enqueue(json.write(Map.of("title", "接口返回错误", "severity", "MAJOR", "reproduceSteps", "请求接口", "expectedResult", "状态码 200", "actualResult", "状态码 500", "rootCauseAnalysis", "待核实服务异常", "fixSuggestion", "检查服务日志")));
            String id = submit(project, requirement, environment.id(), List.of(original.id()), true);
            var before = terminal(project, id);
            String savedRun = before.get("runId").toString();
            Map<String, Object> historical = runs.get(project, savedRun);
            assertThat(map(output(before).get("diagnosis"))).containsEntry("runId", savedRun);
            Object savedDiagnosis = output(before).get("diagnosis");
            Asset recording = uiEvidence(project, site, "/next");
            model.enqueue(ui(site, "/next"));
            pipelines.resume(id, new PipelineService.Resume(project, "S5", "recover-next", null, null, null, List.of(recording.id()), null));
            assertThat(output(terminal(project, id))).containsEntry("execution", "PENDING_NEW_ASSETS");
            pipelines.resume(id, new PipelineService.Resume(project, "S6", "execute-next", null, null, null, null, true));
            assertThat(running.await(15, TimeUnit.SECONDS)).isTrue();
            SoftAssertions checks = new SoftAssertions();
            String nextRun;
            try {
                var inProgress = pipelines.get(project, id); nextRun = inProgress.get("runId").toString();
                checks.assertThat(nextRun).isNotEqualTo(savedRun);
                checks.assertThat(output(inProgress)).doesNotContainKeys("diagnosis", "diagnosisJobId", "runSummary");
                checks.assertThat(map(inProgress.get("execution"))).as("Current run detail while executing").doesNotContainKeys("diagnosis", "diagnosisJobId", "runSummary", "reason");
            } finally { release.countDown(); }
            var completed = terminal(project, id);
            checks.assertThat(map(completed.get("execution"))).as("Current run detail after success").doesNotContainKeys("diagnosis", "diagnosisJobId", "reason");
            checks.assertThat(map(completed.get("execution")).get("runSummary")).isEqualTo(json.tree(json.write(runs.get(project, nextRun).get("summary"))));
            checks.assertThat(output(completed)).doesNotContainKeys("diagnosis", "diagnosisJobId");
            checks.assertThat(completed.get("runId")).isEqualTo(nextRun);
            checks.assertThat(map(completed.get("execution")).get("runIds")).isEqualTo(List.of(savedRun, nextRun));
            checks.assertThat(objects(completed.get("attempts"))).anySatisfy(attempt -> {
                assertThat(map(attempt.get("output")).get("runId")).isEqualTo(savedRun);
                assertThat(map(attempt.get("output")).get("diagnosis")).isEqualTo(savedDiagnosis);
            });
            checks.assertThat(runs.get(project, savedRun)).isEqualTo(historical);
            checks.assertThat(failedCalls.get()).isEqualTo(1); checks.assertThat(nextCalls.get()).isEqualTo(1);
            checks.assertThat(model.requests).hasSize(5);
            checks.assertAll();
        } finally { release.countDown(); site.stop(0); }
    }

    private void pauseS6BeforeProjectLock(String project, CountDownLatch entered, CountDownLatch release) {
        AtomicBoolean armed = new AtomicBoolean(true);
        // Only scheduling is changed: the job checkpoint, project lock and all writes stay real.
        doAnswer(invocation -> {
            boolean inS6Commit = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals(PipelineService.class.getName()) && frame.getMethodName().startsWith("lambda$executeTests")));
            if (inS6Commit && armed.compareAndSet(true, false)) {
                entered.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("S6 project-lock barrier timed out");
            }
            return invocation.callRealMethod();
        }).when(repository).lockProject(project);
    }

    private ModelFixtureServer model() throws Exception { var model = new ModelFixtureServer(); settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30)); return model; }
    private Asset requirement(String project) { return assets.create(project, AssetType.REQUIREMENT, null, "退款需求", Map.of("content", "# 退款\n退款应成功。"), "MANUAL"); }
    private Asset environment(String project, HttpServer site) { return assets.create(project, AssetType.ENVIRONMENT, null, "恢复执行环境", Map.of("baseUrl", "http://127.0.0.1:" + site.getAddress().getPort()), "MANUAL"); }
    private Asset definition(String project, String path) { return assets.create(project, AssetType.API_DEFINITION, null, path, Map.of("method", "GET", "path", path), "IMPORT"); }
    private Asset uiEvidence(String project, HttpServer site, String path) {
        Asset recording = assets.create(project, AssetType.UI_SCENARIO, null, "真实导航证据", Map.of("baseUrl", "http://127.0.0.1:" + site.getAddress().getPort()), "IMPORT");
        assets.create(project, AssetType.UI_STEP, recording.id(), "导航记录", Map.of("action", "navigate", "url", path), "IMPORT");
        return recording;
    }
    private String ui(HttpServer site, String path) { return json.write(Map.of("changes", List.of(
            Map.of("operation", "ADD", "targetType", "UI_SCENARIO", "localKey", "web", "name", "恢复的页面", "data", Map.of("baseUrl", "http://127.0.0.1:" + site.getAddress().getPort())),
            Map.of("operation", "ADD", "targetType", "UI_STEP", "localKey", "go", "parentId", "@web", "name", "打开页面", "data", Map.of("action", "navigate", "url", path))))); }
    private String analysis(Asset requirement) { return json.write(Map.of("changes", List.of(Map.of("operation", "MODIFY", "targetType", "REQUIREMENT", "targetId", requirement.id(), "baseVersion", requirement.version(), "data", Map.of("analysis", Map.of("rules", List.of("退款成功"))))))); }
    private String functional() { return "featureCaseStart\n## 人工退款\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 提交退款 | 成功 |\nfeatureCaseEnd"; }
    private String api(Asset definition) { return json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "API_CASE", "name", definition.name(), "data", Map.of("apiDefinitionId", definition.id(), "method", "GET", "path", definition.data().get("path"), "assertions", List.of(Map.of("type", "status", "expected", 200))))))); }
    private String submit(String project, Asset requirement, String environment, List<String> definitions, boolean execute) { return pipelines.submit(new PipelineService.Request(project, List.of(requirement.id()), definitions, environment, List.of(), List.of(), execute, "initial")).get("pipelineId").toString(); }
    private Map<String, Object> terminal(String project, String id) {
        await().atMost(Duration.ofSeconds(45)).until(() -> Set.of("COMPLETED", "COMPLETED_WITH_GAPS", "FAILED", "CANCELLED", "INTERRUPTED").contains(pipelines.get(project, id).get("status")));
        var result = pipelines.get(project, id);
        assertThat(result.get("status")).as("Pipeline error: %s", result.get("error")).isIn("COMPLETED", "COMPLETED_WITH_GAPS");
        return result;
    }
    private Map<String, Object> output(Map<String, Object> pipeline) { return map(objects(pipeline.get("steps")).stream().filter(step -> "S6".equals(step.get("stage"))).findFirst().orElseThrow().get("output")); }
}
