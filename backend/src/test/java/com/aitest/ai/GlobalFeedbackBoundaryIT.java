package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.job.JobService;
import com.aitest.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class GlobalFeedbackBoundaryIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired GlobalFeedbackService feedback;
    @Autowired AiConversationService conversations;
    @Autowired ModelSettingsService settings;
    @Autowired JobService jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @Autowired AiChangeSetService changes;

    @Test void globalFeedbackRejectsGenerationDiagnosisAndUnboundPipelineConversations() {
        String project = assets.createProject("Feedback boundary", Map.of()).id();
        Asset target = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "Keep scope", Map.of(), "MANUAL");
        for (String scope : List.of("GENERATE", "DIAGNOSE", "PIPELINE")) {
            String conversation = conversations.ensure(null, project, scope, target.id(), null);
            assertThatThrownBy(() -> feedback.submit(new GlobalFeedbackService.Request(project, null, conversation, "优化", List.of(target.id()), "scope-" + scope)))
                    .isInstanceOf(Problem.class).extracting(error -> ((Problem) error).code()).isEqualTo("CONVERSATION_SCOPE_MISMATCH");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_task WHERE project_id=?", Integer.class, project)).isZero();
    }

    @Test void laterPipelineFeedbackUsesOnlySurvivingAssetsWithoutResurrectingDeletedOnes() throws Exception {
        String project = assets.createProject("Pipeline feedback", Map.of()).id();
        Asset keep = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "退款用例", Map.of("remark", "人工补充"), "MANUAL");
        Asset removed = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "应保持删除", Map.of(), "AI");
        String pipeline = Ids.newId();
        String conversation = conversations.ensure(null, project, "PIPELINE", pipeline, null);
        jdbc.update("INSERT INTO ai_pipeline_record(id,project_id,job_id,conversation_id,requirement_snapshot,api_snapshot,status,current_stage,asset_ids,config_snapshot,created_at,updated_at) VALUES(?,?,?,?,'[]','[]','COMPLETED','S6',?,'{}',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))", pipeline, project, "", conversation, json.write(List.of(keep.id(), removed.id())));
        assets.delete(project, removed.id(), removed.version());
        try (var model = new ModelFixtureServer()) {
            settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30));
            model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "MODIFY", "targetType", "FUNCTIONAL_CASE", "targetId", keep.id(), "baseVersion", keep.version(), "data", Map.of("precondition", "支付后七天内"))))));
            var submitted = feedback.submit(new GlobalFeedbackService.Request(project, pipeline, conversation, "补充退款限制", null, "after-delete"));
            await().atMost(Duration.ofSeconds(35)).until(() -> jobs.get(project, submitted.jobId()).terminal());
            var result = jobs.get(project, submitted.jobId());
            assertThat(result.status()).as(result.error()).isEqualTo("SUCCEEDED");
            assertThat(result.result()).containsEntry("status", "PREVIEW");
            assertThat(model.requests).hasSize(1);
            assertThat(model.requests.getFirst()).contains(keep.id(), "人工补充").doesNotContain(removed.id(), "应保持删除");
            assertThat(assets.get(project, keep.id())).isEqualTo(keep);
            assertThatThrownBy(() -> assets.get(project, removed.id())).isInstanceOf(Problem.class);
        }
    }

    @Test void pipelineFeedbackRejectsAnUnusedAlternateConversationWithoutCreatingIt() {
        String project = assets.createProject("Canonical pipeline feedback", Map.of()).id();
        String pipeline = Ids.newId();
        String canonical = conversations.ensure(null, project, "PIPELINE", pipeline, null);
        pipeline(project, pipeline, canonical, List.of());
        String alternate = Ids.newId();

        assertThatThrownBy(() -> feedback.submit(new GlobalFeedbackService.Request(project, pipeline, alternate, "补充退款用例", null, "alternate")))
                .isInstanceOfSatisfying(Problem.class, error -> assertThat(error.code()).isEqualTo("CONVERSATION_SCOPE_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE id=?", Integer.class, alternate)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_task WHERE project_id=?", Integer.class, project)).isZero();
    }

    @Test void defaultPipelineFeedbackReusesItsRecordedConversationAndTracksAdoptedAddsAcrossRounds() throws Exception {
        String project = assets.createProject("Feedback adopted roots", Map.of()).id();
        String pipeline = Ids.newId();
        // Imported/restored pipelines can have a recorded ID different from ensure(null)'s derived ID.
        String canonical = conversations.ensure(Ids.newId(), project, "PIPELINE", pipeline, null);
        pipeline(project, pipeline, canonical, List.of());
        try (var model = new ModelFixtureServer()) {
            settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30));
            model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "FUNCTIONAL_CASE", "localKey", "refund", "name", "新增退款用例", "data", Map.of("precondition", "订单已支付"))))));
            var first = feedback.submit(new GlobalFeedbackService.Request(project, pipeline, null, "增加退款用例", null, "add"));
            await().atMost(Duration.ofSeconds(35)).until(() -> jobs.get(project, first.jobId()).terminal());
            var firstDone = jobs.get(project, first.jobId());
            assertThat(firstDone.status()).as(firstDone.error()).isEqualTo("SUCCEEDED");
            assertThat(first.conversationId()).isEqualTo(canonical);
            String changeId = firstDone.result().get("changeSetId").toString();
            changes.apply(project, changeId, AiGenerationService.changeIds(changes.get(project, changeId)));
            Asset added = assets.list(project, AssetType.FUNCTIONAL_CASE, null, "", 0, 10).items().getFirst();
            Asset edited = assets.update(project, added.id(), added.version(), null, Map.of("remark", "人工确认付款凭证"), null, "MANUAL");

            model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "MODIFY", "targetType", "FUNCTIONAL_CASE", "targetId", edited.id(), "baseVersion", edited.version(), "data", Map.of("precondition", "付款七天内"))))));
            var second = feedback.submit(new GlobalFeedbackService.Request(project, pipeline, null, "限制退款时间", null, "refine-added"));
            await().atMost(Duration.ofSeconds(35)).until(() -> jobs.get(project, second.jobId()).terminal());
            var secondDone = jobs.get(project, second.jobId());
            assertThat(secondDone.status()).as(secondDone.error()).isEqualTo("SUCCEEDED");
            assertThat(second.conversationId()).isEqualTo(canonical);
            assertThat(model.requests.getLast()).contains(edited.id(), "人工确认付款凭证", "增加退款用例");
            assertThat(assets.get(project, edited.id())).isEqualTo(edited);
            assertThat(conversations.messages(project, canonical)).hasSize(4);
        }
    }

    private void pipeline(String project, String id, String conversation, List<String> tracked) {
        jdbc.update("INSERT INTO ai_pipeline_record(id,project_id,job_id,conversation_id,requirement_snapshot,api_snapshot,status,current_stage,asset_ids,config_snapshot,created_at,updated_at) VALUES(?,?,?,?,'[]','[]','COMPLETED','S6',?,'{}',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))", id, project, "", conversation, json.write(tracked));
    }
}
