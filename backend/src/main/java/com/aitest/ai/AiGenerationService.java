package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.analysis.GenerationEvidenceService;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.job.*;
import com.aitest.report.QualityMetricsService;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class AiGenerationService implements JobHandler {
    private final AssetService assets; private final JobService jobs; private final QualityMetricsService metrics;
    private final AiConversationService conversations; private final AiChangeSetService changes;
    private final ModelSettingsService settings; private final AiDraftEngine drafts; private final PromptCatalog prompts; private final JsonCodec json;
    private final GenerationEvidenceService evidence;
    public AiGenerationService(AssetService assets, JobService jobs, AiConversationService conversations, AiChangeSetService changes, ModelSettingsService settings, AiDraftEngine drafts, PromptCatalog prompts, JsonCodec json, QualityMetricsService metrics, GenerationEvidenceService evidence) {
        this.assets = assets; this.jobs = jobs; this.conversations = conversations; this.changes = changes; this.settings = settings; this.drafts = drafts; this.prompts = prompts; this.json = json; this.metrics = metrics; this.evidence = evidence;
    }
    @Override public String kind() { return "AI_GENERATE"; }
    public AiRefinementService.Submission submit(Request request) {
        if (request.type() == null || request.instruction() == null || request.instruction().isBlank() || request.instruction().length() > 16000) throw Problem.invalid("请选择资产类型并输入 1–16000 字符的生成要求");
        if (request.runId() != null) { if (request.type() != AssetType.QUALITY_BRIEF || request.runId().isBlank()) throw Problem.invalid("只有质量简报可以指定有效运行 ID"); metrics.requireRun(request.projectId(), request.runId()); }
        if (request.type() == AssetType.QUALITY_BRIEF && request.sourceIds() != null && !request.sourceIds().isEmpty()) throw Problem.invalid("质量简报使用真实运行范围，请使用 runId 指定运行");
        if (request.parentId() != null && !request.parentId().isBlank() && !assets.get(request.projectId(), request.parentId()).type().childTypes().contains(request.type())) throw Problem.invalid("父级不接受此类型资产");
        String conversation = conversations.ensure(request.conversationId(), request.projectId(), "GENERATE", request.runId() == null ? request.parentId() : request.runId(), request.type().name());
        Map<String, Object> input = json.map(json.write(request)); input.put("conversationId", conversation);
        String source = evidence.inheritedSource(request.projectId(), request.parentId(), request.sourceSnapshotId());
        if (source.isBlank()) input.remove("sourceSnapshotId"); else input.put("sourceSnapshotId", source);
        Job job = jobs.submit(request.projectId(), kind(), request.idempotencyKey(), input);
        return new AiRefinementService.Submission(job.id(), conversation);
    }
    @Override public Map<String, Object> execute(JobContext job, Map<String, Object> input) {
        Map<String, Object> requestMap = new LinkedHashMap<>(input); String conversation = requestMap.remove("conversationId").toString();
        Request request = json.convert(requestMap, Request.class); boolean quality = request.type() == AssetType.QUALITY_BRIEF;
        String template = quality ? "test_summary_report" : request.type() == AssetType.FUNCTIONAL_CASE ? "functional_case_generation" : "asset_generation";
        List<Asset> source = quality ? List.of() : request.sourceIds() == null || request.sourceIds().isEmpty()
                ? assets.all(job.projectId())
                : request.sourceIds().stream().map(id -> assets.get(job.projectId(), id)).toList();
        Set<AssetType> allowed = AiDraftEngine.generatedTypes(request.type());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("instruction", request.instruction()); context.put("assets", source); context.put("project", assets.get(job.projectId(), job.projectId()));
        context.put("targetType", request.type()); context.put("parentId", request.parentId()); context.put("schemas", drafts.schemas(allowed));
        context.put("history", conversations.messages(job.projectId(), conversation));
        context.put("sourceEvidence", evidence.contexts(job.projectId(), source, request.sourceSnapshotId()));
        Map<String, Object> executionMetrics = quality ? metrics.capture(job.projectId(), request.runId()) : Map.of();
        if (quality) { context.put("executionMetrics", executionMetrics); context.put("templateVariables", metrics.templateVariables(executionMetrics)); }
        conversations.recordUser(conversation, job.id(), request.instruction(), null, context);
        String raw = "", stamp = "unconfigured";
        try {
            ModelSettings model = settings.current(); stamp = model.modelName() + ":" + model.version();
            job.progress(10, "正在依据当前需求和资产生成草稿");
            AiDraftEngine.Draft draft = drafts.generate(model, job, template, context, request.type(), request.parentId(), quality ? QualityMetricsService.CONTRACT : "", false); raw = draft.raw();
            List<AiChangeSetService.Proposal> proposals = quality ? metrics.ground(draft.changes(), executionMetrics) : evidence.ground(job.projectId(), request.sourceSnapshotId(), draft.changes());
            for (var proposal : draft.changes()) {
                if (!allowed.contains(proposal.targetType())) throw Problem.invalid("生成结果包含未授权的资产类型");
                if (proposal.targetType() != AssetType.PROJECT && !"ADD".equals(proposal.operation())) throw Problem.invalid("初次生成只能新增草稿，修改既有资产请使用反馈调优");
                if (proposal.targetType() == AssetType.PROJECT && (!"MODIFY".equals(proposal.operation()) || !job.projectId().equals(proposal.targetId()))) throw Problem.invalid("项目生成仅能修改当前项目资料");
                if (request.parentId() != null && proposal.targetType() == request.type() && !Objects.equals(request.parentId(), proposal.parentId())) throw Problem.invalid("生成资产超出指定父级");
                if (proposal.data() != null && proposal.targetType().fields().stream().anyMatch(field -> field.kind().equals("password") && proposal.data().containsKey(field.key()))) throw Problem.invalid("AI 不能生成凭证字段");
            }
            String modelStamp = stamp;
            return job.completeAtomically(() -> {
                String changeId = changes.create(job.projectId(), conversation, job.id(), proposals);
                List<String> ids = changeIds(changes.get(job.projectId(), changeId));
                Map<String, Object> result = changes.apply(job.projectId(), changeId, ids);
                conversations.recordAssistant(conversation, job.id(), draft.raw(), "APPLIED", null, null, proposals, Map.of("valid", true), modelStamp, prompts.version(template));
                return Map.of("assets", result.get("assets"), "changeSetId", changeId, "status", "APPLIED");
            });
        } catch (RuntimeException error) {
            boolean interrupted = Thread.interrupted();
            try { conversations.recordAssistant(conversation, job.id(), raw, "FAILED", null, null, Map.of("raw", raw), Map.of("message", error instanceof Problem ? error.getMessage() : "生成失败"), stamp, prompts.version(template)); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw error;
        }
    }
    public static List<String> changeIds(Map<String, Object> changeSet) { return com.aitest.execution.Values.objects(changeSet.get("items")).stream().map(item -> item.get("id").toString()).toList(); }
    public record Request(String projectId, AssetType type, String parentId, String instruction, List<String> sourceIds, String idempotencyKey, String conversationId, String runId, String sourceSnapshotId) {
        public Request(String projectId, AssetType type, String parentId, String instruction, List<String> sourceIds, String idempotencyKey, String conversationId, String runId) { this(projectId, type, parentId, instruction, sourceIds, idempotencyKey, conversationId, runId, null); }
        public Request(String projectId, AssetType type, String parentId, String instruction, List<String> sourceIds, String idempotencyKey, String conversationId) { this(projectId, type, parentId, instruction, sourceIds, idempotencyKey, conversationId, null); }
        public Request(String projectId, AssetType type, String parentId, String instruction, List<String> sourceIds, String idempotencyKey) {
            this(projectId, type, parentId, instruction, sourceIds, idempotencyKey, null);
        }
    }
}
