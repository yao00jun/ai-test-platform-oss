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
        Map<String, Object> full = Map.of("binding", Map.of("status", "OK"), "backend", Map.of("endpoints", List.of("e")), "frontend", Map.of("selectors", List.of("s")), "database", Map.of("tables", List.of("t")), "diagnostics", List.of(Map.of("code", "X")));
        assertThat(PipelineStageGenerator.scopedEvidence("S2", full)).containsKeys("binding", "backend", "scope").doesNotContainKeys("frontend", "database");
        assertThat(PipelineStageGenerator.scopedEvidence("S2", full).get("diagnostics")).isEqualTo(Map.of("count", 1, "note", "静态分析告警仅在 UI 阶段提供"));
        assertThat(PipelineStageGenerator.scopedEvidence("S4", full)).containsKeys("backend", "database").doesNotContainKey("frontend");
        assertThat(PipelineStageGenerator.scopedEvidence("S5", full)).containsKeys("frontend", "backend").doesNotContainKey("database");
        assertThat(PipelineStageGenerator.scopedEvidence("S5", full).get("diagnostics")).isEqualTo(List.of(Map.of("code", "X")));
        assertThat(PipelineStageGenerator.scopedEvidence("S3", Map.of())).isEmpty();
    }
    @Test void repairContextDropsTheBulkyMaterial() {
        Map<String, Object> context = Map.of("stage", "S3", "schemas", List.of(), "instruction", "x", "sourceEvidence", Map.of("big", "..."), "existingAssets", List.of(1), "sources", List.of(2), "apiIndex", List.of(3));
        assertThat(com.aitest.ai.AiDraftEngine.repairContext(context)).containsOnlyKeys("stage", "schemas", "instruction", "apiIndex");
    }
    @Test void scalarsStillNameTheOffendingType() {
        assertThatThrownBy(() -> PipelineStageGenerator.analysisObject(42)).isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.getMessage()).contains("Integer"));
    }
}
