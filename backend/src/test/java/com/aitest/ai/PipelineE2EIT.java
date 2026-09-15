package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.execution.Values;
import com.aitest.support.ModelFixtureServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class PipelineE2EIT extends ExchangeHttpTest {
    @Autowired ModelSettingsService settings;
    @Autowired Environment configuration;

    @Test void twoDocumentInputsProduceDurableAssetsAndExecuteRealHttpSqlAndBrowserFailures() throws Exception {
        String project = project().id();
        String dbUrl = configuration.getProperty("spring.datasource.url").replace("/ai_test_platform_test", "/ai_test_business_test");
        String user = configuration.getProperty("spring.datasource.username"), password = configuration.getProperty("spring.datasource.password");
        String table = "pipeline_" + UUID.randomUUID().toString().replace("-", "");
        try (var database = DriverManager.getConnection(dbUrl, user, password); var ddl = database.createStatement(); var model = new ModelFixtureServer()) {
            ddl.execute("CREATE TABLE " + table + "(id INT PRIMARY KEY,state VARCHAR(20))");
            HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            site.createContext("/", request -> {
                String body; String media = "application/json";
                if (request.getRequestURI().getPath().equals("/login")) body = "{\"token\":\"pipeline-test-token\"}";
                else if (request.getRequestURI().getPath().equals("/orders")) {
                    try (var connection = DriverManager.getConnection(dbUrl, user, password); var sql = connection.createStatement()) { sql.executeUpdate("INSERT INTO " + table + " VALUES(1,'PAID')"); }
                    catch (Exception error) { request.sendResponseHeaders(500, -1); request.close(); return; }
                    body = "{\"orderId\":1,\"state\":\"PAID\"}";
                } else { body = "<meta charset=utf-8><title>退款测试页</title><h1>订单退款</h1><p id=refund-state>处理中</p>"; media = "text/html;charset=utf-8"; }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8); request.getResponseHeaders().add("Content-Type", media); request.sendResponseHeaders(200, bytes.length); request.getResponseBody().write(bytes); request.close();
            }); site.start();
            try {
                settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30));
                String url = "http://127.0.0.1:" + site.getAddress().getPort();
                Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "流水线验收环境", Map.of("baseUrl", url, "webUrl", url, "autoRunGenerated", true), "MANUAL");
                Asset source = assets.create(project, AssetType.DATABASE_SOURCE, null, "真实订单库", Map.of("environmentId", environment.id(), "jdbcUrl", dbUrl, "username", user, "password", password), "MANUAL");
                Asset requirement = assets.create(project, AssetType.REQUIREMENT, null, "退款 PRD", Map.of("content", "# 退款\n登录后创建已支付订单，数据库 state 为 PAID。退款成功时页面应显示退款成功。"), "IMPORT");
                Asset login = assets.create(project, AssetType.API_DEFINITION, null, "登录接口", Map.of("method", "POST", "path", "/login"), "IMPORT");
                Asset orders = assets.create(project, AssetType.API_DEFINITION, null, "订单接口", Map.of("method", "POST", "path", "/orders"), "IMPORT");
                Asset keep = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "人工保留资产", Map.of("remark", "不要修改"), "MANUAL");
                keep = assets.update(project, keep.id(), keep.version(), null, Map.of(), true, "MANUAL");
                model.enqueue(analysis(requirement)); model.enqueue(functional());
                model.enqueue(changes(List.of(
                        add("API_CASE", "login", null, "登录生成用例", Map.of("apiDefinitionId", login.id(), "method", "POST", "path", "/login", "extractors", List.of(Map.of("variable", "token", "jsonpath", "$.token")), "assertions", List.of(Map.of("type", "status", "expected", 200)))),
                        add("API_CASE", "order", null, "订单生成用例", Map.of("apiDefinitionId", orders.id(), "method", "POST", "path", "/orders", "headers", Map.of("Authorization", "Bearer ${token}"), "extractors", List.of(Map.of("variable", "orderId", "jsonpath", "$.orderId")), "assertions", List.of(Map.of("type", "status", "expected", 200)))),
                        add("SCENARIO", "chain", null, "登录到下单", Map.of()),
                        add("SCENARIO_STEP", "login-step", "@chain", "登录", Map.of("targetId", "@login")),
                        add("SCENARIO_STEP", "order-step", "@chain", "下单", Map.of("targetId", "@order")))));
                Map<String, Object> input = Map.of("projectId", project, "requirementIds", List.of(requirement.id()), "apiDefinitionIds", List.of(login.id(), orders.id()), "idempotencyKey", "full");
                var response = request("POST", "/api/ai/pipelines", input);
                assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
                String pipeline = object(response).get("pipelineId").toString();
                await().atMost(Duration.ofSeconds(35)).until(() -> assets.list(project, AssetType.API_CASE, null, "订单生成用例", 0, 10).total() == 1);
                String orderCase = assets.list(project, AssetType.API_CASE, null, "订单生成用例", 0, 10).items().getFirst().id();
                model.enqueue("{\"changes\":[],\"reason\":\"登录接口没有业务写入，无需 SQL 校验\"}");
                model.enqueue(changes(List.of(add("SQL_VALIDATION", "sql", orderCase, "订单实际入库状态", Map.of("databaseSourceId", source.id(), "sql", "SELECT state FROM " + table + " WHERE id=${orderId}", "assertions", List.of(Map.of("type", "field", "field", "state", "expected", "PAID")))))));
                model.enqueue(changes(List.of(add("UI_SCENARIO", "web", null, "退款页面验收", Map.of("baseUrl", url)), add("UI_STEP", "open", "@web", "访问页面", Map.of("action", "navigate", "url", "/")), add("UI_STEP", "assert", "@web", "核对退款结果", Map.of("action", "assertText", "selector", "#refund-state", "expected", "退款成功", "timeoutMs", 500)))));
                model.enqueue(json.write(Map.of("title", "[退款] 页面未显示成功结果", "severity", "MAJOR", "reproduceSteps", "查看退款页面", "expectedResult", "退款成功", "actualResult", "处理中", "rootCauseAnalysis", "推测页面未更新，需要核对退款接口。", "fixSuggestion", "核对页面轮询与状态映射。")));
                Map<String, Object> completed = terminal(project, pipeline);
                assertThat(completed.get("status")).withFailMessage("%s", completed).isEqualTo("COMPLETED");
                assertThat(objects(completed.get("steps"))).hasSize(6).allSatisfy(step -> assertThat(step.get("status")).isEqualTo("COMPLETED"));
                String runId = Values.text(completed, "runId", ""); assertThat(runId).isNotBlank();
                var run = object(request("GET", "/api/projects/" + project + "/runs/" + runId, null));
                assertThat(map(map(run.get("summary")).get("counts"))).containsEntry("PASSED", 1).containsEntry("FAILED", 1).containsEntry("MANUAL_PENDING", 1);
                assertThat(objects(run.get("items")).stream().flatMap(item -> objects(item.get("steps")).stream()).filter(step -> step.get("engine").equals("SQL_VALIDATION"))).singleElement().satisfies(step -> assertThat(step.get("status")).isEqualTo("PASSED"));
                assertThat(assets.list(project, AssetType.BUG, null, "", 0, 100).items()).hasSize(1);
                assertThat(assets.get(project, keep.id())).isEqualTo(keep);
                int count = assets.all(project).size();
                assertThat(object(request("POST", "/api/ai/pipelines", input)).get("pipelineId")).isEqualTo(pipeline);
                assertThat(assets.all(project)).hasSize(count);
                assertThat(model.requests.stream().anyMatch(text -> text.contains("#refund-state") && text.contains("退款测试页"))).isTrue();
            } finally { site.stop(0); ddl.execute("DROP TABLE " + table); }
        }
    }

    @Test void missingDatabaseAndPageEvidenceRemainExplicitGapsWithoutInventedAssets() throws Exception {
        String project = project().id();
        try (var model = new ModelFixtureServer()) {
            settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30));
            Asset requirement = assets.create(project, AssetType.REQUIREMENT, null, "仅有需求", Map.of("content", "# 退款\n退款前必须检查订单状态。"), "MANUAL");
            model.enqueue(analysis(requirement)); model.enqueue(functional());
            var response = request("POST", "/api/ai/pipelines", Map.of("projectId", project, "requirementIds", List.of(requirement.id()), "apiDefinitionIds", List.of(), "execute", false, "idempotencyKey", "gaps"));
            assertThat(response.statusCode()).isEqualTo(200);
            var completed = terminal(project, object(response).get("pipelineId").toString());
            assertThat(completed.get("status")).isEqualTo("COMPLETED_WITH_GAPS");
            assertThat(objects(completed.get("steps")).stream().filter(step -> Set.of("S3", "S4", "S5").contains(step.get("stage")))).allSatisfy(step -> assertThat(step.get("status")).isEqualTo("BLOCKED"));
            assertThat(assets.list(project, AssetType.SQL_VALIDATION, null, "", 0, 100).items()).isEmpty();
            assertThat(assets.list(project, AssetType.UI_SCENARIO, null, "", 0, 100).items()).isEmpty();
            assertThat(assets.list(project, AssetType.FUNCTIONAL_CASE, null, "", 0, 100).items()).hasSize(1);
            assertThat(model.requests).hasSize(2);
        }
    }
    private Map<String, Object> terminal(String project, String id) {
        final Map<String, Object>[] result = new Map[]{Map.of()};
        await().atMost(Duration.ofSeconds(100)).until(() -> { result[0] = object(request("GET", "/api/ai/pipelines/" + id + "?projectId=" + project, null)); return Set.of("COMPLETED", "COMPLETED_WITH_GAPS", "FAILED", "CANCELLED", "INTERRUPTED").contains(result[0].get("status")); });
        return result[0];
    }
    private String analysis(Asset requirement) { return changes(List.of(Map.of("operation", "MODIFY", "targetType", "REQUIREMENT", "targetId", requirement.id(), "baseVersion", requirement.version(), "data", Map.of("analysis", Map.of("requirements", List.of("退款应核对原支付状态"), "blindSpots", List.of("重复退款需要幂等保护")))))); }
    private String functional() { return "featureCaseStart\n## 已支付订单退款\n### 前置条件\n订单已支付\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 提交退款 | 页面显示退款成功 |\n### 备注\nP1\nfeatureCaseEnd"; }
    private Map<String, Object> add(String type, String key, String parent, String name, Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("operation", "ADD"); result.put("targetType", type); result.put("localKey", key); result.put("parentId", parent); result.put("name", name); result.put("data", data); return result;
    }
    private String changes(List<Map<String, Object>> changes) { return json.write(Map.of("changes", changes)); }
}
