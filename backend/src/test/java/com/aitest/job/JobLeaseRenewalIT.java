package com.aitest.job;

import com.aitest.asset.AssetService;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Import(JobLeaseRenewalIT.Handlers.class)
@TestPropertySource(properties = "aitest.execution.lease-initial-delay-ms=3600000")
class JobLeaseRenewalIT extends MySqlIntegrationTest {
    @Autowired JobService jobs;
    @Autowired AssetService assets;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    private static final CountDownLatch workersEntered = new CountDownLatch(2), releaseWorkers = new CountDownLatch(1);

    @Test void oneAtomicCommitCannotBlockRenewalOfAnotherRunningJobOrCreateAnIndexLockCycle() throws Exception {
        String project = assets.createProject("Lease contention " + UUID.randomUUID(), Map.of()).id();
        Job first = jobs.submit(project, "TEST_RENEWAL_WAIT", "first", Map.of());
        Job second = jobs.submit(project, "TEST_RENEWAL_WAIT", "second", Map.of());
        CountDownLatch locked = new CountDownLatch(1), releaseLock = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(workersEntered.await(10, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE job_task SET lease_until=? WHERE id IN (?,?)", Timestamp.from(Instant.now().plusSeconds(10)), first.id(), second.id());
            Future<?> committing = threads.submit(() -> jobs.atomic(project, first.id(), () -> {
                locked.countDown();
                try { if (!releaseLock.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock was not released"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return Map.of("committed", true);
            }, true));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> renewing = threads.submit(jobs::leases);
            boolean completedWhileLocked; Instant secondDeadline = Instant.MIN;
            try {
                try { renewing.get(2, TimeUnit.SECONDS); completedWhileLocked = true; }
                catch (TimeoutException blocked) { completedWhileLocked = false; }
                if (completedWhileLocked) secondDeadline = jdbc.queryForObject("SELECT lease_until FROM job_task WHERE id=?", Timestamp.class, second.id()).toInstant();
            } finally { releaseLock.countDown(); releaseWorkers.countDown(); }
            committing.get(10, TimeUnit.SECONDS); renewing.get(10, TimeUnit.SECONDS);
            assertThat(completedWhileLocked).as("A held job row must be skipped while other leases renew, without waiting for its transaction").isTrue();
            assertThat(secondDeadline).isAfter(Instant.now().plusSeconds(15));
            assertThat(jobs.get(project, first.id()).status()).isEqualTo("SUCCEEDED");
        } finally { releaseLock.countDown(); releaseWorkers.countDown(); }
    }

    @Test void expirySweepSkipsHeldRowsAndRechecksTheDeadlineOfAnotherOwner() throws Exception {
        String project = assets.createProject("Expiry contention " + UUID.randomUUID(), Map.of()).id();
        String held = previousProcessJob(project, Instant.now().minusSeconds(30));
        String stale = previousProcessJob(project, Instant.now().minusSeconds(30));
        Instant liveDeadline = Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String live = previousProcessJob(project, liveDeadline);
        CountDownLatch locked = new CountDownLatch(1), releaseLock = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> renewingElsewhere = threads.submit(() -> transactions.executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM job_task WHERE id=? FOR UPDATE", String.class, held);
                locked.countDown();
                try { if (!releaseLock.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock was not released"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                jdbc.update("UPDATE job_task SET lease_until=? WHERE id=?", Timestamp.from(liveDeadline), held);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                threads.submit(jobs::leases).get(2, TimeUnit.SECONDS);
                assertThat(jobs.get(project, stale).status()).isEqualTo("INTERRUPTED");
                assertThat(jobs.get(project, held).status()).isEqualTo("RUNNING");
            } finally { releaseLock.countDown(); }
            renewingElsewhere.get(5, TimeUnit.SECONDS);
            jobs.leases();
            assertThat(jobs.get(project, held).status()).isEqualTo("RUNNING");
            assertThat(jobs.get(project, live).status()).isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("SELECT lease_until FROM job_task WHERE id=?", Timestamp.class, live).toInstant()).isEqualTo(liveDeadline);
            assertThat(jobs.events(project, stale, 0)).filteredOn(event -> event.type().equals("done")).hasSize(1);
        } finally {
            releaseLock.countDown(); jobs.cancel(project, held); jobs.cancel(project, live);
        }
    }

    private String previousProcessJob(String project, Instant deadline) {
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO job_task(id,project_id,kind,idempotency_key,input,status,owner,lease_until,created_at,updated_at) VALUES(?,?, 'PREVIOUS_PROCESS',?,'{}','RUNNING','another-process',?,?,?)",
                id, project, id, Timestamp.from(deadline), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return id;
    }

    @TestConfiguration static class Handlers {
        @Bean JobHandler renewalWait() { return new JobHandler() {
            @Override public String kind() { return "TEST_RENEWAL_WAIT"; }
            @Override public Map<String, Object> execute(JobContext context, Map<String, Object> input) throws Exception {
                workersEntered.countDown();
                if (!releaseWorkers.await(25, TimeUnit.SECONDS)) throw new IllegalStateException("Test workers were not released");
                return Map.of("released", true);
            }
        }; }
    }
}
