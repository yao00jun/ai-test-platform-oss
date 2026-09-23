package com.aitest.ai;
import org.junit.jupiter.api.Tag;

import com.aitest.ai.pipeline.PipelineService;
import com.aitest.asset.*;
import com.aitest.common.Ids;
import com.aitest.execution.ExecutionCoordinator;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Tag("slow")
@org.springframework.test.context.TestPropertySource(properties = "aitest.pipeline.api-batch-size=20")
class PipelineBoundaryIT extends ExchangeHttpTest {
    @Autowired ModelSettingsService settings;
    @Autowired PipelineService pipelines;
    @Autowired AiConversationService conversations;
    @Autowired ExecutionCoordinator execution;
    @Autowired JobService jobs;
    @Autowired JdbcTemplate jdbc;

    @Test void aLaterApiBatchCannotAppendStepsToAHumanProtectedEarlierScenario() throws Exception {
        try (var model = new ModelFixtureServer()) {
            settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30));
            String project = project().id();
            Asset requirement = assets.create(project, AssetType.REQUIREMENT, null, "退款", Map.of("content", "# 退款\n支持订单退款。"), "MANUAL");
            List<Asset> definitions = new ArrayList<>();
            for (int index = 0; index < 21; index++) definitions.add(assets.create(project, AssetType.API_DEFINITION, null, "API " + index, Map.of("method", "GET", "path", "/api/" + index), "IMPORT"));
            model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "MODIFY", "targetType", "REQUIREMENT", "targetId", requirement.id(), "baseVersion", requirement.version(), "data", Map.of("analysis", Map.of("rules", List.of("校验退款"))))))));
            model.enqueue("featureCaseStart\n## 退款\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 请求退款 | 成功 |\nfeatureCaseEnd");
            List<Map<String, Object>> firstBatch = new ArrayList<>();
            for (int index = 0; index < 20; index++) firstBatch.add(api(definitions.get(index), "api" + index));
            firstBatch.add(add("SCENARIO", "scenario", null, "第一批场景", Map.of()));
            firstBatch.add(add("SCENARIO_STEP", "first", "@scenario", "第一步", Map.of("targetId", "@api0", "stepType", "HTTP")));
            model.enqueue(json.write(Map.of("changes", firstBatch)));
            var submission = pipelines.submit(new PipelineService.Request(project, List.of(requirement.id()), definitions.stream().map(Asset::id).toList(), null, List.of(), List.of(), false, "batches"));
            String id = submission.get("pipelineId").toString();
            await().atMost(Duration.ofSeconds(60)).until(() -> assets.list(project, AssetType.SCENARIO, null, "", 0, 10).total() == 1);
            Asset prior = assets.list(project, AssetType.SCENARIO, null, "", 0, 10).items().getFirst();
            var held = model.hold(json.write(Map.of("changes", List.of(api(definitions.getLast(), "later"), add("SCENARIO_STEP", "extra", prior.id(), "越界追加", Map.of("targetId", "@later", "stepType", "HTTP"))))));
            assertThat(held.entered().await(10, TimeUnit.SECONDS)).isTrue();
            Asset protectedScenario;
            List<Asset> protectedSteps;
            try {
                protectedScenario = assets.update(project, prior.id(), prior.version(), "人工确认的场景", Map.of("description", "步骤已人工审核"), true, "MANUAL");
                protectedSteps = assets.children(project, prior.id());
            } finally { held.release().countDown(); }
            var stopped = terminal(project, id);
            assertThat(stopped.get("status")).isEqualTo("FAILED");
            assertThat(assets.get(project, prior.id())).isEqualTo(protectedScenario);
            assertThat(assets.children(project, prior.id())).containsExactlyElementsOf(protectedSteps);
            assertThat(assets.list(project, AssetType.API_CASE, null, "", 0, 100).items()).hasSize(20);
            assertThat(model.requests).hasSize(4);
        }
    }

    @Test void aDeletedProjectCannotStopAnotherPipelineFromReconcilingItsCompletedRun() {
        assertReconciliationIsolated(true);
    }

    @Test void anInvalidActiveJobCannotStopAnotherPipelineFromReconcilingItsCompletedRun() {
        assertReconciliationIsolated(false);
    }

    private void assertReconciliationIsolated(boolean deleteProject) {
        String brokenProject = project().id(), healthyProject = project().id();
        Asset manual = assets.create(healthyProject, AssetType.FUNCTIONAL_CASE, null, "人工退款", Map.of(), "MANUAL");
        var run = execution.submit(healthyProject, new ExecutionCoordinator.Request(manual.id(), null, null, "healthy-run"));
        await().atMost(Duration.ofSeconds(15)).until(() -> jobs.get(healthyProject, run.jobId()).terminal());
        assertThat(jobs.get(healthyProject, run.jobId()).status()).isEqualTo("SUCCEEDED");
        String broken = pipeline(brokenProject, "missing-job", Map.of());
        String healthy = pipeline(healthyProject, run.jobId(), Map.of("runId", run.runId(), "runJobId", run.jobId()));
        try {
            if (deleteProject) {
                Asset project = assets.get(brokenProject, brokenProject);
                assets.delete(brokenProject, brokenProject, project.version());
            }
            jdbc.update("UPDATE ai_pipeline_record SET status='RUNNING' WHERE id IN (?,?)", broken, healthy);
            assertThatCode(pipelines::reconcile).doesNotThrowAnyException();
            assertThat(pipelines.get(healthyProject, healthy).get("status")).isEqualTo("COMPLETED");
        } finally {
            jdbc.update("UPDATE ai_pipeline_record SET status='FAILED' WHERE id IN (?,?) AND status='RUNNING'", broken, healthy);
        }
    }

    private String pipeline(String project, String job, Map<String, Object> output) {
        String id = Ids.newId(), conversation = conversations.ensure(null, project, "PIPELINE", id, null);
        jdbc.update("INSERT INTO ai_pipeline_record(id,project_id,job_id,conversation_id,requirement_snapshot,api_snapshot,status,current_stage,asset_ids,config_snapshot,created_at,updated_at) VALUES(?,?,?,?,'[]','[]','QUEUED','S6','[]','{}',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))", id, project, job, conversation);
        jdbc.update("INSERT INTO ai_pipeline_step(id,pipeline_id,stage,status,input_snapshot,output_snapshot,started_at,job_id,attempt) VALUES(?,?,'S6','WAITING_RUN','{}',?,CURRENT_TIMESTAMP(3),?,1)", Ids.newId(), id, json.write(output), job);
        return id;
    }
    private Map<String, Object> api(Asset definition, String key) { return add("API_CASE", key, null, definition.name(), Map.of("apiDefinitionId", definition.id(), "method", "GET", "path", definition.data().get("path"))); }
    private Map<String, Object> add(String type, String key, String parent, String name, Map<String, Object> data) { var result = new LinkedHashMap<String, Object>(Map.of("operation", "ADD", "targetType", type, "localKey", key, "name", name, "data", data)); result.put("parentId", parent); return result; }
    private Map<String, Object> terminal(String project, String id) { await().atMost(Duration.ofSeconds(35)).until(() -> Set.of("COMPLETED", "COMPLETED_WITH_GAPS", "FAILED", "CANCELLED", "INTERRUPTED").contains(pipelines.get(project, id).get("status"))); return pipelines.get(project, id); }
}
