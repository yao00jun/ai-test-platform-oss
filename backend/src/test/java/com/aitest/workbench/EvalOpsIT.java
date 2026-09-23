package com.aitest.workbench;

import com.aitest.ai.*;
import com.aitest.asset.*;
import com.aitest.execution.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.*;
import com.aitest.support.ModelFixtureServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class EvalOpsIT extends ExchangeHttpTest {
    @Autowired AiGenerationService generation;
    @Autowired ModelSettingsService settings;
    @Autowired JobService jobs;
    @Autowired ExecutionCoordinator executions;

    @Test void emptyProjectReportsNoSampleInsteadOfPerfectRatesOrInventedUsage() throws Exception {
        String project = project().id(); var data = metrics(project);
        assertThat(Values.map(data.get("usage"))).containsEntry("invocations", 0).containsEntry("promptTokens", null).containsEntry("completionTokens", null).containsEntry("totalTokens", null);
        assertThat(Values.map(data.get("generation"))).containsEntry("finished", 0).containsEntry("validRatePercent", null);
        assertThat(Values.map(data.get("execution"))).containsEntry("executed", 0).containsEntry("passRatePercent", null);
        assertThat(Values.map(data.get("rca"))).containsEntry("evaluatedVersions", 0).containsEntry("correctRatePercent", null);
        assertThat(Values.objects(data.get("byModel"))).isEmpty();
    }

    @Test void realUsageAndGenerationDenominatorsDifferFromTransportSuccessAndFrozenAiExecution() throws Exception {
        String project = project().id(); String modelName = "eval-" + UUID.randomUUID();
        var business = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        business.createContext("/", exchange -> { byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/bad") ? 500 : 200, body.length); exchange.getResponseBody().write(body); exchange.close(); }); business.start();
        try (var model = new ModelFixtureServer()) {
            configure(model, modelName);
            String address = "http://127.0.0.1:" + business.getAddress().getPort();
            Asset good = assets.create(project, AssetType.API_DEFINITION, null, "成功契约", Map.of("path", address + "/good"), "MANUAL");
            Asset bad = assets.create(project, AssetType.API_DEFINITION, null, "失败契约", Map.of("path", address + "/bad"), "MANUAL");
            model.enqueueWithUsage(json.write(Map.of("changes", List.of(proposal("通过项", good), proposal("失败项", bad)))), Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18));
            var first = generation.submit(new AiGenerationService.Request(project, AssetType.API_CASE, null, "按真实契约生成两个用例", List.of(good.id(), bad.id()), "measured", null, null));
            assertThat(terminal(project, first.jobId()).status()).isEqualTo("SUCCEEDED");
            model.enqueue("broken JSON"); model.enqueue("still broken JSON");
            var second = generation.submit(new AiGenerationService.Request(project, AssetType.API_CASE, null, "另一次错误生成", List.of(good.id()), "invalid", null, null));
            assertThat(terminal(project, second.jobId()).status()).isEqualTo("FAILED");
            List<Asset> aiCases = assets.list(project, AssetType.API_CASE, null, "", 0, 100).items(); assertThat(aiCases).hasSize(2);
            Asset manual = assets.create(project, AssetType.API_CASE, null, "人工通过项", Map.of("path", address + "/good"), "MANUAL");
            Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "真实统计计划", Map.of("diagnoseFailures", false), "MANUAL");
            for (Asset item : List.of(aiCases.get(0), aiCases.get(1), manual)) assets.create(project, AssetType.PLAN_ITEM, plan.id(), item.name(), Map.of("targetId", item.id()), "MANUAL");
            var run = executions.submit(project, new ExecutionCoordinator.Request(plan.id(), null, null, "real-run"));
            assertThat(terminal(project, run.jobId()).status()).isEqualTo("SUCCEEDED");
            Asset edited = aiCases.getFirst(); assets.update(project, edited.id(), edited.version(), "执行后人工改名", Map.of(), null, "MANUAL");
            var data = metrics(project); var usage = Values.map(data.get("usage"));
            assertThat(usage).containsEntry("invocations", 3).containsEntry("succeeded", 3).containsEntry("httpAttempts", 3).containsEntry("usageReported", 1).containsEntry("usageMissing", 2).containsEntry("promptTokens", 11).containsEntry("completionTokens", 7).containsEntry("totalTokens", 18).containsEntry("pricedInvocations", 0);
            assertThat(Values.objects(usage.get("costByCurrency"))).isEmpty();
            assertThat(Values.map(data.get("generation"))).containsEntry("attempts", 2).containsEntry("finished", 2).containsEntry("valid", 1).containsEntry("invalid", 1);
            assertThat(((Number) Values.map(data.get("generation")).get("validRatePercent")).doubleValue()).isEqualTo(50);
            assertThat(Values.map(data.get("execution"))).containsEntry("aiItems", 2).containsEntry("executed", 2).containsEntry("passed", 1);
            assertThat(((Number) Values.map(data.get("execution")).get("passRatePercent")).doubleValue()).isEqualTo(50);
            assertThat(Values.objects(data.get("byModel"))).singleElement().satisfies(group -> assertThat(group).containsEntry("modelName", modelName).containsEntry("invocations", 3));
            assertThat(Values.map(metrics(project().id()).get("usage"))).containsEntry("invocations", 0);
        } finally { business.stop(0); }
    }

    @Test void configuredPricesAreCapturedPerCallAndChangingPricesDoesNotRewriteHistory() throws Exception {
        String project = project().id(), name = "price-" + UUID.randomUUID();
        var saved = request("PUT", "/api/settings/model/pricing", Map.of("modelName", name, "baseVersion", "0", "enabled", true, "currency", "CNY", "inputPerMillion", "2", "outputPerMillion", "3"));
        assertThat(saved.statusCode()).isEqualTo(200);
        try (var model = new ModelFixtureServer()) {
            configure(model, name);
            model.enqueueWithUsage(json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "MODULE", "localKey", "module", "name", "已计费生成", "data", Map.of())))), Map.of("prompt_tokens", 100, "completion_tokens", 50, "total_tokens", 150));
            var accepted = generation.submit(new AiGenerationService.Request(project, AssetType.MODULE, null, "生成模块", List.of(), "priced", null, null));
            assertThat(terminal(project, accepted.jobId()).status()).isEqualTo("SUCCEEDED");
            var changed = request("PUT", "/api/settings/model/pricing", Map.of("modelName", name, "baseVersion", object(saved).get("version"), "enabled", true, "currency", "CNY", "inputPerMillion", "20", "outputPerMillion", "30"));
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(request("PUT", "/api/settings/model/pricing", Map.of("modelName", name, "baseVersion", "0", "enabled", false, "currency", "CNY", "inputPerMillion", "0", "outputPerMillion", "0")).statusCode()).isEqualTo(409);
            var custom = request("PUT", "/api/settings/model/pricing", Map.of("modelName", name + "-points", "baseVersion", "0", "enabled", true, "currency", " credits.v2 ", "inputPerMillion", "1", "outputPerMillion", "1"));
            assertThat(custom.statusCode()).as("provider-specific billing units are accepted").isEqualTo(200);
            assertThat(object(custom)).containsEntry("currency", "CREDITS.V2");
            assertThat(request("PUT", "/api/settings/model/pricing", Map.of("modelName", name + "-bad", "baseVersion", "0", "enabled", true, "currency", "US D", "inputPerMillion", "1", "outputPerMillion", "1")).statusCode()).isEqualTo(422);
            var response = request("GET", "/api/projects/" + project + "/evalops/invocations", null); assertThat(response.statusCode()).isEqualTo(200);
            assertThat(Values.objects(object(response).get("items"))).singleElement().satisfies(call -> {
                assertThat(call).containsEntry("currency", "CNY").containsEntry("pricingVersion", object(saved).get("version"));
                assertThat(new java.math.BigDecimal(call.get("estimatedCost").toString())).isEqualByComparingTo("0.00035");
            });
        }
    }

    @Test void humanScoresUseLatestJudgementPerDiagnosisAndNeverAiConfidence() throws Exception {
        String project = project().id(); Asset first = assets.create(project, AssetType.BUG, null, "第一个诊断", Map.of("rootCauseAnalysis", "待核实"), "MANUAL");
        Asset second = assets.create(project, AssetType.BUG, null, "第二个诊断", Map.of(), "MANUAL");
        evaluate(project, first, "CORRECT", "CONFIRMED"); evaluate(project, first, "PARTIAL", "NOT_REGRESSION"); evaluate(project, second, "INCORRECT", "CONFIRMED");
        var data = Values.map(metrics(project).get("rca"));
        assertThat(data).containsEntry("evaluatedVersions", 2).containsEntry("correct", 0).containsEntry("partial", 1).containsEntry("incorrect", 1).containsEntry("confirmedRegressionBugs", 1);
        assertThat(((Number) data.get("correctRatePercent")).doubleValue()).isZero();
        evaluate(project, first, "UNREVIEWED", "UNREVIEWED");
        assertThat(Values.map(metrics(project).get("rca"))).containsEntry("evaluatedVersions", 1).containsEntry("clearedVersions", 1);
    }

    @Test void timeWindowsAndModelVersionsKeepTheirOwnSamples() throws Exception {
        String project = project().id(), name = "window-" + UUID.randomUUID();
        try (var model = new ModelFixtureServer()) {
            for (int round = 1; round <= 2; round++) {
                configure(model, name);
                model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "MODULE", "localKey", "m", "name", "模型版本 " + round, "data", Map.of())))));
                var accepted = generation.submit(new AiGenerationService.Request(project, AssetType.MODULE, null, "生成模块", List.of(), "version-" + round, null, null));
                assertThat(terminal(project, accepted.jobId()).status()).isEqualTo("SUCCEEDED");
            }
            var data = metrics(project); List<Map<String, Object>> groups = Values.objects(data.get("byModel"));
            assertThat(groups).hasSize(2);
            assertThat(groups).allSatisfy(group -> assertThat(group).containsEntry("invocations", 1).containsEntry("totalTokens", null));
            String version = groups.getFirst().get("modelVersion").toString();
            var page = request("GET", "/api/projects/" + project + "/evalops/invocations?modelVersion=" + version + "&limit=1", null);
            assertThat(page.statusCode()).isEqualTo(200); assertThat(object(page)).containsEntry("total", 1);
            Map<String, Object> invocation = Values.objects(object(page).get("items")).getFirst();
            var before = request("GET", "/api/projects/" + project + "/evalops?to=" + invocation.get("startedAt"), null);
            assertThat(before.statusCode()).isEqualTo(200); assertThat(Values.map(object(before).get("usage"))).containsEntry("invocations", 0);
            assertThat(request("GET", "/api/projects/" + project + "/evalops?from=bad-time", null).statusCode()).isEqualTo(422);
        }
    }

    @Test void cancellationRecordsTheAttemptWithoutInventingUsageOrInvalidGeneration() throws Exception {
        String project = project().id();
        try (var model = new ModelFixtureServer()) {
            configure(model, "cancel-" + UUID.randomUUID()); var held = model.hold("late content");
            var submitted = generation.submit(new AiGenerationService.Request(project, AssetType.MODULE, null, "生成模块", List.of(), "cancelled", null, null));
            assertThat(held.entered().await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            jobs.cancel(project, submitted.jobId());
            try {
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(Values.map(metrics(project).get("usage"))).containsEntry("cancelled", 1));
                var data = metrics(project);
                assertThat(Values.map(data.get("usage"))).containsEntry("invocations", 1).containsEntry("httpAttempts", 1).containsEntry("usageReported", 0).containsEntry("totalTokens", null);
                assertThat(Values.map(data.get("generation"))).containsEntry("attempts", 1).containsEntry("cancelled", 1).containsEntry("finished", 0).containsEntry("validRatePercent", null);
            } finally { held.release().countDown(); }
        }
    }

    private Map<String, Object> proposal(String title, Asset api) { return Map.of("operation", "ADD", "targetType", "API_CASE", "localKey", api.id(), "name", title, "data", Map.of("apiDefinitionId", api.id(), "path", api.data().get("path"), "assertions", List.of(Map.of("type", "status", "expected", 200)))); }
    private void configure(ModelFixtureServer model, String name) { settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", name, 0.1, 30)); }
    private Job terminal(String project, String id) { await().atMost(Duration.ofSeconds(45)).until(() -> jobs.get(project, id).terminal()); return jobs.get(project, id); }
    private Map<String, Object> metrics(String project) throws Exception { var response = request("GET", "/api/projects/" + project + "/evalops", null); assertThat(response.statusCode()).isEqualTo(200); return object(response); }
    private void evaluate(String project, Asset bug, String verdict, String regression) throws Exception { var response = request("POST", "/api/projects/" + project + "/bugs/" + bug.id() + "/rca-evaluations", Map.of("baseVersion", bug.version(), "verdict", verdict, "regression", regression, "idempotencyKey", UUID.randomUUID().toString())); assertThat(response.statusCode()).isEqualTo(200); }
}
