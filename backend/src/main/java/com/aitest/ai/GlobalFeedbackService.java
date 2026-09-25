package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.analysis.GenerationEvidenceService;
import com.aitest.execution.Values;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.job.*;
import com.aitest.report.QualityMetricsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class GlobalFeedbackService implements JobHandler {
    private final AssetService assets; private final AiConversationService conversations; private final AiChangeSetService changes;
    private final JobService jobs; private final ModelSettingsService settings; private final AiDraftEngine drafts; private final JsonCodec json; private final PromptCatalog prompts; private final JdbcTemplate jdbc; private final QualityMetricsService metrics;
    private final GenerationEvidenceService evidence;
    public GlobalFeedbackService(AssetService assets, AiConversationService conversations, AiChangeSetService changes, JobService jobs, ModelSettingsService settings, AiDraftEngine drafts, JsonCodec json, PromptCatalog prompts, JdbcTemplate jdbc, QualityMetricsService metrics, GenerationEvidenceService evidence) {
        this.assets = assets; this.conversations = conversations; this.changes = changes; this.jobs = jobs; this.settings = settings; this.drafts = drafts; this.json = json; this.prompts = prompts; this.jdbc = jdbc; this.metrics = metrics; this.evidence = evidence;
    }
    @Override public String kind() { return "AI_GLOBAL_FEEDBACK"; }
    public AiRefinementService.Submission submit(Request request) {
        if (request.feedback() == null || request.feedback().isBlank() || request.feedback().length() > 16000) throw Problem.invalid("请输入 1–16000 字符的全局反馈");
        String conversation = request.pipelineId() == null
                ? conversations.ensure(request.conversationId(), request.projectId(), "GLOBAL", null, null)
                : pipelineConversation(request);
        Map<String, Object> input = json.map(json.write(request)); input.put("conversationId", conversation);
        evidence.requireSource(request.projectId(), request.sourceSnapshotId());
        if (request.sourceSnapshotId() == null) input.remove("sourceSnapshotId");
        return new AiRefinementService.Submission(jobs.submit(request.projectId(), kind(), request.idempotencyKey(), input).id(), conversation);
    }
    @Override public Map<String, Object> execute(JobContext job, Map<String, Object> input) {
        Request request = json.convert(input, Request.class);
        Map<String, Asset> targets = new LinkedHashMap<>();
        if (request.pipelineId() != null) {
            pipelineConversation(request);
            var rows = jdbc.queryForList("SELECT asset_ids FROM ai_pipeline_record WHERE id=? AND project_id=?", request.pipelineId(), job.projectId());
            if (rows.isEmpty()) throw Problem.missing();
            for (Object id : (List<?>) json.tree(rows.getFirst().get("asset_ids").toString())) {
                try { Asset asset = assets.get(job.projectId(), id.toString()); targets.put(asset.id(), asset); }
                catch (Problem missing) { if (missing.status() != 404) throw missing; }
            }
        }
        if (request.assetIds() != null) for (String id : request.assetIds()) { Asset asset = assets.get(job.projectId(), id); targets.put(asset.id(), asset); }
        else if (request.pipelineId() == null) assets.all(job.projectId()).forEach(asset -> targets.put(asset.id(), asset));
        List<Asset> manifest = List.copyOf(targets.values());
        String source = Objects.toString(request.sourceSnapshotId(), "");
        if (request.pipelineId() != null) {
            String bound = Values.text(json.map(jdbc.queryForObject("SELECT config_snapshot FROM ai_pipeline_record WHERE id=? AND project_id=?", String.class, request.pipelineId(), job.projectId())), "sourceSnapshotId", "");
            if (!bound.isBlank() && !source.isBlank() && !source.equals(bound)) throw Problem.conflict("流水线反馈必须使用已经绑定的源码快照");
            if (!bound.isBlank()) source = bound;
        }
        Map<String, Object> executionMetrics = metrics.capture(job.projectId(), null);
        Map<String, Object> context = Map.of("feedback", request.feedback(), "manifest", ModelContextBudget.assets(manifest, 150_000, json), "project", assets.get(job.projectId(), job.projectId()), "history", ModelContextBudget.history(conversations.messages(job.projectId(), request.conversationId())), "schemas", drafts.schemas(EnumSet.allOf(AssetType.class)), "executionMetrics", executionMetrics, "sourceEvidence", evidence.contexts(job.projectId(), manifest, source));
        conversations.recordUser(request.conversationId(), job.id(), request.feedback(), null, context);
        String raw = "", stamp = "unconfigured";
        try {
            ModelSettings model = settings.current(); stamp = model.modelName() + ":" + model.version();
            job.progress(10, "正在比较当前资产、人工修改和多轮反馈");
            AiDraftEngine.Draft draft = drafts.generate(model, job, "pipeline_feedback", context, null, null, "", true, proposed -> {
                for (var proposal : proposed) if (!"ADD".equals(proposal.operation()) && !targets.containsKey(proposal.targetId()))
                    throw Problem.invalid("「" + proposal.name() + "」不在本轮反馈的资产范围内，MODIFY/DELETE 只能针对 manifest 中的资产");
                return changes.preflight(job.projectId(), proposed);
            }); raw = draft.raw();
            if (draft.changes().isEmpty()) {
                String reason = "本轮反馈无需修改已有资产";
                try { reason = Values.text(json.map(draft.raw().strip()), "reason", reason); } catch (RuntimeException unreadable) { /* Keep the generic sentence. */ }
                conversations.recordAssistant(request.conversationId(), job.id(), draft.raw(), "NO_CHANGES", null, null, List.of(), Map.of("valid", true), stamp, prompts.version("pipeline_feedback"));
                return Map.of("status", "NO_CHANGES", "message", reason);
            }
            List<AiChangeSetService.Proposal> proposals = evidence.groundGlobal(job.projectId(), source, manifest, metrics.ground(draft.changes(), executionMetrics));
            for (var proposal : draft.changes()) if (!proposal.operation().equals("ADD")) {
                Asset target = targets.get(proposal.targetId());
                if (target == null) throw Problem.invalid("全局候选试图修改作用域之外的资产");
                AssetService.requireVersion(target, proposal.baseVersion());
                if (target.confirmed()) throw new Problem(409, "PROTECTED_ASSET", "已确认资产保持原状，未生成可覆盖的变更");
            }
            String modelStamp = stamp;
            return job.completeAtomically(() -> {
                String changeId = changes.create(job.projectId(), request.conversationId(), job.id(), proposals);
                conversations.recordAssistant(request.conversationId(), job.id(), draft.raw(), "PREVIEW", null, null, proposals, Map.of("valid", true), modelStamp, prompts.version("pipeline_feedback"));
                return Map.of("changeSetId", changeId, "status", "PREVIEW");
            });
        } catch (RuntimeException error) {
            boolean interrupted = Thread.interrupted();
            try { conversations.recordAssistant(request.conversationId(), job.id(), raw, "FAILED", null, null, Map.of("raw", raw), Map.of("message", error instanceof Problem ? error.getMessage() : "全局反馈生成失败"), stamp, prompts.version("pipeline_feedback")); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw error;
        }
    }
    private String pipelineConversation(Request request) {
        var rows = jdbc.queryForList("SELECT conversation_id FROM ai_pipeline_record WHERE id=? AND project_id=?", request.pipelineId(), request.projectId());
        if (rows.isEmpty()) throw Problem.missing();
        String canonical = rows.getFirst().get("conversation_id").toString();
        if (request.conversationId() != null && !request.conversationId().isBlank() && !canonical.equals(request.conversationId()))
            throw new Problem(409, "CONVERSATION_SCOPE_MISMATCH", "流水线反馈必须使用该流水线保存的会话");
        return conversations.ensure(canonical, request.projectId(), "PIPELINE", request.pipelineId(), null);
    }
    public record Request(String projectId, String pipelineId, String conversationId, String feedback, List<String> assetIds, String idempotencyKey, String sourceSnapshotId) {
        public Request(String projectId, String pipelineId, String conversationId, String feedback, List<String> assetIds, String idempotencyKey) { this(projectId, pipelineId, conversationId, feedback, assetIds, idempotencyKey, null); }
    }
}
