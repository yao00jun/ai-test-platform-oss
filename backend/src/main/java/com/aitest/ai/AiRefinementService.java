package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.analysis.GenerationEvidenceService;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.job.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

@Service
public class AiRefinementService implements JobHandler {
    private final AssetService assets;
    private final JobService jobs;
    private final ModelSettingsService settings;
    private final ModelInvocationService gateway;
    private final AiConversationService conversations;
    private final AiChangeSetService changes;
    private final PromptCatalog prompts;
    private final JsonCodec json;
    private final GenerationEvidenceService evidence;
    private final TransactionTemplate adoptionTransactions;
    public AiRefinementService(AssetService assets, JobService jobs, ModelSettingsService settings, ModelInvocationService gateway,
                               AiConversationService conversations, AiChangeSetService changes, PromptCatalog prompts, JsonCodec json, GenerationEvidenceService evidence, TransactionTemplate transactions) {
        this.assets = assets; this.jobs = jobs; this.settings = settings; this.gateway = gateway; this.conversations = conversations; this.changes = changes; this.prompts = prompts; this.json = json; this.evidence = evidence;
        // The job checkpoint reads before the project lock. Final dependency checks must
        // see manual consumers committed while this short adoption transaction waited.
        this.adoptionTransactions = new TransactionTemplate(transactions.getTransactionManager());
        this.adoptionTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public String kind() { return "AI_REFINE"; }
    public Submission submit(RefineRequest request) {
        if (request.targetType() == null || request.projectId() == null || request.targetId() == null) throw Problem.invalid("需要项目、目标类型和目标 ID");
        if (request.feedback() == null || request.feedback().isBlank() || request.feedback().length() > 16000) throw Problem.invalid("请输入 1–16000 字符的修改意见");
        String mode = request.applyMode() == null ? "REPLACE_ON_SUCCESS" : request.applyMode();
        if (!Set.of("REPLACE_ON_SUCCESS", "PREVIEW").contains(mode)) throw Problem.invalid("applyMode 无效");
        String conversation = conversations.ensure(request.conversationId(), request.projectId(), "LOCAL", request.targetId(), request.targetType().name());
        RefineRequest normalized = new RefineRequest(request.projectId(), request.targetType(), request.targetId(), request.baseVersion(), conversation, request.feedback(), request.targetFields(), mode, request.idempotencyKey());
        Job job = jobs.submit(request.projectId(), kind(), request.idempotencyKey(), json.map(json.write(normalized)));
        return new Submission(job.id(), conversation);
    }
    @Override public Map<String, Object> execute(JobContext job, Map<String, Object> input) {
        RefineRequest request = json.convert(input, RefineRequest.class);
        Asset current = assets.getInternal(job.projectId(), request.targetId());
        if (current.type() != request.targetType()) throw Problem.invalid("目标类型不匹配");
        AssetService.requireVersion(current, request.baseVersion());
        Set<String> allowed = allowedFields(current.type(), request.targetFields());
        List<Map<String, Object>> history = conversations.messages(job.projectId(), request.conversationId());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("target", assets.get(job.projectId(), current.id()));
        context.put("allowedFields", allowed);
        context.put("history", ModelContextBudget.history(history));
        context.put("feedback", request.feedback());
        context.put("sourceEvidence", evidence.contexts(job.projectId(), List.of(current), null));
        if (current.type() == AssetType.DASHBOARD) context.put("cardSchema", com.aitest.workbench.DashboardCardSchema.contract());
        if (current.parentId() != null) context.put("parent", assets.get(job.projectId(), current.parentId()));
        if (!current.type().childTypes().isEmpty()) context.put("childrenReadOnly", assets.children(job.projectId(), current.id()));
        conversations.recordUser(request.conversationId(), job.id(), request.feedback(), current.version(), context);
        String raw = "", modelVersion = "unconfigured"; Object candidate = Map.of();
        try {
            ModelSettings model = settings.current();
            modelVersion = model.modelName() + ":" + model.version();
            job.progress(10, "正在根据当前资产和历史反馈生成单项修改");
            raw = gateway.complete(model, job, "local_refinement", "INITIAL", prompts.load("local_refinement"), json.write(context), token -> job.event("token", Map.of("token", token)));
            candidate = parseCandidate(raw, allowed);
            Map<String, Object> proposal = castMap(candidate);
            String name = proposal.get("name") instanceof String s ? s : null;
            Map<String, Object> patch = proposal.get("data") == null ? Map.of() : castMap(proposal.get("data"));
            Map<String, Object> data = evidence.ground(job.projectId(), null, List.of(new AiChangeSetService.Proposal("MODIFY", current.type(), current.id(), current.parentId(), null, current.version(), name, patch))).getFirst().data();
            Asset prepared = assets.prepare(current, name, data, null);
            assets.validateCandidate(current, prepared); evidence.validate(current, prepared, null);
            job.progress(80, "候选内容已校验，正在检查版本和依赖");
            Object acceptedCandidate = candidate;
            String acceptedRaw = raw;
            return adoptionTransactions.execute(tx -> job.completeAtomically(() -> {
                Asset latest = assets.getInternal(job.projectId(), current.id());
                AssetService.requireVersion(latest, current.version());
                evidence.validate(latest, assets.prepare(latest, name, data, null), null);
                if (request.applyMode().equals("PREVIEW")) {
                    String changeSetId = changes.create(job.projectId(), request.conversationId(), job.id(), List.of(new AiChangeSetService.Proposal("MODIFY", current.type(), current.id(), current.parentId(), null, current.version(), name, data)));
                    conversations.recordAssistant(request.conversationId(), job.id(), acceptedRaw, "PREVIEW", current.version(), null, acceptedCandidate, Map.of("valid", true), model.modelName() + ":" + model.version(), prompts.version("local_refinement"));
                    return Map.of("changeSetId", changeSetId, "targetId", current.id(), "status", "PREVIEW");
                }
                Asset updated = assets.update(job.projectId(), current.id(), current.version(), name, data, null, "AI");
                conversations.recordAssistant(request.conversationId(), job.id(), acceptedRaw, "APPLIED", current.version(), updated.version(), acceptedCandidate, Map.of("valid", true), model.modelName() + ":" + model.version(), prompts.version("local_refinement"));
                job.jobs().event(job.id(), job.projectId(), "asset", Map.of("asset", updated));
                return Map.of("asset", updated, "targetId", current.id(), "status", "APPLIED");
            }));
        } catch (RuntimeException e) {
            boolean interrupted = Thread.interrupted();
            String status = e instanceof CancellationException ? "CANCELLED" : e instanceof Problem p && p.status() == 409 ? "CONFLICT" : "FAILED";
            String message = e instanceof Problem ? e.getMessage() : status.equals("CANCELLED") ? "任务已取消" : "候选生成或校验失败";
            try {
                conversations.recordAssistant(request.conversationId(), job.id(), raw, status, current.version(), null, raw.isEmpty() ? candidate : Map.of("raw", raw), Map.of("valid", false, "message", message), modelVersion, prompts.version("local_refinement"));
            } catch (RuntimeException persistenceFailure) { e.addSuppressed(persistenceFailure); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw e;
        }
    }
    public static Set<String> allowedFields(AssetType type, List<String> requested) {
        Set<String> allowed = new LinkedHashSet<>(); allowed.add("name");
        type.fields().stream().filter(f -> !f.key().endsWith("Id") && !f.kind().equals("password") && !Set.of("fingerprint", "sourcePath", "sections", "schema", "metrics", "scheduleEnabled", "enabled", "generationEvidence").contains(f.key())).map(FieldDefinition::key).forEach(allowed::add);
        if (requested != null && !requested.isEmpty()) {
            if (!allowed.containsAll(requested)) throw Problem.invalid("请求包含不允许 AI 调优的字段");
            allowed.retainAll(requested);
        }
        return allowed;
    }
    private Map<String, Object> parseCandidate(String raw, Set<String> allowed) {
        String value = raw.strip();
        if (value.startsWith("```")) {
            var matcher = java.util.regex.Pattern.compile("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$").matcher(value);
            if (!matcher.matches()) throw Problem.invalid("模型 JSON 输出不完整"); value = matcher.group(1);
        }
        Map<String, Object> result = json.map(value);
        if (result.isEmpty() || !Set.of("name", "data").containsAll(result.keySet())) throw Problem.invalid("局部调优仅接受单个目标的 name/data，不允许修改 ID、父级、排序或其他资产");
        if (result.containsKey("name") && (!allowed.contains("name") || !(result.get("name") instanceof String))) throw Problem.invalid("不允许修改名称或名称类型无效");
        if (result.containsKey("data")) {
            Map<String, Object> data = castMap(result.get("data"));
            if (!allowed.containsAll(data.keySet()) || data.containsKey("name")) throw Problem.invalid("候选修改超出允许字段范围");
        }
        if (!result.containsKey("name") && (!result.containsKey("data") || castMap(result.get("data")).isEmpty())) throw Problem.invalid("候选没有可应用的修改");
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String))) throw Problem.invalid("候选 data 必须是 JSON 对象");
        return (Map<String, Object>) map;
    }
    public record RefineRequest(String projectId, AssetType targetType, String targetId, String baseVersion,
                                String conversationId, String feedback, List<String> targetFields, String applyMode, String idempotencyKey) { }
    public record Submission(String jobId, String conversationId) { }
}
