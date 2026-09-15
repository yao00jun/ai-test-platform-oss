package com.aitest.execution;

import com.aitest.asset.*;

public interface AssetExecutor {
    AssetType type();
    StepResult execute(Asset asset, AssetGraph graph, ExecutionContext context);
    default String executeAndRecord(Asset asset, AssetGraph graph, ExecutionContext context, java.util.function.BiConsumer<Asset, StepResult> record) {
        StepResult result = execute(asset, graph, context); record.accept(asset, result); return result.status();
    }
}
