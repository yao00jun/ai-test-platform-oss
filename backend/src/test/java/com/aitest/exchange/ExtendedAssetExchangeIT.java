package com.aitest.exchange;

import com.aitest.ai.ModelSettingsService;
import com.aitest.asset.Asset;
import com.aitest.asset.AssetType;
import com.aitest.job.JobService;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"aitest.schedules.enabled=false", "aitest.morning-brief.enabled=false", "aitest.notifications.enabled=false"})
class ExtendedAssetExchangeIT extends ExchangeHttpTest {
    static final ModelFixtureServer model;
    static { try { model = new ModelFixtureServer(); } catch (Exception failure) { throw new ExceptionInInitializerError(failure); } }
    @Autowired ModelSettingsService settings;
    @Autowired JobService jobs;
    @Autowired JdbcTemplate jdbc;
    @AfterAll static void closeModel() { model.close(); }

    @Test void sourceBoundSqlCanMoveProjectsWithoutForgingDestinationEvidenceOrChangingItsQuery() throws Exception {
        String project = project().id();
        var analysis = request("POST", "/api/projects/" + project + "/source-analyses", Map.of("ddlText", "CREATE TABLE orders(id BIGINT PRIMARY KEY, state VARCHAR(20));", "idempotencyKey", UUID.randomUUID().toString()));
        assertThat(analysis.statusCode()).as(new String(analysis.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        var submitted = object(analysis);
        finished(project, submitted.get("jobId").toString());
        String source = submitted.get("analysisId").toString();
        settings.save(new ModelSettingsService.Input(model.url(), "exchange-fixture", "fixture", 0.1, 30));
        model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "SQL_VALIDATION", "localKey", "query", "name", "退款状态", "data", Map.of("sql", "SELECT state FROM orders WHERE id=:id", "parameters", Map.of("id", 1), "exports", Map.of("state", "state")))))));
        var generated = request("POST", "/api/ai/generate", Map.of("projectId", project, "type", "SQL_VALIDATION", "sourceSnapshotId", source, "instruction", "查询退款状态", "idempotencyKey", UUID.randomUUID().toString()));
        assertThat(generated.statusCode()).isEqualTo(202);
        finished(project, object(generated).get("jobId").toString());
        Asset original = assets.list(project, AssetType.SQL_VALIDATION, null, "", 0, 10).items().getFirst();
        assertThat(original.data()).containsEntry("sourceSnapshotId", source);
        assertThat(map(original.data().get("generationEvidence"))).isNotEmpty();

        for (String format : List.of("json", "yaml", "xlsx")) {
            var file = request("POST", "/api/projects/" + project + "/exports", Map.of("type", original.type(), "assetIds", List.of(original.id()), "format", format));
            assertThat(file.statusCode()).isEqualTo(200);
            String destination = project().id();
            var preview = preview(destination, "SQL_VALIDATION", format, "query." + format, file.body(), Map.of());
            assertThat(objects(preview.get("errors"))).as("portable source references in " + format).isEmpty();
            assertThat(objects(preview.get("warnings"))).anySatisfy(warning -> assertThat(warning.get("field")).isEqualTo("sourceSnapshotId"));
            var archived = map(map(preview.get("metadata")).get("detachedSourceEvidence"));
            assertThat(archived).hasSize(1);
            var evidence = map(archived.values().iterator().next());
            assertThat(evidence).containsEntry("sourceSnapshotId", source);
            assertThat(map(evidence.get("generationEvidence"))).isNotEmpty().doesNotContainKey("seal");
            apply(destination, preview);
            var copy = assets.list(destination, original.type(), null, "", 0, 10).items().getFirst();
            assertThat(copy.data()).containsEntry("sql", "SELECT state FROM orders WHERE id=:id").containsEntry("sourceSnapshotId", "").containsEntry("generationEvidence", Map.of());
            assertThat(copy.data().get("exports")).isEqualTo(Map.of("state", "state"));
            assertThat(assets.get(project, original.id())).isEqualTo(original);
        }
    }

    @Test void enabledWebhookRoundTripsAsDisabledWithCredentialsOmittedAndRetrySettingsIntact() throws Exception {
        String project = project().id();
        Asset created = assets.create(project, AssetType.WEBHOOK, null, "发布通知", Map.of("platform", "DINGTALK", "webhookUrl", "http://127.0.0.1:9/hook?access_token=portable-secret", "secret", "portable-signing-key", "enabled", true, "maxRetries", 2, "timeoutSeconds", 5), "MANUAL");
        Asset original = assets.get(project, created.id());
        for (String format : List.of("json", "yaml", "xlsx")) {
            var file = request("POST", "/api/projects/" + project + "/exports", Map.of("type", original.type(), "assetIds", List.of(original.id()), "format", format));
            assertThat(file.statusCode()).isEqualTo(200);
            String destination = project().id();
            var preview = preview(destination, "WEBHOOK", format, "notice." + format, file.body(), Map.of());
            assertThat(objects(preview.get("errors"))).as("portable enabled webhook in " + format).isEmpty();
            assertThat(json.write(preview)).doesNotContain("portable-secret", "portable-signing-key");
            apply(destination, preview);
            Asset copy = assets.list(destination, AssetType.WEBHOOK, null, "", 0, 10).items().getFirst();
            assertThat(copy.data()).containsEntry("enabled", false).containsEntry("webhookUrl", "").containsEntry("secret", "");
            assertThat(((Number) copy.data().get("maxRetries")).intValue()).isEqualTo(2);
            assertThat(((Number) copy.data().get("timeoutSeconds")).intValue()).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT enabled FROM notification_subscription WHERE webhook_id=?", Boolean.class, copy.id())).isFalse();
        }
        assertThat(assets.get(project, original.id())).isEqualTo(original);
    }

    @Test void portablePlanKeepsScheduleChoicesButRequiresActivationInTheDestination() throws Exception {
        String project = project().id();
        Asset original = assets.create(project, AssetType.TEST_PLAN, null, "每日回归", Map.of("cronExpression", "0 0 9 * * *", "timezone", "Asia/Shanghai", "scheduleEnabled", true, "overlapPolicy", "QUEUE", "misfirePolicy", "FIRE_ONCE"), "MANUAL");
        var file = request("POST", "/api/projects/" + project + "/exports", Map.of("type", original.type(), "assetIds", List.of(original.id()), "format", "json"));
        String destination = project().id();
        var preview = preview(destination, "TEST_PLAN", "json", "plan.json", file.body(), Map.of());
        assertThat(objects(preview.get("errors"))).isEmpty();
        apply(destination, preview);
        Asset copy = assets.list(destination, AssetType.TEST_PLAN, null, "", 0, 10).items().getFirst();
        assertThat(copy.data()).containsEntry("scheduleEnabled", false).containsEntry("cronExpression", "0 0 9 * * *").containsEntry("timezone", "Asia/Shanghai").containsEntry("overlapPolicy", "QUEUE").containsEntry("misfirePolicy", "FIRE_ONCE");
        assertThat(assets.get(project, original.id())).isEqualTo(original);
    }

    @Test void codeDiagnosisRemainsAnEditableHypothesisAfterPortableImport() throws Exception {
        String project = project().id();
        var diagnosis = Map.of("formatVersion", "aitest.code-rca/v1", "root_cause", "金额边界遗漏", "affected_code_path", "Refund.java:12", "suggested_fix", "--- a/Refund.java\n+++ b/Refund.java\n@@ -12 +12 @@\n-if (amount < 0)\n+if (amount <= 0)\n", "is_regression", false, "confidence", 0.6);
        Asset original = assets.create(project, AssetType.BUG, null, "退款金额检查", Map.of("codeDiagnosis", diagnosis, "rootCauseAnalysis", "人工修订诊断"), "MANUAL");
        for (String format : List.of("json", "xlsx")) {
            var file = request("POST", "/api/projects/" + project + "/exports", Map.of("type", original.type(), "assetIds", List.of(original.id()), "format", format));
            String destination = project().id();
            var preview = preview(destination, "BUG", format, "bug." + format, file.body(), Map.of());
            assertThat(objects(preview.get("errors"))).isEmpty(); apply(destination, preview);
            Asset copy = assets.list(destination, AssetType.BUG, null, "", 0, 10).items().getFirst();
            assertThat(copy.data().get("codeDiagnosis")).isEqualTo(diagnosis);
            assertThat(copy.data()).containsEntry("rootCauseAnalysis", "人工修订诊断").containsEntry("runId", "").containsEntry("sourceSnapshotId", "");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bug_rca_evaluation WHERE bug_id=?", Long.class, copy.id())).isZero();
        }
    }

    private void finished(String project, String id) {
        await().atMost(Duration.ofSeconds(35)).until(() -> jobs.get(project, id).terminal());
        assertThat(jobs.get(project, id).status()).as(jobs.get(project, id).error()).isEqualTo("SUCCEEDED");
    }
}
