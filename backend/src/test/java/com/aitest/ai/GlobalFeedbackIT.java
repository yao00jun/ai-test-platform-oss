package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.common.JsonCodec;
import com.aitest.job.Job;
import com.aitest.job.JobService;
import com.aitest.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class GlobalFeedbackIT extends MySqlIntegrationTest {
    static final ModelFixtureServer model;
    static { try { model = new ModelFixtureServer(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @Autowired AssetService assets; @Autowired GlobalFeedbackService feedback; @Autowired AiGenerationService generation;
    @Autowired AiChangeSetService changes; @Autowired AiConversationService conversations; @Autowired JobService jobs; @Autowired ModelSettingsService settings; @Autowired JsonCodec json;
    @BeforeEach void configure() { settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30)); }
    @AfterAll static void close() { model.close(); }
    @Test void markdownGenerationUsesPortedParserAndCreatesIndependentSteps() {
        Asset project = assets.createProject("Generate " + UUID.randomUUID(), Map.of());
        model.enqueue("featureCaseStart\n## 正常退款\n### 前置条件\n订单已支付\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 申请退款 | 退款成功 |\n### 备注\nP1\nfeatureCaseEnd");
        var submitted = generation.submit(new AiGenerationService.Request(project.id(), AssetType.FUNCTIONAL_CASE, null, "生成退款用例", List.of(), "generate"));
        assertThat(terminal(project.id(), submitted.jobId()).status()).isEqualTo("SUCCEEDED");
        Asset item = assets.list(project.id(), AssetType.FUNCTIONAL_CASE, null, "", 0, 10).items().getFirst();
        assertThat(assets.children(project.id(), item.id())).singleElement().satisfies(step -> assertThat(step.type()).isEqualTo(AssetType.FUNCTIONAL_STEP));
    }
    @Test void threeGlobalRoundsUseAdoptedAndHumanEditedVersionsAndNeverOverwriteProtectedAssets() {
        Asset project = assets.createProject("Feedback " + UUID.randomUUID(), Map.of());
        Asset target = assets.create(project.id(), AssetType.FUNCTIONAL_CASE, null, "退款", Map.of(), "MANUAL");
        Asset keep = assets.create(project.id(), AssetType.FUNCTIONAL_CASE, null, "登录", Map.of(), "MANUAL");
        keep = assets.update(project.id(), keep.id(), keep.version(), null, Map.of(), true, "MANUAL");
        String conversation = null;
        for (int round=1;round<=3;round++) {
            model.enqueue(json.write(Map.of("changes", List.of(new AiChangeSetService.Proposal("MODIFY", target.type(), target.id(), null, null, target.version(), null, Map.of("precondition", "退款条件 " + round))))));
            var submitted = feedback.submit(new GlobalFeedbackService.Request(project.id(), null, conversation, "退款反馈 " + round, List.of(target.id(), keep.id()), "feedback-" + round));
            conversation = submitted.conversationId();
            Job done = terminal(project.id(), submitted.jobId()); assertThat(done.status()).withFailMessage("%s", done.error()).isEqualTo("SUCCEEDED");
            assertThat(assets.get(project.id(), target.id())).isEqualTo(target);
            String changeId = done.result().get("changeSetId").toString();
            changes.apply(project.id(), changeId, AiGenerationService.changeIds(changes.get(project.id(), changeId)));
            target = assets.get(project.id(), target.id());
            if (round == 1) target = assets.update(project.id(), target.id(), target.version(), null, Map.of("remark", "人工确认七天内退款"), null, "MANUAL");
        }
        assertThat(assets.get(project.id(), keep.id())).isEqualTo(keep);
        assertThat(target.data()).containsEntry("remark", "人工确认七天内退款");
        assertThat(conversations.messages(project.id(), conversation)).hasSize(6);
        assertThat(model.requests.getLast()).contains("人工确认七天内退款", "退款条件 2", "退款反馈 1");
    }
    private Job terminal(String project, String id) { await().atMost(Duration.ofSeconds(35)).until(() -> jobs.get(project, id).terminal()); return jobs.get(project, id); }
}
