package com.aitest.ai;
import org.junit.jupiter.api.Tag;

import com.aitest.ai.pipeline.PipelineService;
import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.support.ModelFixtureServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Tag("slow")
class PipelineEvidenceRecoveryIT extends ExchangeHttpTest {
    @Autowired ModelSettingsService settings;
    @Autowired PipelineService pipelines;

    @Test void generatedUiNavigationMustBeSupportedByPageOrRecordingEvidence() throws Exception {
        try (var model = model()) {
            String project = project().id();
            Asset requirement = requirement(project);
            Asset recording = assets.create(project, AssetType.UI_SCENARIO, null, "人工录制", Map.of("baseUrl", "http://evidence.local/"), "IMPORT");
            assets.create(project, AssetType.UI_STEP, recording.id(), "打开退款页", Map.of("action", "navigate", "url", "/refund"), "IMPORT");
            model.enqueue(analysis(requirement)); model.enqueue(functional());
            model.enqueue(json.write(Map.of("changes", List.of(
                    add("UI_SCENARIO", "web", null, "无证据场景", Map.of("baseUrl", "http://unobserved.invalid/")),
                    add("UI_STEP", "go", "@web", "无证据跳转", Map.of("action", "navigate", "url", "/secret-refund"))))));
            String id = submit(project, requirement.id(), Map.of("uiEvidenceIds", List.of(recording.id()), "execute", false));
            var result = terminal(project, id);
            assertThat(result.get("status")).isEqualTo("FAILED");
            assertThat(result.get("error").toString()).contains("页面地址", "证据");
            assertThat(assets.list(project, AssetType.UI_SCENARIO, null, "", 0, 100).items()).containsExactly(recording);
        }
    }

    @Test void supplyingAnEnvironmentResumesTheBlockedRunWithoutRegeneratingCases() throws Exception {
        try (var model = model()) {
            String project = project().id(); Asset requirement = requirement(project);
            model.enqueue(analysis(requirement)); model.enqueue(functional());
            String id = submit(project, requirement.id(), Map.of("execute", true));
            var before = terminal(project, id);
            assertThat(objects(before.get("steps")).stream().filter(step -> step.get("stage").equals("S6"))).singleElement().satisfies(step -> assertThat(step.get("status")).isEqualTo("BLOCKED"));
            Asset saved = assets.list(project, AssetType.FUNCTIONAL_CASE, null, "", 0, 10).items().getFirst();
            assets.create(project, AssetType.ENVIRONMENT, null, "另一环境", Map.of("baseUrl", "http://unused.local"), "MANUAL");
            Asset chosen = assets.create(project, AssetType.ENVIRONMENT, null, "本次环境", Map.of("baseUrl", "http://manual-run.local"), "MANUAL");
            var response = request("POST", "/api/ai/pipelines/" + id + "/resume", Map.of("projectId", project, "stage", "S6", "idempotencyKey", "configure-run", "environmentId", chosen.id(), "execute", true));
            assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
            var after = terminal(project, id);
            assertThat(after.get("runId")).isNotNull();
            assertThat(map(after.get("config"))).containsEntry("environmentId", chosen.id());
            assertThat(objects(after.get("steps")).stream().filter(step -> step.get("stage").equals("S6"))).singleElement().satisfies(step -> assertThat(step.get("status")).isEqualTo("COMPLETED"));
            assertThat(assets.get(project, saved.id())).isEqualTo(saved);
            assertThat(model.requests).hasSize(2);
        }
    }

    @Test void failedDiagnosisCanResumeWithoutReplayingTheHttpRun() throws Exception {
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        site.createContext("/failure", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(500, 0); exchange.getResponseBody().write("actual backend error".getBytes()); exchange.close(); });
        site.start();
        try (var model = model()) {
            String project = project().id(); Asset requirement = requirement(project);
            Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "接口运行环境", Map.of("baseUrl", "http://127.0.0.1:" + site.getAddress().getPort()), "MANUAL");
            Asset definition = assets.create(project, AssetType.API_DEFINITION, null, "退款", Map.of("method", "GET", "path", "/failure"), "IMPORT");
            model.enqueue(analysis(requirement)); model.enqueue(functional());
            model.enqueue(json.write(Map.of("changes", List.of(add("API_CASE", "api", null, "退款失败", Map.of("apiDefinitionId", definition.id(), "method", "GET", "path", "/failure", "assertions", List.of(Map.of("type", "status", "expected", 200))))))));
            model.enqueue("invalid diagnosis"); model.enqueue("invalid repair");
            String id = submit(project, requirement.id(), Map.of("environmentId", environment.id(), "apiDefinitionIds", List.of(definition.id()), "execute", true));
            var stopped = terminal(project, id);
            assertThat(stopped.get("status")).isEqualTo("FAILED");
            assertThat(stopped.get("runId")).isNotNull(); assertThat(calls).hasValue(1);
            Asset plan = assets.list(project, AssetType.TEST_PLAN, null, "", 0, 10).items().getFirst();
            model.enqueue(json.write(Map.of("title", "退款接口返回错误", "severity", "MAJOR", "reproduceSteps", "请求退款接口", "expectedResult", "状态码 200", "actualResult", "状态码 500", "rootCauseAnalysis", "推测退款处理异常", "fixSuggestion", "检查服务端日志")));
            assertThat(request("POST", "/api/ai/pipelines/" + id + "/resume", Map.of("projectId", project, "stage", "S6", "idempotencyKey", "diagnose-only")).statusCode()).isEqualTo(200);
            var resumed = terminal(project, id);
            assertThat(resumed.get("status")).as(resumed.toString()).isEqualTo("COMPLETED_WITH_GAPS");
            assertThat(resumed.get("runId")).isEqualTo(stopped.get("runId")); assertThat(calls).hasValue(1);
            assertThat(assets.get(project, plan.id())).isEqualTo(plan);
            assertThat(assets.list(project, AssetType.BUG, null, "", 0, 10).items()).hasSize(1);
        } finally { site.stop(0); }
    }

    private ModelFixtureServer model() throws Exception { var model = new ModelFixtureServer(); settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30)); return model; }
    private Asset requirement(String project) { return assets.create(project, AssetType.REQUIREMENT, null, "退款需求", Map.of("content", "# 退款\n退款需校验订单状态。"), "MANUAL"); }
    private String analysis(Asset asset) { return json.write(Map.of("changes", List.of(Map.of("operation", "MODIFY", "targetType", "REQUIREMENT", "targetId", asset.id(), "baseVersion", asset.version(), "data", Map.of("analysis", Map.of("rules", List.of("退款需校验订单状态"))))))); }
    private String functional() { return "featureCaseStart\n## 退款校验\n### 前置条件\n订单已支付\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 请求退款 | 成功 |\n### 备注\nP1\nfeatureCaseEnd"; }
    private Map<String, Object> add(String type, String key, String parent, String name, Map<String, Object> data) { Map<String, Object> proposal = new LinkedHashMap<>(Map.of("operation", "ADD", "targetType", type, "localKey", key, "name", name, "data", data)); proposal.put("parentId", parent); return proposal; }
    private String submit(String project, String requirement, Map<String, Object> options) throws Exception { var input = new LinkedHashMap<>(options); input.putAll(Map.of("projectId", project, "requirementIds", List.of(requirement), "idempotencyKey", "run")); var response = request("POST", "/api/ai/pipelines", input); assertThat(response.statusCode()).isEqualTo(200); return object(response).get("pipelineId").toString(); }
    private Map<String, Object> terminal(String project, String id) { await().atMost(Duration.ofSeconds(45)).until(() -> Set.of("COMPLETED", "COMPLETED_WITH_GAPS", "FAILED", "CANCELLED", "INTERRUPTED").contains(pipelines.get(project, id).get("status"))); return pipelines.get(project, id); }
}
