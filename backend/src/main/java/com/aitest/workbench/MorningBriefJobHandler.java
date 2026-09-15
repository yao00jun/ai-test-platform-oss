package com.aitest.workbench;

import com.aitest.ai.*;
import com.aitest.asset.AssetType;
import com.aitest.common.*;
import com.aitest.execution.Values;
import com.aitest.job.*;
import com.aitest.report.QualityMetricsService;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class MorningBriefJobHandler implements JobHandler {
    private final MorningBriefService briefs;
    private final AiDraftEngine drafts;
    private final AiChangeSetService changes;
    private final AiConversationService conversations;
    private final ModelSettingsService settings;
    private final PromptCatalog prompts;
    private final QualityMetricsService metrics;
    private final JsonCodec json;
    public MorningBriefJobHandler(MorningBriefService briefs, AiDraftEngine drafts, AiChangeSetService changes, AiConversationService conversations,
                                  ModelSettingsService settings, PromptCatalog prompts, QualityMetricsService metrics, JsonCodec json) {
        this.briefs = briefs; this.drafts = drafts; this.changes = changes; this.conversations = conversations;
        this.settings = settings; this.prompts = prompts; this.metrics = metrics; this.json = json;
    }
    @Override public String kind() { return MorningBriefService.KIND; }
    @Override public Map<String, Object> execute(JobContext job, Map<String, Object> input) {
        String id = Objects.toString(input.get("occurrenceId"), ""), raw = "", stamp = "unconfigured";
        MorningBriefService.Occurrence occurrence = briefs.freeze(job, id);
        String conversation = occurrence.conversationId();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("instruction", occurrence.instruction()); context.put("executionMetrics", occurrence.metrics());
        context.put("templateVariables", metrics.templateVariables(occurrence.metrics())); context.put("targetType", AssetType.QUALITY_BRIEF);
        context.put("morningBrief", Map.of("localDate", occurrence.localDate(), "timezone", occurrence.timezone(), "windowFrom", occurrence.windowFrom(), "windowTo", occurrence.windowTo()));
        context.put("schemas", drafts.schemas(Set.of(AssetType.QUALITY_BRIEF))); context.put("history", conversations.messages(job.projectId(), conversation));
        job.atomic(() -> { conversations.recordUser(conversation, job.id(), occurrence.instruction(), null, context); return null; });
        try {
            ModelSettings model = settings.current(); stamp = model.modelName() + ":" + model.version();
            job.progress(15, "已保存昨日统计快照，正在生成质量晨报");
            AiDraftEngine.Draft draft = drafts.generate(model, job, "test_summary_report", context, AssetType.QUALITY_BRIEF, null, QualityMetricsService.CONTRACT, false);
            raw = draft.raw(); validate(draft.changes());
            var proposals = metrics.ground(draft.changes(), occurrence.metrics()); String modelStamp = stamp;
            return job.completeAtomically(() -> {
                String changeId = changes.create(job.projectId(), conversation, job.id(), proposals);
                var applied = changes.apply(job.projectId(), changeId, AiGenerationService.changeIds(changes.get(job.projectId(), changeId)));
                var values = json.map(json.write(applied)); String assetId = Values.objects(values.get("assets")).getFirst().get("id").toString();
                briefs.published(job, id, assetId);
                conversations.recordAssistant(conversation, job.id(), draft.raw(), "APPLIED", null, null, proposals, Map.of("valid", true), modelStamp, prompts.version("test_summary_report"));
                Map<String, Object> result = new LinkedHashMap<>(applied); result.put("occurrenceId", id); result.put("assetId", assetId); result.put("changeSetId", changeId);
                return result;
            });
        } catch (RuntimeException error) {
            boolean interrupted = Thread.interrupted();
            try {
                briefs.failed(job.id(), error);
                conversations.recordAssistant(conversation, job.id(), raw, "FAILED", null, null, Map.of("raw", raw), Map.of("message", error instanceof Problem ? error.getMessage() : "晨报生成未完成"), stamp, prompts.version("test_summary_report"));
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw error;
        }
    }
    private static void validate(List<AiChangeSetService.Proposal> changes) {
        if (changes.size() != 1) throw invalid();
        var proposal = changes.getFirst();
        if (proposal.targetType() != AssetType.QUALITY_BRIEF || !"ADD".equals(proposal.operation()) || proposal.targetId() != null || proposal.parentId() != null || proposal.baseVersion() != null
                || proposal.name() == null || proposal.name().isBlank() || proposal.data() == null || !(proposal.data().get("content") instanceof String content) || content.isBlank()) throw invalid();
    }
    private static Problem invalid() { return new Problem(422, "MORNING_BRIEF_OUTPUT_INVALID", "晨报必须且只能新增一条含非空正文的质量简报，不能修改已有资产"); }
}
