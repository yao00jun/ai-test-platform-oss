package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.engine.web.WebWorkerClient;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.BiConsumer;

@Component
public final class WebAssetExecutor implements AssetExecutor {
    private final WebWorkerClient workers;
    public WebAssetExecutor(WebWorkerClient workers) { this.workers = workers; }
    public AssetType type() { return AssetType.UI_SCENARIO; }
    @Override public String executeAndRecord(Asset asset, AssetGraph graph, ExecutionContext context, BiConsumer<Asset, StepResult> record) { return workers.execute(asset, graph, context, record); }
    @Override public StepResult execute(Asset asset, AssetGraph graph, ExecutionContext context) {
        List<StepResult> steps = new ArrayList<>(); String outcome = workers.execute(asset, graph, context, (step, result) -> steps.add(result));
        return new StepResult(outcome, steps.stream().mapToLong(StepResult::durationMs).sum(), Map.of(), Map.of("steps", steps), List.of(), Map.of(), steps.stream().flatMap(step -> step.artifactIds().stream()).toList(), null);
    }
}
