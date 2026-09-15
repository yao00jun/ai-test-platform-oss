package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.jayway.jsonpath.JsonPath;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class ExecutionAssetPolicy implements AssetPolicy {
    private final AssertionEvaluator assertions;
    private final AssetRepository repository;
    private final VariableResolver variables;
    public ExecutionAssetPolicy(AssertionEvaluator assertions, AssetRepository repository, VariableResolver variables) { this.assertions = assertions; this.repository = repository; this.variables = variables; }
    @Override public void validate(Asset previous, Asset candidate) {
        validateInGraph(previous, candidate, null);
    }
    @Override public void validateInGraph(Asset previous, Asset candidate, Map<String, Asset> proposedGraph) {
        if (Set.of(AssetType.API_CASE, AssetType.SQL_VALIDATION).contains(candidate.type())) assertions.validate(Values.objects(candidate.data().get("assertions")));
        if (candidate.type() == AssetType.API_CASE) {
            Set<String> names = new HashSet<>();
            for (var extractor : Values.objects(candidate.data().get("extractors"))) {
                String name = Values.text(extractor, "variable", "");
                if (!name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}") || !names.add(name)) throw Problem.invalid("提取变量名必须合法且唯一");
                String type = Values.text(extractor, "type", "jsonpath");
                if (type.equals("jsonpath")) try { JsonPath.compile(Values.text(extractor, "jsonpath", Values.text(extractor, "path", "$"))); }
                    catch (RuntimeException e) { throw Problem.invalid("提取器 JSONPath 无效"); }
                else if (!type.equals("header")) throw Problem.invalid("提取器仅支持 jsonpath/header");
            }
        }
        if (previous == null) return;
        Set<String> removed = exported(previous); removed.removeAll(exported(candidate));
        if (removed.isEmpty()) return;
        // A target-only change may not silently invalidate consumers, even when the consumer
        // refers through a scenario rather than through a direct SQL foreign key.
        Collection<Asset> consumers = proposedGraph == null ? repository.all(candidate.projectId(), null, null) : proposedGraph.values();
        for (Asset consumer : consumers) {
            if (consumer.id().equals(candidate.id())) continue;
            Set<String> refs = variables.references(ExecutionConfiguration.data(consumer)); refs.retainAll(removed);
            if (!refs.isEmpty()) throw new Problem(409, "DEPENDENCY_CONFLICT", "修改移除了其他资产正在使用的变量，原资产保持不变", Map.of("targetId", candidate.id(), "consumerId", consumer.id(), "variables", refs));
        }
    }
    public static Set<String> exported(Asset asset) {
        Set<String> result = new HashSet<>();
        if (asset.type() == AssetType.API_CASE) Values.objects(asset.data().get("extractors")).forEach(e -> result.add(Values.text(e, "variable", "")));
        if (asset.type() == AssetType.SQL_VALIDATION) result.addAll(Values.map(asset.data().get("exports")).keySet());
        if (asset.type() == AssetType.UI_STEP && Values.text(asset.data(), "action", "").equals("extract")) result.add(Values.text(asset.data(), "saveAs", ""));
        result.remove(""); return result;
    }
}
