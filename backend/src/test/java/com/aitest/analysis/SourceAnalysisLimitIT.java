package com.aitest.analysis;

import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"aitest.analysis.max-file-bytes=4096", "aitest.analysis.max-total-bytes=4096"})
class SourceAnalysisLimitIT extends ExchangeHttpTest {
    @TempDir Path root;
    @Autowired JobService jobs;

    @Test void totalByteLimitSpansBackendFrontendAndPastedDdl() throws Exception {
        Files.writeString(root.resolve("Sample.java"), "class Sample {}\n//" + "a".repeat(2500));
        Files.writeString(root.resolve("Page.tsx"), "export const Page = () => <input id='sample' />;\n//" + "b".repeat(2500));
        String project = project().id(), path = "/api/projects/" + project + "/source-analyses";
        var response = request("POST", path, Map.of("backendRepoPath", root.toString(), "frontendRepoPath", root.toString(), "ddlText", "CREATE TABLE sample(id INT);", "idempotencyKey", "aggregate"));
        assertThat(response.statusCode()).isEqualTo(200);
        var submitted = object(response);
        await().atMost(Duration.ofSeconds(20)).until(() -> jobs.get(project, submitted.get("jobId").toString()).terminal());
        var snapshot = object(request("GET", path + "/" + submitted.get("analysisId"), null));
        assertThat(snapshot.get("status")).isEqualTo("PARTIAL");
        assertThat(((Number) snapshot.get("totalBytes")).longValue()).isLessThanOrEqualTo(4096);
        assertThat(objects(snapshot.get("diagnostics"))).anySatisfy(row -> assertThat(row.get("code")).isEqualTo("SOURCE_LIMIT"));
    }
}
