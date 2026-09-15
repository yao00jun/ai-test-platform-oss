package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.job.Job;
import com.aitest.job.JobService;
import com.aitest.support.ModelFixtureServer;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class LocalRefinementIT extends MySqlIntegrationTest {
    static final ModelFixtureServer model;
    static { try { model = new ModelFixtureServer(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @Autowired AssetService assets;
    @Autowired ModelSettingsService settings;
    @Autowired AiRefinementService refinement;
    @Autowired JobService jobs;
    @Autowired AiConversationService conversations;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @BeforeEach void configure() { settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30)); }
    @AfterAll static void close() { model.close(); }

    @Test void threeFeedbackRoundsKeepIdentityAndOtherNinetyNineAssetsUntouched() {
        Asset project = assets.createProject("AI rounds " + UUID.randomUUID(), Map.of());
        for (int i = 0; i < 100; i++) assets.create(project.id(), AssetType.FUNCTIONAL_CASE, null, "用例 " + i, Map.of(), "MANUAL");
        List<Asset> before = assets.list(project.id(), AssetType.FUNCTIONAL_CASE, null, "", 0, 200).items();
        Asset target = before.get(42); String conversationId = null;
        for (int round = 1; round <= 3; round++) {
            model.enqueue("{\"data\":{\"precondition\":\"第 " + round + " 轮已细化\"}}");
            var request = request(project.id(), target, conversationId, "第 " + round + " 轮反馈", "round-" + round);
            var submitted = refinement.submit(request); conversationId = submitted.conversationId();
            Job done = terminal(project.id(), submitted.jobId());
            assertThat(done.status()).withFailMessage("Refinement failed: %s", done.error()).isEqualTo("SUCCEEDED");
            assertThat(refinement.submit(request).jobId()).isEqualTo(submitted.jobId());
            target = assets.get(project.id(), target.id());
            assertThat(target.version()).isEqualTo(String.valueOf(round + 1));
        }
        List<Asset> after = assets.list(project.id(), AssetType.FUNCTIONAL_CASE, null, "", 0, 200).items();
        for (int i = 0; i < 100; i++) if (i != 42) assertThat(after.get(i)).isEqualTo(before.get(i));
        assertThat(conversations.messages(project.id(), conversationId)).hasSize(6);
        assertThat(model.requests.getLast()).contains("第 2 轮已细化", "第 1 轮反馈", "第 3 轮反馈");
    }
    @Test void humanEditDuringGenerationWinsAndInvalidCandidateDoesNotChangeTheAsset() throws Exception {
        Asset project = assets.createProject("AI race " + UUID.randomUUID(), Map.of());
        Asset target = assets.create(project.id(), AssetType.FUNCTIONAL_CASE, null, "原名", Map.of(), "MANUAL");
        var held = model.hold("{\"data\":{\"precondition\":\"AI 修改\"}}");
        var submitted = refinement.submit(request(project.id(), target, null, "修改前置", "race"));
        assertThat(held.entered().await(10, TimeUnit.SECONDS)).isTrue();
        Asset human = assets.update(project.id(), target.id(), target.version(), "人工修订", Map.of(), null, "MANUAL");
        held.release().countDown();
        assertThat(terminal(project.id(), submitted.jobId()).status()).isEqualTo("FAILED");
        assertThat(assets.get(project.id(), target.id())).isEqualTo(human);
        model.enqueue("{\"id\":\"replace-sibling\",\"data\":{\"precondition\":\"越界\"}}");
        var invalid = refinement.submit(request(project.id(), human, null, "修改", "invalid"));
        assertThat(terminal(project.id(), invalid.jobId()).status()).isEqualTo("FAILED");
        assertThat(assets.get(project.id(), target.id())).isEqualTo(human);
    }
    @Test void missingModelKeepsFeedbackAndFailureHistoryWithoutChangingTarget() {
        jdbc.update("DELETE FROM ai_model_config");
        Asset project = assets.createProject("Missing model " + UUID.randomUUID(), Map.of());
        Asset target = assets.create(project.id(), AssetType.FUNCTIONAL_CASE, null, "Original", Map.of(), "MANUAL");
        var submitted = refinement.submit(request(project.id(), target, null, "保存这条修改意见", "missing-model"));
        assertThat(terminal(project.id(), submitted.jobId()).status()).isEqualTo("FAILED");
        assertThat(conversations.messages(project.id(), submitted.conversationId())).hasSize(2).anySatisfy(message -> assertThat(message.get("content")).isEqualTo("保存这条修改意见"));
        assertThat(assets.get(project.id(), target.id())).isEqualTo(target);
    }
    @Test void oneSqlAndOneOfTwentyUiStepsAreReplacedInPlaceAndInvalidSqlIsRejected() {
        Asset project = assets.createProject("Nested refinement " + UUID.randomUUID(), Map.of());
        Asset source = assets.create(project.id(), AssetType.DATABASE_SOURCE, null, "DB", Map.of("jdbcUrl", "jdbc:mysql://localhost/test", "username", "fixture"), "MANUAL");
        Asset api = assets.create(project.id(), AssetType.API_CASE, null, "API", Map.of("path", "/orders"), "MANUAL");
        for (int i=0;i<3;i++) assets.create(project.id(), AssetType.SQL_VALIDATION, api.id(), "SQL " + i, Map.of("databaseSourceId", source.id(), "sql", "SELECT 1 AS amount"), "MANUAL");
        List<Asset> sqlBefore = assets.children(project.id(), api.id()); Asset sql = sqlBefore.get(1);
        model.enqueue("{\"data\":{\"sql\":\"SELECT 2 AS amount\"}}");
        var changed = refinement.submit(new AiRefinementService.RefineRequest(project.id(), sql.type(), sql.id(), sql.version(), null, "只调整金额", List.of("sql"), "REPLACE_ON_SUCCESS", "sql"));
        assertThat(terminal(project.id(), changed.jobId()).status()).isEqualTo("SUCCEEDED");
        List<Asset> sqlAfter = assets.children(project.id(), api.id());
        assertThat(sqlAfter.get(0)).isEqualTo(sqlBefore.get(0)); assertThat(sqlAfter.get(2)).isEqualTo(sqlBefore.get(2));
        assertThat(sqlAfter.get(1).id()).isEqualTo(sql.id()); assertThat(assets.get(project.id(), api.id())).isEqualTo(api);
        model.enqueue("{\"data\":{\"sql\":\"DROP TABLE orders\"}}");
        var illegal = refinement.submit(new AiRefinementService.RefineRequest(project.id(), sql.type(), sql.id(), "2", null, "改 SQL", List.of("sql"), "REPLACE_ON_SUCCESS", "illegal-sql"));
        assertThat(terminal(project.id(), illegal.jobId()).status()).isEqualTo("FAILED");
        assertThat(assets.children(project.id(), api.id())).isEqualTo(sqlAfter);
        Asset ui = assets.create(project.id(), AssetType.UI_SCENARIO, null, "UI", Map.of(), "MANUAL");
        for (int i=0;i<20;i++) assets.create(project.id(), AssetType.UI_STEP, ui.id(), "Step " + i, Map.of("action", "fill", "selector", "#field" + i, "value", "value"), "MANUAL");
        List<Asset> before = assets.children(project.id(), ui.id()); Asset step = before.get(7);
        model.enqueue("{\"data\":{\"selector\":\"label=用户名\"}}");
        var selected = refinement.submit(new AiRefinementService.RefineRequest(project.id(), step.type(), step.id(), step.version(), null, "改为 label 定位", List.of("selector"), "REPLACE_ON_SUCCESS", "ui"));
        assertThat(terminal(project.id(), selected.jobId()).status()).isEqualTo("SUCCEEDED");
        List<Asset> after = assets.children(project.id(), ui.id());
        for (int i=0;i<20;i++) if (i != 7) assertThat(after.get(i)).isEqualTo(before.get(i));
        assertThat(after.get(7).position()).isEqualTo(step.position()); assertThat(after.get(7).id()).isEqualTo(step.id());
        assertThat(assets.get(project.id(), ui.id())).isEqualTo(ui);
    }
    private AiRefinementService.RefineRequest request(String project, Asset asset, String conversation, String feedback, String key) {
        return new AiRefinementService.RefineRequest(project, asset.type(), asset.id(), asset.version(), conversation, feedback, List.of("precondition"), "REPLACE_ON_SUCCESS", key);
    }
    private Job terminal(String projectId, String jobId) {
        await().atMost(Duration.ofSeconds(35)).until(() -> jobs.get(projectId, jobId).terminal());
        return jobs.get(projectId, jobId);
    }
}
