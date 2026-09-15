package com.aitest.report;

import com.aitest.ai.ModelSettingsService;
import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.execution.*;
import com.aitest.job.*;
import com.aitest.support.ModelFixtureServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class AiQualityReportIT extends ExchangeHttpTest {
    static final ModelFixtureServer model;
    static { try { model = new ModelFixtureServer(); } catch (Exception failure) { throw new ExceptionInInitializerError(failure); } }
    @Autowired ModelSettingsService settings;
    @Autowired JobService jobs;
    @Autowired ExecutionCoordinator execution;
    @Autowired RunRepository runs;
    @AfterAll static void closeModel() { model.close(); }

    @Test void selectedRunReportUsesActualDdtManualCountsAndKeepsItsSourceFactsAfterLaterEdits() throws Exception {
        settings.save(new ModelSettingsService.Input(model.url(), "quality-fixture", "fixture", 0.1, 30));
        String project = project().id(), secret = "Quality-credential-never-send-995";
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            site.setExecutor(executor); site.createContext("/", request -> {
                byte[] body = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
                request.sendResponseHeaders(200, body.length); request.getResponseBody().write(body); request.close();
            }); site.start();
            Asset env = create(project, AssetType.ENVIRONMENT, null, "报告环境", Map.of("baseUrl", "http://127.0.0.1:" + site.getAddress().getPort(), "variables", Map.of("apiKey", secret), "headers", Map.of("Authorization", "Bearer ${apiKey}")));
            Asset api = create(project, AssetType.API_CASE, null, "保存时的订单接口", Map.of("path", "/", "assertions", List.of(Map.of("type", "status", "expected", "${expectedStatus}"))));
            Asset data = create(project, AssetType.DATASET, null, "两行不同预期", Map.of("columns", List.of("expectedStatus"), "rows", List.of(Map.of("expectedStatus", 200), Map.of("expectedStatus", 201))));
            Asset manual = create(project, AssetType.FUNCTIONAL_CASE, null, "待人工验收", Map.of());
            Asset plan = create(project, AssetType.TEST_PLAN, null, "真实订单质量", Map.of("environmentId", env.id(), "diagnoseFailures", false));
            create(project, AssetType.PLAN_ITEM, plan.id(), "数据驱动项", Map.of("targetId", api.id(), "datasetId", data.id()));
            create(project, AssetType.PLAN_ITEM, plan.id(), "人工项", Map.of("targetId", manual.id(), "executionMode", "MANUAL"));
            var submitted = execution.submit(project, new ExecutionCoordinator.Request(plan.id(), env.id(), null, UUID.randomUUID().toString()));
            finished(project, submitted.jobId());
            var metricsResponse = request("GET", "/api/projects/" + project + "/quality-metrics?runId=" + submitted.runId(), null);
            assertThat(metricsResponse.statusCode()).as(new String(metricsResponse.body(), StandardCharsets.UTF_8)).isEqualTo(200);
            Map<String, Object> metrics = object(metricsResponse);
            assertThat(metrics).containsEntry("itemCount", 3).containsEntry("caseCount", 2).containsEntry("dataRowCount", 2);
            assertThat(map(metrics.get("itemStatuses"))).containsEntry("PASSED", 1).containsEntry("FAILED", 1).containsEntry("MANUAL_PENDING", 1);
            assertThat(((Number) metrics.get("passRatePercent")).doubleValue()).isEqualTo(33.33);
            assertThat(map(metrics.get("duration"))).containsEntry("sampleCount", 2);
            assertThat(json.write(metrics)).contains("保存时的订单接口").doesNotContain(secret);
            assertThat(request("GET", "/api/projects/" + project().id() + "/quality-metrics?runId=" + submitted.runId(), null).statusCode()).isEqualTo(404);

            model.enqueue(candidate("分析实际失败与待人工项", Map.of("content", "需先核验失败订单与待人工项。")));
            Job generated = generate(project, submitted.runId());
            assertThat(generated.status()).as(generated.error()).isEqualTo("SUCCEEDED");
            Asset brief = assets.get(project, objects(generated.result().get("assets")).getFirst().get("id").toString());
            assertThat(brief.data().get("runId")).isEqualTo(submitted.runId());
            Map<String, Object> savedMetrics = map(brief.data().get("metrics"));
            assertThat(savedMetrics.get("snapshotHash")).asString().matches("[0-9a-f]{64}");
            assertThat(((Number) savedMetrics.get("itemCount")).intValue()).isEqualTo(3);
            Map<String, Object> context = json.map(objects(json.map(model.requests.getLast()).get("messages")).getLast().get("content").toString());
            assertThat(context.get("executionMetrics")).isEqualTo(savedMetrics);
            assertThat(model.requests.getLast()).doesNotContain(secret, "{planName}", "{passRate}");
            var run = runs.get(project, submitted.runId());
            var item = objects(run.get("items")).stream().filter(value -> value.get("assetId").equals(manual.id())).findFirst().orElseThrow();
            runs.manual(project, submitted.runId(), item.get("id").toString(), item.get("manualVersion").toString(), "PASSED", "后来才完成的人工验收");
            assertThat(assets.get(project, brief.id())).isEqualTo(brief);
            var now = object(request("GET", "/api/projects/" + project + "/quality-metrics?runId=" + submitted.runId(), null));
            assertThat(((Number) now.get("passRatePercent")).doubleValue()).isEqualTo(66.67);
        } finally { site.stop(0); }
    }

    @Test void emptyProjectHasNoInventedPassRateAndForgedGeneratedMetricsAreRejected() throws Exception {
        settings.save(new ModelSettingsService.Input(model.url(), "quality-fixture", "fixture", 0.1, 30));
        String project = project().id();
        var response = request("GET", "/api/projects/" + project + "/quality-metrics", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(object(response)).containsEntry("itemCount", 0).containsEntry("runCount", 0).containsEntry("passRatePercent", null).containsEntry("scope", "LAST_24_HOURS");
        model.enqueue(candidate("虚构的全通过报告", Map.of("content", "模型声称已通过", "metrics", Map.of("itemCount", 100, "passRatePercent", 100))));
        Job rejected = generate(project, null);
        assertThat(rejected.status()).as(rejected.error()).isEqualTo("FAILED");
        assertThat(assets.all(project).stream().filter(asset -> asset.type() == AssetType.QUALITY_BRIEF)).isEmpty();
        model.enqueue(candidate("空项目简报", Map.of("content", "尚无运行记录，无法评估通过率。")));
        Job accepted = generate(project, null);
        assertThat(accepted.status()).as(accepted.error()).isEqualTo("SUCCEEDED");
        var brief = assets.get(project, objects(accepted.result().get("assets")).getFirst().get("id").toString());
        assertThat(map(brief.data().get("metrics"))).containsEntry("passRatePercent", null);
    }

    @Test void repeatedTextRefinementPreservesMetricsAndGlobalFeedbackCannotRewriteThem() throws Exception {
        settings.save(new ModelSettingsService.Input(model.url(), "quality-fixture", "fixture", 0.1, 30));
        String project = project().id();
        Asset brief = create(project, AssetType.QUALITY_BRIEF, null, "人工维护的简报", Map.of("content", "保留人工发布条件", "metrics", Map.of("itemCount", 7, "passRatePercent", 0)));
        Asset sibling = create(project, AssetType.QUALITY_BRIEF, null, "其他简报", Map.of("content", "保持不变"));
        Object originalMetrics = brief.data().get("metrics"); String conversation = null;
        for (int round = 1; round <= 2; round++) {
            model.enqueue(json.write(Map.of("data", Map.of("content", "保留人工发布条件，第 " + round + " 轮补充行动建议"))));
            var input = new LinkedHashMap<String, Object>(Map.of("projectId", project, "targetType", "QUALITY_BRIEF", "targetId", brief.id(), "baseVersion", brief.version(), "feedback", "仅调整正文", "idempotencyKey", UUID.randomUUID().toString()));
            if (conversation != null) input.put("conversationId", conversation);
            var submitted = object(request("POST", "/api/ai/refine-item", input)); conversation = submitted.get("conversationId").toString();
            Job job = finished(project, submitted.get("jobId").toString());
            assertThat(job.status()).as(job.error()).isEqualTo("SUCCEEDED");
            brief = assets.get(project, brief.id());
            assertThat(brief.data().get("metrics")).isEqualTo(originalMetrics);
            assertThat(assets.get(project, sibling.id())).isEqualTo(sibling);
        }
        Asset unchanged = brief;
        model.enqueue(json.write(Map.of("data", Map.of("metrics", Map.of("passRatePercent", 100)))));
        var local = object(request("POST", "/api/ai/refine-item", Map.of("projectId", project, "targetType", "QUALITY_BRIEF", "targetId", brief.id(), "baseVersion", brief.version(), "feedback", "伪造通过率", "idempotencyKey", UUID.randomUUID().toString())));
        assertThat(finished(project, local.get("jobId").toString()).status()).isEqualTo("FAILED");
        assertThat(assets.get(project, brief.id())).isEqualTo(unchanged);
        model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "MODIFY", "targetType", "QUALITY_BRIEF", "targetId", brief.id(), "baseVersion", brief.version(), "data", Map.of("metrics", Map.of("passRatePercent", 100)))))));
        var global = object(request("POST", "/api/ai/feedback", Map.of("projectId", project, "feedback", "改写报告统计", "idempotencyKey", UUID.randomUUID().toString())));
        assertThat(finished(project, global.get("jobId").toString()).status()).isEqualTo("FAILED");
        assertThat(assets.get(project, brief.id())).isEqualTo(unchanged);
    }

    @Test void globalAdditionPreviewsItsServerFactsAndOnlyCreatesTheBriefAfterSelection() throws Exception {
        settings.save(new ModelSettingsService.Input(model.url(), "quality-fixture", "fixture", 0.1, 30));
        String project = project().id();
        model.enqueue(candidate("待人工采纳的晨报", Map.of("content", "当前没有运行，请先执行计划。")));
        var response = object(request("POST", "/api/ai/feedback", Map.of("projectId", project, "feedback", "补充质量晨报", "idempotencyKey", UUID.randomUUID().toString())));
        Job job = finished(project, response.get("jobId").toString());
        assertThat(job.status()).as(job.error()).isEqualTo("SUCCEEDED");
        assertThat(assets.all(project).stream().filter(asset -> asset.type() == AssetType.QUALITY_BRIEF)).isEmpty();
        String changeId = job.result().get("changeSetId").toString();
        var preview = object(request("GET", "/api/ai/change-sets/" + changeId + "?projectId=" + project, null));
        var item = objects(preview.get("items")).getFirst();
        Map<String, Object> facts = map(map(item.get("after")).get("data"));
        assertThat(map(facts.get("metrics"))).containsEntry("runCount", 0).containsEntry("passRatePercent", null);
        var accepted = request("POST", "/api/ai/change-sets/" + changeId + "/apply", Map.of("projectId", project, "itemIds", List.of(item.get("id"))));
        assertThat(accepted.statusCode()).isEqualTo(200);
        var brief = objects(object(accepted).get("assets")).getFirst();
        assertThat(map(brief.get("data")).get("metrics")).isEqualTo(facts.get("metrics"));
    }

    private Asset create(String project, AssetType type, String parent, String name, Map<String, Object> data) { return assets.create(project, type, parent, name, data, "MANUAL"); }
    private String candidate(String name, Map<String, Object> data) { return json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "QUALITY_BRIEF", "localKey", "brief", "name", name, "data", data)))); }
    private Job generate(String project, String run) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>(Map.of("projectId", project, "type", "QUALITY_BRIEF", "instruction", "分析真实运行事实，保留人工裁决", "idempotencyKey", UUID.randomUUID().toString()));
        if (run != null) input.put("runId", run);
        var response = request("POST", "/api/ai/generate", input);
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(202);
        return finished(project, object(response).get("jobId").toString());
    }
    private Job finished(String project, String id) {
        await().atMost(Duration.ofSeconds(30)).until(() -> jobs.get(project, id).terminal());
        return jobs.get(project, id);
    }
}
