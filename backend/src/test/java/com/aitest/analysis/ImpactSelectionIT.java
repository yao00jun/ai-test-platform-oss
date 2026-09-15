package com.aitest.analysis;

import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.execution.*;
import com.aitest.job.JobService;
import com.aitest.support.GitFixture;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** A real Git diff must narrow regression requests; changing a sibling must invalidate stale selection. */
class ImpactSelectionIT extends ExchangeHttpTest {
    @TempDir Path temporary;
    @Autowired JobService jobs;
    @Autowired ExecutionCoordinator execution;
    @Autowired RunRepository runs;

    @Test void preciseMethodDiffMapsAllCandidatesAndOnlySelectedTestsRunWithFixedEvidence() throws Exception {
        String project = project().id();
        List<Asset> cases = new ArrayList<>();
        for (int i = 0; i < 7; i++) cases.add(assets.create(project, AssetType.API_CASE, null, "Refund " + i, Map.of("method", "POST", "path", "/refund", "assertions", List.of(Map.of("type", "status_code", "expected", 200))), "MANUAL"));
        Asset stable = assets.create(project, AssetType.API_CASE, null, "Stable", Map.of("path", "/stable"), "MANUAL");
        Asset scenario = assets.create(project, AssetType.SCENARIO, null, "Refund scenario", Map.of(), "MANUAL");
        assets.create(project, AssetType.SCENARIO_STEP, scenario.id(), "HTTP", Map.of("targetId", cases.getFirst().id()), "MANUAL");
        String snapshot = gitSnapshot(project);
        Map<String, Object> report = impact(project, snapshot, Map.of("idempotencyKey", "precise"));
        Map<String, Object> result = map(report.get("result"));
        assertThat(objects(result.get("changedMethods"))).extracting(row -> row.get("signature")).contains("apply(int)").doesNotContain("unchanged()");
        assertThat(objects(result.get("affectedEndpoints"))).extracting(row -> row.get("path")).contains("/refund").doesNotContain("/stable");
        assertThat(objects(result.get("affectedTables"))).anySatisfy(row -> assertThat(row).containsEntry("table", "orders"));
        assertThat(objects(result.get("candidates"))).extracting(row -> row.get("assetId")).containsAll(cases.stream().map(Asset::id).toList()).contains(scenario.id()).doesNotContain(stable.id());
        assertThat(objects(map(result.get("headGraph")).get("edges"))).anySatisfy(edge -> assertThat(edge.get("to").toString()).contains("RefundService#apply(int)"));
        AtomicInteger refunds = new AtomicInteger(), untouched = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/refund", exchange -> { refunds.incrementAndGet(); exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 0); exchange.close(); });
        server.createContext("/stable", exchange -> { untouched.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close(); });
        server.start();
        try {
            Asset env = assets.create(project, AssetType.ENVIRONMENT, null, "Local", Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort()), "MANUAL");
            Map<String, Object> input = Map.of("assetIds", List.of(cases.getFirst().id()), "name", "Selected regression", "environmentId", env.id(), "idempotencyKey", "selected");
            var response = request("POST", planPath(project, report), input);
            assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
            var plan = object(response);
            assertThat(object(request("POST", planPath(project, report), input))).isEqualTo(plan);
            String planId = plan.get("planId").toString();
            assertThat(assets.children(project, planId)).singleElement().satisfies(item -> assertThat(item.data()).containsEntry("targetId", cases.getFirst().id()));
            assertThat(assets.get(project, planId).data()).containsEntry("sourceSnapshotId", snapshot).containsEntry("impactId", report.get("id"));
            Files.writeString(temporary.resolve("repo/Services.java"), "class ModifiedAfterCapture {}");
            var run = execution.submit(project, new ExecutionCoordinator.Request(planId, env.id(), null, "selected-run"));
            awaitJob(project, run.jobId());
            var finished = runs.get(project, run.runId());
            assertThat(finished.get("status")).isEqualTo("PASSED");
            assertThat(refunds).hasValue(1); assertThat(untouched).hasValue(0);
            assertThat(objects(map(finished.get("snapshot")).get("sourceEvidence"))).singleElement().satisfies(binding -> assertThat(binding).containsEntry("sourceSnapshotId", snapshot).containsEntry("impactId", report.get("id")));
            assertThat(object(request("GET", reportPath(project, report), null))).isEqualTo(report);
            assertThat(assets.get(project, stable.id())).isEqualTo(stable);
        } finally { server.stop(0); }
    }

    @Test void selectionRechecksCompleteCandidateDependenciesAndEnforcesProjectAndRequestIdentity() throws Exception {
        String project = project().id();
        Asset call = assets.create(project, AssetType.API_CASE, null, "Refund", Map.of("method", "POST", "path", "/refund"), "MANUAL");
        Asset scenario = assets.create(project, AssetType.SCENARIO, null, "Chain", Map.of(), "MANUAL");
        Asset step = assets.create(project, AssetType.SCENARIO_STEP, scenario.id(), "Call", Map.of("targetId", call.id()), "MANUAL");
        String snapshot = gitSnapshot(project);
        Map<String, Object> report = impact(project, snapshot, Map.of("idempotencyKey", "guarded"));
        var duplicate = object(request("POST", sourcePath(project, snapshot) + "/impact", Map.of("idempotencyKey", "guarded")));
        assertThat(duplicate).containsEntry("impactId", report.get("id"));
        assertThat(request("POST", sourcePath(project, snapshot) + "/impact", Map.of("idempotencyKey", "guarded", "baselineSnapshotId", snapshot)).statusCode()).isEqualTo(409);
        assertThat(request("GET", reportPath(project().id(), report), null).statusCode()).isEqualTo(404);
        assertThat(request("POST", planPath(project, report), Map.of("assetIds", List.of(), "idempotencyKey", "empty")).statusCode()).isEqualTo(422);
        Asset outsider = assets.create(project().id(), AssetType.API_CASE, null, "Other project", Map.of("path", "/refund"), "MANUAL");
        assertThat(request("POST", planPath(project, report), Map.of("assetIds", List.of(outsider.id()), "idempotencyKey", "foreign")).statusCode()).isEqualTo(422);
        assets.update(project, step.id(), step.version(), "Human edit", Map.of("variables", Map.of("amount", 3)), null, "MANUAL");
        var stale = request("POST", planPath(project, report), Map.of("assetIds", List.of(scenario.id()), "idempotencyKey", "stale"));
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(object(stale).get("code")).isEqualTo("IMPACT_CANDIDATE_CHANGED");
        assertThat(assets.all(project)).noneMatch(asset -> asset.type() == AssetType.TEST_PLAN);
        var goodInput = Map.of("assetIds", List.of(call.id()), "idempotencyKey", "direct");
        assertThat(request("POST", planPath(project, report), goodInput).statusCode()).isEqualTo(200);
        assertThat(request("POST", planPath(project, report), Map.of("assetIds", List.of(scenario.id()), "idempotencyKey", "direct")).statusCode()).isEqualTo(409);
        assertThat(request("POST", sourcePath(project, snapshot) + "/impact", Map.of("idempotencyKey", "bad", "baselineSnapshotId", outsider.projectId())).statusCode()).isEqualTo(404);
    }

    private String gitSnapshot(String project) throws Exception {
        Path repo = Files.createDirectories(temporary.resolve("repo"));
        GitFixture.run(repo, "init");
        Files.writeString(repo.resolve("Api.java"), """
                package shop;
                @RestController class Api {
                    RefundPort service;
                    @PostMapping("/refund") int refund() { return service.apply(1); }
                    @GetMapping("/stable") int stable() { return service.unchanged(); }
                }
                """);
        String before = """
                package shop;
                interface RefundPort { int apply(int id); int unchanged(); }
                class RefundService implements RefundPort {
                    OrderMapper mapper;
                    public int apply(int id) {
                        mapper.update(id);
                        return 1;
                    }
                    public int unchanged() {
                        return 40;
                    }
                }
                interface OrderMapper {
                    @Update("UPDATE orders SET state='REFUNDED' WHERE id=#{id}") void update(int id);
                }
                """;
        Files.writeString(repo.resolve("Services.java"), before);
        String baseline = GitFixture.commit(repo, "baseline");
        Files.writeString(repo.resolve("Services.java"), before.replace("return 1;", "return 2;"));
        String head = GitFixture.commit(repo, "changed refund only");
        var response = request("POST", "/api/projects/" + project + "/source-analyses", Map.of("backendRepoPath", repo.toString(), "backendRef", head, "baselineRef", baseline, "idempotencyKey", "source"));
        assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
        var submitted = object(response); awaitJob(project, submitted.get("jobId").toString());
        return submitted.get("analysisId").toString();
    }
    private Map<String, Object> impact(String project, String snapshot, Map<String, Object> input) throws Exception {
        var response = request("POST", sourcePath(project, snapshot) + "/impact", input);
        assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
        var submitted = object(response); awaitJob(project, submitted.get("jobId").toString());
        return object(request("GET", "/api/projects/" + project + "/source-impacts/" + submitted.get("impactId"), null));
    }
    private void awaitJob(String project, String id) {
        await().atMost(Duration.ofSeconds(40)).until(() -> jobs.get(project, id).terminal());
        assertThat(jobs.get(project, id).status()).as(jobs.get(project, id).error()).isEqualTo("SUCCEEDED");
    }
    private String sourcePath(String project, String snapshot) { return "/api/projects/" + project + "/source-analyses/" + snapshot; }
    private String reportPath(String project, Map<String, Object> report) { return "/api/projects/" + project + "/source-impacts/" + report.get("id"); }
    private String planPath(String project, Map<String, Object> report) { return reportPath(project, report) + "/regression-plan"; }
}
