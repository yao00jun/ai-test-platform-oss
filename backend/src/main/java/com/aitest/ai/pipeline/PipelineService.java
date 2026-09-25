package com.aitest.ai.pipeline;

import com.aitest.ai.AiConversationService;
import com.aitest.analysis.SourceSnapshotRepository;
import com.aitest.asset.*;
import com.aitest.bug.BugDiagnosisService;
import com.aitest.common.*;
import com.aitest.execution.*;
import com.aitest.job.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.aitest.ai.pipeline.PipelineRepository.now;

/** Stage jobs chain through committed rows. Waiting for runs never occupies a model-worker slot. */
@Service
public class PipelineService implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);
    private final PipelineRepository repository;
    private final PipelineStageGenerator generator;
    private final AssetService assets;
    private final AiConversationService conversations;
    private final JobService jobs;
    private final JsonCodec json;
    private final ExecutionCoordinator execution;
    private final RunRepository runs;
    private final BugDiagnosisService bugs;
    private final ScenarioDependencyValidator dependencies;
    private final TransactionTemplate transactions;
    private final PipelinePlanService plans;
    private final SourceSnapshotRepository sources;
    public PipelineService(PipelineRepository repository, PipelineStageGenerator generator, AssetService assets, AiConversationService conversations, JobService jobs, JsonCodec json, ExecutionCoordinator execution, RunRepository runs, BugDiagnosisService bugs, ScenarioDependencyValidator dependencies, TransactionTemplate transactions, PipelinePlanService plans, SourceSnapshotRepository sources) {
        this.repository = repository; this.generator = generator; this.assets = assets; this.conversations = conversations; this.jobs = jobs; this.json = json; this.execution = execution; this.runs = runs; this.bugs = bugs; this.dependencies = dependencies; this.transactions = transactions; this.plans = plans; this.sources = sources;
    }
    public String kind() { return "AI_PIPELINE_STAGE"; }
    public record Request(String projectId, List<String> requirementIds, List<String> apiDefinitionIds, String environmentId, List<String> databaseSourceIds, List<String> uiEvidenceIds, Boolean execute, String idempotencyKey, String sourceSnapshotId) {
        public Request(String projectId, List<String> requirementIds, List<String> apiDefinitionIds, String environmentId, List<String> databaseSourceIds, List<String> uiEvidenceIds, Boolean execute, String idempotencyKey) { this(projectId, requirementIds, apiDefinitionIds, environmentId, databaseSourceIds, uiEvidenceIds, execute, idempotencyKey, null); }
    }
    public record Resume(String projectId, String stage, String idempotencyKey, String environmentId,
                         List<String> apiDefinitionIds, List<String> databaseSourceIds,
                         List<String> uiEvidenceIds, Boolean execute, String sourceSnapshotId) {
        public Resume(String projectId, String stage, String idempotencyKey, String environmentId, List<String> apiDefinitionIds, List<String> databaseSourceIds, List<String> uiEvidenceIds, Boolean execute) { this(projectId, stage, idempotencyKey, environmentId, apiDefinitionIds, databaseSourceIds, uiEvidenceIds, execute, null); }
    }

    @Transactional
    public Map<String, Object> submit(Request input) {
        if (input.idempotencyKey() == null || input.idempotencyKey().isBlank() || input.idempotencyKey().length() > 100) throw Problem.invalid("流水线需要 1–100 字符的幂等键");
        repository.lock(input.projectId());
        String id = UUID.nameUUIDFromBytes((input.projectId() + ":pipeline:" + input.idempotencyKey()).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        var prior = repository.jdbc().queryForList("SELECT id,config_snapshot FROM ai_pipeline_record WHERE id=? AND project_id=?", id, input.projectId());
        if (!prior.isEmpty()) {
            var stored = json.map(prior.getFirst().get("config_snapshot").toString());
            if (!sameRequest(stored.get("request"), input)) throw Problem.conflict("此幂等键已用于其他流水线输入");
            return submission(repository.get(input.projectId(), id));
        }
        String sourceSnapshot = Objects.toString(input.sourceSnapshotId(), "");
        if (!sourceSnapshot.isBlank()) sources.binding(input.projectId(), sourceSnapshot);
        List<String> requirements = checked(input.projectId(), input.requirementIds(), Set.of(AssetType.REQUIREMENT));
        if (requirements.isEmpty()) throw Problem.invalid("请提供至少一份 BA 需求文档");
        List<String> apis = checked(input.projectId(), input.apiDefinitionIds(), Set.of(AssetType.API_DEFINITION));
        String environment = input.environmentId();
        if (environment == null || environment.isBlank()) {
            var environments = assets.list(input.projectId(), AssetType.ENVIRONMENT, null, "", 0, 2);
            environment = environments.total() == 1 ? environments.items().getFirst().id() : "";
        } else checked(input.projectId(), List.of(environment), Set.of(AssetType.ENVIRONMENT));
        List<String> sources = input.databaseSourceIds();
        if (sources == null) {
            String selected = environment;
            sources = assets.all(input.projectId()).stream().filter(asset -> asset.type() == AssetType.DATABASE_SOURCE && (selected.isBlank() || Values.text(asset.data(), "environmentId", "").isBlank() || selected.equals(asset.data().get("environmentId")))).map(Asset::id).toList();
        }
        sources = checked(input.projectId(), sources, Set.of(AssetType.DATABASE_SOURCE));
        List<String> recordings = checked(input.projectId(), input.uiEvidenceIds(), Set.of(AssetType.UI_SCENARIO, AssetType.UI_STEP));
        boolean autoRun = input.execute() != null ? input.execute() : !environment.isBlank() && Values.bool(assets.get(input.projectId(), environment).data(), "autoRunGenerated", false);
        Map<String, Object> config = new LinkedHashMap<>(); config.put("requirementIds", requirements); config.put("apiDefinitionIds", apis); config.put("environmentId", environment); config.put("databaseSourceIds", sources); config.put("uiEvidenceIds", recordings); config.put("execute", autoRun); config.put("request", json.tree(json.write(input)));
        config.put("sourceSnapshotId", sourceSnapshot);
        String conversation = conversations.ensure(null, input.projectId(), "PIPELINE", id, null);
        repository.jdbc().update("INSERT INTO ai_pipeline_record(id,project_id,job_id,conversation_id,requirement_snapshot,api_snapshot,status,current_stage,asset_ids,config_snapshot,created_at,updated_at) VALUES(?,?,?, ?,?,?,'QUEUED','S1','[]',?,?,?)", id, input.projectId(), "", conversation, json.write(requirements.stream().map(asset -> assets.get(input.projectId(), asset)).toList()), json.write(apis.stream().map(asset -> assets.get(input.projectId(), asset)).toList()), json.write(config), now(), now());
        enqueue(input.projectId(), id, "S1", "start"); return submission(repository.get(input.projectId(), id));
    }
    public Map<String, Object> get(String project, String id) { return repository.get(project, id); }
    public List<Map<String, Object>> list(String project) { return repository.list(project); }
    @Transactional
    public Map<String, Object> resume(String id, Resume input) {
        if (input.idempotencyKey() == null || input.idempotencyKey().isBlank() || input.idempotencyKey().length() > 80) throw Problem.invalid("重试需要 1–80 字符的幂等键");
        repository.lock(input.projectId()); var pipeline = repository.get(input.projectId(), id);
        String discriminator = "retry:" + input.idempotencyKey();
        Object resumeRequest = json.tree(json.write(input));
        var existing = repository.jdbc().queryForList("SELECT id,input FROM job_task WHERE project_id=? AND kind=? AND idempotency_key=?", input.projectId(), kind(), "pipeline:" + id + ":" + discriminator);
        if (!existing.isEmpty()) {
            var jobInput = json.map(existing.getFirst().get("input").toString());
            if (!sameRequest(jobInput.get("resumeRequest"), input)) throw new Problem(409, "IDEMPOTENCY_CONFLICT", "此重试幂等键已用于其他阶段或配置");
            return submission(pipeline, existing.getFirst().get("id").toString());
        }
        if (Set.of("RUNNING", "QUEUED").contains(pipeline.get("status"))) throw Problem.conflict("流水线仍在运行");
        String stage = input.stage();
        if (stage == null || stage.isBlank()) stage = Values.objects(pipeline.get("steps")).stream().filter(step -> !Set.of("COMPLETED", "SKIPPED").contains(step.get("status"))).map(step -> step.get("stage").toString()).findFirst().orElseThrow(() -> Problem.invalid("已完成阶段请使用全局反馈优化，不重复生成资产"));
        String chosen = stage;
        var previous = Values.objects(pipeline.get("steps")).stream().filter(step -> chosen.equals(step.get("stage"))).findFirst().orElseThrow(() -> Problem.invalid("没有可重试的阶段"));
        if (Set.of("COMPLETED", "SKIPPED").contains(previous.get("status"))) throw Problem.invalid("已完成阶段请使用全局反馈，重试仅处理未完成阶段");
        updateResumeConfig(id, pipeline, input);
        Job job = enqueue(input.projectId(), id, stage, discriminator, Map.of("resumeRequest", resumeRequest));
        return submission(repository.get(input.projectId(), id), job.id());
    }
    private void updateResumeConfig(String id, Map<String, Object> pipeline, Resume input) {
        Map<String, Object> config = new LinkedHashMap<>(Values.map(pipeline.get("config")));
        if (input.sourceSnapshotId() != null) {
            String previous = Values.text(config, "sourceSnapshotId", "");
            if (!previous.isBlank() && !previous.equals(input.sourceSnapshotId())) throw new Problem(409, "PIPELINE_SOURCE_CHANGED", "流水线已绑定固定源码；其他版本请创建新流水线，已有资产和历史保持不变");
            if (!input.sourceSnapshotId().isBlank()) sources.binding(input.projectId(), input.sourceSnapshotId());
            config.put("sourceSnapshotId", input.sourceSnapshotId());
        }
        if (input.environmentId() != null) {
            if (!input.environmentId().isBlank()) checked(input.projectId(), List.of(input.environmentId()), Set.of(AssetType.ENVIRONMENT));
            if (pipeline.get("runId") != null && !Objects.equals(config.get("environmentId"), input.environmentId())) throw Problem.conflict("已有运行保持其环境快照；请在测试计划中另行发起新环境运行");
            config.put("environmentId", input.environmentId());
        }
        if (input.apiDefinitionIds() != null) config.put("apiDefinitionIds", checked(input.projectId(), input.apiDefinitionIds(), Set.of(AssetType.API_DEFINITION)));
        if (input.databaseSourceIds() != null) config.put("databaseSourceIds", checked(input.projectId(), input.databaseSourceIds(), Set.of(AssetType.DATABASE_SOURCE)));
        if (input.uiEvidenceIds() != null) config.put("uiEvidenceIds", checked(input.projectId(), input.uiEvidenceIds(), Set.of(AssetType.UI_SCENARIO, AssetType.UI_STEP)));
        if (input.execute() != null) config.put("execute", input.execute());
        repository.jdbc().update("UPDATE ai_pipeline_record SET config_snapshot=?,updated_at=? WHERE id=?", json.write(config), now(), id);
    }
    private boolean sameRequest(Object previous, Object input) {
        Map<String, Object> old = new LinkedHashMap<>(Values.map(previous)), current = json.map(json.write(input));
        if (current.get("sourceSnapshotId") == null && !old.containsKey("sourceSnapshotId")) current.remove("sourceSnapshotId");
        return old.equals(current);
    }
    public Map<String, Object> cancel(String project, String id) {
        // Publish the pipeline stop under its project lock, then release it before taking
        // the job lock. Stage commits use job -> project and re-check this durable state.
        String activeJob = transactions.execute(tx -> {
            repository.lock(project); var pipeline = repository.get(project, id);
            if (!Set.of("RUNNING", "QUEUED").contains(pipeline.get("status"))) return null;
            repository.jdbc().update("UPDATE ai_pipeline_record SET status='CANCELLED',error='用户已取消',revision=revision+1,updated_at=? WHERE id=?", now(), id);
            repository.jdbc().update("UPDATE ai_pipeline_step SET status='CANCELLED',error='用户已取消',completed_at=? WHERE pipeline_id=? AND status IN ('RUNNING','QUEUED','WAITING_RUN','WAITING_DIAGNOSIS')", now(), id);
            return pipeline.get("jobId").toString();
        });
        if (activeJob != null) jobs.cancel(project, activeJob);
        return repository.get(project, id);
    }
    @Override public Map<String, Object> execute(JobContext job, Map<String, Object> input) {
        String id = input.get("pipelineId").toString(), stage = input.get("stage").toString();
        Map<String, Object> pipeline = repository.get(job.projectId(), id);
        if (!job.id().equals(pipeline.get("jobId"))) throw Problem.conflict("阶段已被其他尝试替代");
        job.atomic(() -> { repository.requireActive(job.projectId(), id, job.id()); repository.jdbc().update("UPDATE ai_pipeline_step SET status='RUNNING',input_snapshot=?,started_at=? WHERE pipeline_id=? AND job_id=?", json.write(Map.of("config", pipeline.get("config"), "assetIds", pipeline.get("assetIds"))), now(), id, job.id()); return true; });
        try {
            if (stage.equals("S6")) return executeTests(job, pipeline, input);
            Map<String, Object> output = generator.run(stage, job, pipeline);
            return job.completeAtomically(() -> { complete(job, id, stage, "COMPLETED", output, null); return Map.of("pipelineId", id, "stage", stage, "output", output); });
        } catch (Problem error) {
            if (error.code().equals("GENERATION_BLOCKED") && !Set.of("S1", "S2").contains(stage)) return job.completeAtomically(() -> { complete(job, id, stage, "BLOCKED", Map.of("reason", error.getMessage()), error.getMessage()); return Map.of("pipelineId", id, "stage", stage, "status", "BLOCKED", "reason", error.getMessage()); });
            fail(job, id, error); throw error;
        } catch (RuntimeException error) { fail(job, id, error); throw error; }
    }
    private Map<String, Object> executeTests(JobContext job, Map<String, Object> pipeline, Map<String, Object> input) {
        String id = pipeline.get("id").toString();
        var config = Values.map(pipeline.get("config")); String environment = Values.text(config, "environmentId", "");
        return job.completeAtomically(() -> {
            repository.requireActive(job.projectId(), id, job.id());
            Map<String, Object> current = repository.get(job.projectId(), id);
            Map<String, Object> output = latestExecutionOutput(current);
            var reconciled = plans.reconcile(job.projectId(), current, output, environment);
            appendAssets(id, current, reconciled.addedIds());
            if (reconciled.blockedReason() != null) return executionGap(job, id, output, "PLAN_UPDATE_REQUIRED", reconciled.blockedReason());
            if (Values.bool(config, "execute", false) && environment.isBlank())
                return executionGap(job, id, output, "ENVIRONMENT_REQUIRED", "缺少执行环境；生成资产已纳入计划，配置后可重试执行阶段");
            String planId = reconciled.plan().id();
            String runId = Values.text(output, "runId", "");
            boolean continuation = !reconciled.pending().isEmpty();
            if (continuation && !Values.bool(Values.map(input.get("resumeRequest")), "execute", false))
                return executionGap(job, id, output, "PENDING_NEW_ASSETS", "新生成资产已纳入计划，但不在已有运行快照内；请显式重试 S6 并选择执行，以单独运行待执行项");
            // Generated cases include POST/PUT/DELETE requests; against production they only run after a person reviews them.
            if (Values.bool(config, "execute", false) && "PRODUCTION".equals(Values.text(assets.get(job.projectId(), environment).data(), "purpose", ""))) {
                output.put("execution", "NOT_REQUESTED"); output.put("requiresExecutionResume", false);
                output.put("reason", "所选环境是生产环境，AI 生成的测试不会自动执行（其中可能有新增、修改、删除类请求）；测试计划已生成，请检查用例后在测试计划中手动发起执行");
                complete(job, id, "S6", "COMPLETED", output, null);
                return Map.of("pipelineId", id, "stage", "S6", "output", output);
            }
            if (runId.isBlank() || continuation) {
                var checked = dependencies.validate(job.projectId(), planId, environment, null);
                if (!Boolean.TRUE.equals(checked.get("valid"))) {
                    output.put("validation", checked);
                    String reason = Values.objects(checked.get("errors")).stream().map(error -> Values.text(error, "message", "执行条件未满足")).distinct().limit(3).collect(java.util.stream.Collectors.joining("；"));
                    if (!Values.bool(config, "execute", false)) {
                        output.put("execution", "NOT_REQUESTED"); output.put("requiresExecutionResume", false); output.put("reason", reason);
                        complete(job, id, "S6", "BLOCKED", output, reason);
                        return Map.of("pipelineId", id, "stage", "S6", "status", "BLOCKED", "output", output);
                    }
                    return executionGap(job, id, output, "DEPENDENCY_UPDATE_REQUIRED", reason + "。生成资产已保留，补充后可重试执行阶段");
                }
                output.remove("validation");
            }
            if (!Values.bool(config, "execute", false)) {
                output.put("execution", "NOT_REQUESTED"); output.put("requiresExecutionResume", false); output.remove("reason"); complete(job, id, "S6", "COMPLETED", output, null);
                return Map.of("pipelineId", id, "stage", "S6", "output", output);
            }
            String runJob;
            if (runId.isBlank() || continuation) {
                String executionPlan = planId;
                if (continuation) {
                    var next = plans.continuation(job.projectId(), reconciled); executionPlan = next.planId();
                    appendAssets(id, repository.get(job.projectId(), id), next.addedIds());
                }
                String key = continuation ? "pipeline:" + id + ":continuation:" + job.id() : "pipeline:" + id;
                var submitted = execution.submit(job.projectId(), new ExecutionCoordinator.Request(executionPlan, environment, null, key));
                runId = submitted.runId(); runJob = submitted.jobId(); output.put("runId", runId); output.put("runJobId", runJob);
                output.put("runIds", plans.runIds(output)); output.put("executionPlanId", executionPlan);
                output.remove("diagnosisJobId"); output.remove("diagnosis"); output.remove("runSummary");
            } else runJob = Values.text(output, "runJobId", "");
            output.put("execution", "RUNNING"); output.put("requiresExecutionResume", false); output.remove("reason");
            repository.jdbc().update("UPDATE ai_pipeline_step SET status='WAITING_RUN',output_snapshot=? WHERE pipeline_id=? AND job_id=?", json.write(output), id, job.id());
            repository.jdbc().update("UPDATE ai_pipeline_record SET run_id=?,job_id=?,status='RUNNING',progress=90,revision=revision+1,updated_at=? WHERE id=?", runId, runJob, now(), id);
            return Map.of("pipelineId", id, "stage", "S6", "status", "WAITING_RUN", "runId", runId, "runJobId", runJob, "output", output);
        });
    }
    private Map<String, Object> executionGap(JobContext job, String id, Map<String, Object> output, String executionState, String reason) {
        output.put("execution", executionState); output.put("requiresExecutionResume", true); output.put("reason", reason);
        complete(job, id, "S6", "BLOCKED", output, reason);
        return Map.of("pipelineId", id, "stage", "S6", "status", "BLOCKED", "output", output);
    }
    private void complete(JobContext job, String id, String stage, String status, Map<String, Object> output, String error) {
        repository.requireActive(job.projectId(), id, job.id());
        repository.jdbc().update("UPDATE ai_pipeline_step SET status=?,output_snapshot=?,error=?,completed_at=? WHERE pipeline_id=? AND job_id=?", status, json.write(output), error, now(), id, job.id());
        next(job.projectId(), id, Integer.parseInt(stage.substring(1)) + 1);
    }
    private Job enqueue(String project, String id, String stage, String discriminator) { return enqueue(project, id, stage, discriminator, Map.of()); }
    private Job enqueue(String project, String id, String stage, String discriminator, Map<String, Object> extra) {
        int attempt = repository.jdbc().queryForObject("SELECT COALESCE(MAX(attempt),0)+1 FROM ai_pipeline_step WHERE pipeline_id=? AND stage=?", Integer.class, id, stage);
        Map<String, Object> input = new LinkedHashMap<>(extra); input.put("pipelineId", id); input.put("stage", stage);
        String key = "pipeline:" + id + ":" + (discriminator.startsWith("retry:") ? discriminator : stage + ":" + discriminator);
        var job = jobs.submit(project, kind(), key, input);
        if (repository.jdbc().queryForObject("SELECT COUNT(*) FROM ai_pipeline_step WHERE job_id=?", Long.class, job.id()) == 0) repository.jdbc().update("INSERT INTO ai_pipeline_step(id,pipeline_id,stage,status,input_snapshot,started_at,job_id,attempt) VALUES(?,?,?,'QUEUED','{}',?,?,?)", Ids.newId(), id, stage, now(), job.id(), attempt);
        repository.jdbc().update("UPDATE ai_pipeline_record SET job_id=?,status='RUNNING',current_stage=?,progress=?,error=NULL,revision=revision+1,updated_at=? WHERE id=?", job.id(), stage, (Integer.parseInt(stage.substring(1)) - 1) * 15, now(), id);
        return job;
    }
    private void next(String project, String id, int from) {
        var pipeline = repository.get(project, id); var steps = Values.objects(pipeline.get("steps"));
        for (int index = from; index <= 6; index++) {
            String stage = "S" + index;
            boolean done = steps.stream().anyMatch(step -> stage.equals(step.get("stage")) && Set.of("COMPLETED", "BLOCKED", "SKIPPED").contains(step.get("status")));
            if (done && stage.equals("S6") && plans.needsRefresh(project, pipeline, latestExecutionOutput(pipeline))) done = false;
            if (!done) { enqueue(project, id, stage, "next:" + pipeline.get("revision")); return; }
        }
        boolean gaps = steps.stream().anyMatch(step -> !Set.of("COMPLETED", "SKIPPED").contains(step.get("status")));
        repository.jdbc().update("UPDATE ai_pipeline_record SET status=?,progress=100,revision=revision+1,updated_at=? WHERE id=?", gaps ? "COMPLETED_WITH_GAPS" : "COMPLETED", now(), id);
    }
    private void fail(JobContext job, String id, RuntimeException failure) {
        boolean interrupted = Thread.interrupted();
        try {
            transactions.executeWithoutResult(tx -> {
                repository.lock(job.projectId()); var pipeline = repository.get(job.projectId(), id);
                if (!job.id().equals(pipeline.get("jobId")) || pipeline.get("status").equals("CANCELLED")) return;
                String status = failure instanceof java.util.concurrent.CancellationException ? "CANCELLED" : failure instanceof JobService.LeaseLostException ? "INTERRUPTED" : "FAILED";
                String message = failure instanceof Problem ? failure.getMessage() : "阶段执行已停止（" + failure.getClass().getSimpleName() + "）";
                Map<String, Object> error = errorDetails(failure);
                String encoded = json.write(error);
                repository.jdbc().update("UPDATE ai_pipeline_record SET status=?,error=?,revision=revision+1,updated_at=? WHERE id=?", status, encoded, now(), id);
                repository.jdbc().update("UPDATE ai_pipeline_step SET status=?,error=?,completed_at=? WHERE pipeline_id=? AND job_id=?", status, encoded, now(), id, job.id());
                repository.jdbc().update("UPDATE ai_message SET status='FAILED',validation=? WHERE job_id=? AND role='assistant' AND status='PREVIEW'", encoded, job.id());
            });
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private Map<String, Object> errorDetails(RuntimeException failure) {
        String message = failure instanceof Problem ? failure.getMessage() : "阶段执行已停止（" + failure.getClass().getSimpleName() + "）";
        if (failure instanceof Problem problem) return Map.of("code", problem.code(), "message", Objects.toString(message, "阶段执行失败"), "details", problem.details() == null ? Map.of() : problem.details());
        return Map.of("code", "INTERNAL_ERROR", "message", message, "details", Map.of());
    }
    @Scheduled(fixedDelay = 500, initialDelay = 1500)
    public void reconcile() {
        var candidates = repository.jdbc().queryForList("SELECT r.id,r.project_id FROM ai_pipeline_record r JOIN project p ON p.id=r.project_id AND p.deleted=FALSE WHERE r.status='RUNNING'");
        for (var candidate : candidates) {
            String project = candidate.get("project_id").toString(), id = candidate.get("id").toString();
            try { transactions.executeWithoutResult(tx -> reconcileOne(project, id)); }
            catch (RuntimeException unavailable) {
                // Deletion can race the candidate query; a corrupt/unavailable candidate also
                // must not prevent other projects from advancing. Do not log provider bodies.
                log.warn("Pipeline reconciliation skipped pipeline {} in project {} ({})", id, project, unavailable.getClass().getSimpleName());
            }
        }
    }
    private void reconcileOne(String project, String id) {
        repository.lock(project); var pipeline = repository.get(project, id);
        if (!pipeline.get("status").equals("RUNNING")) return;
        Job active = jobs.get(project, pipeline.get("jobId").toString()); if (!active.terminal()) return;
        var step = Values.objects(pipeline.get("steps")).stream().filter(value -> pipeline.get("currentStage").equals(value.get("stage"))).findFirst().orElseThrow();
        String state = step.get("status").toString(); Map<String, Object> output = new LinkedHashMap<>(Values.map(step.get("output")));
        if (state.equals("WAITING_RUN") && active.status().equals("SUCCEEDED")) {
            String runId = output.get("runId").toString();
            Map<String, Object> run = runs.get(project, runId); output.put("runSummary", run.get("summary"));
            var counts = Values.map(Values.map(run.get("summary")).get("counts"));
            if (counts.containsKey("FAILED") || counts.containsKey("ERROR")) {
                String key = "pipeline-diagnose:" + id + ":" + step.get("attempt");
                String jobId = bugs.submitForPipeline(project, runId, key, id).get("jobId").toString(); output.put("diagnosisJobId", jobId);
                repository.jdbc().update("UPDATE ai_pipeline_step SET status='WAITING_DIAGNOSIS',output_snapshot=? WHERE id=?", json.write(output), step.get("id"));
                repository.jdbc().update("UPDATE ai_pipeline_record SET job_id=?,progress=96,revision=revision+1,updated_at=? WHERE id=?", jobId, now(), id); return;
            }
            finishExecution(project, id, step, output); return;
        }
        if (state.equals("WAITING_DIAGNOSIS") && active.status().equals("SUCCEEDED")) {
            output.put("diagnosis", active.result());
            List<String> bugIds = Values.objects(active.result().get("items")).stream().filter(item -> !"SUPPRESSED_DELETED".equals(item.get("status"))).map(item -> Values.text(item, "bugId", "")).filter(value -> !value.isBlank()).distinct().toList(); appendAssets(id, pipeline, bugIds);
            finishExecution(project, id, step, output); return;
        }
        if (!active.status().equals("SUCCEEDED")) {
            repository.jdbc().update("UPDATE ai_pipeline_record SET status=?,error=?,revision=revision+1,updated_at=? WHERE id=?", active.status(), active.error(), now(), id);
            repository.jdbc().update("UPDATE ai_pipeline_step SET status=?,error=?,completed_at=? WHERE id=?", active.status(), active.error(), now(), step.get("id"));
        }
    }
    private void finishExecution(String project, String id, Map<String, Object> step, Map<String, Object> output) {
        output.put("execution", "COMPLETED"); output.put("pendingAssetIds", List.of()); output.put("requiresExecutionResume", false); output.remove("reason");
        repository.jdbc().update("UPDATE ai_pipeline_step SET status='COMPLETED',output_snapshot=?,completed_at=? WHERE id=?", json.write(output), now(), step.get("id")); next(project, id, 6);
    }
    private Map<String, Object> latestExecutionOutput(Map<String, Object> pipeline) {
        // Reuse the repository's run-aware merge so later attempts cannot revive
        // summary/diagnosis metadata belonging to an earlier run.
        return new LinkedHashMap<>(Values.map(pipeline.get("execution")));
    }
    private void appendAssets(String id, Map<String, Object> pipeline, List<String> added) {
        Set<Object> ids = new LinkedHashSet<>((List<?>) pipeline.get("assetIds")); ids.addAll(added);
        repository.jdbc().update("UPDATE ai_pipeline_record SET asset_ids=?,updated_at=? WHERE id=?", json.write(ids), now(), id);
    }
    private List<String> checked(String project, List<String> ids, Set<AssetType> allowed) {
        if (ids == null) return List.of();
        if (ids.size() > 5000 || new HashSet<>(ids).size() != ids.size()) throw Problem.invalid("输入资产 ID 重复或超过 5000 项");
        for (String id : ids) if (!allowed.contains(assets.get(project, id).type())) throw Problem.invalid("输入资产类型不符合流水线要求");
        return List.copyOf(ids);
    }
    private Map<String, Object> submission(Map<String, Object> pipeline) { return Map.of("pipelineId", pipeline.get("id"), "jobId", pipeline.get("jobId"), "conversationId", pipeline.get("conversationId"), "status", pipeline.get("status")); }
    private Map<String, Object> submission(Map<String, Object> pipeline, String attemptJob) {
        Map<String, Object> result = new LinkedHashMap<>(submission(pipeline));
        result.put("activeJobId", pipeline.get("jobId")); result.put("jobId", attemptJob); return result;
    }
}
