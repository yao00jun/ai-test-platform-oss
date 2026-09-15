package com.aitest.bug;

import com.aitest.ai.ModelSettingsService;
import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.execution.*;
import com.aitest.job.*;
import com.aitest.support.ModelFixtureServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** A fresh diagnosis must link a repeat failure without rewriting human decisions. */
class BugDeduplicationIT extends ExchangeHttpTest {
    @Autowired ExecutionCoordinator coordinator;
    @Autowired JobService jobs;
    @Autowired ModelSettingsService settings;
    @MockitoSpyBean BugOccurrenceService occurrences;

    @Test void repeatedFailuresPreserveClosedEditedAndDeletedBugsAcrossConcurrentRequests() throws Exception {
        String project = project().id(), secret = "bug-secret-dont-leak-5132";
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/orders", request -> {
            byte[] body = "{\"error\":\"库存不足\",\"requestId\":\"879162\"}".getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().add("Content-Type", "application/json");
            request.sendResponseHeaders(500, body.length); request.getResponseBody().write(body); request.close();
        }); target.start();
        try (var model = new ModelFixtureServer()) {
            settings.save(new ModelSettingsService.Input(model.url(), "test-key", "fixture", 0.1, 30));
            Asset api = assets.create(project, AssetType.API_CASE, null, "下单失败", Map.of("path", "http://127.0.0.1:" + target.getAddress().getPort() + "/orders", "headers", Map.of("Authorization", "Bearer " + secret), "assertions", List.of(Map.of("type", "status", "operator", "eq", "expected", 200))), "MANUAL");
            Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "不自动诊断的验收计划", Map.of("diagnoseFailures", false), "MANUAL");
            assets.create(project, AssetType.PLAN_ITEM, plan.id(), "下单", Map.of("targetId", api.id()), "MANUAL");
            String run = run(project, plan.id());
            model.enqueue(diagnosis());
            var submitted = request("POST", diagnosePath(project, run), Map.of("idempotencyKey", "first"));
            assertThat(submitted.statusCode()).as(new String(submitted.body(), StandardCharsets.UTF_8)).isEqualTo(200);
            Job first = terminal(project, object(submitted).get("jobId").toString());
            assertThat(first.status()).withFailMessage("%s", first.error()).isEqualTo("SUCCEEDED");
            Asset bug = assets.list(project, AssetType.BUG, null, "", 0, 100).items().getFirst();
            assertThat(bug.data()).containsEntry("associatedCaseId", api.id()).containsEntry("status", "OPEN");
            assertThat(bug.data().get("actualResult").toString()).contains("500", "库存不足");
            assertThat(bug.data().get("rootCauseAnalysis")).isEqualTo("推测：库存服务异常，需结合服务日志核对。");
            assertThat(model.requests.getFirst()).contains("500", "库存不足").doesNotContain(secret);
            assertThat(assets.all(project)).filteredOn(item -> item.type() == AssetType.BUG).hasSize(1);
            bug = assets.update(project, bug.id(), bug.version(), "人工已确认并关闭", Map.of("severity", "MINOR", "status", "CLOSED", "suggestion", "保留人工结论"), true, "MANUAL");
            String secondRun = run(project, plan.id());
            CountDownLatch bothTransactionsStarted = new CountDownLatch(2);
            // Only control scheduling; both calls still execute the real MySQL transaction.
            doAnswer(invocation -> {
                FailureEvidenceReader.Failure failure = invocation.getArgument(2);
                if (failure.runId().equals(secondRun)) {
                    bothTransactionsStarted.countDown();
                    if (!bothTransactionsStarted.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent diagnosis did not reach both write barriers");
                }
                return invocation.callRealMethod();
            }).when(occurrences).record(eq(project), anyString(), any(), anyMap(), anyString(), anyString());
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var calls = executor.<java.net.http.HttpResponse<byte[]>>invokeAll(List.of(
                        () -> request("POST", diagnosePath(project, secondRun), Map.of("idempotencyKey", "repeat-a")),
                        () -> request("POST", diagnosePath(project, secondRun), Map.of("idempotencyKey", "repeat-b"))));
                for (var call : calls) {
                    var response = call.get(); assertThat(response.statusCode()).isEqualTo(200);
                    Job repeated = terminal(project, object(response).get("jobId").toString());
                    assertThat(repeated.status()).withFailMessage("%s", repeated.error()).isEqualTo("SUCCEEDED");
                }
            }
            assertThat(assets.get(project, bug.id())).isEqualTo(bug);
            var occurrences = request("GET", "/api/projects/" + project + "/bugs/" + bug.id() + "/occurrences", null);
            assertThat(occurrences.statusCode()).isEqualTo(200);
            assertThat(objects(object(occurrences).get("items"))).hasSize(2);
            assertThat(object(occurrences).get("occurrenceCount")).isEqualTo(2);
            assertThat(new String(occurrences.body(), StandardCharsets.UTF_8)).doesNotContain(secret);
            assets.delete(project, bug.id(), bug.version());
            String thirdRun = run(project, plan.id());
            var third = request("POST", diagnosePath(project, thirdRun), Map.of("idempotencyKey", "deleted"));
            Job done = terminal(project, object(third).get("jobId").toString());
            assertThat(done.status()).withFailMessage("%s", done.error()).isEqualTo("SUCCEEDED");
            assertThat(assets.list(project, AssetType.BUG, null, "", 0, 100).items()).isEmpty();
            assertThat(objects(done.result().get("items")).getFirst()).containsEntry("status", "SUPPRESSED_DELETED");
            assertThat(model.requests).hasSize(1);
            assertThat(request("POST", diagnosePath(project().id(), run), Map.of("idempotencyKey", "foreign")).statusCode()).isEqualTo(404);
        } finally { target.stop(0); }
    }

    @Test void deletionCommittedAfterDiagnosisStartsIsPreservedByOccurrenceRecording() throws Exception {
        String project = project().id();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", request -> { request.sendResponseHeaders(500, -1); request.close(); });
        target.start();
        CountDownLatch beforeProjectLock = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var model = new ModelFixtureServer()) {
            settings.save(new ModelSettingsService.Input(model.url(), "test-key", "fixture", 0.1, 30));
            Asset api = assets.create(project, AssetType.API_CASE, null, "删除竞争的接口", Map.of("path", "http://127.0.0.1:" + target.getAddress().getPort(), "assertions", List.of(Map.of("type", "status", "operator", "eq", "expected", 200))), "MANUAL");
            Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "手工诊断计划", Map.of("diagnoseFailures", false), "MANUAL");
            assets.create(project, AssetType.PLAN_ITEM, plan.id(), "接口", Map.of("targetId", api.id()), "MANUAL");
            String firstRun = run(project, plan.id());
            model.enqueue(diagnosis());
            var first = request("POST", diagnosePath(project, firstRun), Map.of("idempotencyKey", "first"));
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(terminal(project, object(first).get("jobId").toString()).status()).isEqualTo("SUCCEEDED");
            Asset bug = assets.list(project, AssetType.BUG, null, "", 0, 100).items().getFirst();
            String secondRun = run(project, plan.id());
            doAnswer(invocation -> {
                beforeProjectLock.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Deletion barrier was not released");
                return invocation.callRealMethod();
            }).when(occurrences).record(eq(project), anyString(), any(), anyMap(), anyString(), anyString());
            var second = request("POST", diagnosePath(project, secondRun), Map.of("idempotencyKey", "delete-race"));
            assertThat(second.statusCode()).isEqualTo(200);
            try {
                assertThat(beforeProjectLock.await(10, TimeUnit.SECONDS)).isTrue();
                assets.delete(project, bug.id(), bug.version());
            } finally { release.countDown(); }
            Job done = terminal(project, object(second).get("jobId").toString());
            assertThat(done.status()).withFailMessage("%s", done.error()).isEqualTo("SUCCEEDED");
            assertThat(objects(done.result().get("items"))).singleElement().satisfies(item ->
                    assertThat(item).containsEntry("bugId", bug.id()).containsEntry("status", "SUPPRESSED_DELETED"));
            assertThat(assets.list(project, AssetType.BUG, null, "", 0, 100).items()).isEmpty();
            assertThat(model.requests).hasSize(1);
        } finally { release.countDown(); target.stop(0); }
    }

    @Test void cancellingDiagnosisCannotCreateAnIssueFromALateModelReply() throws Exception {
        String project = project().id();
        Asset manual = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "手工退款检查", Map.of(), "MANUAL");
        Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "人工计划", Map.of("diagnoseFailures", false), "MANUAL");
        assets.create(project, AssetType.PLAN_ITEM, plan.id(), "退款", Map.of("targetId", manual.id(), "executionMode", "MANUAL"), "MANUAL");
        String run = run(project, plan.id());
        var before = object(request("GET", "/api/projects/" + project + "/runs/" + run, null));
        var item = objects(before.get("items")).getFirst();
        request("POST", "/api/projects/" + project + "/runs/" + run + "/manual-result", Map.of("itemId", item.get("id"), "baseVersion", item.get("manualVersion"), "status", "FAILED", "notes", "退款金额不符"));
        try (var model = new ModelFixtureServer()) {
            settings.save(new ModelSettingsService.Input(model.url(), "test-key", "fixture", 0.1, 30));
            var pending = model.hold(diagnosis());
            var response = request("POST", diagnosePath(project, run), Map.of("idempotencyKey", "cancel"));
            assertThat(response.statusCode()).isEqualTo(200);
            String job = object(response).get("jobId").toString();
            assertThat(pending.entered().await(10, TimeUnit.SECONDS)).isTrue();
            jobs.cancel(project, job); pending.release().countDown();
            await().atMost(Duration.ofSeconds(5)).during(Duration.ofMillis(700)).untilAsserted(() -> assertThat(assets.list(project, AssetType.BUG, null, "", 0, 100).items()).isEmpty());
            assertThat(terminal(project, job).status()).isEqualTo("CANCELLED");
        }
    }

    @Test void automaticDiagnosisUsesCommittedFailuresAndInvalidOutputRemainsInspectable() throws Exception {
        String project = project().id();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", request -> { request.sendResponseHeaders(503, -1); request.close(); }); target.start();
        try (var model = new ModelFixtureServer()) {
            settings.save(new ModelSettingsService.Input(model.url(), "test-key", "fixture", 0.1, 30));
            Asset api = assets.create(project, AssetType.API_CASE, null, "自动诊断输入", Map.of("path", "http://127.0.0.1:" + target.getAddress().getPort(), "assertions", List.of(Map.of("type", "status", "operator", "eq", "expected", 200))), "MANUAL");
            model.enqueue(diagnosis());
            String automaticRun = run(project, api.id());
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(objects(object(request("GET", "/api/projects/" + project + "/runs/" + automaticRun + "/diagnoses", null)).get("items"))).hasSize(1));
            assertThat(assets.list(project, AssetType.BUG, null, "", 0, 100).items()).hasSize(1);

            Asset other = assets.create(project, AssetType.API_CASE, null, "格式错误诊断输入", Map.of("path", "http://127.0.0.1:" + target.getAddress().getPort(), "assertions", List.of(Map.of("type", "status", "operator", "eq", "expected", 200))), "MANUAL");
            Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "手工触发诊断", Map.of("diagnoseFailures", false), "MANUAL");
            assets.create(project, AssetType.PLAN_ITEM, plan.id(), "第二接口", Map.of("targetId", other.id()), "MANUAL");
            String invalidRun = run(project, plan.id());
            model.enqueue("{\"severity\":\"UNKNOWN\"}"); model.enqueue("{\"severity\":\"UNKNOWN\"}");
            var submission = object(request("POST", diagnosePath(project, invalidRun), Map.of("idempotencyKey", "bad-output")));
            assertThat(terminal(project, submission.get("jobId").toString()).status()).isEqualTo("FAILED");
            assertThat(submission).containsKey("conversationId");
            var history = request("GET", "/api/ai/conversations/" + submission.get("conversationId") + "?projectId=" + project, null);
            assertThat(history.statusCode()).isEqualTo(200);
            assertThat(objects(object(history).get("messages"))).anySatisfy(message -> assertThat(message).containsEntry("role", "assistant").containsEntry("status", "FAILED").containsEntry("content", "{\"severity\":\"UNKNOWN\"}"));
            assertThat(assets.list(project, AssetType.BUG, null, "", 0, 100).items()).hasSize(1);
        } finally { target.stop(0); }
    }
    private String run(String project, String plan) {
        var run = coordinator.submit(project, new ExecutionCoordinator.Request(plan, null, null, UUID.randomUUID().toString()));
        assertThat(terminal(project, run.jobId()).status()).isEqualTo("SUCCEEDED"); return run.runId();
    }
    private Job terminal(String project, String id) { await().atMost(Duration.ofSeconds(35)).until(() -> jobs.get(project, id).terminal()); return jobs.get(project, id); }
    private String diagnosePath(String project, String run) { return "/api/projects/" + project + "/runs/" + run + "/diagnose"; }
    private String diagnosis() { return json.write(Map.of("title", "[订单] 库存不足返回异常", "severity", "MAJOR", "reproduceSteps", "提交订单", "expectedResult", "返回正确结果", "actualResult", "接口返回错误", "rootCauseAnalysis", "推测：库存服务异常，需结合服务日志核对。", "fixSuggestion", "检查库存扣减与异常转换日志。")); }
}
