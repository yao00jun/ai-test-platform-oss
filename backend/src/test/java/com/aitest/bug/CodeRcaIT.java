package com.aitest.bug;
import org.junit.jupiter.api.Tag;

import com.aitest.asset.*;
import com.aitest.execution.Values;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("slow")
class CodeRcaIT extends CodeRcaSupport {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Test void recurringFailureCannotReplaceTheCreationSourceWhenTheClockMovesBackwards() throws Exception {
        String project = project().id(); var captured = source(project, false, false);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { byte[] bytes = STACK.getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(500, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }); server.start();
        try (var model = new ModelFixtureServer()) {
            Asset api = assets.create(project, AssetType.API_CASE, null, "同一退款接口", Map.of("sourceSnapshotId", captured.id(), "path", "http://127.0.0.1:" + server.getAddress().getPort() + "/", "assertions", List.of(Map.of("type", "status", "expected", 200))), "MANUAL");
            Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "重复失败计划", Map.of("diagnoseFailures", false), "MANUAL");
            assets.create(project, AssetType.PLAN_ITEM, plan.id(), "退款", Map.of("targetId", api.id()), "MANUAL");
            var first = coordinator.submit(project, new com.aitest.execution.ExecutionCoordinator.Request(plan.id(), null, null, "first"));
            assertThat(terminal(project, first.jobId()).status()).isEqualTo("SUCCEEDED");
            Asset bug = diagnosedBug(project, first.runId(), model, code("初始源码诊断", "OrderService.java:5", PATCH));
            Map<String, Object> original = object(request("GET", "/api/projects/" + project + "/bugs/" + bug.id() + "/code-evidence", null));
            var next = source(project, false, false);
            assets.update(project, api.id(), api.version(), null, Map.of("sourceSnapshotId", next.id()), null, "MANUAL");
            var second = coordinator.submit(project, new com.aitest.execution.ExecutionCoordinator.Request(plan.id(), null, null, "second"));
            assertThat(terminal(project, second.jobId()).status()).isEqualTo("SUCCEEDED");
            var accepted = diagnose(project, second.runId());
            assertThat(terminal(project, accepted.get("jobId").toString()).status()).isEqualTo("SUCCEEDED");
            var recurrence = jdbc.queryForMap("SELECT evidence FROM bug_failure_occurrence WHERE bug_id=? AND status='RECURRENCE'", bug.id());
            assertThat(json.write(recurrence)).contains(next.id());
            // Simulate wall-clock rollback after the creation event; status retains the true anchor.
            jdbc.update("UPDATE bug_failure_occurrence SET created_at='2020-01-01 00:00:00' WHERE bug_id=? AND status='RECURRENCE'", bug.id());
            assertThat(object(request("GET", "/api/projects/" + project + "/bugs/" + bug.id() + "/code-evidence", null))).isEqualTo(original);
            assertThat(assets.get(project, bug.id())).isEqualTo(bug);
        } finally { server.stop(0); }
    }
    @Test void moduleAndClassLoaderFramesResolveTheSameCapturedJavaMethod() throws Exception {
        String project = project().id(); var captured = source(project, false, false);
        for (String prefix : List.of("app//", "application/payments.core@1.2.3/")) {
            String run = failure(project, captured.id(), STACK.replace("at shop.", "at " + prefix + "shop."));
            var evidence = Values.map(reader.read(project, run).getFirst().evidence().get("sourceEvidence"));
            assertThat(Values.objects(evidence.get("locations"))).as(prefix).singleElement().satisfies(location ->
                    assertThat(location).containsEntry("path", "OrderService.java").containsEntry("className", "shop.OrderService").containsEntry("line", 5));
        }
    }
    @Test void actualStackBindsExactClassLineAndFixedBaselineEvenAfterDiskEdits() throws Exception {
        String project = project().id(); var captured = source(project, true, false);
        String run = failure(project, captured.id(), STACK);
        Files.writeString(captured.directory().resolve("OrderService.java"), "class LaterFile {}\n");
        var observed = reader.read(project, run).getFirst();
        var evidence = Values.map(observed.evidence().get("sourceEvidence"));
        assertThat(evidence).containsEntry("formatVersion", "aitest.failure-source-evidence/v1");
        assertThat(Values.objects(evidence.get("locations"))).singleElement().satisfies(location -> {
            assertThat(location).containsEntry("path", "OrderService.java").containsEntry("className", "shop.OrderService").containsEntry("method", "refund").containsEntry("line", 5);
            assertThat(location.get("content").toString()).contains("missing id").doesNotContain("LaterFile", "source-secret-4717", "other class");
        });
        assertThat(json.write(evidence.get("diffs"))).contains("old acceptance", "missing id").doesNotContain("source-secret-4717");
        try (var model = new ModelFixtureServer()) {
            Asset bug = diagnosedBug(project, run, model, code("依据堆栈提出的待验证推测", "OrderService.java:5", PATCH));
            assertThat(Values.map(bug.data().get("codeDiagnosis"))).containsEntry("affected_code_path", "OrderService.java:5").containsEntry("suggested_fix", PATCH);
            var response = request("GET", "/api/projects/" + project + "/bugs/" + bug.id() + "/code-evidence", null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(object(response)).isEqualTo(evidence);
            assertThat(model.requests.getFirst()).contains("missing id", "old acceptance").doesNotContain("source-secret-4717", "LaterFile");
        }
    }
    @Test void missingAmbiguousAndOutOfRangeEvidenceIsExplicitAndNeverInventsLocations() throws Exception {
        String project = project().id(); var captured = source(project, false, false); var duplicate = source(project, false, true);
        for (var input : List.of(Map.of("source", "", "stack", STACK, "reason", "SOURCE_NOT_BOUND"), Map.of("source", captured.id(), "stack", STACK.replace(":5)", ":9999)"), "reason", "SOURCE_LINE_OUT_OF_RANGE"), Map.of("source", duplicate.id(), "stack", STACK, "reason", "SOURCE_LOCATION_AMBIGUOUS"), Map.of("source", captured.id(), "stack", "Error: failed at refund.js:5:2", "reason", "JAVA_STACK_NOT_FOUND"))) {
            String run = failure(project, input.get("source").isBlank() ? null : input.get("source"), input.get("stack"));
            var evidence = Values.map(reader.read(project, run).getFirst().evidence().get("sourceEvidence"));
            assertThat(evidence).containsEntry("formatVersion", "aitest.failure-source-evidence/v1");
            assertThat(Values.objects(evidence.get("locations"))).isEmpty();
            assertThat(Values.objects(evidence.get("diagnostics"))).anySatisfy(item -> assertThat(item).containsEntry("code", input.get("reason")));
        }
    }
    @Test void unsupportedPreciseModelDiagnosisIsDroppedAndRemainsInspectable() throws Exception {
        String project = project().id(); String run = failure(project, null, STACK);
        try (var model = new ModelFixtureServer()) {
            configure(model);
            for (int retry = 0; retry < 2; retry++) model.enqueue(json.write(diagnosis(code("凭空猜测", "OrderService.java:5", PATCH))));
            var accepted = diagnose(project, run);
            assertThat(terminal(project, accepted.get("jobId").toString()).status()).isEqualTo("SUCCEEDED");
            var bug = assets.list(project, AssetType.BUG, null, "", 0, 100).items().getFirst();
            assertThat(Values.map(bug.data().get("codeDiagnosis"))).isEmpty();
            var history = object(request("GET", "/api/ai/conversations/" + accepted.get("conversationId") + "?projectId=" + project, null));
            assertThat(Values.objects(history.get("messages"))).anySatisfy(message -> assertThat(message).containsEntry("role", "assistant").containsEntry("status", "APPLIED"));
        }
    }
}
