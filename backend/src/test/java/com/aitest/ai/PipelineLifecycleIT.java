package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.ai.pipeline.PipelineService;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class PipelineLifecycleIT extends ExchangeHttpTest {
    @Autowired ModelSettingsService settings;
    @Autowired PipelineService pipelines;
    @Autowired JobService jobs;
    @Autowired AssetRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test void retryKeepsCompletedBatchesAndRepeatedSubmissionReturnsTheSameAttempt() throws Exception {
        try (var model = model()) {
            Failed partial = partial(model);
            List<Asset> saved = generated(partial.project());
            var held = model.hold(functional("第二份需求"));
            Map<String, Object> retry = retry(partial.project(), "finish");
            var first = request("POST", path(partial.id(), "resume"), retry);
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(held.entered().await(10, TimeUnit.SECONDS)).isTrue();
            try {
                var duplicate = request("POST", path(partial.id(), "resume"), retry);
                assertThat(duplicate.statusCode()).as(new String(duplicate.body())).isEqualTo(200);
                assertThat(object(duplicate).get("jobId")).isEqualTo(object(first).get("jobId"));
            } finally { held.release().countDown(); }
            assertThat(terminal(partial.project(), partial.id()).get("status")).isEqualTo("COMPLETED_WITH_GAPS");
            assertThat(generated(partial.project())).hasSize(4).containsAll(saved);
            assertThat(model.requests).hasSize(6);
            var replay = object(request("POST", path(partial.id(), "resume"), retry));
            assertThat(replay.get("jobId")).isEqualTo(object(first).get("jobId"));
            assertThat(replay.get("status")).isEqualTo("COMPLETED_WITH_GAPS");
            assertThat(generated(partial.project())).hasSize(4).containsAll(saved);
        }
    }

    @Test void editingASourceAfterPartialGenerationCannotDuplicateTheAlreadySavedBatch() throws Exception {
        try (var model = model()) {
            Failed partial = partial(model);
            List<Asset> before = generated(partial.project());
            Asset source = assets.get(partial.project(), partial.firstRequirement());
            assets.update(partial.project(), source.id(), source.version(), null, Map.of("content", "# 人工修订\n增加优惠券退款规则。"), null, "MANUAL");
            model.enqueue(functional("不得重复生成")); model.enqueue(functional("第二份需求"));
            assertThat(request("POST", path(partial.id(), "resume"), retry(partial.project(), "changed-source")).statusCode()).isEqualTo(200);
            var result = terminal(partial.project(), partial.id());
            assertThat(result.get("status")).isEqualTo("FAILED");
            assertThat(result.get("error").toString()).contains("来源", "全局反馈");
            assertThat(generated(partial.project())).containsExactlyElementsOf(before);
            assertThat(model.requests).hasSize(5);
        }
    }

    @Test void replayingAFailedRetryDoesNotReactivateItsTerminalJob() throws Exception {
        try (var model = model()) {
            Failed partial = partial(model);
            model.enqueue("invalid retry"); model.enqueue("invalid repair");
            var retry = retry(partial.project(), "failed-once");
            var first = object(request("POST", path(partial.id(), "resume"), retry));
            var stopped = terminal(partial.project(), partial.id());
            assertThat(stopped.get("status")).isEqualTo("FAILED");
            var replay = object(request("POST", path(partial.id(), "resume"), retry));
            assertThat(replay.get("status")).isEqualTo("FAILED");
            assertThat(replay.get("jobId")).isEqualTo(first.get("jobId"));
            assertThat(pipelines.get(partial.project(), partial.id()).get("attempts")).isEqualTo(stopped.get("attempts"));
            assertThat(model.requests).hasSize(7);
        }
    }

    @Test void cancellationSerializesWithAnAtomicCheckpointAndDiscardsTheLateModelReply() throws Exception {
        try (var model = model(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            String project = project().id();
            Asset requirement = requirement(project, "等待取消");
            var held = model.hold(analysis(requirement));
            var submitted = object(request("POST", "/api/ai/pipelines", Map.of("projectId", project, "requirementIds", List.of(requirement.id()), "execute", false, "idempotencyKey", "cancel")));
            String id = submitted.get("pipelineId").toString(), jobId = submitted.get("jobId").toString();
            assertThat(held.entered().await(10, TimeUnit.SECONDS)).isTrue();
            CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
            var checkpoint = workers.submit(() -> jobs.atomic(project, jobId, () -> {
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("checkpoint barrier timed out"); }
                catch (InterruptedException error) { throw new RuntimeException(error); }
                repository.lockProject(project); return true;
            }, false));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var cancel = workers.submit(() -> pipelines.cancel(project, id));
            try {
                // A cancellation must publish its durable stop before waiting for an in-flight
                // job checkpoint; otherwise it holds project -> job against job -> project.
                await().atMost(Duration.ofSeconds(4)).until(() -> "CANCELLED".equals(pipelines.get(project, id).get("status")));
            } finally { release.countDown(); held.release().countDown(); }
            assertThat(checkpoint.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cancel.get(10, TimeUnit.SECONDS).get("status")).isEqualTo("CANCELLED");
            await().atMost(Duration.ofSeconds(10)).until(() -> jobs.get(project, jobId).terminal());
            assertThat(assets.get(project, requirement.id())).isEqualTo(requirement);
            assertThat(generated(project)).isEmpty();
            assertThat(objects(pipelines.get(project, id).get("steps"))).hasSize(1);
        }
    }

    private Failed partial(ModelFixtureServer model) throws Exception {
        String project = project().id();
        Asset first = requirement(project, "第一份需求"), second = requirement(project, "第二份需求");
        model.enqueue(analysis(first)); model.enqueue(analysis(second));
        model.enqueue(functional("第一份需求")); model.enqueue("invalid first response"); model.enqueue("invalid repaired response");
        var submitted = request("POST", "/api/ai/pipelines", Map.of("projectId", project, "requirementIds", List.of(first.id(), second.id()), "execute", false, "idempotencyKey", "partial"));
        assertThat(submitted.statusCode()).isEqualTo(200);
        String id = object(submitted).get("pipelineId").toString();
        var stopped = terminal(project, id);
        assertThat(stopped.get("status")).isEqualTo("FAILED");
        assertThat(stopped.get("currentStage")).isEqualTo("S2");
        assertThat(generated(project)).hasSize(2);
        return new Failed(project, id, first.id());
    }
    private ModelFixtureServer model() throws Exception {
        var model = new ModelFixtureServer();
        settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30)); return model;
    }
    private Asset requirement(String project, String name) { return assets.create(project, AssetType.REQUIREMENT, null, name, Map.of("content", "# " + name + "\n支持订单退款并校验状态。"), "MANUAL"); }
    private String analysis(Asset source) { return json.write(Map.of("changes", List.of(Map.of("operation", "MODIFY", "targetType", "REQUIREMENT", "targetId", source.id(), "baseVersion", source.version(), "data", Map.of("analysis", Map.of("rules", List.of("退款前校验订单状态"))))))); }
    private String functional(String title) { return "featureCaseStart\n## " + title + "\n### 前置条件\n订单已支付\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 提交退款 | 退款成功 |\n### 备注\nP1\nfeatureCaseEnd"; }
    private List<Asset> generated(String project) { return assets.all(project).stream().filter(asset -> Set.of(AssetType.FUNCTIONAL_CASE, AssetType.FUNCTIONAL_STEP).contains(asset.type())).sorted(Comparator.comparing(Asset::id)).toList(); }
    private Map<String, Object> retry(String project, String key) { return Map.of("projectId", project, "stage", "S2", "idempotencyKey", key); }
    private String path(String id, String action) { return "/api/ai/pipelines/" + id + "/" + action; }
    private Map<String, Object> terminal(String project, String id) {
        await().atMost(Duration.ofSeconds(45)).until(() -> Set.of("COMPLETED", "COMPLETED_WITH_GAPS", "FAILED", "CANCELLED", "INTERRUPTED").contains(pipelines.get(project, id).get("status")));
        return pipelines.get(project, id);
    }
    private record Failed(String project, String id, String firstRequirement) { }
}
