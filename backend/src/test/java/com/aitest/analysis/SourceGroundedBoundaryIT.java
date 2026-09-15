package com.aitest.analysis;

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

class SourceGroundedBoundaryIT extends SourceGroundedSupport {
    @Autowired AiGenerationService generation;
    @Autowired GlobalFeedbackService feedback;
    @Autowired AiChangeSetService changes;
    @Autowired AiConversationService conversations;
    @Autowired AiRefinementService refinement;
    @Autowired JdbcTemplate jdbc;

    @Test void aChildBeforeItsParentCannotUseModelWrittenRuntimeEvidence() throws Exception {
        String project = project().id(), source = snapshot(project);
        try (var model = new ModelFixtureServer()) {
            configure(model);
            model.enqueue(changes(add("UI_STEP", "child", "@page", "虚构按钮", Map.of("action", "click", "selector", "testId=invented")),
                    add("UI_SCENARIO", "page", null, "生成页面", Map.of("generationEvidence", Map.of("runtime", inventedRuntime())))));
            var submitted = generation.submit(new AiGenerationService.Request(project, AssetType.UI_SCENARIO, null, "依据源码生成", List.of(), "forged-parent", null, null, source));
            assertThat(finished(project, submitted.jobId()).status()).as(finished(project, submitted.jobId()).error()).isEqualTo("FAILED");
            assertThat(assets.list(project, AssetType.UI_SCENARIO, null, "", 0, 10).total()).isZero();
            assertThat(assets.list(project, AssetType.UI_STEP, null, "", 0, 10).total()).isZero();
        }
    }

    @Test void anOldPreviewCannotIntroduceUnobservedLocatorsUsingSelfDeclaredEvidence() throws Exception {
        String project = project().id(), source = snapshot(project);
        Asset parent = assets.create(project, AssetType.UI_SCENARIO, null, "页面", Map.of("sourceSnapshotId", source), "MANUAL");
        Asset step = assets.create(project, AssetType.UI_STEP, parent.id(), "按钮", Map.of("sourceSnapshotId", source, "action", "click", "selector", "testId=refund-submit"), "MANUAL");
        String conversation = conversations.ensure(null, project, "GLOBAL", null, null);
        String set = changes.create(project, conversation, "old-preview", List.of(new AiChangeSetService.Proposal("MODIFY", step.type(), step.id(), null, null, step.version(), null, Map.of("selector", "testId=refund-target"))));
        jdbc.update("UPDATE ai_change_item SET after_snapshot=? WHERE change_set_id=? AND target_id=?", json.write(Map.of("data", Map.of("selector", "testId=invented", "generationEvidence", Map.of("runtime", inventedRuntime())))), set, step.id());
        assertThatThrownBy(() -> changes.apply(project, set, AiGenerationService.changeIds(changes.get(project, set)))).isInstanceOf(Problem.class);
        assertThat(assets.get(project, step.id())).isEqualTo(step);
        assertThat(assets.get(project, parent.id())).isEqualTo(parent);
    }

    @Test void genericAndGlobalSqlGenerationRejectUnknownTablesAndColumnsAndRebinding() throws Exception {
        String project = project().id(), source = snapshot(project);
        Asset target = assets.create(project, AssetType.SQL_VALIDATION, null, "SQL", Map.of("sourceSnapshotId", source, "sql", "SELECT state FROM orders"), "MANUAL");
        try (var model = new ModelFixtureServer()) {
            configure(model);
            model.enqueue(changes(add("SQL_VALIDATION", "sql", null, "错误 SQL", Map.of("sql", "SELECT state FROM imaginary"))));
            var generated = generation.submit(new AiGenerationService.Request(project, AssetType.SQL_VALIDATION, null, "生成校验", List.of(), "invented-table", null, null, source));
            assertThat(finished(project, generated.jobId()).status()).isEqualTo("FAILED");
            for (var patch : List.of(Map.<String, Object>of("sql", "SELECT imaginary AS state FROM orders"), Map.<String, Object>of("sourceSnapshotId", ""))) {
                model.enqueue(json.write(Map.of("changes", List.of(new AiChangeSetService.Proposal("MODIFY", target.type(), target.id(), null, null, target.version(), null, patch)))));
                var submitted = feedback.submit(new GlobalFeedbackService.Request(project, null, null, "修订 SQL", List.of(target.id()), UUID.randomUUID().toString(), source));
                assertThat(finished(project, submitted.jobId()).status()).isEqualTo("FAILED");
                assertThat(assets.get(project, target.id())).isEqualTo(target);
            }
            assertThat(assets.list(project, AssetType.SQL_VALIDATION, null, "", 0, 10).total()).isEqualTo(1);
            assertThat(model.requests).allSatisfy(call -> assertThat(call).contains(source).doesNotContain("LaterSource", "later-only"));
        }
    }

    @Test void pendingAndForeignSourcesAreRejectedBeforeGenericJobsExist() throws Exception {
        String project = project().id(), source = snapshot(project);
        for (String target : List.of(project().id(), project)) {
            if (target.equals(project)) jdbc.update("UPDATE source_snapshot SET status='PENDING' WHERE id=?", source);
            try {
                var response = request("POST", "/api/ai/generate", Map.of("projectId", target, "type", "API_CASE", "instruction", "生成", "sourceSnapshotId", source, "idempotencyKey", "invalid-source"));
                assertThat(response.statusCode()).isEqualTo(target.equals(project) ? 409 : 404);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_task WHERE project_id=? AND kind='AI_GENERATE'", Long.class, target)).isZero();
            } finally { jdbc.update("UPDATE source_snapshot SET status='READY' WHERE id=?", source); }
        }
    }
    @Test void ddlOnlyGenerationRejectsUncheckedFetchWithoutCreatingAssetsOrAChangeSet() throws Exception {
        String project = project().id(), source = snapshot(project);
        try (var model = new ModelFixtureServer()) {
            configure(model);
            model.enqueue(changes(add("SQL_VALIDATION", "sql", null, "不受支持的 SQL", Map.of("sql", "SELECT id FROM orders FETCH FIRST missing ROWS ONLY"))));
            var submitted = generation.submit(new AiGenerationService.Request(project, AssetType.SQL_VALIDATION, null, "离线生成", List.of(), "unsupported-fetch", null, null, source));
            assertThat(finished(project, submitted.jobId()).status()).isEqualTo("FAILED");
            assertThat(assets.list(project, AssetType.SQL_VALIDATION, null, "", 0, 10).total()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_change_set WHERE project_id=?", Long.class, project)).isZero();
        }
    }
    @Test void anOldUncheckedLimitByPreviewCannotPartiallyAdoptTheSelectedSet() throws Exception {
        String project = project().id(), source = snapshot(project);
        Asset sql = assets.create(project, AssetType.SQL_VALIDATION, null, "当前 SQL", Map.of("sourceSnapshotId", source, "sql", "SELECT state FROM orders"), "MANUAL");
        Asset sibling = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "人工用例", Map.of("precondition", "人工前置条件"), "MANUAL");
        String conversation = conversations.ensure(null, project, "GLOBAL", null, null);
        String set = changes.create(project, conversation, "prior-version-preview", List.of(
                new AiChangeSetService.Proposal("MODIFY", sibling.type(), sibling.id(), null, null, sibling.version(), "候选标题", Map.of()),
                new AiChangeSetService.Proposal("MODIFY", sql.type(), sql.id(), null, null, sql.version(), null, Map.of("sql", "SELECT state FROM orders WHERE id=1"))));
        jdbc.update("UPDATE ai_change_item SET after_snapshot=? WHERE change_set_id=? AND target_id=?", json.write(Map.of("data", Map.of("sql", "SELECT state FROM orders LIMIT 2 BY missing"))), set, sql.id());
        assertThatThrownBy(() -> changes.apply(project, set, AiGenerationService.changeIds(changes.get(project, set)))).isInstanceOf(Problem.class);
        assertThat(assets.get(project, sql.id())).isEqualTo(sql);
        assertThat(assets.get(project, sibling.id())).isEqualTo(sibling);
    }

    @Test void globalAddInheritsTheOnlySourceFromTheCurrentManifest() throws Exception {
        String project = project().id(), source = snapshot(project);
        Asset existing = assets.create(project, AssetType.SQL_VALIDATION, null, "已有 SQL", Map.of("sourceSnapshotId", source, "sql", "SELECT state FROM orders"), "MANUAL");
        try (var model = new ModelFixtureServer()) {
            configure(model);
            model.enqueue(changes(add("SQL_VALIDATION", "new", null, "虚构表", Map.of("sql", "SELECT state FROM invented"))));
            var rejected = feedback.submit(new GlobalFeedbackService.Request(project, null, null, "补充校验", List.of(existing.id()), "inherit-invalid"));
            assertThat(finished(project, rejected.jobId()).status()).isEqualTo("FAILED");
            model.enqueue(changes(add("SQL_VALIDATION", "new", null, "新增 SQL", Map.of("sql", "SELECT state FROM orders WHERE id=2"))));
            var submitted = feedback.submit(new GlobalFeedbackService.Request(project, null, null, "补充有效校验", List.of(existing.id()), "inherit-valid"));
            var completed = finished(project, submitted.jobId());
            assertThat(completed.status()).as(completed.error()).isEqualTo("SUCCEEDED");
            String id = completed.result().get("changeSetId").toString();
            changes.apply(project, id, AiGenerationService.changeIds(changes.get(project, id)));
            assertThat(assets.list(project, AssetType.SQL_VALIDATION, null, "", 0, 10).items()).filteredOn(asset -> !asset.id().equals(existing.id())).singleElement()
                    .satisfies(asset -> assertThat(asset.data()).containsEntry("sourceSnapshotId", source));
            assertThat(assets.get(project, existing.id())).isEqualTo(existing);
        }
    }

    @Test void multipleSourcesAllowTargetedChangesButRequireAChoiceForNewRoots() throws Exception {
        String project = project().id(), first = snapshot(project), second = snapshot(project, "CREATE TABLE archive(id BIGINT PRIMARY KEY,state VARCHAR(20));");
        Asset left = assets.create(project, AssetType.SQL_VALIDATION, null, "订单", Map.of("sourceSnapshotId", first, "sql", "SELECT state FROM orders"), "MANUAL");
        Asset right = assets.create(project, AssetType.SQL_VALIDATION, null, "归档", Map.of("sourceSnapshotId", second, "sql", "SELECT state FROM archive"), "MANUAL");
        List<String> scope = List.of(left.id(), right.id());
        try (var model = new ModelFixtureServer()) {
            configure(model);
            model.enqueue(json.write(Map.of("changes", List.of(
                    new AiChangeSetService.Proposal("MODIFY", left.type(), left.id(), null, null, left.version(), null, Map.of("sql", "SELECT state FROM orders WHERE id=1")),
                    new AiChangeSetService.Proposal("MODIFY", right.type(), right.id(), null, null, right.version(), null, Map.of("sql", "SELECT state FROM archive WHERE id=1"))))));
            var modified = feedback.submit(new GlobalFeedbackService.Request(project, null, null, "只修改现有条件", scope, "multiple-modify"));
            var result = finished(project, modified.jobId());
            assertThat(result.status()).as(result.error()).isEqualTo("SUCCEEDED");
            String set = result.result().get("changeSetId").toString();
            changes.apply(project, set, AiGenerationService.changeIds(changes.get(project, set)));
            assertThat(assets.get(project, left.id()).data()).containsEntry("sourceSnapshotId", first).containsEntry("sql", "SELECT state FROM orders WHERE id=1");
            assertThat(assets.get(project, right.id()).data()).containsEntry("sourceSnapshotId", second).containsEntry("sql", "SELECT state FROM archive WHERE id=1");
            String addition = changes(add("SQL_VALIDATION", "new", null, "归档校验", Map.of("sql", "SELECT state FROM archive WHERE id=2")));
            model.enqueue(addition);
            var ambiguous = feedback.submit(new GlobalFeedbackService.Request(project, null, null, "增加一个校验", scope, "multiple-add"));
            assertThat(finished(project, ambiguous.jobId()).status()).isEqualTo("FAILED");
            assertThat(finished(project, ambiguous.jobId()).error()).contains("源码快照");
            model.enqueue(addition);
            var selected = feedback.submit(new GlobalFeedbackService.Request(project, null, null, "根据归档版本补充校验", scope, "multiple-selected", second));
            var selectedResult = finished(project, selected.jobId());
            assertThat(selectedResult.status()).as(selectedResult.error()).isEqualTo("SUCCEEDED");
            String selectedSet = selectedResult.result().get("changeSetId").toString();
            changes.apply(project, selectedSet, AiGenerationService.changeIds(changes.get(project, selectedSet)));
            assertThat(assets.list(project, AssetType.SQL_VALIDATION, null, "", 0, 10).items()).filteredOn(asset -> !scope.contains(asset.id())).singleElement()
                    .satisfies(asset -> assertThat(asset.data()).containsEntry("sourceSnapshotId", second));
        }
    }

    @Test void aManualEditDuringSourceBoundLocalFeedbackWinsTheCompareAndSwap() throws Exception {
        String project = project().id(), source = snapshot(project);
        Asset target = assets.create(project, AssetType.SQL_VALIDATION, null, "SQL", Map.of("sourceSnapshotId", source, "sql", "SELECT state FROM orders"), "MANUAL");
        try (var model = new ModelFixtureServer()) {
            configure(model);
            var held = model.hold("{\"data\":{\"sql\":\"SELECT state FROM orders WHERE id=2\"}}");
            var submitted = refinement.submit(new AiRefinementService.RefineRequest(project, target.type(), target.id(), target.version(), null, "只修改订单条件", List.of("sql"), "REPLACE_ON_SUCCESS", "held-sql"));
            assertThat(held.entered().await(15, TimeUnit.SECONDS)).isTrue();
            Asset manual = assets.update(project, target.id(), target.version(), null, Map.of("sql", "SELECT state FROM orders WHERE id=99"), null, "MANUAL");
            held.release().countDown();
            assertThat(finished(project, submitted.jobId()).status()).isEqualTo("FAILED");
            assertThat(conversations.messages(project, submitted.conversationId()).getLast()).containsEntry("status", "CONFLICT");
            assertThat(assets.get(project, target.id())).isEqualTo(manual);
        }
    }

    @Test void evidenceTextIsNotAnExecutableDependencyDuringSelectiveAdoption() {
        String project = project().id();
        Asset target = assets.create(project, AssetType.API_CASE, null, "接口", Map.of("path", "/refund", "generationEvidence", Map.of("note", "source mentions ${newToken}")), "MANUAL");
        Asset provider = assets.create(project, AssetType.API_CASE, null, "上游", Map.of("path", "/login"), "MANUAL");
        String conversation = conversations.ensure(null, project, "GLOBAL", null, null);
        String set = changes.create(project, conversation, "metadata-preview", List.of(
                new AiChangeSetService.Proposal("MODIFY", target.type(), target.id(), null, null, target.version(), "接口新名称", Map.of()),
                new AiChangeSetService.Proposal("MODIFY", provider.type(), provider.id(), null, null, provider.version(), null, Map.of("extractors", List.of(Map.of("variable", "newToken", "jsonpath", "$.token"))))));
        String selected = Values.objects(changes.get(project, set).get("items")).stream().filter(item -> target.id().equals(item.get("targetId"))).findFirst().orElseThrow().get("id").toString();
        assertThatCode(() -> changes.apply(project, set, List.of(selected))).doesNotThrowAnyException();
        assertThat(assets.get(project, target.id()).name()).isEqualTo("接口新名称");
        assertThat(assets.get(project, provider.id())).isEqualTo(provider);
    }

    private Map<String, Object> inventedRuntime() { return Map.of("locators", List.of(Map.of("selector", "testId=invented", "frame", "", "evidenceLevel", "OBSERVED")), "urls", List.of(), "configuredBaseUrl", ""); }
}
