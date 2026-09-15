package com.aitest.analysis;

import com.aitest.ai.*;
import com.aitest.asset.*;
import com.aitest.execution.*;
import com.aitest.support.ModelFixtureServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SourceGroundedExecutionIT extends SourceGroundedSupport {
    @Autowired ExecutionCoordinator execution;
    @Autowired RunRepository runs;
    @Autowired AiRefinementService refinement;
    @Autowired AiGenerationService generation;
    @Autowired Environment configuration;

    @Test void aBlockedSqlDraftPreventsAllHttpSideEffectsAndManualBindingEnablesAnImmutableRun() throws Exception {
        String project = project().id(), table = "grounded_" + UUID.randomUUID().toString().replace("-", "");
        String source = snapshot(project, "CREATE TABLE " + table + "(id INT PRIMARY KEY,state VARCHAR(20));");
        String dbUrl = configuration.getProperty("spring.datasource.url").replace("/ai_test_platform_test", "/ai_test_business_test");
        String user = configuration.getProperty("spring.datasource.username"), password = configuration.getProperty("spring.datasource.password");
        AtomicInteger requests = new AtomicInteger(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/refund", exchange -> {
            requests.incrementAndGet(); entered.countDown();
            try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"state\":\"PAID\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); site.start();
        try (var connection = DriverManager.getConnection(dbUrl, user, password); var statement = connection.createStatement(); var model = new ModelFixtureServer()) {
            configure(model);
            statement.execute("CREATE TABLE " + table + "(id INT PRIMARY KEY,state VARCHAR(20))");
            try {
                statement.executeUpdate("INSERT INTO " + table + " VALUES(1,'PAID'),(2,'REFUNDED')");
                Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "受控 HTTP", Map.of("baseUrl", "http://127.0.0.1:" + site.getAddress().getPort()), "MANUAL");
                Asset api = assets.create(project, AssetType.API_CASE, null, "写入前核对", Map.of("path", "/refund", "sourceSnapshotId", source), "MANUAL");
                Asset sql = assets.create(project, AssetType.SQL_VALIDATION, api.id(), "离线 SQL", Map.of("sourceSnapshotId", source, "sql", "SELECT state FROM " + table + " WHERE id=1", "assertions", List.of(Map.of("type", "field", "field", "state", "expected", "PAID"))), "MANUAL");
                Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "混合计划", Map.of("sourceSnapshotId", source, "diagnoseFailures", false), "MANUAL");
                assets.create(project, AssetType.PLAN_ITEM, plan.id(), "HTTP 与后置 SQL", Map.of("targetId", api.id()), "MANUAL");
                var blocked = request("POST", "/api/projects/" + project + "/runs", Map.of("assetId", plan.id(), "environmentId", environment.id(), "idempotencyKey", "missing-source"));
                assertThat(blocked.statusCode()).isEqualTo(422);
                assertThat(new String(blocked.body(), StandardCharsets.UTF_8)).contains("DATABASE_SOURCE_REQUIRED");
                assertThat(requests).hasValue(0); assertThat(runs.list(project, 0, 10).get("total")).isEqualTo(0L);
                Asset database = assets.create(project, AssetType.DATABASE_SOURCE, null, "真实业务库", Map.of("jdbcUrl", dbUrl, "username", user, "password", password), "MANUAL");
                sql = assets.update(project, sql.id(), sql.version(), null, Map.of("databaseSourceId", database.id()), null, "MANUAL");
                var submitted = execution.submit(project, new ExecutionCoordinator.Request(plan.id(), environment.id(), null, "configured"));
                assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                model.enqueue(json.write(Map.of("data", Map.of("sql", "SELECT state FROM " + table + " WHERE id=2"))));
                var refinement = this.refinement.submit(new AiRefinementService.RefineRequest(project, sql.type(), sql.id(), sql.version(), null, "修改下次运行的订单", List.of("sql"), "REPLACE_ON_SUCCESS", "during-run"));
                assertThat(finished(project, refinement.jobId()).status()).as(finished(project, refinement.jobId()).error()).isEqualTo("SUCCEEDED");
                Asset updated = assets.get(project, sql.id());
                assertThat(Values.objects(Values.map(Values.map(updated.data().get("generationEvidence")).get("liveSchema")).get("tables"))).singleElement().satisfies(item -> assertThat(item.get("name")).isEqualTo(table));
                release.countDown();
                assertThat(finished(project, submitted.jobId()).status()).isEqualTo("SUCCEEDED");
                assertThat(runs.get(project, submitted.runId()).get("status")).isEqualTo("PASSED");
                assertThat(runs.definition(project, submitted.runId()).graph().get(sql.id()).data().get("sql")).isEqualTo(sql.data().get("sql"));
                assertThat(runs.definition(project, submitted.runId()).graph().sourceEvidence()).singleElement().satisfies(binding -> assertThat(binding).containsEntry("sourceSnapshotId", source));
                assertThat(requests).hasValue(1);
                // DDL is necessary but not sufficient: a later live schema change must fail EXPLAIN.
                statement.execute("ALTER TABLE " + table + " DROP COLUMN state");
                model.enqueue(json.write(Map.of("data", Map.of("sql", "SELECT state FROM " + table + " WHERE id=3"))));
                var rejected = this.refinement.submit(new AiRefinementService.RefineRequest(project, updated.type(), updated.id(), updated.version(), null, "以当前业务库复核", List.of("sql"), "REPLACE_ON_SUCCESS", "schema-changed"));
                assertThat(finished(project, rejected.jobId()).status()).isEqualTo("FAILED");
                assertThat(assets.get(project, updated.id())).isEqualTo(updated);
            } finally { statement.execute("DROP TABLE IF EXISTS " + table); }
        } finally { release.countDown(); site.stop(0); }
    }

    @Test void actualObservedLocatorsSurvivePersistenceAndLocalFeedbackAndExecuteAgainstTheRealPage() throws Exception {
        String project = project().id(), source = snapshot(project);
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", exchange -> { byte[] body = "<meta charset=utf-8><title>真实观察页</title><p id=live-only>ready</p><input aria-label='${metadataOnly}' />".getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/html;charset=utf-8"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); }); site.start();
        try (var model = new ModelFixtureServer()) {
            configure(model); String url = "http://127.0.0.1:" + site.getAddress().getPort();
            Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "实际网页", Map.of("baseUrl", url, "webUrl", url), "MANUAL");
            Asset requirement = assets.create(project, AssetType.REQUIREMENT, null, "PRD", Map.of("content", "页面显示 ready"), "MANUAL");
            model.enqueue(changes(Map.of("operation", "MODIFY", "targetType", "REQUIREMENT", "targetId", requirement.id(), "baseVersion", requirement.version(), "data", Map.of("analysis", Map.of("rules", List.of("显示 ready"))))));
            model.enqueue("featureCaseStart\n## 显示状态\n### 测试步骤与预期结果\n|步骤|预期|\n|---|---|\n|查看页面|ready|\nfeatureCaseEnd");
            model.enqueue(changes(add("UI_SCENARIO", "page", null, "实时页面", Map.of("baseUrl", url)), add("UI_STEP", "open", "@page", "访问实际页面", Map.of("action", "navigate", "url", url)), add("UI_STEP", "step", "@page", "可见状态", Map.of("action", "assertVisible", "selector", "#live-only"))));
            var response = request("POST", "/api/ai/pipelines", Map.of("projectId", project, "requirementIds", List.of(requirement.id()), "sourceSnapshotId", source, "environmentId", environment.id(), "execute", false, "idempotencyKey", "observed-ui"));
            assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
            var completed = terminalPipeline(project, object(response).get("pipelineId").toString());
            assertThat(Values.objects(completed.get("steps"))).filteredOn(step -> step.get("stage").equals("S5")).singleElement().satisfies(step -> assertThat(step.get("status")).as(step.toString()).isEqualTo("COMPLETED"));
            Asset page = assets.list(project, AssetType.UI_SCENARIO, null, "", 0, 10).items().getFirst();
            Asset step = assets.children(project, page.id()).getLast();
            assertThat(json.write(step.data().get("generationEvidence"))).contains("OBSERVED", "#live-only", "${metadataOnly}");
            model.enqueue("{\"data\":{\"action\":\"assertText\",\"expected\":\"ready\"}}");
            var changed = refinement.submit(new AiRefinementService.RefineRequest(project, step.type(), step.id(), step.version(), null, "核对状态文字", List.of("action", "expected"), "REPLACE_ON_SUCCESS", "observed-round"));
            assertThat(finished(project, changed.jobId()).status()).as(finished(project, changed.jobId()).error()).isEqualTo("SUCCEEDED");
            var run = execution.submit(project, new ExecutionCoordinator.Request(page.id(), environment.id(), null, "run-observed"));
            assertThat(finished(project, run.jobId()).status()).isEqualTo("SUCCEEDED");
            assertThat(runs.get(project, run.runId()).get("status")).as(runs.get(project, run.runId()).toString()).isEqualTo("PASSED");
            assertThat(model.requests.get(2)).contains("#live-only", "真实观察页", source);
        } finally { site.stop(0); }
    }
}
