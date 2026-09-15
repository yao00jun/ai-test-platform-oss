package com.aitest.workbench;

import com.aitest.ai.ModelSettingsService;
import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.execution.ExecutionCoordinator;
import com.aitest.job.*;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"aitest.morning-brief.poll-interval-ms=100", "aitest.morning-brief.retry-delay-seconds=2"})
class MorningBriefIT extends ExchangeHttpTest {
    ModelFixtureServer model;
    private final List<String> ownedProjects = new ArrayList<>();
    @Autowired ModelSettingsService settings;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobService jobs;
    @Autowired ExecutionCoordinator execution;
    @Autowired MorningBriefService briefs;
    @BeforeEach void configureModel() throws Exception {
        model = new ModelFixtureServer();
        settings.save(new ModelSettingsService.Input(model.url(), "morning-fixture", "fixture", 0.1, 30));
    }
    @AfterEach void releaseOwnedSchedulesAndModel() {
        try {
            for (String project : ownedProjects) {
                var schedule = briefs.settings(project);
                if (schedule.enabled()) briefs.configure(project, Map.of("baseVersion", schedule.version(), "enabled", false,
                        "time", schedule.time(), "timezone", schedule.timezone(), "instruction", schedule.instruction(), "maxRetries", schedule.maxRetries()));
                // Disable under the production project lock before fencing any already dispatched jobs.
                for (String id : jdbc.queryForList("SELECT id FROM job_task WHERE project_id=? AND status IN ('QUEUED','RUNNING')", String.class, project))
                    jobs.cancel(project, id);
            }
        } finally { if (model != null) model.close(); }
    }
    @Override protected Asset project() { Asset project = super.project(); ownedProjects.add(project.id()); return project; }

    @Test void scheduledBriefFreezesPreviousDayFactsAndAllowsIndependentTextFeedback() throws Exception {
        String project = project().id();
        Asset sibling = assets.create(project, AssetType.QUALITY_BRIEF, null, "人工简报", Map.of("content", "保留人工判断"), "MANUAL");
        createPreviousDayRun(project);
        configure(project, "0", true, "只分析昨天的真实记录", 0);
        model.enqueue(candidate("昨日质量", "有一项等待人工验收。")); due(project);
        Map<String, Object> occurrence = terminal(project, "SUCCEEDED");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(occurrence).containsEntry("localDate", today.toString()).containsEntry("timezone", "UTC").containsEntry("attemptCount", 1);
        assertThat(occurrence.get("windowFrom")).isEqualTo(today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        assertThat(occurrence.get("windowTo")).isEqualTo(today.atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        Map<String, Object> metrics = map(occurrence.get("metrics"));
        assertThat(metrics).containsEntry("scope", "DATE_RANGE").containsEntry("runCount", 1).containsEntry("itemCount", 1);
        assertThat(map(metrics.get("itemStatuses"))).containsEntry("MANUAL_PENDING", 1);
        Asset brief = assets.get(project, occurrence.get("assetId").toString());
        assertThat(brief.data().get("metrics")).isEqualTo(metrics);
        assertThat(assets.get(project, sibling.id())).isEqualTo(sibling);
        for (int round = 1; round <= 2; round++) {
            model.enqueue(json.write(Map.of("data", Map.of("content", "人工验收建议，第 " + round + " 轮"))));
            var response = request("POST", "/api/ai/refine-item", Map.of("projectId", project, "targetType", "QUALITY_BRIEF", "targetId", brief.id(), "baseVersion", brief.version(), "feedback", "只改善正文", "idempotencyKey", UUID.randomUUID().toString()));
            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(finished(project, object(response).get("jobId").toString()).status()).isEqualTo("SUCCEEDED");
            brief = assets.get(project, brief.id());
            assertThat(brief.data().get("metrics")).isEqualTo(metrics);
            assertThat(assets.get(project, sibling.id())).isEqualTo(sibling);
        }
        assertThat(latest(project).get("metrics")).isEqualTo(metrics);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE job_id=? AND role='assistant' AND status='APPLIED'", Long.class, objects(occurrence.get("attempts")).getFirst().get("jobId"))).isEqualTo(1);
    }

    @Test void automaticRetryReusesFrozenFactsAndPolicyAfterSettingsAndRunChanges() throws Exception {
        String project = project().id();
        configure(project, "0", true, "冻结的原始要求", 1);
        model.enqueue(candidate("无效正文", " "));
        var held = model.hold(candidate("重试简报", "缺少运行样本，请先执行测试。")); due(project);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(history(project)).hasSize(1));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(firstAttempt(project).get("status")).isEqualTo("FAILED"));
        Map<String, Object> frozen = map(latest(project).get("metrics"));
        createPreviousDayRun(project);
        configure(project, "1", true, "后来的新要求不能污染重试", 0);
        assertThat(held.entered().await(20, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(assets.all(project).stream().filter(a -> a.type() == AssetType.QUALITY_BRIEF)).isEmpty();
            Map<String, Object> context = json.map(objects(json.map(model.requests.getLast()).get("messages")).getLast().get("content").toString());
            assertThat(context.get("executionMetrics")).isEqualTo(frozen);
            assertThat(context).containsEntry("instruction", "冻结的原始要求");
        } finally { held.release().countDown(); }
        var occurrence = terminal(project, "SUCCEEDED");
        assertThat(occurrence).containsEntry("attemptCount", 2).containsEntry("metrics", frozen);
        assertThat(objects(occurrence.get("attempts")).stream().map(a -> a.get("status"))).containsExactly("FAILED", "SUCCEEDED");
        assertThat(assets.all(project).stream().filter(a -> a.type() == AssetType.QUALITY_BRIEF)).hasSize(1);
    }

    @Test void invalidScopeMultipleBriefsAndForgedFactsNeverWriteAnAsset() throws Exception {
        for (var changes : List.of(
                List.of(proposal("QUALITY_BRIEF", "ADD", Map.of("content", " "))),
                List.of(proposal("QUALITY_BRIEF", "ADD", Map.of("content", "一")), proposal("QUALITY_BRIEF", "ADD", Map.of("content", "二"))),
                List.of(proposal("BUG", "ADD", Map.of())),
                List.of(proposal("QUALITY_BRIEF", "ADD", Map.of("content", "虚假数据", "metrics", Map.of("passRatePercent", 100)))))) {
            String project = project().id(); configure(project, "0", true, "仅生成一份晨报", 0);
            model.enqueue(json.write(Map.of("changes", changes))); due(project);
            var occurrence = terminal(project, "FAILED");
            assertThat(occurrence).containsEntry("assetId", null).containsEntry("attemptCount", 1);
            assertThat(assets.all(project)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_change_set WHERE project_id=?", Long.class, project)).isZero();
        }
    }

    @Test void disabledScheduleAllowsDispatchedWorkToFinishButStopsAutomaticRetries() throws Exception {
        String project = project().id(); configure(project, "0", true, "已派发任务保持快照", 1);
        var held = model.hold(candidate("已派发晨报", "本次派发可以完成。")); due(project);
        assertThat(held.entered().await(20, TimeUnit.SECONDS)).isTrue();
        try { configure(project, "1", false, "停用后的要求", 0); }
        finally { held.release().countDown(); }
        terminal(project, "SUCCEEDED");

        String failed = project().id(); configure(failed, "0", true, "重试也可以暂停", 1);
        model.enqueue(candidate("无效", " ")); due(failed);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(firstAttempt(failed).get("status")).isEqualTo("FAILED"));
        configure(failed, "1", false, "停用", 1);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(latest(failed).get("attemptCount")).isEqualTo(1));
        var occurrence = latest(failed); String id = occurrence.get("id").toString();
        assertThat(request("POST", retryPath(project, id), Map.of("idempotencyKey", "manual")).statusCode()).isEqualTo(404);
        model.enqueue(candidate("人工重试", "复用原来事实的结果。"));
        var first = request("POST", retryPath(failed, id), Map.of("idempotencyKey", "manual"));
        assertThat(first.statusCode()).isEqualTo(202);
        var again = request("POST", retryPath(failed, id), Map.of("idempotencyKey", "manual"));
        assertThat(again.statusCode()).isEqualTo(202); assertThat(object(again)).isEqualTo(object(first));
        assertThat(finished(failed, object(first).get("jobId").toString()).status()).isEqualTo("SUCCEEDED");
        assertThat(request("POST", retryPath(failed, id), Map.of("idempotencyKey", "manual")).statusCode()).isEqualTo(202);
        assertThat(request("POST", retryPath(failed, id), Map.of("idempotencyKey", "different")).statusCode()).isEqualTo(409);
        assertThat(assets.all(failed).stream().filter(a -> a.type() == AssetType.QUALITY_BRIEF)).hasSize(1);
    }

    @Test void cancelledAttemptIsNeverAutomaticallyReplayed() throws Exception {
        String project = project().id(); configure(project, "0", true, "取消不应自动重放", 3);
        var held = model.hold(candidate("取消的晨报", "不能写入")); due(project);
        assertThat(held.entered().await(20, TimeUnit.SECONDS)).isTrue();
        try {
            var occurrence = latest(project);
            jobs.cancel(project, objects(occurrence.get("attempts")).getFirst().get("jobId").toString());
        } finally { held.release().countDown(); }
        terminal(project, "CANCELLED");
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(latest(project).get("attemptCount")).isEqualTo(1));
        assertThat(assets.all(project)).isEmpty();
    }

    private void createPreviousDayRun(String project) {
        Asset manual = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "真实人工待验收", Map.of(), "MANUAL");
        Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "昨日运行", Map.of("diagnoseFailures", false), "MANUAL");
        assets.create(project, AssetType.PLAN_ITEM, plan.id(), "人工项", Map.of("targetId", manual.id(), "executionMode", "MANUAL"), "MANUAL");
        var run = execution.submit(project, new ExecutionCoordinator.Request(plan.id(), null, null, UUID.randomUUID().toString()));
        assertThat(finished(project, run.jobId()).status()).isEqualTo("SUCCEEDED");
        jdbc.update("UPDATE test_run SET created_at=? WHERE id=?", Timestamp.from(LocalDate.now(ZoneOffset.UTC).minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC)), run.runId());
    }
    private Map<String, Object> configure(String project, String version, boolean enabled, String instruction, int maxRetries) throws Exception {
        var response = request("PUT", "/api/projects/" + project + "/morning-brief/schedule", Map.of("baseVersion", version, "enabled", enabled, "time", "00:00", "timezone", "UTC", "instruction", instruction, "maxRetries", maxRetries));
        assertThat(response.statusCode()).as(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200);
        return object(response);
    }
    private void due(String project) { jdbc.update("UPDATE morning_brief_schedule SET next_fire_at=? WHERE project_id=?", Timestamp.from(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()), project); }
    private List<Map<String, Object>> history(String project) throws Exception { var response = request("GET", "/api/projects/" + project + "/morning-brief/history", null); assertThat(response.statusCode()).isEqualTo(200); return objects(object(response).get("items")); }
    private Map<String, Object> latest(String project) throws Exception { var items = history(project); assertThat(items).isNotEmpty(); return items.getFirst(); }
    private Map<String, Object> firstAttempt(String project) throws Exception { var attempts = objects(latest(project).get("attempts")); assertThat(attempts).isNotEmpty(); return attempts.getFirst(); }
    private Map<String, Object> terminal(String project, String status) throws Exception { await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(latest(project).get("status")).isEqualTo(status)); return latest(project); }
    private Job finished(String project, String id) { await().atMost(Duration.ofSeconds(30)).until(() -> jobs.get(project, id).terminal()); return jobs.get(project, id); }
    private String retryPath(String project, String id) { return "/api/projects/" + project + "/morning-brief/occurrences/" + id + "/retry"; }
    private Map<String, Object> proposal(String type, String operation, Map<String, Object> data) { return Map.of("operation", operation, "targetType", type, "name", "晨报", "data", data); }
    private String candidate(String name, String content) { return json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "QUALITY_BRIEF", "name", name, "data", Map.of("content", content))))); }
}
