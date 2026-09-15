package com.aitest.workbench;

import com.aitest.ai.*;
import com.aitest.asset.AssetRepository;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import com.aitest.report.QualityMetricsService;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = "aitest.morning-brief.enabled=false")
class MorningBriefBoundaryIT extends ExchangeHttpTest {
    static final ModelFixtureServer model;
    static { try { model = new ModelFixtureServer(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @Autowired MorningBriefService briefs;
    @Autowired AssetRepository projects;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired JobService jobs;
    @Autowired AiConversationService conversations;
    @Autowired QualityMetricsService metrics;
    @Autowired ModelSettingsService settings;
    @BeforeEach void setModel() { settings.save(new ModelSettingsService.Input(model.url(), "morning-boundary-fixture", "fixture", 0.1, 30)); }
    @AfterAll static void closeModel() { model.close(); }

    @Test void simultaneousSchedulersAndReplacementCoalesceDowntimeWithoutDuplicateOccurrences() throws Exception {
        String project = project().id(); configure(project, "0", true, "UTC", 0);
        Instant now = Instant.now(); LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        due(project, today.minusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant());
        var peer = replacement();
        int priorCalls = model.requests.size(); model.enqueue(candidate("同一日期仅一份"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int n = 0; n < 8; n++) { MorningBriefService instance = n % 2 == 0 ? briefs : peer; futures.add(executor.submit(() -> instance.sweep(now))); }
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(latest(project)).containsEntry("status", "SUCCEEDED"));
        var occurrence = latest(project);
        assertThat(occurrence).containsEntry("localDate", today.toString()).containsEntry("missedFrom", today.minusDays(5).toString()).containsEntry("attemptCount", 1);
        assertThat(model.requests.size() - priorCalls).isEqualTo(1);
        var saved = peer.settings(project);
        assertThat(saved.nextFireAt()).isEqualTo(today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        peer.sweep(now); peer.sweep(now.minusSeconds(60));
        assertThat(objects(peer.history(project, 0, 20).get("items"))).hasSize(1);
        // Changing the timezone cannot give an already represented calendar date a second asset.
        String sameDateZone = now.atZone(ZoneOffset.UTC).getHour() == 0 ? "Etc/GMT-1" : "Etc/GMT+1";
        configure(project, "1", true, sameDateZone, 0);
        due(project, now.minusSeconds(120)); peer.sweep(now);
        assertThat(objects(peer.history(project, 0, 20).get("items"))).hasSize(1);
        assertThat(assets.all(project).stream().filter(a -> a.type().name().equals("QUALITY_BRIEF"))).hasSize(1);
    }

    @Test void dayWindowUsesRealDstBoundariesAndOverlapFiresOnlyOnce() {
        ZoneId zone = ZoneId.of("America/New_York");
        assertThat(MorningBriefService.fire(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), zone)).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        assertThat(MorningBriefService.fire(LocalDate.of(2026, 11, 1), LocalTime.of(1, 30), zone)).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(MorningBriefService.nextFire(LocalTime.of(1, 30), zone, Instant.parse("2026-11-01T05:30:00Z"))).isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
        String project = project().id();
        Map<String, Object> spring = metrics.captureRange(project, LocalDate.of(2026, 3, 8).atStartOfDay(zone).toInstant(), LocalDate.of(2026, 3, 9).atStartOfDay(zone).toInstant());
        assertThat(spring).containsEntry("from", "2026-03-08T05:00:00Z").containsEntry("to", "2026-03-09T04:00:00Z").containsEntry("passRatePercent", null);
        Map<String, Object> autumn = metrics.captureRange(project, LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant(), LocalDate.of(2026, 11, 2).atStartOfDay(zone).toInstant());
        assertThat(autumn).containsEntry("from", "2026-11-01T04:00:00Z").containsEntry("to", "2026-11-02T05:00:00Z");
    }

    @Test void retryBudgetAndInterruptedAttemptsSurviveSchedulerReplacement() throws Exception {
        String project = project().id(); configure(project, "0", true, "UTC", 1);
        model.enqueue(candidate(" ")); model.enqueue(candidate(" "));
        due(project, LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()); briefs.sweep(Instant.now());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(latest(project)).containsEntry("status", "FAILED"));
        var peer = replacement();
        peer.sweep(Instant.now().plusSeconds(5));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var item = latest(project); assertThat(item).containsEntry("attemptCount", 2).containsEntry("status", "FAILED");
        });
        peer.sweep(Instant.now().plusSeconds(10)); peer.sweep(Instant.now().plusSeconds(20));
        assertThat(latest(project)).containsEntry("attemptCount", 2).containsEntry("status", "FAILED").containsEntry("assetId", null);

        String interrupted = project().id(); configure(interrupted, "0", true, "UTC", 3);
        var held = model.hold(candidate("中断后不能写入"));
        due(interrupted, LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()); peer.sweep(Instant.now());
        assertThat(held.entered().await(20, TimeUnit.SECONDS)).isTrue();
        String job = attemptJob(latest(interrupted));
        try {
            jdbc.update("UPDATE job_task SET lease_until=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(5)), job);
            jobs.leases();
        } finally { held.release().countDown(); }
        peer.sweep(Instant.now().plusSeconds(10));
        assertThat(latest(interrupted)).containsEntry("status", "INTERRUPTED").containsEntry("attemptCount", 1);
        assertThat(assets.all(interrupted)).isEmpty();
    }

    @Test void projectDeletionPreventsPublicationAndStillTerminatesTheInternalJob() throws Exception {
        String project = project().id(); configure(project, "0", true, "UTC", 1);
        var held = model.hold(candidate("删除后的晚到结果"));
        due(project, LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()); briefs.sweep(Instant.now());
        assertThat(held.entered().await(20, TimeUnit.SECONDS)).isTrue();
        String job = attemptJob(latest(project));
        try { assets.delete(project, project, assets.get(project, project).version()); }
        finally { held.release().countDown(); }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT status FROM job_task WHERE id=?", String.class, job)).isIn("FAILED", "CANCELLED", "INTERRUPTED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE project_id=? AND asset_type='QUALITY_BRIEF'", Long.class, project)).isZero();
        assertThat(request("GET", "/api/projects/" + project + "/morning-brief/history", null).statusCode()).isEqualTo(404);
        replacement().sweep(Instant.now().plusSeconds(20));
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM morning_brief_occurrence WHERE project_id=?", Integer.class, project)).isEqualTo(1);
    }

    private MorningBriefService replacement() { return new MorningBriefService(projects, jdbc, transactions, json, jobs, conversations, metrics, false, 1); }
    private void configure(String project, String version, boolean enabled, String zone, int retries) { briefs.configure(project, Map.of("baseVersion", version, "enabled", enabled, "time", "00:00", "timezone", zone, "instruction", "以前一天的实际记录为依据", "maxRetries", retries)); }
    private void due(String project, Instant instant) { jdbc.update("UPDATE morning_brief_schedule SET next_fire_at=? WHERE project_id=?", Timestamp.from(instant), project); }
    private Map<String, Object> latest(String project) { var rows = objects(briefs.history(project, 0, 20).get("items")); assertThat(rows).isNotEmpty(); return rows.getFirst(); }
    private String attemptJob(Map<String, Object> occurrence) { return objects(json.map(json.write(occurrence)).get("attempts")).getLast().get("jobId").toString(); }
    private String candidate(String content) { return json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "QUALITY_BRIEF", "name", "晨报", "data", Map.of("content", content))))); }
}
