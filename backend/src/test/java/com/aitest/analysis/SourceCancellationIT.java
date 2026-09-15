package com.aitest.analysis;

import com.aitest.analysis.source.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobContext;
import com.aitest.job.JobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

class SourceCancellationIT extends ExchangeHttpTest {
    @TempDir Path root;
    @Autowired JobService jobs;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean SourceCollector collector;
    @MockitoSpyBean SourceAnalysisService analysis;

    @Test void cancellationAfterRealFileCollectionCannotPublishASnapshotOrGeneratedAssets() throws Exception {
        Files.writeString(root.resolve("Canceled.java"), "class Canceled { int value() { return 3; } }");
        CountDownLatch collected = new CountDownLatch(1), resume = new CountDownLatch(1), exited = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod(); collected.countDown();
            if (!resume.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Cancellation fixture timed out");
            return result;
        }).when(collector).local(eq("BACKEND"), eq(root.toString()), any(SourceBudget.class), any(Runnable.class));
        doAnswer(call -> { try { return call.callRealMethod(); } finally { exited.countDown(); } }).when(analysis).execute(any(JobContext.class), anyMap());
        String project = project().id(), path = "/api/projects/" + project + "/source-analyses";
        var submission = object(request("POST", path, Map.of("backendRepoPath", root.toString(), "idempotencyKey", "cancel")));
        try {
            assertThat(collected.await(15, TimeUnit.SECONDS)).isTrue();
            var cancelled = request("POST", path + "/" + submission.get("analysisId") + "/cancel", Map.of());
            assertThat(cancelled.statusCode()).isEqualTo(200);
            assertThat(object(cancelled).get("status")).isEqualTo("CANCELLED");
        } finally { resume.countDown(); }
        assertThat(exited.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(jobs.get(project, submission.get("jobId").toString()).status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_snapshot_file WHERE snapshot_id=?", Integer.class, submission.get("analysisId"))).isZero();
        assertThat(assets.all(project)).isEmpty();
        assertThat(request("GET", path + "/" + submission.get("analysisId") + "/files", null).statusCode()).isEqualTo(409);
    }
}
