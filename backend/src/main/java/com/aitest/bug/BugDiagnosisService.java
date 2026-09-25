package com.aitest.bug;

import com.aitest.ai.*;
import com.aitest.ai.pipeline.PipelineRepository;
import com.aitest.common.*;
import com.aitest.execution.ExecutionCoordinator;
import com.aitest.job.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public final class BugDiagnosisService implements JobHandler {
    private static final Set<String> FIELDS = Set.of("title", "severity", "reproduceSteps", "expectedResult", "actualResult", "rootCauseAnalysis", "fixSuggestion");
    private final FailureEvidenceReader evidence;
    private final BugOccurrenceService occurrences;
    private final ModelSettingsService settings;
    private final ModelInvocationService gateway;
    private final PromptCatalog prompts;
    private final JobService jobs;
    private final JsonCodec json;
    private final AiConversationService conversations;
    private final PipelineRepository pipelines;
    private final com.aitest.analysis.rca.FailureDiagnosisAgent code;
    private final TransactionTemplate recordTransactions;
    public BugDiagnosisService(FailureEvidenceReader evidence, BugOccurrenceService occurrences, ModelSettingsService settings, ModelInvocationService gateway, PromptCatalog prompts, JobService jobs, JsonCodec json, AiConversationService conversations, PipelineRepository pipelines, com.aitest.analysis.rca.FailureDiagnosisAgent code, TransactionTemplate transactions) {
        this.evidence = evidence; this.occurrences = occurrences; this.settings = settings; this.gateway = gateway; this.prompts = prompts; this.jobs = jobs; this.json = json; this.conversations = conversations; this.pipelines = pipelines; this.code = code;
        // Waiting for the project lock must reveal newly committed occurrences and human deletion.
        // Only the short occurrence/history transaction needs statement-current visibility.
        this.recordTransactions = new TransactionTemplate(transactions.getTransactionManager());
        this.recordTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public String kind() { return "BUG_DIAGNOSE"; }
    public Map<String, Object> submit(String project, String runId, String idempotencyKey) {
        return submitForPipeline(project, runId, idempotencyKey, null);
    }
    public Map<String, Object> submitForPipeline(String project, String runId, String idempotencyKey, String pipelineId) {
        evidence.readWithoutSource(project, runId);
        String conversation = conversations.ensure(null, project, "DIAGNOSE", runId, "BUG");
        Map<String, Object> input = new LinkedHashMap<>(Map.of("runId", runId, "conversationId", conversation));
        if (pipelineId != null) input.put("pipelineId", pipelineId);
        return Map.of("jobId", jobs.submit(project, kind(), idempotencyKey, input).id(), "conversationId", conversation);
    }
    /** The run-completion event occurs inside its commit, so the queued diagnostic is durable too. */
    @EventListener public void completed(ExecutionCoordinator.RunCompleted completed) {
        if (completed.diagnose() && !evidence.readWithoutSource(completed.projectId(), completed.runId()).isEmpty()) submit(completed.projectId(), completed.runId(), "auto:" + completed.runId());
    }
    @Override public Map<String, Object> execute(JobContext job, Map<String, Object> input) {
        List<FailureEvidenceReader.Failure> failures = evidence.read(job.projectId(), input.get("runId").toString());
        String conversation = input.get("conversationId").toString();
        List<Map<String, Object>> results = new ArrayList<>();
        for (var failure : failures) {
            job.checkpoint();
            try {
                var existing = occurrences.existing(job.projectId(), failure);
                if (existing != null) results.add(existing);
                else {
                    conversations.recordUser(conversation, job.id(), "根据实际失败记录诊断：" + failure.caseName(), null, failure.evidence());
                    Draft draft = occurrences.known(job.projectId(), failure.fingerprint())
                            ? new Draft("记录新的失败证据，保留已有人工结论和状态", Map.of("source", "EXISTING_DEFECT"), "existing")
                            : generate(job, failure, conversation);
                    Map<String, Object> recorded;
                    try {
                        recorded = recordTransactions.execute(tx -> job.atomic(() -> {
                            // Keep job -> project ordering and hold the project lock through the write.
                            // A durable pipeline cancellation or replacement fences even this in-flight job.
                            if (input.get("pipelineId") != null) pipelines.requireActive(job.projectId(), input.get("pipelineId").toString(), job.id());
                            var value = occurrences.record(job.projectId(), job.id(), failure, draft.candidate(), draft.stamp(), prompts.version("bug_auto_creation"));
                            conversations.recordAssistant(conversation, job.id(), draft.raw(), "APPLIED", null, null, draft.candidate(), value, draft.stamp(), prompts.version("bug_auto_creation")); return value;
                        }));
                    } catch (RuntimeException failed) { failed(conversation, job, draft.raw(), draft.stamp(), failed); throw failed; }
                    results.add(recorded); job.event("diagnosis", recorded);
                }
            } catch (java.util.concurrent.CancellationException | JobService.LeaseLostException stopped) {
                throw stopped;
            } catch (RuntimeException failed) {
                results.add(Map.of("failureKey", failure.key(), "caseId", failure.caseId(), "caseName", failure.caseName(),
                        "status", "FAILED", "error", failed instanceof Problem problem ? problem.getMessage() : "诊断此失败项时发生异常"));
            }
            job.progress(Math.min(98, results.size() * 98 / failures.size()), "已诊断 " + results.size() + "/" + failures.size() + " 个失败检查");
        }
        return job.completeAtomically(() -> Map.of("runId", input.get("runId"), "items", results, "failureCount", failures.size()));
    }
    private record Draft(String raw, Map<String, Object> candidate, String stamp) { }
    private Draft generate(JobContext job, FailureEvidenceReader.Failure failure, String conversation) {
        String context = json.write(Map.of("evidence", failure.evidence(), "instruction", "仅根据保存的失败证据诊断。根因是待验证推测，不修改实际结果或执行状态。"));
        String raw = "", stamp = "unconfigured";
        try {
        ModelSettings model = settings.current(); stamp = model.modelName() + ":" + model.version();
        raw = gateway.complete(model, job, "bug_auto_creation", "INITIAL", prompts.load("bug_auto_creation"), context, token -> job.event("token", Map.of("content", token)));
        for (int attempt = 0; ; attempt++) {
            try {
                String text = raw.strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                Map<String, Object> candidate = json.map(text);
                var fields = new HashSet<>(candidate.keySet()); fields.remove("codeDiagnosis");
                if (!fields.equals(FIELDS)) throw Problem.invalid("缺陷诊断字段不完整或包含未知字段");
                if (candidate.containsKey("codeDiagnosis")) {
                    try {
                        var checked = code.validate(candidate.get("codeDiagnosis"), com.aitest.execution.Values.map(failure.evidence().get("sourceEvidence")));
                        candidate.put("codeDiagnosis", checked);
                        if (candidate.get("rootCauseAnalysis") instanceof String rootText && rootText.isBlank() && checked.get("root_cause") instanceof String root && !root.isBlank()) candidate.put("rootCauseAnalysis", root);
                    } catch (RuntimeException invalidCodeDiagnosis) {
                        candidate.put("codeDiagnosis", Map.of());
                    }
                }
                for (String key : FIELDS) if (!(candidate.get(key) instanceof String value) || value.isBlank() || value.length() > (key.equals("title") ? 255 : 20000)) throw Problem.invalid("诊断字段为空、过长或不是文本：" + key);
                if (!Set.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR").contains(candidate.get("severity"))) throw Problem.invalid("诊断严重度必须为单个合法值");
                return new Draft(raw, candidate, stamp);
            } catch (Problem invalid) {
                if (attempt == 1) throw invalid;
                // A structural repair needs the draft and the error, not the full evidence again (sent once already, as an object this time).
                Map<String, Object> facts = new LinkedHashMap<>();
                for (String key : List.of("caseName", "stepName", "engine", "failedAssertions")) if (failure.evidence().get(key) != null) facts.put(key, failure.evidence().get(key));
                raw = gateway.complete(model, job, "bug_auto_creation", "FORMAT_REPAIR", prompts.load("bug_auto_creation"), json.write(Map.of("failure", facts, "invalidOutput", raw, "validationError", invalid.getMessage(), "instruction", "仅修复输出结构和不合规字段，不改变诊断结论")), token -> job.event("token", Map.of("content", token)));
            }
        }
        } catch (RuntimeException failed) { failed(conversation, job, raw, stamp, failed); throw failed; }
    }
    private void failed(String conversation, JobContext job, String raw, String stamp, RuntimeException error) {
        boolean interrupted = Thread.interrupted();
        try { conversations.recordAssistant(conversation, job.id(), raw, "FAILED", null, null, Map.of("raw", raw), Map.of("message", error instanceof Problem ? error.getMessage() : "诊断已停止"), stamp, prompts.version("bug_auto_creation")); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
