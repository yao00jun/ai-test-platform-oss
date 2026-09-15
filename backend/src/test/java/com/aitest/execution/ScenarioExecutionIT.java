package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.common.JsonCodec;
import com.aitest.job.JobService;
import com.aitest.support.MySqlIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ScenarioExecutionIT extends MySqlIntegrationTest {
    @Autowired AssetService assets; @Autowired ExecutionCoordinator coordinator; @Autowired RunRepository runs; @Autowired JobService jobs;
    @Autowired Environment configuration; @Autowired JsonCodec json;
    @Test void twoDdtRowsRunLoginOrderAndRealSqlWithIsolatedTokens() throws Exception {
        String project = assets.createProject("Scenario DDT " + UUID.randomUUID(), Map.of()).id();
        String url = configuration.getProperty("spring.datasource.url").replace("/ai_test_platform_test", "/ai_test_business_test");
        String user = configuration.getProperty("spring.datasource.username"), password = configuration.getProperty("spring.datasource.password");
        String table = "orders_" + UUID.randomUUID().toString().replace("-", "");
        AtomicInteger logins = new AtomicInteger(), orders = new AtomicInteger(); Set<String> tokens = ConcurrentHashMap.newKeySet();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/login", request -> {
            request.getRequestBody().readAllBytes(); byte[] body = ("{\"token\":\"token-" + logins.incrementAndGet() + "\"}").getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, body.length); request.getResponseBody().write(body); request.close();
        });
        server.createContext("/orders", request -> {
            try {
                Map<String, Object> body = json.map(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String token = request.getRequestHeaders().getFirst("Authorization"); tokens.add(token);
                int id = orders.incrementAndGet();
                try (var connection = DriverManager.getConnection(url, user, password); var insert = connection.prepareStatement("INSERT INTO " + table + "(id,qty,state) VALUES(?,?,'PAID')")) {
                    insert.setInt(1, id); insert.setObject(2, body.get("qty")); insert.executeUpdate();
                }
                byte[] response = ("{\"id\":" + id + "}").getBytes(StandardCharsets.UTF_8); request.sendResponseHeaders(201, response.length); request.getResponseBody().write(response);
            } catch (Exception failure) { request.sendResponseHeaders(500, -1); }
            finally { request.close(); }
        }); server.start();
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + "(id INT PRIMARY KEY,qty INT,state VARCHAR(30))");
            Asset env = assets.create(project, AssetType.ENVIRONMENT, null, "Local", Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort()), "MANUAL");
            Asset database = assets.create(project, AssetType.DATABASE_SOURCE, null, "DB", Map.of("environmentId", env.id(), "jdbcUrl", url, "username", user, "password", password), "MANUAL");
            Asset login = assets.create(project, AssetType.API_CASE, null, "Login", Map.of("path", "/login", "method", "POST", "extractors", List.of(Map.of("variable", "token", "jsonpath", "$.token"))), "MANUAL");
            Asset order = assets.create(project, AssetType.API_CASE, null, "Order", Map.of("path", "/orders", "method", "POST", "bodyType", "JSON", "body", Map.of("qty", "${qty}"), "headers", Map.of("Authorization", "Bearer ${token}"), "extractors", List.of(Map.of("variable", "orderId", "jsonpath", "$.id")), "assertions", List.of(Map.of("type", "status_code", "expected", 201))), "MANUAL");
            Asset check = assets.create(project, AssetType.SQL_VALIDATION, null, "Check order", Map.of("databaseSourceId", database.id(), "sql", "SELECT qty,state FROM " + table + " WHERE id=:orderId", "assertions", List.of(Map.of("field", "state", "expected", "PAID"), Map.of("field", "qty", "expected", "${qty}"))), "MANUAL");
            Asset dataset = assets.create(project, AssetType.DATASET, null, "Rows", Map.of("columns", List.of("qty"), "rows", List.of(Map.of("qty", 1), Map.of("qty", 2))), "MANUAL");
            Asset scenario = assets.create(project, AssetType.SCENARIO, null, "Login-order-SQL", Map.of("datasetId", dataset.id()), "MANUAL");
            assets.create(project, AssetType.SCENARIO_STEP, scenario.id(), "Login", Map.of("stepType", "HTTP", "targetId", login.id()), "MANUAL");
            assets.create(project, AssetType.SCENARIO_STEP, scenario.id(), "Order", Map.of("stepType", "HTTP", "targetId", order.id()), "MANUAL");
            assets.create(project, AssetType.SCENARIO_STEP, scenario.id(), "SQL", Map.of("stepType", "SQL", "targetId", check.id()), "MANUAL");
            var input = new ExecutionCoordinator.Request(scenario.id(), env.id(), null, "ddt");
            var submitted = coordinator.submit(project, input); assertThat(coordinator.submit(project, input)).isEqualTo(submitted);
            await().atMost(Duration.ofSeconds(30)).until(() -> jobs.get(project, submitted.jobId()).terminal());
            var run = runs.get(project, submitted.runId());
            assertThat(run.get("status")).withFailMessage("%s", json.write(run)).isEqualTo("PASSED");
            assertThat(Values.map(run.get("summary"))).containsEntry("total", 2L).containsEntry("dataRows", 2L);
            assertThat(Values.objects(run.get("items"))).allSatisfy(item -> assertThat(Values.objects(item.get("steps"))).hasSize(3));
            assertThat(tokens).hasSize(2); assertThat(logins).hasValue(2); assertThat(orders).hasValue(2);
            String evidence = json.write(run); assertThat(evidence).doesNotContain("Bearer token-1", password);
            statement.execute("DROP TABLE " + table);
        } finally { server.stop(0); }
    }

    @Test void runningRequestUsesFrozenAssetAndManualCasesRemainPendingUntilRecorded() throws Exception {
        String project = assets.createProject("Snapshot " + UUID.randomUUID(), Map.of()).id();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/original", request -> {
            entered.countDown(); try { release.await(10, TimeUnit.SECONDS); request.sendResponseHeaders(204, -1); } catch (Exception ignored) { } finally { request.close(); }
        }); server.start();
        try {
            Asset env = assets.create(project, AssetType.ENVIRONMENT, null, "Local", Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort()), "MANUAL");
            Asset api = assets.create(project, AssetType.API_CASE, null, "Frozen API", Map.of("path", "/original"), "MANUAL");
            var submitted = coordinator.submit(project, new ExecutionCoordinator.Request(api.id(), env.id(), null, "snapshot"));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assets.update(project, api.id(), api.version(), null, Map.of("path", "/not-present"), null, "MANUAL"); release.countDown();
            await().atMost(Duration.ofSeconds(15)).until(() -> jobs.get(project, submitted.jobId()).terminal());
            assertThat(runs.get(project, submitted.runId()).get("status")).isEqualTo("PASSED");
            assertThat(Values.map(Values.map(Values.map(runs.get(project, submitted.runId()).get("snapshot")).get("assets")).get(api.id())).get("version")).isEqualTo("1");
            Asset manual = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "Manual", Map.of(), "MANUAL");
            var pending = coordinator.submit(project, new ExecutionCoordinator.Request(manual.id(), null, null, "manual"));
            await().atMost(Duration.ofSeconds(15)).until(() -> jobs.get(project, pending.jobId()).terminal());
            var run = runs.get(project, pending.runId()); assertThat(run.get("status")).isEqualTo("MANUAL_PENDING");
            var item = Values.objects(run.get("items")).getFirst();
            assertThat(runs.manual(project, pending.runId(), item.get("id").toString(), "1", "PASSED", "人工验证成功").get("status")).isEqualTo("PASSED");
            assertThatThrownBy(() -> runs.manual(project, pending.runId(), item.get("id").toString(), "1", "FAILED", "stale")).isInstanceOf(com.aitest.common.Problem.class);
        } finally { release.countDown(); server.stop(0); }
    }
}
