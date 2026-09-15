package com.aitest.analysis;

import com.aitest.ai.*;
import com.aitest.asset.*;
import com.aitest.execution.Values;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SourceGroundedRefinementIT extends SourceGroundedSupport {
    @Autowired AiRefinementService refinement;
    @Autowired AiChangeSetService changeSets;
    @Autowired AiConversationService conversations;
    @Autowired JdbcTemplate jdbc;
    @Test void localSqlRejectsInventedColumnsAndPreservesItsSiblingsAcrossThreeValidRounds() throws Exception {
        String project = project().id(), source = snapshot(project);
        Asset parent = assets.create(project, AssetType.API_CASE, null, "人工接口", Map.of("path", "/refund", "sourceSnapshotId", source), "MANUAL");
        for (int i = 0; i < 3; i++) assets.create(project, AssetType.SQL_VALIDATION, parent.id(), "SQL " + i, Map.of("sourceSnapshotId", source, "sql", "SELECT state FROM orders WHERE id=1", "exports", Map.of("state", "state")), "MANUAL");
        List<Asset> original = assets.children(project, parent.id()); Asset target = original.get(1);
        try (var model = new ModelFixtureServer()) {
            configure(model);
            model.enqueue("{\"data\":{\"sql\":\"SELECT invented AS state FROM orders\"}}");
            var refused = refine(project, target, List.of("sql"), "未知列", "invalid-sql");
            assertThat(finished(project, refused.jobId()).status()).isEqualTo("FAILED");
            assertThat(assets.children(project, parent.id())).isEqualTo(original);
            for (int round = 1; round <= 3; round++) {
                model.enqueue(json.write(Map.of("data", Map.of("sql", "SELECT state FROM orders WHERE id=" + (round + 1)))));
                var accepted = refine(project, target, List.of("sql"), "调整指定订单 " + round, "sql-" + round);
                assertThat(finished(project, accepted.jobId()).status()).as(finished(project, accepted.jobId()).error()).isEqualTo("SUCCEEDED");
                target = assets.get(project, target.id());
                assertThat(target.id()).isEqualTo(original.get(1).id()); assertThat(target.position()).isEqualTo(original.get(1).position());
                assertThat(target.data()).containsEntry("sourceSnapshotId", source).containsEntry("exports", Map.of("state", "state"));
                List<Asset> now = assets.children(project, parent.id());
                assertThat(now.getFirst()).isEqualTo(original.getFirst()); assertThat(now.getLast()).isEqualTo(original.getLast());
                assertThat(assets.get(project, parent.id())).isEqualTo(parent);
            }
            assertThat(model.requests).allSatisfy(request -> assertThat(request).contains("sourceEvidence", source).doesNotContain("LaterSource"));
        }
    }
    @Test void localUiChecksBothLocatorsAgainstFixedSourceWithoutChangingOtherNineteenSteps() throws Exception {
        String project = project().id(), source = snapshot(project);
        Asset parent = assets.create(project, AssetType.UI_SCENARIO, null, "人工 UI", Map.of("sourceSnapshotId", source), "MANUAL");
        for (int i = 0; i < 20; i++) assets.create(project, AssetType.UI_STEP, parent.id(), "步骤 " + i, Map.of("sourceSnapshotId", source, "action", "click", "selector", "testId=refund-submit"), "MANUAL");
        List<Asset> original = assets.children(project, parent.id()); Asset target = original.get(7);
        try (var model = new ModelFixtureServer()) {
            configure(model);
            model.enqueue("{\"data\":{\"selector\":\"testId=later-only\"}}");
            assertThat(finished(project, refine(project, target, List.of("selector"), "错误定位", "missing-selector").jobId()).status()).isEqualTo("FAILED");
            model.enqueue("{\"data\":{\"action\":\"dragAndDrop\",\"targetSelector\":\"testId=absent\"}}");
            assertThat(finished(project, refine(project, target, List.of("action", "targetSelector"), "错误拖放目标", "missing-target").jobId()).status()).isEqualTo("FAILED");
            assertThat(assets.children(project, parent.id())).isEqualTo(original);
            model.enqueue("{\"data\":{\"selector\":\"testId=refund-target\"}}");
            assertThat(finished(project, refine(project, target, List.of("selector"), "正确目标", "valid-selector").jobId()).status()).isEqualTo("SUCCEEDED");
            List<Asset> now = assets.children(project, parent.id());
            for (int i = 0; i < original.size(); i++) if (i != 7) assertThat(now.get(i)).isEqualTo(original.get(i));
            assertThat(now.get(7).id()).isEqualTo(target.id()); assertThat(now.get(7).position()).isEqualTo(target.position());
            assertThat(assets.get(project, parent.id())).isEqualTo(parent);
        }
    }
    @Test void globalAdoptionRechecksOldCandidatesAndCannotUseAValidSiblingToSmuggleInvalidSql() throws Exception {
        String project = project().id(), source = snapshot(project);
        Asset sql = assets.create(project, AssetType.SQL_VALIDATION, null, "SQL", Map.of("sourceSnapshotId", source, "sql", "SELECT state FROM orders"), "MANUAL");
        Asset keep = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "保留人工用例", Map.of("precondition", "人工前置"), "MANUAL");
        String conversation = conversations.ensure(null, project, "GLOBAL", null, null);
        String change = changeSets.create(project, conversation, "legacy-fixture", List.of(new AiChangeSetService.Proposal("MODIFY", sql.type(), sql.id(), null, null, sql.version(), null, Map.of("sql", "SELECT state FROM orders WHERE id=2")),
                new AiChangeSetService.Proposal("MODIFY", keep.type(), keep.id(), null, null, keep.version(), "候选名称", Map.of())));
        // A persisted pre-upgrade preview is still subject to current evidence validation at adoption.
        jdbc.update("UPDATE ai_change_item SET after_snapshot=? WHERE change_set_id=? AND target_id=?", json.write(Map.of("data", Map.of("sql", "SELECT state FROM invented"))), change, sql.id());
        var before = assets.all(project);
        assertThatThrownBy(() -> changeSets.apply(project, change, AiGenerationService.changeIds(changeSets.get(project, change)))).isInstanceOf(com.aitest.common.Problem.class);
        assertThat(assets.all(project)).isEqualTo(before);
    }
    private AiRefinementService.Submission refine(String project, Asset target, List<String> fields, String feedback, String key) {
        return refinement.submit(new AiRefinementService.RefineRequest(project, target.type(), target.id(), target.version(), null, feedback, fields, "REPLACE_ON_SUCCESS", key));
    }
}
