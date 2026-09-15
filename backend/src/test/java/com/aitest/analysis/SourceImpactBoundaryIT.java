package com.aitest.analysis;

import com.aitest.analysis.impact.*;
import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import com.aitest.common.SecretProtector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class SourceImpactBoundaryIT extends ExchangeHttpTest {
    @TempDir Path temporary;
    @Autowired SourceAnalysisService sources;
    @Autowired SourceImpactService impacts;
    @Autowired SourceImpactRepository reports;
    @Autowired ImpactSelectionService selection;
    @Autowired JobService jobs;
    @Autowired AssetRepository repository;
    @Autowired SourceSnapshotRepository snapshots;
    @Autowired SecretProtector secrets;

    @Test void twoLocalSnapshotsSupportConcurrentExactPlanRecoveryAndRejectNewChildMembership() throws Exception {
        String project = project().id();
        Asset call = assets.create(project, AssetType.API_CASE, null, "Changed route", Map.of("path", "/orders/{orderId}"), "MANUAL");
        Asset scene = assets.create(project, AssetType.SCENARIO, null, "Chain", Map.of(), "MANUAL");
        assets.create(project, AssetType.SCENARIO_STEP, scene.id(), "First", Map.of("targetId", call.id()), "MANUAL");
        String baseline = capture(project, "1"), head = capture(project, "2");
        Map<String, Object> submitted = impacts.submit(project, head, new SourceImpactService.Request(baseline, "local"));
        finish(project, submitted);
        String impact = submitted.get("impactId").toString();
        var report = reports.get(project, impact);
        assertThat(objects(map(report.get("result")).get("candidates"))).extracting(row -> row.get("assetId")).contains(call.id(), scene.id());
        assets.create(project, AssetType.SCENARIO_STEP, scene.id(), "New human step", Map.of("targetId", call.id()), "MANUAL");
        var stale = request("POST", planPath(project, impact), Map.of("assetIds", List.of(scene.id()), "idempotencyKey", "new-child"));
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(object(stale).get("code")).isEqualTo("IMPACT_CANDIDATE_CHANGED");
        var input = new ImpactSelectionService.Request(List.of(call.id()), "Recovered once", "", "parallel");
        List<Map<String, Object>> replies = new ArrayList<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch ready = new CountDownLatch(6), go = new CountDownLatch(1); List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(threads.submit(() -> { ready.countDown(); go.await(); return selection.create(project, impact, input); }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            for (var future : futures) replies.add(future.get(20, TimeUnit.SECONDS));
        }
        assertThat(replies.stream().map(result -> result.get("planId")).distinct()).hasSize(1);
        String plan = replies.getFirst().get("planId").toString();
        assertThat(assets.all(project)).filteredOn(asset -> asset.type() == AssetType.TEST_PLAN).hasSize(1);
        assets.update(project, call.id(), call.version(), "Human revised after creation", Map.of(), null, "MANUAL");
        assertThat(selection.create(project, impact, input)).containsEntry("planId", plan);
        assertThat(assets.children(project, plan)).hasSize(1);
        String otherProject = project().id(), otherSnapshot = capture(otherProject, "8");
        assertThat(request("POST", "/api/projects/" + project + "/source-analyses/" + head + "/impact", Map.of("baselineSnapshotId", otherSnapshot, "idempotencyKey", "foreign")).statusCode()).isEqualTo(404);
        Asset currentPlan = assets.get(project, plan);
        assertThat(request("PATCH", "/api/projects/" + project + "/assets/" + plan, Map.of("baseVersion", currentPlan.version(), "data", Map.of("sourceSnapshotId", otherSnapshot))).statusCode()).isEqualTo(404);
        assertThat(request("PATCH", "/api/projects/" + project + "/assets/" + plan, Map.of("baseVersion", currentPlan.version(), "data", Map.of("sourceSnapshotId", baseline))).statusCode()).isEqualTo(422);
        assertThat(assets.get(project, plan)).isEqualTo(currentPlan);
    }
    @Test void planItemSelectionPreservesManualModeDatasetAndVariablesWithoutIncludingSiblingTests() throws Exception {
        String project = project().id();
        Asset call = assets.create(project, AssetType.API_CASE, null, "Order", Map.of("path", "/orders/{id}"), "MANUAL");
        Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "Test", Map.of("baseUrl", "http://127.0.0.1:1"), "MANUAL");
        Asset rows = assets.create(project, AssetType.DATASET, null, "Inputs", Map.of("columns", List.of("id"), "rows", List.of(Map.of("id", 1))), "MANUAL");
        Asset original = assets.create(project, AssetType.TEST_PLAN, null, "Original plan", Map.of("environmentId", environment.id(), "datasetId", rows.id()), "MANUAL");
        Asset item = assets.create(project, AssetType.PLAN_ITEM, original.id(), "Manual selection", Map.of("targetId", call.id(), "executionMode", "MANUAL", "variables", Map.of("x", "kept")), "MANUAL");
        assets.create(project, AssetType.PLAN_ITEM, original.id(), "Unselected sibling", Map.of("targetId", call.id()), "MANUAL");
        String baseline = capture(project, "1"), head = capture(project, "2");
        var submitted = impacts.submit(project, head, new SourceImpactService.Request(baseline, "plan-item")); finish(project, submitted);
        var result = selection.create(project, submitted.get("impactId").toString(), new ImpactSelectionService.Request(List.of(item.id()), "Only manual", "", "plan"));
        String plan = result.get("planId").toString();
        assertThat(assets.get(project, plan).data()).containsEntry("environmentId", environment.id());
        assertThat(assets.children(project, plan)).singleElement().satisfies(child -> assertThat(child.data()).containsEntry("targetId", call.id()).containsEntry("executionMode", "MANUAL").containsEntry("datasetId", rows.id()).containsEntry("variables", Map.of("x", "kept")));
        assertThat(assets.get(project, original.id())).isEqualTo(original);
    }
    @Test void legacyParsedSnapshotsAreUpgradedFromTheirCapturedFilesBeforeDeletedCallbackImpact() throws Exception {
        String project = project().id();
        String before = "package orders;\nclass Work {\n void removed() {}\n}\n@RestController class Api {\n Work work;\n @GetMapping(\"/callback\") Runnable callback() { return work::removed; }\n}";
        String baseline = captureText(project, before), head = captureText(project, before.replace(" void removed() {}\n", ""));
        var legacy = snapshots.result(project, baseline);
        legacy.remove("javaAstFormatVersion");
        var backend = map(legacy.get("backend"));
        backend.put("calls", objects(backend.get("calls")).stream().filter(call -> !"METHOD_REFERENCE".equals(call.get("kind"))).toList());
        // Simulate a persisted A1 snapshot from before callback extraction existed; captured bytes are unchanged.
        repository.jdbc().update("UPDATE source_snapshot SET result_cipher=? WHERE id=?", secrets.encrypt(json.write(legacy)), baseline);
        var submitted = impacts.submit(project, head, new SourceImpactService.Request(baseline, "legacy")); finish(project, submitted);
        var report = reports.result(project, submitted.get("impactId").toString());
        assertThat(objects(report.get("affectedEndpoints"))).anySatisfy(endpoint -> assertThat(endpoint).containsEntry("path", "/callback").containsEntry("sourceVersion", "BASELINE"));
    }
    private String capture(String project, String value) throws Exception {
        return captureText(project, "package orders; @RestController class Api {\n @GetMapping(\"/orders/{id}\") int order() {\n  return " + value + ";\n }\n}");
    }
    private String captureText(String project, String text) throws Exception {
        Files.writeString(temporary.resolve("Api.java"), text);
        var submission = sources.submit(project, new SourceAnalysisService.Input(temporary.toString(), null, null, null, null, null, null, UUID.randomUUID().toString()));
        finish(project, submission); return submission.get("analysisId").toString();
    }
    private void finish(String project, Map<String, Object> submission) {
        String job = submission.get("jobId").toString(); await().atMost(Duration.ofSeconds(30)).until(() -> jobs.get(project, job).terminal());
        assertThat(jobs.get(project, job).status()).as(jobs.get(project, job).error()).isEqualTo("SUCCEEDED");
    }
    private String planPath(String project, String id) { return "/api/projects/" + project + "/source-impacts/" + id + "/regression-plan"; }
}
