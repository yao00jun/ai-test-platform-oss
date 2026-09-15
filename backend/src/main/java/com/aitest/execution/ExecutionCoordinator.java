package com.aitest.execution;

import com.aitest.asset.AssetType;
import com.aitest.common.Problem;
import com.aitest.job.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ExecutionCoordinator implements JobHandler {
    private final JobService jobs; private final RunRepository runs; private final SnapshotService snapshots;
    private final ScenarioRunner runner; private final VariableResolver resolver; private final JdbcTemplate jdbc; private final ApplicationEventPublisher events;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor(); private final Semaphore capacity;
    public ExecutionCoordinator(JobService jobs, RunRepository runs, SnapshotService snapshots, ScenarioRunner runner, VariableResolver resolver, JdbcTemplate jdbc, ApplicationEventPublisher events, @Value("${aitest.execution.concurrency:8}") int concurrency) {
        this.jobs = jobs; this.runs = runs; this.snapshots = snapshots; this.runner = runner; this.resolver = resolver; this.jdbc = jdbc; this.events = events; capacity = new Semaphore(Math.clamp(concurrency, 1, 64));
    }
    public String kind() { return "TEST_RUN"; }
    @Transactional public Submission submit(String projectId, Request request) {
        if (request.assetId() == null) throw Problem.invalid("请选择测试资产");
        String runId = UUID.nameUUIDFromBytes((projectId + ":" + request.idempotencyKey()).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        Map<String, Object> input = new LinkedHashMap<>(); input.put("runId", runId); input.put("assetId", request.assetId()); input.put("environmentId", request.environmentId()); input.put("datasetId", request.datasetId());
        Job job = jobs.submit(projectId, kind(), request.idempotencyKey(), input);
        if (!runs.exists(projectId, runId)) {
            RunDefinition definition = snapshots.capture(projectId, request.assetId(), request.environmentId(), request.datasetId());
            ExecutionConfiguration.requireReady(definition); runs.create(projectId, runId, job.id(), definition);
        }
        return new Submission(job.id(), runId);
    }
    @Override public Map<String, Object> execute(JobContext job, Map<String, Object> input) throws Exception {
        String runId = input.get("runId").toString(); RunDefinition definition = runs.definition(job.projectId(), runId);
        runs.started(runId);
        Semaphore planLimit = new Semaphore(Values.integer(definition.graph().root().data(), "concurrency", 4, 1, 32));
        AtomicInteger finished = new AtomicInteger(); List<Future<?>> futures = new ArrayList<>();
        try {
            for (RunDefinition.Item item : definition.items()) futures.add(workers.submit(() -> {
                boolean planAcquired = false, globalAcquired = false;
                long started = System.nanoTime();
                try {
                    planLimit.acquire(); planAcquired = true; capacity.acquire(); globalAcquired = true; job.checkpoint();
                    if (item.mode().equals("MANUAL") || item.type() == AssetType.FUNCTIONAL_CASE) return;
                    runs.itemStarted(item.id());
                    Map<String, Object> variables = new LinkedHashMap<>(definition.graph().environmentVariables()); variables.putAll(item.variables());
                    Map<String, Object> overrides = Values.map(variables.remove("__planOverrides")); variables.putAll(Values.map(resolver.resolve(overrides, variables)));
                    ExecutionContext context = new ExecutionContext(variables, job::checkpoint); AtomicInteger position = new AtomicInteger();
                    String outcome = runner.execute(definition.graph().get(item.assetId()), definition.graph(), context,
                            (asset, result) -> runs.step(item.id(), asset, position.getAndIncrement(), result, context.variables()));
                    runs.itemFinished(item.id(), outcome, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), null);
                } catch (CancellationException error) { runs.itemFinished(item.id(), "CANCELLED", 0, "任务已取消"); }
                catch (InterruptedException error) { Thread.interrupted(); runs.itemFinished(item.id(), "INTERRUPTED", 0, "运行已中断，未重放"); }
                catch (RuntimeException error) { runs.itemFinished(item.id(), "ERROR", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), error instanceof Problem ? error.getMessage() : "执行错误（" + error.getClass().getSimpleName() + "）"); }
                finally {
                    if (globalAcquired) capacity.release(); if (planAcquired) planLimit.release();
                    int done = finished.incrementAndGet();
                    try { job.progress(Math.min(98, done * 98 / definition.items().size()), "已处理 " + done + "/" + definition.items().size() + " 项"); }
                    catch (CancellationException | JobService.LeaseLostException stopped) { /* Terminal state is durable; results remain inspectable. */ }
                }
            }));
            for (Future<?> future : futures) future.get();
            return job.completeAtomically(() -> {
                runs.finish(runId, null);
                boolean diagnose = Values.bool(definition.graph().root().data(), "diagnoseFailures", true);
                events.publishEvent(new RunCompleted(job.projectId(), runId, diagnose));
                return Map.of("runId", runId, "summary", runs.summary(runId));
            });
        } catch (CancellationException error) { runs.finish(runId, "CANCELLED"); throw error; }
        catch (Exception error) { runs.finish(runId, error instanceof JobService.LeaseLostException ? "INTERRUPTED" : "ERROR"); throw error; }
    }
    @Scheduled(fixedDelay = 5000) public void reconcileTerminalJobs() {
        var stale = jdbc.queryForList("SELECT r.id,j.status FROM test_run r JOIN job_task j ON j.id=r.job_id WHERE r.status IN ('QUEUED','RUNNING') AND j.status IN ('CANCELLED','INTERRUPTED','FAILED')");
        for (var row : stale) runs.finish(row.get("id").toString(), row.get("status").equals("FAILED") ? "ERROR" : row.get("status").toString());
    }
    @PreDestroy void close() {
        workers.shutdown();
        try { if (!workers.awaitTermination(20, TimeUnit.SECONDS)) { workers.shutdownNow(); workers.awaitTermination(10, TimeUnit.SECONDS); } }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); workers.shutdownNow(); }
    }
    public record Request(String assetId, String environmentId, String datasetId, String idempotencyKey) { }
    public record Submission(String jobId, String runId) { }
    public record RunCompleted(String projectId, String runId, boolean diagnose) { }
}
