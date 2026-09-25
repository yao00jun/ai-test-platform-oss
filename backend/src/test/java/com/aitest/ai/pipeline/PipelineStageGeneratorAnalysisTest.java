package com.aitest.ai.pipeline;

import com.aitest.ai.AiChangeSetService.Proposal;
import com.aitest.asset.AssetType;
import com.aitest.common.Problem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PipelineStageGeneratorAnalysisTest {
    @Test void objectAnalysisIsStoredAsIs() {
        assertThat(PipelineStageGenerator.analysisObject(Map.of("roles", List.of("用户")))).containsEntry("roles", List.of("用户"));
    }
    @Test void sectionListsAndPlainTextAreWrappedInsteadOfRejected() {
        var wrapped = PipelineStageGenerator.analysisObject(List.of(Map.of("category", "主流程", "content", "登录"), "补充说明"));
        assertThat(wrapped).containsOnlyKeys("sections");
        assertThat(wrapped.get("sections")).isEqualTo(List.of(Map.of("category", "主流程", "content", "登录"), Map.of("content", "补充说明")));
        assertThat(PipelineStageGenerator.analysisObject("纯文本分析")).containsEntry("summary", "纯文本分析");
        assertThat(PipelineStageGenerator.analysisObject(List.of())).isEmpty();
        assertThat(PipelineStageGenerator.analysisObject(" ")).isEmpty();
        assertThat(PipelineStageGenerator.analysisObject(null)).isEmpty();
    }
    private static Proposal add(AssetType type, String key, String parent, String name) { return new Proposal("ADD", type, null, parent, key, null, name, Map.of()); }

    @Test void stepsWithoutParentJoinTheOnlyScenarioOfTheBatch() {
        var owned = PipelineStageGenerator.ownScenarioSteps(List.of(add(AssetType.API_CASE, "c1", null, "用例"), add(AssetType.SCENARIO, "s1", null, "场景"),
                add(AssetType.SCENARIO_STEP, "st1", null, "步骤一"), add(AssetType.SCENARIO_STEP, "st2", "s1", "步骤二"), add(AssetType.SCENARIO_STEP, "st3", "@s1", "步骤三")));
        assertThat(owned).extracting(Proposal::parentId).containsExactly(null, null, "@s1", "@s1", "@s1");
    }
    @Test void ambiguousOrForeignParentsAreRejectedWithTheStepNameAndChoices() {
        var twoScenarios = List.of(add(AssetType.SCENARIO, "s1", null, "场景一"), add(AssetType.SCENARIO, "s2", null, "场景二"), add(AssetType.SCENARIO_STEP, "st", null, "孤儿步骤"));
        assertThatThrownBy(() -> PipelineStageGenerator.ownScenarioSteps(twoScenarios)).isInstanceOfSatisfying(Problem.class, p -> { assertThat(p.getMessage()).contains("孤儿步骤").contains("@s1").contains("@s2"); assertThat(p.code()).as("an omission gets one repair round").isEqualTo("VALIDATION_FAILED"); });
        var existing = List.of(add(AssetType.SCENARIO, "s1", null, "场景一"), add(AssetType.SCENARIO_STEP, "st", "0a1b2c3d4e5f", "追加步骤"));
        assertThatThrownBy(() -> PipelineStageGenerator.ownScenarioSteps(existing)).isInstanceOfSatisfying(Problem.class, p -> { assertThat(p.getMessage()).contains("追加步骤").contains("不能追加到已有场景"); assertThat(p.code()).as("boundary violations are never replayed").isEqualTo(com.aitest.ai.AiDraftEngine.OUT_OF_SCOPE); });
        var none = List.of(add(AssetType.SCENARIO_STEP, "st", null, "无场景步骤"));
        assertThatThrownBy(() -> PipelineStageGenerator.ownScenarioSteps(none)).isInstanceOfSatisfying(Problem.class, p -> assertThat(p.getMessage()).contains("本批还没有新增任何 SCENARIO"));
    }
    @Test void apiSchemaKeepsOnlyTheOperationAndTheComponentsItReferences() {
        Map<String, Object> document = Map.of("info", Map.of("title", "big"), "components", Map.of("schemas", Map.of(
                "Note", Map.of("type", "object", "properties", Map.of("tags", Map.of("$ref", "#/components/schemas/Tag"))),
                "Tag", Map.of("type", "string"),
                "Unrelated", Map.of("type", "object"))));
        Map<String, Object> schema = Map.of("document", document, "sourceFormat", "openapi", "operation", Map.of("requestBody", Map.of("content", Map.of("application/json", Map.of("schema", Map.of("$ref", "#/components/schemas/Note"))))));
        var slim = PipelineStageGenerator.slimApiSchema(schema);
        assertThat(slim).containsKeys("sourceFormat", "operation", "components").doesNotContainKey("document");
        assertThat(((Map<?, ?>) ((Map<?, ?>) slim.get("components")).get("schemas")).keySet()).isEqualTo(java.util.Set.of("Note", "Tag"));
        assertThat(PipelineStageGenerator.slimApiSchema(null)).isEmpty();
        assertThat(PipelineStageGenerator.slimApiSchema(Map.of("operation", Map.of("summary", "no refs")))).containsOnlyKeys("operation");
    }
    @Test void evidenceIsScopedToWhatEachStageCanUse() {
        Map<String, Object> full = Map.of("binding", Map.of("status", "OK"), "backend", Map.of("endpoints", List.of("e")), "frontend", Map.of("selectors", List.of("s")), "database", Map.of("tables", List.of("t")), "diagnostics", List.of(Map.of("code", "X", "kind", "FRONTEND"), Map.of("code", "Y", "kind", "BACKEND")));
        assertThat(PipelineStageGenerator.scopedEvidence("S2", full)).containsKeys("binding", "backend", "scope").doesNotContainKeys("frontend", "database");
        assertThat(PipelineStageGenerator.scopedEvidence("S2", full).get("diagnostics")).isEqualTo(Map.of("count", 2, "note", "静态分析告警仅在 UI 阶段提供"));
        assertThat(PipelineStageGenerator.scopedEvidence("S4", full)).containsKeys("backend", "database").doesNotContainKey("frontend");
        assertThat(PipelineStageGenerator.scopedEvidence("S5", full)).containsKeys("frontend", "backend").doesNotContainKey("database");
        assertThat(PipelineStageGenerator.scopedEvidence("S5", full).get("diagnostics")).as("The UI stage only needs frontend gaps").isEqualTo(List.of(Map.of("code", "X", "kind", "FRONTEND")));
        assertThat(PipelineStageGenerator.scopedEvidence("S3", Map.of())).isEmpty();
    }
    @Test void repairContextDropsTheBulkyMaterial() {
        Map<String, Object> context = Map.of("stage", "S3", "schemas", List.of(), "instruction", "x", "sourceEvidence", Map.of("big", "..."), "existingAssets", List.of(1), "sources", List.of(2), "apiIndex", List.of(3));
        assertThat(com.aitest.ai.AiDraftEngine.repairContext(context)).containsOnlyKeys("stage", "schemas", "instruction");
    }
    @Test void evidenceBudgetGoesToTheDomainsAStageActsOnFirst() {
        assertThat(PipelineStageGenerator.evidenceDomains("S4")).containsExactly("database", "backend");
        assertThat(PipelineStageGenerator.evidenceDomains("S5")).containsExactly("frontend", "backend");
        assertThat(PipelineStageGenerator.evidenceDomains("S2")).containsExactly("backend");
        assertThat(PipelineStageGenerator.evidenceBudget("S1")).isLessThan(PipelineStageGenerator.evidenceBudget("S3"));
    }

    @Test void pathParametersMayBeFilledButTheDocumentedPathMayNotChange() {
        String documented = "/position/base/page/{pageSize}/{curPage}";
        assertThat(PipelineStageGenerator.pathMismatch(documented, "/position/base/page/10/1")).isNull();
        assertThat(PipelineStageGenerator.pathMismatch(documented, "/position/base/page/${pageSize}/1")).isNull();
        assertThat(PipelineStageGenerator.pathMismatch("/files/{name}.{ext}", "/files/report.pdf")).isNull();
        assertThat(PipelineStageGenerator.pathMismatch("/orders", "/orders")).isNull();
        assertThat(PipelineStageGenerator.pathMismatch(documented, documented)).as("A literal template cannot be executed").contains("{pageSize}").contains("取值");
        assertThat(PipelineStageGenerator.pathMismatch(documented, "/position/base/list/10/1")).contains("一字不差");
        assertThat(PipelineStageGenerator.pathMismatch(documented, "/position/base/page/10")).contains("一字不差");
        assertThat(PipelineStageGenerator.pathMismatch(documented, "https://api.example.com/position/base/page/10/1")).contains("协议");
        assertThat(PipelineStageGenerator.pathMismatch("/orders", "/orders?status=1")).contains("queryParams");
        assertThat(PipelineStageGenerator.pathMismatch("/orders", "")).contains("不能为空");
    }

    @Test void generatedCasesAreHeldToTheDocumentedOperationWithUnambiguousSlipsFixed() {
        var definition = new com.aitest.asset.Asset("api-1", "p", AssetType.API_DEFINITION, null, "职位底座分页", "1", 0, "IMPORT", false, java.time.Instant.EPOCH, java.time.Instant.EPOCH,
                Map.of("method", "POST", "path", "/position/base/page/{pageSize}/{curPage}"));
        var proposal = new Proposal("ADD", AssetType.API_CASE, null, null, "case_1", null, "职位底座分页-正向-空条件默认分页查询", Map.of("apiDefinitionId", "api-1", "method", "post", "path", "/position/base/page/10/1/",
                "assertions", List.of(Map.of("type", "status_code", "expected", 200), Map.of("type", "jsonpath", "expression", "$.code", "expected", 0))));
        var held = PipelineStageGenerator.documentedRequest(proposal, definition);
        assertThat(held.data()).containsEntry("method", "POST").containsEntry("path", "/position/base/page/10/1");
        assertThat(held.data().get("assertions")).isEqualTo(List.of(Map.of("type", "status_code", "expected", 200), Map.of("type", "jsonpath", "expected", 0, "path", "$.code")));

        var wrongPath = new Proposal("ADD", AssetType.API_CASE, null, null, "case_2", null, "错接口", Map.of("apiDefinitionId", "api-1", "method", "POST", "path", "/position/base/list/10/1", "assertions", List.of()));
        assertThatThrownBy(() -> PipelineStageGenerator.documentedRequest(wrongPath, definition)).isInstanceOfSatisfying(Problem.class, problem -> {
            assertThat(problem.code()).as("A repair round can fix it, so the whole batch is not thrown away").isEqualTo("VALIDATION_FAILED");
            assertThat(problem.getMessage()).contains("错接口").contains("/position/base/list/10/1").contains("POST /position/base/page/{pageSize}/{curPage}");
        });
        var wrongMethod = new Proposal("ADD", AssetType.API_CASE, null, null, "case_3", null, "错方法", Map.of("apiDefinitionId", "api-1", "method", "GET", "path", "/position/base/page/10/1"));
        assertThatThrownBy(() -> PipelineStageGenerator.documentedRequest(wrongMethod, definition)).isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.getMessage()).contains("GET").contains("POST"));
        var untyped = new Proposal("ADD", AssetType.API_CASE, null, null, "case_4", null, "无类型断言", Map.of("apiDefinitionId", "api-1", "method", "POST", "path", "/position/base/page/10/1", "assertions", List.of(Map.of("expected", 200))));
        assertThatThrownBy(() -> PipelineStageGenerator.documentedRequest(untyped, definition)).isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.getMessage()).contains("type"));
    }

    @Test void databaseSchemasAreSentOneLinePerColumn() {
        var compact = PipelineStageGenerator.compactSchema(Map.of("databaseSourceId", "db-1", "version", "3", "tables", List.of(Map.of("name", "t_order", "primaryKeys", List.of("id"),
                "columns", List.of(Map.of("name", "id", "type", "BIGINT", "nullable", false, "size", 19, "remarks", ""), Map.of("name", "status", "type", "VARCHAR", "nullable", true, "size", 16, "remarks", "订单状态")),
                "references", List.of(Map.of("column", "user_id", "targetTable", "t_user", "targetColumn", "id"))))));
        assertThat(compact).containsEntry("databaseSourceId", "db-1").doesNotContainKey("version");
        assertThat(compact.get("tables")).isEqualTo(List.of(Map.of("name", "t_order", "columns", List.of("id BIGINT NOT NULL PK", "status VARCHAR -- 订单状态"), "references", List.of("user_id -> t_user.id"))));
    }

    @Test void scalarsStillNameTheOffendingType() {
        assertThatThrownBy(() -> PipelineStageGenerator.analysisObject(42)).isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.getMessage()).contains("Integer"));
    }
}
