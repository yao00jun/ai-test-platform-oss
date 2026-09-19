package com.aitest.bug;
import org.junit.jupiter.api.Tag;

import com.aitest.ai.*;
import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.execution.Values;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@Tag("slow")
class RcaFeedbackIT extends CodeRcaSupport {
    @Autowired AiRefinementService refinement;
    @Autowired AiChangeSetService changes;
    @Autowired AiConversationService conversations;
    @Autowired AiGenerationService generation;
    @Autowired JdbcTemplate jdbc;
    @Test void independentHumanEvaluationUsesTheExactDiagnosisDigestAndSupportsIdempotencyAndCas() throws Exception {
        String project = project().id(); Asset bug = assets.create(project, AssetType.BUG, null, "人工 RCA", Map.of("rootCauseAnalysis", "待核实"), "MANUAL");
        String endpoint = "/api/projects/" + project + "/bugs/" + bug.id() + "/rca-evaluations";
        var empty = request("GET", endpoint, null); assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(object(empty)).containsEntry("current", null).containsEntry("total", 0);
        Map<String, Object> input = Map.of("baseVersion", bug.version(), "verdict", "PARTIAL", "regression", "NOT_REGRESSION", "note", "人工等待补充", "idempotencyKey", "review-1");
        var saved = request("POST", endpoint, input); assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(object(saved)).containsEntry("actor", "LOCAL_USER").containsEntry("source", "MANUAL").containsEntry("assetVersion", bug.version());
        assertThat(object(request("POST", endpoint, input))).isEqualTo(object(saved));
        var mismatch = new LinkedHashMap<>(input); mismatch.put("verdict", "CORRECT"); assertThat(request("POST", endpoint, mismatch).statusCode()).isEqualTo(409);
        Asset edited = assets.update(project, bug.id(), bug.version(), null, Map.of("rootCauseAnalysis", "新的人工结论"), null, "MANUAL");
        var state = object(request("GET", endpoint, null)); assertThat(state).containsEntry("current", null).containsEntry("total", 1);
        assertThat(Values.objects(state.get("items"))).singleElement().satisfies(item -> assertThat(item).isEqualTo(object(saved)));
        var stale = new LinkedHashMap<>(input); stale.put("idempotencyKey", "review-stale"); assertThat(request("POST", endpoint, stale).statusCode()).isEqualTo(409);
        var foreign = request("GET", "/api/projects/" + project().id() + "/bugs/" + bug.id() + "/rca-evaluations", null); assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(assets.get(project, bug.id())).isEqualTo(edited);
        assertThat(request("GET", "/api/projects/" + project + "/bugs/" + bug.id() + "/code-evidence", null).statusCode()).isEqualTo(200);
    }
    @Test void twoScopedRoundsKeepHumanTextAndOtherBugsAndLateHumanEditWins() throws Exception {
        String project = project().id(); var captured = source(project, false, false); String run = failure(project, captured.id(), STACK);
        try (var model = new ModelFixtureServer()) {
            Asset bug = diagnosedBug(project, run, model, code("初始推测", "OrderService.java:5", PATCH));
            bug = assets.update(project, bug.id(), bug.version(), "人工标题", Map.of("rootCauseAnalysis", "人工文本", "suggestion", "人工修复步骤", "status", "CLOSED"), null, "MANUAL");
            Asset sibling = assets.create(project, AssetType.BUG, null, "其他缺陷", Map.of(), "MANUAL"); String conversation = null;
            for (int round = 1; round <= 2; round++) {
                var next = code("第 " + round + " 轮推测", "OrderService.java:5", PATCH);
                model.enqueue(json.write(Map.of("data", Map.of("codeDiagnosis", next))));
                var accepted = refinement.submit(new AiRefinementService.RefineRequest(project, bug.type(), bug.id(), bug.version(), conversation, "只优化代码", List.of("codeDiagnosis"), "REPLACE_ON_SUCCESS", "round-" + round));
                var job = terminal(project, accepted.jobId()); assertThat(job.status()).as(job.error()).isEqualTo("SUCCEEDED"); conversation = accepted.conversationId();
                bug = assets.get(project, bug.id());
                assertThat(bug.data()).containsEntry("codeDiagnosis", next).containsEntry("rootCauseAnalysis", "人工文本").containsEntry("suggestion", "人工修复步骤").containsEntry("status", "CLOSED").containsEntry("runId", run);
                assertThat(assets.get(project, sibling.id())).isEqualTo(sibling);
            }
            assertThat(conversations.messages(project, conversation)).hasSize(4);
            var held = model.hold(json.write(Map.of("data", Map.of("codeDiagnosis", code("迟到推测", "OrderService.java:5", PATCH)))));
            var accepted = refinement.submit(new AiRefinementService.RefineRequest(project, bug.type(), bug.id(), bug.version(), conversation, "再优化", List.of("codeDiagnosis"), "REPLACE_ON_SUCCESS", "late"));
            assertThat(held.entered().await(15, TimeUnit.SECONDS)).isTrue();
            Asset human = assets.update(project, bug.id(), bug.version(), null, Map.of("suggestion", "人工最终修复"), null, "MANUAL"); held.release().countDown();
            assertThat(terminal(project, accepted.jobId()).status()).isEqualTo("FAILED");
            assertThat(assets.get(project, bug.id())).isEqualTo(human);
            assertThat(model.requests.getLast()).contains("sourceEvidence", "missing id").doesNotContain("source-secret-4717");
        }
    }
    @Test void fabricatedPatchesAndPathsAreRejectedAcrossLocalGenericAndOldSelectedAdoption() throws Exception {
        String project = project().id(); var captured = source(project, false, false); String run = failure(project, captured.id(), STACK);
        try (var model = new ModelFixtureServer()) {
            Asset bug = diagnosedBug(project, run, model, code("初始推测", "OrderService.java:5", PATCH));
            for (var bad : List.of(code("未知位置", "OrderService.java:9999", ""), code("越界路径", "../OrderService.java:5", PATCH), code("伪造文件", "OrderService.java:5", PATCH.replace("OrderService.java", "Another.java")), code("伪造上下文", "OrderService.java:5", PATCH.replace("missing id", "never in snapshot")))) {
                model.enqueue(json.write(Map.of("data", Map.of("codeDiagnosis", bad))));
                var accepted = refinement.submit(new AiRefinementService.RefineRequest(project, bug.type(), bug.id(), bug.version(), null, "尝试非法诊断", List.of("codeDiagnosis"), "REPLACE_ON_SUCCESS", UUID.randomUUID().toString()));
                assertThat(terminal(project, accepted.jobId()).status()).isEqualTo("FAILED"); assertThat(assets.get(project, bug.id())).isEqualTo(bug);
            }
            model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "BUG", "localKey", "bug", "name", "没有失败现场的代码缺陷", "data", Map.of("codeDiagnosis", code("未知失败", "OrderService.java:5", PATCH)))))));
            var generated = generation.submit(new AiGenerationService.Request(project, AssetType.BUG, null, "生成代码缺陷", List.of(), "generic-code", null, null, captured.id()));
            assertThat(terminal(project, generated.jobId()).status()).isEqualTo("FAILED");
            Asset sibling = assets.create(project, AssetType.BUG, null, "另一条人工缺陷", Map.of(), "MANUAL");
            String conversation = conversations.ensure(null, project, "GLOBAL", null, null);
            String set = changes.create(project, conversation, "old-rca-preview", List.of(
                    new AiChangeSetService.Proposal("MODIFY", sibling.type(), sibling.id(), null, null, sibling.version(), "不应采纳的标题", Map.of()),
                    new AiChangeSetService.Proposal("MODIFY", bug.type(), bug.id(), null, null, bug.version(), null, Map.of("codeDiagnosis", code("合法候选", "OrderService.java:5", PATCH)))));
            jdbc.update("UPDATE ai_change_item SET after_snapshot=? WHERE change_set_id=? AND target_id=?", json.write(Map.of("data", Map.of("codeDiagnosis", code("旧候选伪造", "Another.java:5", PATCH)))), set, bug.id());
            assertThatThrownBy(() -> changes.apply(project, set, AiGenerationService.changeIds(changes.get(project, set)))).isInstanceOf(Problem.class);
            assertThat(assets.get(project, sibling.id())).isEqualTo(sibling); assertThat(assets.get(project, bug.id())).isEqualTo(bug);
            model.enqueue(json.write(Map.of("data", Map.of("rcaVerdict", "CORRECT"))));
            var grading = refinement.submit(new AiRefinementService.RefineRequest(project, bug.type(), bug.id(), bug.version(), null, "给自己评价", null, "REPLACE_ON_SUCCESS", "self-grade"));
            assertThat(terminal(project, grading.jobId()).status()).isEqualTo("FAILED"); assertThat(assets.get(project, bug.id())).isEqualTo(bug);
        }
    }
}
