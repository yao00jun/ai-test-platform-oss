package com.aitest.job;

import com.aitest.asset.AssetService;
import com.aitest.asset.AssetType;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Import(JobShutdownIT.Handlers.class)
class JobShutdownIT extends MySqlIntegrationTest {
    static final CountDownLatch inTransaction = new CountDownLatch(1);
    static final AtomicBoolean interrupted = new AtomicBoolean();
    @Autowired JobService jobs;
    @Autowired AssetService assets;

    @Test void gracefulShutdownLetsAnActiveTransactionCommit() throws Exception {
        String project = assets.createProject("Shutdown " + UUID.randomUUID(), Map.of()).id();
        Job job = jobs.submit(project, "TEST_SHUTDOWN", "shutdown", Map.of());
        assertThat(inTransaction.await(10, TimeUnit.SECONDS)).isTrue();
        jobs.close();
        assertThat(interrupted).isFalse();
        assertThat(jobs.get(project, job.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(assets.list(project, AssetType.FUNCTIONAL_CASE, null, "", 0, 10).total()).isEqualTo(1);
    }

    @TestConfiguration static class Handlers {
        @Bean JobHandler slowCommit(AssetService assets) {
            return new JobHandler() {
                public String kind() { return "TEST_SHUTDOWN"; }
                public Map<String, Object> execute(JobContext job, Map<String, Object> input) {
                    return job.completeAtomically(() -> {
                        assets.create(job.projectId(), AssetType.FUNCTIONAL_CASE, null, "Committed", Map.of(), "MANUAL");
                        inTransaction.countDown();
                        try { Thread.sleep(300); }
                        catch (InterruptedException e) { interrupted.set(true); Thread.currentThread().interrupt(); }
                        return Map.of("committed", true);
                    });
                }
            };
        }
    }
}
