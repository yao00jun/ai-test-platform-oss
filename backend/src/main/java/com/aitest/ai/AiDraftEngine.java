package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.ai.text.MdUtil;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.execution.Values;
import com.aitest.job.JobContext;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class AiDraftEngine {
    private final ModelInvocationService gateway;
    private final PromptCatalog prompts;
    private final JsonCodec json;
    public AiDraftEngine(ModelInvocationService gateway, PromptCatalog prompts, JsonCodec json) { this.gateway = gateway; this.prompts = prompts; this.json = json; }
    public Draft generate(ModelSettings model, JobContext job, String template, Map<String, Object> context, AssetType requestedType, String parentId) {
        return generate(model, job, template, context, requestedType, parentId, "", false);
    }
    public Draft generate(ModelSettings model, JobContext job, String template, Map<String, Object> context, AssetType requestedType, String parentId, String contract, boolean allowEmpty) {
        Map<String, String> variables = new LinkedHashMap<>(); Values.map(context.get("templateVariables")).forEach((key, value) -> variables.put(key, Objects.toString(value, "")));
        String system = prompts.render(template, variables) + (contract.isBlank() ? "" : "\n\n# 当前平台调用协议（输出结构以此为准）\n" + contract);
        String raw = gateway.complete(model, job, template, "INITIAL", system, json.write(context), token -> job.event("token", Map.of("token", token, "content", token)));
        for (int attempt = 0; ; attempt++) {
            try { return new Draft(raw, requestedType == AssetType.FUNCTIONAL_CASE && template.equals("functional_case_generation") ? markdown(raw, parentId) : jsonDraft(raw, allowEmpty)); }
            catch (Problem formatError) {
                if (formatError.code().equals("GENERATION_BLOCKED")) throw formatError;
                if (attempt != 0) throw new Problem(422, "AI_OUTPUT_INVALID", formatError.getMessage(), Map.of("rawOutput", raw));
                job.event("progress", Map.of("message", "输出格式需要修复，正在进行一次有限重试"));
                raw = gateway.complete(model, job, template, "FORMAT_REPAIR", system, json.write(Map.of("context", context, "invalidOutput", raw, "validationError", formatError.getMessage(), "instruction", "仅修复输出结构，不改变作用范围和业务事实")), token -> job.event("token", Map.of("token", token, "content", token)));
            }
        }
    }
    public List<Map<String, Object>> schemas(Set<AssetType> types) {
        return types.stream().map(type -> {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", type.name());
            schema.put("fields", type.fields().stream().filter(f -> !f.kind().equals("password") && !Set.of("scheduleEnabled", "enabled", "sourceSnapshotId", "generationEvidence").contains(f.key()) && (type != AssetType.QUALITY_BRIEF || !Set.of("metrics", "runId").contains(f.key()))).toList());
            schema.put("childTypes", type.childTypes());
            if (type == AssetType.DASHBOARD) schema.put("cardSchema", com.aitest.workbench.DashboardCardSchema.contract());
            return schema;
        }).toList();
    }
    private List<AiChangeSetService.Proposal> markdown(String raw, String parentId) {
        List<AiChangeSetService.Proposal> changes = new ArrayList<>(); int index = 0;
        for (var item : MdUtil.batchTransformToCaseDTO(raw)) {
            String key = "case_" + index++;
            Map<String, Object> data = new LinkedHashMap<>(); data.put("precondition", item.getPrerequisite()); data.put("remark", item.getDescription());
            var priority = java.util.regex.Pattern.compile("\\bP[0-3]\\b").matcher(Objects.toString(item.getDescription(), ""));
            if (priority.find()) data.put("priority", priority.group());
            changes.add(new AiChangeSetService.Proposal("ADD", AssetType.FUNCTIONAL_CASE, null, parentId, key, null, item.getName(), data));
            int stepIndex = 0;
            for (var step : item.getSteps()) changes.add(new AiChangeSetService.Proposal("ADD", AssetType.FUNCTIONAL_STEP, null, "@" + key, key + "_step_" + stepIndex++, null, "步骤 " + stepIndex, Map.of("step", Objects.toString(step.getDesc(), ""), "expected", Objects.toString(step.getResult(), ""))));
        }
        return changes;
    }
    private List<AiChangeSetService.Proposal> jsonDraft(String raw, boolean allowEmpty) {
        String text = raw.strip();
        if (text.startsWith("```")) {
            var wrapper = java.util.regex.Pattern.compile("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$").matcher(text);
            if (!wrapper.matches()) throw Problem.invalid("模型输出被截断"); text = wrapper.group(1);
        }
        Map<String, Object> object = json.map(text);
        if (object.containsKey("blocked")) throw new Problem(422, "GENERATION_BLOCKED", Objects.toString(object.get("blocked"), "缺少生成依据"));
        if (allowEmpty && object.keySet().equals(Set.of("changes", "reason")) && object.get("changes") instanceof List<?> list && list.isEmpty() && object.get("reason") instanceof String reason && !reason.isBlank()) return List.of();
        if (!object.keySet().equals(Set.of("changes"))) throw Problem.invalid("输出应只包含 changes 数组");
        List<Map<String, Object>> rows = Values.objects(object.get("changes"));
        if (rows.isEmpty() || rows.size() > 200) throw Problem.invalid("每轮需要 1–200 个明确变更");
        return rows.stream().map(row -> json.convert(row, AiChangeSetService.Proposal.class)).toList();
    }
    public static Set<AssetType> generatedTypes(AssetType type) {
        Set<AssetType> result = new LinkedHashSet<>(); collect(type, result);
        if (type == AssetType.SCENARIO) result.add(AssetType.API_CASE);
        return result;
    }
    private static void collect(AssetType type, Set<AssetType> result) { if (result.add(type)) type.childTypes().forEach(child -> collect(child, result)); }
    public record Draft(String raw, List<AiChangeSetService.Proposal> changes) { }
}
