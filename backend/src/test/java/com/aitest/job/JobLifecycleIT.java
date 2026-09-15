package com.aitest.job;

import com.aitest.asset.AssetService;
import com.aitest.common.Problem;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Import(JobLifecycleIT.Handlers.class)
@org.springframework.test.context.TestPropertySource(properties = "aitest.execution.lease-initial-delay-ms=60000")
class JobLifecycleIT extends MySqlIntegrationTest {
    @Autowired JobService jobs;
    @Autowired AssetService assets;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    static final java.util.concurrent.CountDownLatch finishing = new java.util.concurrent.CountDownLatch(1);
    static final java.util.concurrent.CountDownLatch releaseFinish = new java.util.concurrent.CountDownLatch(1);

    @Test void ordinaryHandlerCannotSucceedAfterLeaseExpiresWithoutScannerIntervention() throws Exception {
        String project = assets.createProject("Expired finish " + UUID.randomUUID(), Map.of()).id();
        Job job = jobs.submit(project, "TEST_FINISH_LEASE", "expired", Map.of());
        assertThat(finishing.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        jdbc.update("UPDATE job_task SET lease_until=? WHERE id=?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)), job.id());
        releaseFinish.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> jobs.get(project, job.id()).terminal());
        assertThat(jobs.get(project, job.id()).status()).isEqualTo("INTERRUPTED");
    }

    @Test
    void anExpiredLeaseCannotBeRenewedOrAuthorizeAnAtomicAssetWrite() {
        String project = assets.createProject("Lease " + UUID.randomUUID(), Map.of()).id();
        Job job = jobs.submit(project, "TEST_LEASE", "lease", Map.of());
        await().atMost(Duration.ofSeconds(10)).until(() -> jobs.get(project, job.id()).status().equals("RUNNING"));
        jdbc.update("UPDATE job_task SET lease_until=? WHERE id=?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)), job.id());
        jobs.leases();
        assertThat(jobs.get(project, job.id()).status()).isEqualTo("INTERRUPTED");
        assertThatThrownBy(() -> jobs.atomic(project, job.id(), () -> assets.create(project, com.aitest.asset.AssetType.FUNCTIONAL_CASE, null, "Must not exist", Map.of(), "AI"), true)).isInstanceOf(RuntimeException.class);
        assertThat(assets.list(project, com.aitest.asset.AssetType.FUNCTIONAL_CASE, null, "", 0, 100).total()).isZero();
    }

    @Test
    void duplicateSubmissionRunsOnceAndEventsCanResumeFromLastId() {
        String project = assets.createProject("Jobs " + UUID.randomUUID(), Map.of()).id();
        Job first = jobs.submit(project, "TEST_ECHO", "same-key", Map.of("value", "保留结果"));
        Job duplicate = jobs.submit(project, "TEST_ECHO", "same-key", Map.of("value", "保留结果"));
        assertThat(duplicate.id()).isEqualTo(first.id());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jobs.get(project, first.id()).status()).isEqualTo("SUCCEEDED"));
        assertThat(jobs.get(project, first.id()).result()).containsEntry("value", "保留结果");
        var events = jobs.events(project, first.id(), 0);
        assertThat(events).extracting(JobEvent::type).contains("progress", "done");
        long cut = events.getFirst().seq();
        assertThat(jobs.events(project, first.id(), cut)).allMatch(e -> e.seq() > cut);
        assertThatThrownBy(() -> jobs.submit(project, "TEST_ECHO", "same-key", Map.of("value", "不同输入"))).isInstanceOf(Problem.class);
    }

    @Test
    void cancellationKeepsTerminalStateAndRejectsCrossProjectAccess() {
        String project = assets.createProject("Cancellation " + UUID.randomUUID(), Map.of()).id();
        String other = assets.createProject("Other " + UUID.randomUUID(), Map.of()).id();
        Job job = jobs.submit(project, "TEST_WAIT", "wait", Map.of());
        jobs.cancel(project, job.id());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jobs.get(project, job.id()).status()).isEqualTo("CANCELLED"));
        assertThatThrownBy(() -> jobs.get(other, job.id())).isInstanceOf(Problem.class);
        assertThat(jobs.events(project, job.id(), 0)).extracting(JobEvent::type).contains("done");
    }

    @TestConfiguration
    static class Handlers {
        @Bean JobHandler finishLease() { return new JobHandler() {
            public String kind() { return "TEST_FINISH_LEASE"; }
            public Map<String, Object> execute(JobContext context, Map<String, Object> input) throws Exception {
                finishing.countDown(); releaseFinish.await(10, java.util.concurrent.TimeUnit.SECONDS); return Map.of();
            }
        }; }
        @Bean JobHandler leased() { return new JobHandler() {
            public String kind() { return "TEST_LEASE"; }
            public Map<String, Object> execute(JobContext context, Map<String, Object> input) throws Exception {
                Thread.sleep(1500); context.checkpoint(); return Map.of();
            }
        }; }
        @Bean JobHandler echo() { return new JobHandler() {
            public String kind() { return "TEST_ECHO"; }
            public Map<String, Object> execute(JobContext context, Map<String, Object> input) {
                context.progress(50, "已处理"); return input;
            }
        }; }
        @Bean JobHandler waiting() { return new JobHandler() {
            public String kind() { return "TEST_WAIT"; }
            public Map<String, Object> execute(JobContext context, Map<String, Object> input) throws Exception {
                for (int i = 0; i < 200; i++) { context.checkpoint(); Thread.sleep(50); }
                return Map.of("unexpected", true);
            }
        }; }
    }
}
