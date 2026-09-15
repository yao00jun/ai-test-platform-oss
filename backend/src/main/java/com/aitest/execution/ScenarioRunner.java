package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;

@Component
public final class ScenarioRunner {
    private final Map<AssetType, AssetExecutor> engines = new EnumMap<>(AssetType.class);
    private final VariableResolver variables;
    public ScenarioRunner(List<AssetExecutor> executors, VariableResolver variables) { executors.forEach(engine -> engines.put(engine.type(), engine)); this.variables = variables; }
    public String execute(Asset asset, AssetGraph graph, ExecutionContext context, BiConsumer<Asset, StepResult> record) {
        context.checkpoint();
        if (asset.type() == AssetType.FUNCTIONAL_CASE) return "MANUAL_PENDING";
        if (asset.type() == AssetType.SCENARIO) {
            context.variables().putAll(Values.map(variables.resolve(asset.data().get("variables"), context.variables())));
            List<Asset> steps = graph.children(asset.id());
            if (steps.isEmpty()) return "BLOCKED";
            String outcome = "PASSED"; boolean stopped = false;
            for (Asset step : steps) {
                if (stopped) { record.accept(step, new StepResult("SKIPPED", 0, Map.of(), Map.of(), List.of(), Map.of(), List.of(), "前序步骤失败")); continue; }
                context.checkpoint();
                String status;
                if (step.type() == AssetType.SQL_VALIDATION) status = execute(step, graph, context, record);
                else {
                    Map<String, Object> saved = new LinkedHashMap<>(context.variables());
                    context.beginStep();
                    context.variables().putAll(Values.map(variables.resolve(step.data().get("variables"), saved)));
                    if (Values.text(step.data(), "stepType", "HTTP").equals("WAIT")) {
                        int wait = Values.integer(step.data(), "waitMs", 0, 0, 60000);
                        for (int elapsed = 0; elapsed < wait; elapsed += 100) {
                            context.checkpoint(); try { Thread.sleep(Math.min(100, wait - elapsed)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                        }
                        StepResult result = new StepResult("PASSED", wait, Map.of("waitMs", wait), Map.of(), List.of(), Map.of(), List.of(), null); record.accept(step, result); status = "PASSED";
                    } else {
                        Asset target = graph.get(Values.text(step.data(), "targetId", ""));
                        status = execute(target, graph, context, record);
                        for (Asset validation : graph.children(step.id())) if (validation.type() == AssetType.SQL_VALIDATION && status.equals("PASSED")) status = execute(validation, graph, context, record);
                    }
                    // Explicit overrides apply to this step only. Extracted values keep their new values.
                    for (String name : Values.map(step.data().get("variables")).keySet()) {
                        if (context.published(name)) continue;
                        if (saved.containsKey(name)) context.variables().put(name, saved.get(name)); else context.variables().remove(name);
                    }
                }
                if (!status.equals("PASSED")) { outcome = status; stopped = !Values.bool(asset.data(), "continueOnFailure", false); }
            }
            return outcome;
        }
        AssetExecutor executor = engines.get(asset.type());
        if (executor == null) throw Problem.invalid("没有对应的执行引擎: " + asset.type());
        String outcome;
        try { outcome = executor.executeAndRecord(asset, graph, context, record); }
        catch (CancellationException e) { throw e; }
        catch (RuntimeException error) {
            StepResult result = StepResult.error(error instanceof Problem ? error.getMessage() : "执行失败（" + error.getClass().getSimpleName() + "）", 0, Map.of());
            record.accept(asset, result); outcome = result.status();
        }
        if (outcome.equals("PASSED")) for (Asset validation : graph.children(asset.id())) if (validation.type() == AssetType.SQL_VALIDATION) {
            outcome = execute(validation, graph, context, record); if (!outcome.equals("PASSED")) break;
        }
        return outcome;
    }
}
