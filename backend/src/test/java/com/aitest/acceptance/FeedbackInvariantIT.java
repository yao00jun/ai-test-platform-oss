package com.aitest.acceptance;

import com.aitest.ai.AiConversationService;
import com.aitest.ai.ModelSettingsService;
import com.aitest.asset.Asset;
import com.aitest.asset.AssetService;
import com.aitest.asset.AssetType;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Additional R06/R07/R08/R09/R11/R12/R16/R18 boundaries beyond the three-round isolation suites. */
@TestPropertySource(properties = {"aitest.schedules.enabled=false", "aitest.morning-brief.enabled=false", "aitest.notifications.enabled=false"})
class FeedbackInvariantIT extends ExchangeHttpTest {
    static final ModelFixtureServer model;
    static { try { model = new ModelFixtureServer(); } catch (Exception failure) { throw new ExceptionInInitializerError(failure); } }
    @Autowired ModelSettingsService settings;
    @Autowired JobService jobs;
    @Autowired AiConversationService conversations;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void configure() { settings.save(new ModelSettingsService.Input(model.url(), "feedback-fixture", "fixture", 0.1, 30)); }
    @AfterAll static void closeModel() { model.close(); }

    @Test void twoInFlightRefinementsOfOneVersionHaveOneWinnerAndOneConflict() throws Exception {
        String project = project().id();
        Asset target = functional(project, "退款"), sibling = functional(project, "保持");
        var first = model.hold("{\"data\":{\"precondition\":\"先到结果\"}}");
        var one = refine(target, List.of("precondition"));
        assertThat(first.entered().await(10, TimeUnit.SECONDS)).isTrue();
        var second = model.hold("{\"data\":{\"precondition\":\"迟到结果\"}}");
        var two = refine(target, List.of("precondition"));
        assertThat(second.entered().await(10, TimeUnit.SECONDS)).isTrue();
        first.release().countDown();
        finished(project, one, "SUCCEEDED");
        Asset accepted = assets.get(project, target.id());
        second.release().countDown();
        finished(project, two, "FAILED");
        assertThat(assets.get(project, target.id())).isEqualTo(accepted);
        assertThat(accepted.data()).containsEntry("precondition", "先到结果");
        assertThat(accepted.version()).isEqualTo("2");
        assertThat(assets.history(project, target.id())).hasSize(2);
        assertThat(assets.get(project, sibling.id())).isEqualTo(sibling);
        assertThat(conversations.messages(project, two.get("conversationId").toString())).anySatisfy(message -> assertThat(message.get("status")).isEqualTo("CONFLICT"));
    }

    @Test void reorderingOrDeletingTheTargetWhileGeneratingNeverLetsTheLateResultReplaceAnotherRecord() throws Exception {
        String project = project().id();
        Asset first = functional(project, "第一条"), target = functional(project, "第二条");
        var held = model.hold("{\"data\":{\"precondition\":\"过期修改\"}}");
        var submitted = refine(target, List.of("precondition"));
        assertThat(held.entered().await(10, TimeUnit.SECONDS)).isTrue();
        var reordered = assets.reorder(project, AssetType.FUNCTIONAL_CASE, null, List.of(new AssetService.OrderItem(target.id(), target.version()), new AssetService.OrderItem(first.id(), first.version())));
        held.release().countDown(); finished(project, submitted, "FAILED");
        assertThat(assets.list(project, AssetType.FUNCTIONAL_CASE, null, "", 0, 10).items()).isEqualTo(reordered);

        target = assets.get(project, target.id());
        held = model.hold("{\"data\":{\"precondition\":\"不应复活\"}}");
        submitted = refine(target, List.of("precondition"));
        assertThat(held.entered().await(10, TimeUnit.SECONDS)).isTrue();
        assets.delete(project, target.id(), target.version());
        Asset survivor = assets.get(project, first.id());
        held.release().countDown(); finished(project, submitted, "FAILED");
        assertThat(request("GET", path(project, target.id()), null).statusCode()).isEqualTo(404);
        assertThat(assets.all(project)).containsExactly(survivor);
        assertThat(jdbc.queryForObject("SELECT deleted FROM asset WHERE id=?", Boolean.class, target.id())).isTrue();
    }

    @Test void multiTargetUnknownFieldsAndTruncatedJsonKeepOriginalAssetsAndFeedbackHistory() throws Exception {
        String project = project().id();
        Asset target = functional(project, "退款"), sibling = functional(project, "旁项");
        for (String output : List.of("{\"changes\":[{\"name\":\"A\"},{\"name\":\"B\"}]}", "{\"data\":{\"precondition\":\"新增\",\"position\":99}}", "{\"data\":{\"precondition\":\"截断")) {
            model.enqueue(output);
            var submitted = refine(target, List.of("precondition"));
            finished(project, submitted, "FAILED");
            assertThat(assets.get(project, target.id())).isEqualTo(target);
            assertThat(assets.get(project, sibling.id())).isEqualTo(sibling);
            assertThat(assets.history(project, target.id())).hasSize(1);
            assertThat(conversations.messages(project, submitted.get("conversationId").toString()).getLast()).containsEntry("status", "FAILED").containsEntry("content", output);
        }
    }

    @Test void undoRestoresOnlyTheRefinedTargetAndAStaleUndoCannotOverwriteLaterManualWork() throws Exception {
        String project = project().id();
        Asset target = functional(project, "退款"), sibling = functional(project, "旁项");
        String revision = assets.history(project, target.id()).getFirst().id();
        model.enqueue("{\"data\":{\"precondition\":\"AI 条件\"}}");
        finished(project, refine(target, List.of("precondition")), "SUCCEEDED");
        var restoredResponse = request("POST", path(project, target.id()) + "/undo", Map.of("baseVersion", "2", "revisionId", revision));
        assertThat(restoredResponse.statusCode()).isEqualTo(200);
        Asset restored = assets.get(project, target.id());
        assertThat(restored.data()).isEqualTo(target.data());
        assertThat(restored.position()).isEqualTo(target.position());
        Asset human = assets.update(project, target.id(), restored.version(), "人工最终稿", Map.of("precondition", "人工条件"), null, "MANUAL");
        assertThat(request("POST", path(project, target.id()) + "/undo", Map.of("baseVersion", restored.version(), "revisionId", revision)).statusCode()).isEqualTo(409);
        assertThat(assets.get(project, target.id())).isEqualTo(human);
        assertThat(assets.get(project, sibling.id())).isEqualTo(sibling);
    }

    @Test void localTokenRenameCannotSilentlyBreakOrModifyItsConsumer() throws Exception {
        String project = project().id();
        Asset producer = assets.create(project, AssetType.API_CASE, null, "登录", Map.of("path", "/login", "extractors", List.of(Map.of("variable", "token", "jsonpath", "$.token"))), "MANUAL");
        Asset consumer = assets.create(project, AssetType.API_CASE, null, "退款", Map.of("path", "/refund", "headers", Map.of("X-Session", "${token}")), "MANUAL");
        model.enqueue("{\"data\":{\"extractors\":[{\"variable\":\"renamed\",\"jsonpath\":\"$.token\"}]}}");
        finished(project, refine(producer, List.of("extractors")), "FAILED");
        assertThat(assets.get(project, producer.id())).isEqualTo(producer);
        assertThat(assets.get(project, consumer.id())).isEqualTo(consumer);
    }

    @Test void conversationScopeAndUnavailableModelCannotBlockManualEditingOrderingOrExchange() throws Exception {
        String project = project().id(), other = project().id();
        Asset target = functional(project, "退款"), sibling = functional(project, "登录"), foreign = functional(other, "其他项目");
        String conversation = conversations.ensure(null, project, "LOCAL", target.id(), target.type().name());
        assertThat(request("POST", "/api/ai/refine-item", input(sibling, List.of("precondition"), conversation)).statusCode()).isEqualTo(409);
        assertThat(request("POST", "/api/ai/refine-item", input(foreign, List.of("precondition"), conversation)).statusCode()).isEqualTo(404);
        jdbc.update("DELETE FROM ai_model_config");
        finished(project, refine(target, List.of("precondition")), "FAILED");
        assertThat(assets.get(project, target.id())).isEqualTo(target);
        var edited = request("PATCH", path(project, target.id()), Map.of("baseVersion", target.version(), "name", "离线人工编辑", "data", Map.of("remark", "没有模型仍可工作")));
        assertThat(edited.statusCode()).isEqualTo(200);
        Asset updated = assets.get(project, target.id());
        var sorted = request("POST", "/api/projects/" + project + "/assets/reorder", Map.of("type", "FUNCTIONAL_CASE", "items", List.of(Map.of("id", sibling.id(), "baseVersion", sibling.version()), Map.of("id", updated.id(), "baseVersion", updated.version()))));
        assertThat(sorted.statusCode()).isEqualTo(200);
        var file = request("POST", "/api/projects/" + project + "/exports", Map.of("type", "FUNCTIONAL_CASE", "assetIds", List.of(target.id()), "format", "json"));
        assertThat(file.statusCode()).isEqualTo(200);
        var checked = preview(other, "FUNCTIONAL_CASE", "json", "offline.json", file.body(), Map.of());
        assertThat(objects(checked.get("errors"))).isEmpty(); apply(other, checked);
        assertThat(assets.list(other, AssetType.FUNCTIONAL_CASE, null, "离线人工编辑", 0, 10).items()).singleElement().satisfies(copy -> assertThat(copy.data()).containsEntry("remark", "没有模型仍可工作"));
        assertThat(assets.get(other, foreign.id())).isEqualTo(foreign);
    }

    private Asset functional(String project, String name) { return assets.create(project, AssetType.FUNCTIONAL_CASE, null, name, Map.of(), "MANUAL"); }
    private Map<String, Object> input(Asset asset, List<String> fields, String conversation) {
        var body = new java.util.LinkedHashMap<String, Object>(Map.of("projectId", asset.projectId(), "targetType", asset.type(), "targetId", asset.id(), "baseVersion", asset.version(), "feedback", "根据当前资产细化", "targetFields", fields, "idempotencyKey", UUID.randomUUID().toString()));
        if (conversation != null) body.put("conversationId", conversation);
        return body;
    }
    private Map<String, Object> refine(Asset asset, List<String> fields) throws Exception {
        var response = request("POST", "/api/ai/refine-item", input(asset, fields, null));
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(202);
        return object(response);
    }
    private void finished(String project, Map<String, Object> submission, String status) {
        String id = submission.get("jobId").toString();
        await().atMost(Duration.ofSeconds(35)).until(() -> jobs.get(project, id).terminal());
        assertThat(jobs.get(project, id).status()).as(jobs.get(project, id).error()).isEqualTo(status);
    }
    private String path(String project, String id) { return "/api/projects/" + project + "/assets/" + id; }
}
