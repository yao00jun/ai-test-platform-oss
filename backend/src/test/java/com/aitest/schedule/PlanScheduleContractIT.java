package com.aitest.schedule;

import com.aitest.ai.ModelSettingsService;
import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.*;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = "aitest.schedules.enabled=false")
class PlanScheduleContractIT extends ExchangeHttpTest {
    @Autowired ModelSettingsService settings;
    @Autowired JobService jobs;
    static final ModelFixtureServer model;
    static { try { model = new ModelFixtureServer(); } catch (Exception failure) { throw new ExceptionInInitializerError(failure); } }
    @AfterAll static void closeModel() { model.close(); }

    @Test void previewRespectsTimezoneAndSkipsANonexistentDstTimeWithoutCreatingRuns() throws Exception {
        String project = project().id();
        Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "排期预览", Map.of("diagnoseFailures", false), "MANUAL");
        String path = "/api/projects/" + project + "/plans/" + plan.id() + "/schedule/preview";
        var china = request("POST", path, Map.of("cronExpression", "0 0 9 * * *", "timezone", "Asia/Shanghai", "from", "2026-09-15T00:00:00Z"));
        assertThat(china.statusCode()).as(new String(china.body())).isEqualTo(200);
        assertThat(((List<?>) object(china).get("nextFireTimes")).stream().map(Object::toString).toList()).startsWith("2026-09-15T01:00:00Z", "2026-09-16T01:00:00Z");
        var dst = object(request("POST", path, Map.of("cronExpression", "0 30 2 * * *", "timezone", "America/New_York", "from", "2026-03-08T00:00:00Z")));
        assertThat(((List<?>) dst.get("nextFireTimes")).stream().map(Object::toString).toList()).startsWith("2026-03-09T06:30:00Z");
        assertThat(assets.get(project, plan.id())).isEqualTo(plan);
        assertThat(object(request("GET", "/api/projects/" + project + "/runs", null)).get("total")).isEqualTo(0);
        assertThat(request("POST", path, Map.of("cronExpression", "not cron", "timezone", "Asia/Shanghai")).statusCode()).isEqualTo(422);
        assertThat(request("GET", "/api/projects/" + project().id() + "/plans/" + plan.id() + "/schedule", null).statusCode()).isEqualTo(404);
    }

    @Test void anEnabledScheduleNeedsAnExplicitValidCronAndScheduleEditsUseTheAssetVersion() throws Exception {
        String project = project().id();
        var invalid = request("POST", "/api/projects/" + project + "/assets", Map.of("type", "TEST_PLAN", "name", "空排期", "data", Map.of("scheduleEnabled", true)));
        assertThat(invalid.statusCode()).isEqualTo(422);
        Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "手工排期", Map.of("description", "保留的说明", "diagnoseFailures", false), "MANUAL");
        String path = "/api/projects/" + project + "/plans/" + plan.id() + "/schedule";
        var saved = request("PUT", path, Map.of("baseVersion", plan.version(), "cronExpression", "0 0 9 * * *", "timezone", "Asia/Shanghai", "scheduleEnabled", true, "overlapPolicy", "QUEUE", "misfirePolicy", "FIRE_ONCE"));
        assertThat(saved.statusCode()).as(new String(saved.body())).isEqualTo(200);
        Asset updated = assets.get(project, plan.id());
        assertThat(updated.data()).containsEntry("description", "保留的说明").containsEntry("scheduleEnabled", true).containsEntry("misfirePolicy", "FIRE_ONCE");
        assertThat(request("PUT", path, Map.of("baseVersion", plan.version(), "scheduleEnabled", false)).statusCode()).isEqualTo(409);
        assertThat(assets.get(project, plan.id())).isEqualTo(updated);
        assets.update(project, plan.id(), updated.version(), null, Map.of("scheduleEnabled", false), null, "MANUAL");
    }

    @Test void aiCannotActivateAPatrolDuringGenerationOrLocalFeedback() throws Exception {
        settings.save(new ModelSettingsService.Input(model.url(), "schedule-fixture", "fixture", 0.1, 30));
        String project = project().id();
        model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "TEST_PLAN", "localKey", "plan", "name", "未经人工启用的排期", "data", Map.of("cronExpression", "0 0 9 * * *", "timezone", "Asia/Shanghai", "scheduleEnabled", true))))));
        var generation = object(request("POST", "/api/ai/generate", Map.of("projectId", project, "type", "TEST_PLAN", "instruction", "生成巡检草稿", "idempotencyKey", UUID.randomUUID().toString())));
        await().atMost(Duration.ofSeconds(20)).until(() -> jobs.get(project, generation.get("jobId").toString()).terminal());
        assertThat(jobs.get(project, generation.get("jobId").toString()).status()).isEqualTo("FAILED");
        assertThat(assets.all(project)).isEmpty();
        Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "草稿排期", Map.of("cronExpression", "0 0 9 * * *", "timezone", "Asia/Shanghai"), "MANUAL");
        model.enqueue(json.write(Map.of("data", Map.of("scheduleEnabled", true))));
        var local = object(request("POST", "/api/ai/refine-item", Map.of("projectId", project, "targetType", "TEST_PLAN", "targetId", plan.id(), "baseVersion", plan.version(), "feedback", "改进巡检说明", "idempotencyKey", UUID.randomUUID().toString())));
        await().atMost(Duration.ofSeconds(20)).until(() -> jobs.get(project, local.get("jobId").toString()).terminal());
        assertThat(jobs.get(project, local.get("jobId").toString()).status()).isEqualTo("FAILED");
        assertThat(assets.get(project, plan.id())).isEqualTo(plan);
    }
}
