package com.aitest.analysis;

import com.aitest.asset.*;
import com.aitest.execution.Values;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class SourceGroundedPipelineIT extends SourceGroundedSupport {
    @Test void fixedSourceFeedsAllStagesAndOfflineSqlAndStaticUiRemainEditableBlockedDrafts() throws Exception {
        String project = project().id(), source = snapshot(project);
        Asset requirement = assets.create(project, AssetType.REQUIREMENT, null, "退款 PRD", Map.of("content", "退款成功后订单状态变为 REFUNDED。"), "MANUAL");
        Asset definition = assets.create(project, AssetType.API_DEFINITION, null, "退款接口", Map.of("method", "POST", "path", "/refund"), "MANUAL");
        Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "待绑定环境", Map.of("baseUrl", "http://127.0.0.1:1"), "MANUAL");
        try (var model = new ModelFixtureServer()) {
            configure(model);
            model.enqueue(changes(Map.of("operation", "MODIFY", "targetType", "REQUIREMENT", "targetId", requirement.id(), "baseVersion", requirement.version(), "data", Map.of("analysis", Map.of("blindSpots", List.of("amount > 5000 需要人工审核"))))));
            model.enqueue("featureCaseStart\n## 大额退款人工审核边界\n### 前置条件\namount 大于 5000\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 提交 5001 | 返回人工审核提示 |\n### 备注\nP1，依据 Refund.java\nfeatureCaseEnd");
            model.enqueue(changes(add("API_CASE", "refund", null, "退款用例", Map.of("apiDefinitionId", definition.id(), "method", "POST", "path", "/refund"))));
            var input = Map.of("projectId", project, "requirementIds", List.of(requirement.id()), "apiDefinitionIds", List.of(definition.id()), "sourceSnapshotId", source,
                    "environmentId", environment.id(), "execute", true, "idempotencyKey", "grounded");
            var response = request("POST", "/api/ai/pipelines", input);
            assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
            String id = object(response).get("pipelineId").toString();
            assertThat(Values.map(pipeline(project, id).get("config"))).containsEntry("sourceSnapshotId", source);
            await().atMost(Duration.ofSeconds(30)).until(() -> assets.list(project, AssetType.API_CASE, null, "", 0, 10).total() == 1 || "FAILED".equals(pipeline(project, id).get("status")));
            assertThat(assets.list(project, AssetType.API_CASE, null, "", 0, 10).total()).as("stage=%s error=%s", pipeline(project, id).get("currentStage"), pipeline(project, id).get("error")).isEqualTo(1);
            Asset api = assets.list(project, AssetType.API_CASE, null, "", 0, 10).items().getFirst();
            model.enqueue(changes(add("SQL_VALIDATION", "sql", api.id(), "订单退款状态", Map.of("sql", "SELECT state FROM orders WHERE id=${orderId}", "parameters", Map.of("orderId", 1),
                    "assertions", List.of(Map.of("type", "field", "field", "state", "expected", "REFUNDED"))))));
            model.enqueue(changes(add("UI_SCENARIO", "web", null, "退款页面", Map.of()), add("UI_STEP", "submit", "@web", "点击退款", Map.of("action", "click", "selector", "testId=refund-submit"))));
            var complete = terminalPipeline(project, id);
            assertThat(complete.get("status")).as(complete.toString()).isEqualTo("COMPLETED_WITH_GAPS");
            assertThat(Values.objects(complete.get("steps"))).filteredOn(step -> step.get("stage").equals("S6")).singleElement().satisfies(step -> assertThat(step.get("status")).isEqualTo("BLOCKED"));
            Asset sql = assets.list(project, AssetType.SQL_VALIDATION, null, "", 0, 10).items().getFirst();
            assertThat(sql.data()).containsEntry("sourceSnapshotId", source).containsEntry("databaseSourceId", "");
            assertThat(Values.map(sql.data().get("generationEvidence"))).containsEntry("executionState", "BLOCKED");
            Asset step = assets.all(project).stream().filter(a -> a.type() == AssetType.UI_STEP).findFirst().orElseThrow();
            assertThat(json.write(step.data().get("generationEvidence"))).contains("Refund.vue", "requiresRuntimeVerification", "STATIC");
            assertThat(complete.get("runId")).isNull();
            assertThat(model.requests).hasSize(5).allSatisfy(call -> assertThat(call).contains("sourceEvidence", source));
            assertThat(model.requests.getFirst()).contains("amount > 5000").doesNotContain("LaterSource", "later-only");
            var prior = assets.all(project);
            assertThat(object(request("POST", "/api/ai/pipelines", input))).containsEntry("pipelineId", id);
            assertThat(assets.all(project)).isEqualTo(prior);
            // The durable dependency result reports missing configuration before any executable run exists.
            assertThat(Values.map(Values.objects(complete.get("steps")).getLast().get("output")).get("execution")).isEqualTo("DEPENDENCY_UPDATE_REQUIRED");
            assertThat(json.write(Values.map(Values.objects(complete.get("steps")).getLast().get("output")).get("validation"))).contains("DATABASE_SOURCE_REQUIRED", "WEB_BASE_URL_REQUIRED");
        }
    }

    @Test void sourceBindingsRejectForeignSnapshotsBeforeCreatingPipelineJobs() throws Exception {
        String foreign = snapshot(project().id()), project = project().id();
        Asset requirement = assets.create(project, AssetType.REQUIREMENT, null, "PRD", Map.of("content", "测试"), "MANUAL");
        var response = request("POST", "/api/ai/pipelines", Map.of("projectId", project, "requirementIds", List.of(requirement.id()), "sourceSnapshotId", foreign, "idempotencyKey", "foreign"));
        assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(404);
        assertThat((List<?>) json.tree(new String(request("GET", "/api/ai/pipelines?projectId=" + project, null).body(), java.nio.charset.StandardCharsets.UTF_8))).isEmpty();
    }
}
