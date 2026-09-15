package com.aitest.ai;

import com.aitest.ai.pipeline.PipelineService;
import com.aitest.asset.*;
import com.aitest.bug.BugDiagnosisService;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import com.aitest.support.ModelFixtureServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

class PipelineDiagnosisCancellationIT extends ExchangeHttpTest {
    @Autowired ModelSettingsService settings;
    @Autowired PipelineService pipelines;
    @Autowired JobService jobs;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AssetRepository repository;

    @Test void cancellationFencesDiagnosisAfterItsJobCheckpointAndBeforeItsProjectWrite() throws Exception {
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/failure", exchange -> { exchange.sendResponseHeaders(500, -1); exchange.close(); });
        site.start();
        CountDownLatch atProjectWrite = new CountDownLatch(1), releaseWrite = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        try (var model = new ModelFixtureServer(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30));
            String project = project().id();
            Asset requirement = assets.create(project, AssetType.REQUIREMENT, null, "退款", Map.of("content", "# 退款\n退款应成功。"), "MANUAL");
            Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "接口环境", Map.of("baseUrl", "http://127.0.0.1:" + site.getAddress().getPort()), "MANUAL");
            Asset api = assets.create(project, AssetType.API_DEFINITION, null, "退款", Map.of("method", "GET", "path", "/failure"), "IMPORT");
            model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "MODIFY", "targetType", "REQUIREMENT", "targetId", requirement.id(), "baseVersion", requirement.version(), "data", Map.of("analysis", Map.of("rules", List.of("退款成功"))))))));
            model.enqueue("featureCaseStart\n## 退款\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 提交退款 | 成功 |\nfeatureCaseEnd");
            model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "API_CASE", "name", "退款失败", "data", Map.of("apiDefinitionId", api.id(), "method", "GET", "path", "/failure", "assertions", List.of(Map.of("type", "status", "expected", 200))))))));
            var diagnosis = model.hold(json.write(Map.of("title", "退款返回错误", "severity", "MAJOR", "reproduceSteps", "提交退款", "expectedResult", "状态 200", "actualResult", "状态 500", "rootCauseAnalysis", "待核实退款服务异常", "fixSuggestion", "检查服务日志")));
            // This spy changes only scheduling: every real repository operation still runs.
            doAnswer(invocation -> {
                boolean diagnosisCommit = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals(BugDiagnosisService.class.getName()) && frame.getMethodName().startsWith("lambda$execute")));
                if (diagnosisCommit && armed.compareAndSet(true, false)) {
                    atProjectWrite.countDown();
                    if (!releaseWrite.await(15, TimeUnit.SECONDS)) throw new AssertionError("diagnostic project-write barrier timed out");
                }
                return invocation.callRealMethod();
            }).when(repository).lockProject(project);
            String id = pipelines.submit(new PipelineService.Request(project, List.of(requirement.id()), List.of(api.id()), environment.id(), List.of(), List.of(), true, "cancel-diagnosis")).get("pipelineId").toString();
            assertThat(diagnosis.entered().await(60, TimeUnit.SECONDS))
                    .as("Pipeline did not reach diagnosis; current stage: %s", pipelines.get(project, id).get("currentStage")).isTrue();
            armed.set(true); diagnosis.release().countDown();
            assertThat(atProjectWrite.await(10, TimeUnit.SECONDS)).isTrue();
            String diagnosisJob = pipelines.get(project, id).get("jobId").toString();
            assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(input,'$.pipelineId')) FROM job_task WHERE id=?", String.class, diagnosisJob)).isEqualTo(id);
            var cancel = workers.submit(() -> pipelines.cancel(project, id));
            try {
                await().atMost(Duration.ofSeconds(4)).until(() -> "CANCELLED".equals(pipelines.get(project, id).get("status")));
            } finally { releaseWrite.countDown(); }
            assertThat(cancel.get(10, TimeUnit.SECONDS).get("status")).isEqualTo("CANCELLED");
            await().atMost(Duration.ofSeconds(10)).until(() -> jobs.get(project, diagnosisJob).terminal());
            assertThat(assets.list(project, AssetType.BUG, null, "", 0, 10).items()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bug_failure_occurrence WHERE project_id=?", Integer.class, project)).isZero();
            assertThat(jobs.get(project, diagnosisJob).status()).isEqualTo("CANCELLED");
        } finally { releaseWrite.countDown(); site.stop(0); }
    }
}
