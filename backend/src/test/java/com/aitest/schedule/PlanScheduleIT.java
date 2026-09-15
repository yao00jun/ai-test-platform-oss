package com.aitest.schedule;

import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.execution.*;
import com.aitest.job.JobService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"aitest.schedules.enabled=false", "aitest.schedules.max-queued-per-plan=2"})
class PlanScheduleIT extends ExchangeHttpTest {
    @Autowired PlanScheduleService schedules;
    @Autowired JdbcTemplate jdbc;
    @Autowired AssetRepository repository;
    @Autowired TransactionTemplate transactions;
    @Autowired ExecutionCoordinator execution;
    @Autowired RunRepository runs;
    @Autowired JobService jobs;
    final List<String> projects = new ArrayList<>();
    @Override protected Asset project() { Asset project = super.project(); projects.add(project.id()); return project; }
    @AfterEach void cleanProjects() { for (String project : projects) assets.delete(project, project, assets.get(project, project).version()); }

    @Test void concurrentSweepsSubmitOnceAndQueueWaitsForTheActualRunBeforeCapturingCurrentAssets() throws Exception {
        String project = project().id(); Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        try (Site site = new Site()) {
            Asset api = create(project, AssetType.API_CASE, "第一次请求", Map.of("path", "/first"));
            Asset plan = automaticPlan(project, api, site, "QUEUE", "SKIP");
            schedules.sweep(start);
            parallelSweeps(start.plusSeconds(1));
            assertThat(site.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(runCount(plan)).isEqualTo(1);
            schedules.sweep(start.plusSeconds(2));
            assertThat(statuses(plan)).containsExactlyInAnyOrder("SUBMITTED", "WAITING");
            assertThat(runCount(plan)).isEqualTo(1);
            assets.update(project, api.id(), api.version(), null, Map.of("path", "/second"), null, "MANUAL");
            assets.update(project, plan.id(), plan.version(), null, Map.of("description", "排队期间的人工修改"), null, "MANUAL");
            String firstRun = runIds(plan).getFirst(); site.release.countDown(); finished(project, firstRun);
            schedules.sweep(start.plusSeconds(2));
            await().atMost(Duration.ofSeconds(10)).until(() -> site.paths.size() == 2);
            assertThat(site.paths).containsExactly("/first", "/second");
            for (String run : runIds(plan)) finished(project, run);
            assertThat(site.peak.get()).isEqualTo(1);
            var second = jdbc.queryForMap("SELECT plan_version,dispatched_plan_version FROM plan_schedule_occurrence WHERE plan_id=? AND scheduled_for=?", plan.id(), Timestamp.from(start.plusSeconds(2)));
            assertThat(((Number) second.get("plan_version")).longValue()).isEqualTo(1);
            assertThat(((Number) second.get("dispatched_plan_version")).longValue()).isEqualTo(2);
            jdbc.update("UPDATE plan_schedule_cursor SET next_fire_at=? WHERE plan_id=?", Timestamp.from(start.plusSeconds(1)), plan.id());
            parallelSweeps(start.plusSeconds(2));
            assertThat(runCount(plan)).isEqualTo(2);
            assertThat(statuses(plan)).containsExactlyInAnyOrder("SUBMITTED", "SUBMITTED");
        }
    }

    @Test void skipPolicyDetectsAnAlreadyRunningManualLaunchOfTheSamePlan() throws Exception {
        String project = project().id(); Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        try (Site site = new Site()) {
            Asset api = create(project, AssetType.API_CASE, "运行中的接口", Map.of("path", "/"));
            Asset plan = automaticPlan(project, api, site, "SKIP", "SKIP");
            var manual = execution.submit(project, new ExecutionCoordinator.Request(plan.id(), null, null, UUID.randomUUID().toString()));
            assertThat(site.entered.await(10, TimeUnit.SECONDS)).isTrue();
            schedules.sweep(start); schedules.sweep(start.plusSeconds(1));
            assertThat(statuses(plan)).containsExactly("SKIPPED_OVERLAP"); assertThat(runCount(plan)).isEqualTo(1);
            site.release.countDown(); finished(project, manual.runId());
            schedules.sweep(start.plusSeconds(2));
            await().atMost(Duration.ofSeconds(10)).until(() -> site.paths.size() == 2);
            assertThat(runCount(plan)).isEqualTo(2); assertThat(site.peak.get()).isEqualTo(1);
            for (String run : runIds(plan)) finished(project, run);
        }
    }

    @Test void waitingManualResultsDoNotBlockTheNextPatrolAndInvalidPlansHaveVisibleFailedOccurrences() {
        String project = project().id(); Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Asset manual = create(project, AssetType.FUNCTIONAL_CASE, "待人工核对", Map.of());
        Asset plan = plan(project, "SKIP", "SKIP");
        assets.create(project, AssetType.PLAN_ITEM, plan.id(), "人工项", Map.of("targetId", manual.id(), "executionMode", "MANUAL"), "MANUAL");
        Asset empty = plan(project, "SKIP", "SKIP");
        schedules.sweep(start); schedules.sweep(start.plusSeconds(1));
        finished(project, runIds(plan).getFirst());
        assertThat(runs.get(project, runIds(plan).getFirst()).get("status")).isEqualTo("MANUAL_PENDING");
        assertThat(statuses(empty)).containsExactly("FAILED"); assertThat(runCount(empty)).isZero();
        schedules.sweep(start.plusSeconds(2));
        assertThat(runCount(plan)).isEqualTo(2);
        for (String run : runIds(plan)) finished(project, run);
    }

    @Test void monthsOfMissedSecondTriggersCoalesceAndAReplacementSchedulerDoesNotReplayTheOccurrence() {
        String project = project().id(); Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS), later = start.plus(90, ChronoUnit.DAYS);
        Asset manual = create(project, AssetType.FUNCTIONAL_CASE, "巡检人工项", Map.of());
        Asset fire = plan(project, "QUEUE", "FIRE_ONCE"), skip = plan(project, "QUEUE", "SKIP");
        for (Asset plan : List.of(fire, skip)) assets.create(project, AssetType.PLAN_ITEM, plan.id(), "人工项", Map.of("targetId", manual.id()), "MANUAL");
        schedules.sweep(start);
        long started = System.nanoTime(); schedules.sweep(later);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(statuses(fire)).containsExactly("SUBMITTED"); assertThat(runCount(fire)).isEqualTo(1);
        assertThat(statuses(skip)).containsExactly("SKIPPED_MISFIRE"); assertThat(runCount(skip)).isZero();
        assertThat(jdbc.queryForObject("SELECT misfire_through FROM plan_schedule_occurrence WHERE plan_id=?", Timestamp.class, fire.id()).toInstant()).isEqualTo(later);
        finished(project, runIds(fire).getFirst());
        PlanScheduleService recovered = new PlanScheduleService(repository, assets, jdbc, transactions, execution, json, "Asia/Shanghai", 30, 2, false);
        recovered.sweep(later);
        assertThat(runCount(fire)).isEqualTo(1); assertThat(statuses(fire)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT next_fire_at FROM plan_schedule_cursor WHERE plan_id=?", Timestamp.class, fire.id()).toInstant()).isEqualTo(later.plusSeconds(1));
    }

    @Test void aShortPollingDelayRecordsTheCoalescedIntervalWithoutAnUnboundedCatchupLoop() {
        String project = project().id(); Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Asset manual = create(project, AssetType.FUNCTIONAL_CASE, "延迟巡检人工项", Map.of());
        Asset plan = plan(project, "QUEUE", "FIRE_ONCE");
        assets.create(project, AssetType.PLAN_ITEM, plan.id(), "人工项", Map.of("targetId", manual.id()), "MANUAL");
        schedules.sweep(start); schedules.sweep(start.plusSeconds(3));
        assertThat(runCount(plan)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT misfire_through FROM plan_schedule_occurrence WHERE plan_id=?", Timestamp.class, plan.id())).isEqualTo(Timestamp.from(start.plusSeconds(3)));
        assertThat(jdbc.queryForObject("SELECT reason FROM plan_schedule_occurrence WHERE plan_id=?", String.class, plan.id())).contains("合并");
        finished(project, runIds(plan).getFirst());
    }

    @Test void queueCapacityScheduleChangesAndDisablingPreserveTheInFlightRun() throws Exception {
        String project = project().id(); Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        try (Site site = new Site()) {
            Asset api = create(project, AssetType.API_CASE, "长耗时巡检", Map.of("path", "/"));
            Asset plan = automaticPlan(project, api, site, "QUEUE", "SKIP");
            schedules.sweep(start); schedules.sweep(start.plusSeconds(1));
            assertThat(site.entered.await(10, TimeUnit.SECONDS)).isTrue();
            schedules.sweep(start.plusSeconds(2)); schedules.sweep(start.plusSeconds(3)); schedules.sweep(start.plusSeconds(4));
            assertThat(statuses(plan)).containsExactlyInAnyOrder("SUBMITTED", "WAITING", "WAITING", "SKIPPED_CAPACITY");
            Asset edited = assets.update(project, plan.id(), plan.version(), null, Map.of("timezone", "UTC"), null, "MANUAL");
            schedules.sweep(start.plusSeconds(4));
            assertThat(statuses(plan)).containsExactlyInAnyOrder("SUBMITTED", "CANCELLED", "CANCELLED", "SKIPPED_CAPACITY");
            schedules.sweep(start.plusSeconds(5));
            assertThat(statuses(plan)).contains("WAITING");
            assets.update(project, plan.id(), edited.version(), null, Map.of("scheduleEnabled", false), null, "MANUAL");
            schedules.sweep(start.plusSeconds(6));
            assertThat(statuses(plan)).doesNotContain("WAITING"); assertThat(runCount(plan)).isEqualTo(1);
            site.release.countDown(); finished(project, runIds(plan).getFirst());
            assertThat(runs.get(project, runIds(plan).getFirst()).get("status")).isEqualTo("PASSED");
        }
    }

    @Test void deletingAPlanRetiresItsQueueWithoutRemovingOrChangingTheExecutingSnapshot() throws Exception {
        String project = project().id(); Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        try (Site site = new Site()) {
            Asset api = create(project, AssetType.API_CASE, "执行快照里的接口", Map.of("path", "/"));
            Asset plan = automaticPlan(project, api, site, "QUEUE", "SKIP");
            schedules.sweep(start); schedules.sweep(start.plusSeconds(1));
            assertThat(site.entered.await(10, TimeUnit.SECONDS)).isTrue();
            schedules.sweep(start.plusSeconds(2));
            assertThat(statuses(plan)).contains("WAITING");
            String run = runIds(plan).getFirst(); Object snapshot = runs.get(project, run).get("snapshot");
            assets.delete(project, plan.id(), plan.version()); schedules.sweep(start.plusSeconds(3));
            assertThat(statuses(plan)).containsExactlyInAnyOrder("SUBMITTED", "CANCELLED");
            assertThat(jdbc.queryForObject("SELECT enabled FROM plan_schedule_cursor WHERE plan_id=?", Boolean.class, plan.id())).isFalse();
            site.release.countDown(); finished(project, run);
            assertThat(runs.get(project, run)).containsEntry("status", "PASSED").containsEntry("snapshot", snapshot);
        }
    }

    private Asset plan(String project, String overlap, String misfire) { return create(project, AssetType.TEST_PLAN, "持久化巡检", Map.of("scheduleEnabled", true, "cronExpression", "* * * * * *", "timezone", "Asia/Shanghai", "overlapPolicy", overlap, "misfirePolicy", misfire, "diagnoseFailures", false)); }
    private Asset automaticPlan(String project, Asset api, Site site, String overlap, String misfire) {
        Asset env = create(project, AssetType.ENVIRONMENT, "巡检环境", Map.of("baseUrl", site.url()));
        Asset plan = create(project, AssetType.TEST_PLAN, "持久化巡检", Map.of("scheduleEnabled", true, "cronExpression", "* * * * * *", "timezone", "Asia/Shanghai", "overlapPolicy", overlap, "misfirePolicy", misfire, "diagnoseFailures", false, "environmentId", env.id()));
        assets.create(project, AssetType.PLAN_ITEM, plan.id(), "接口项", Map.of("targetId", api.id()), "MANUAL"); return plan;
    }
    private Asset create(String project, AssetType type, String name, Map<String, Object> data) { return assets.create(project, type, null, name, data, "MANUAL"); }
    private List<String> statuses(Asset plan) { return jdbc.queryForList("SELECT status FROM plan_schedule_occurrence WHERE plan_id=? ORDER BY scheduled_for", String.class, plan.id()); }
    private long runCount(Asset plan) { return jdbc.queryForObject("SELECT COUNT(*) FROM test_run WHERE project_id=? AND asset_id=?", Long.class, plan.projectId(), plan.id()); }
    private List<String> runIds(Asset plan) { return jdbc.queryForList("SELECT run_id FROM plan_schedule_occurrence WHERE plan_id=? AND run_id IS NOT NULL ORDER BY scheduled_for", String.class, plan.id()); }
    private void finished(String project, String run) { await().atMost(Duration.ofSeconds(15)).until(() -> !List.of("QUEUED", "RUNNING").contains(runs.get(project, run).get("status"))); }
    private void parallelSweeps(Instant instant) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) { List<Future<?>> futures = new ArrayList<>(); for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> schedules.sweep(instant))); for (var future : futures) future.get(20, TimeUnit.SECONDS); }
    }
    private static class Site implements AutoCloseable {
        final HttpServer server; final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        final List<String> paths = new CopyOnWriteArrayList<>();
        Site() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
            server.createContext("/", request -> {
                int current = active.incrementAndGet(); peak.accumulateAndGet(current, Math::max); paths.add(request.getRequestURI().getPath()); entered.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture gate timed out"); request.sendResponseHeaders(200, 2); request.getResponseBody().write("{}".getBytes()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { active.decrementAndGet(); request.close(); }
            }); server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        public void close() { release.countDown(); server.stop(0); executor.close(); }
    }
}
