package com.aitest.engine.sql;

import com.aitest.asset.*;
import com.aitest.common.JsonCodec;
import com.aitest.execution.*;
import com.aitest.job.JobService;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class SqlEvidenceRegressionIT extends MySqlIntegrationTest {
    @Autowired AssetService assets; @Autowired ExecutionCoordinator coordinator; @Autowired RunRepository runs;
    @Autowired JobService jobs; @Autowired Environment configuration; @Autowired JsonCodec json;
    @Test void sqlLocalSecretIsMaskedInAnonymousBindingsAndReturnedAliases() {
        String project = assets.createProject("SQL evidence " + UUID.randomUUID(), Map.of()).id();
        Asset database = assets.create(project, AssetType.DATABASE_SOURCE, null, "Business", Map.of("jdbcUrl", configuration.getProperty("spring.datasource.url").replace("/ai_test_platform_test", "/ai_test_business_test"), "username", configuration.getProperty("spring.datasource.username"), "password", configuration.getProperty("spring.datasource.password")), "MANUAL");
        Asset query = assets.create(project, AssetType.SQL_VALIDATION, null, "Secret query", Map.of("databaseSourceId", database.id(), "sql", "SELECT :password AS value", "parameters", Map.of("password", "local-sql-secret-8123")), "MANUAL");
        var submitted = coordinator.submit(project, new ExecutionCoordinator.Request(query.id(), null, null, UUID.randomUUID().toString()));
        await().atMost(Duration.ofSeconds(20)).until(() -> jobs.get(project, submitted.jobId()).terminal());
        Map<String, Object> run = runs.get(project, submitted.runId());
        assertThat(run.get("status")).isEqualTo("PASSED");
        assertThat(json.write(run)).doesNotContain("local-sql-secret-8123");
    }
}
