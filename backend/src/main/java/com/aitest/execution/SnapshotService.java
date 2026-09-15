package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.analysis.SourceBindingService;
import com.aitest.common.Ids;
import com.aitest.common.Problem;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class SnapshotService {
    private final AssetRepository repository;
    private final SourceBindingService sourceBindings;
    public SnapshotService(AssetRepository repository, SourceBindingService sourceBindings) { this.repository = repository; this.sourceBindings = sourceBindings; }
    public RunDefinition capture(String projectId, String rootId, String environmentId, String datasetId) {
        repository.lockProject(projectId);
        Asset root = repository.find(projectId, rootId);
        if (!Set.of(AssetType.FUNCTIONAL_CASE, AssetType.API_CASE, AssetType.SCENARIO, AssetType.UI_SCENARIO, AssetType.SQL_VALIDATION, AssetType.TEST_PLAN).contains(root.type())) throw Problem.invalid("此资产类型不能作为测试执行入口");
        if (environmentId == null || environmentId.isBlank()) environmentId = Values.text(root.data(), "environmentId", "");
        Map<String, Asset> captured = new LinkedHashMap<>(); ArrayDeque<String> pending = new ArrayDeque<>(); pending.add(rootId);
        if (environmentId != null && !environmentId.isBlank()) {
            if (repository.find(projectId, environmentId).type() != AssetType.ENVIRONMENT) throw Problem.invalid("执行环境 ID 类型无效");
            pending.add(environmentId);
            String selected = environmentId;
            repository.all(projectId, AssetType.AUTH_CONFIG, null).stream().filter(a -> selected.equals(a.data().get("environmentId")) && Values.bool(a.data(), "enabled", true)).forEach(a -> pending.add(a.id()));
        }
        if (datasetId != null && !datasetId.isBlank()) pending.add(datasetId);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst(); if (captured.containsKey(id)) continue;
            Asset asset = repository.find(projectId, id); captured.put(id, asset);
            if (!asset.type().childTypes().isEmpty()) repository.all(projectId, null, id).forEach(child -> pending.add(child.id()));
            for (String field : AssetReferences.FIELDS) {
                String reference = Values.text(asset.data(), field, ""); if (!reference.isBlank()) pending.add(reference);
            }
            if (captured.size() > 20000) throw Problem.invalid("单次运行引用超过 20000 个资产，请拆分计划");
        }
        AssetGraph graph = new AssetGraph(rootId, environmentId, captured, sourceBindings.capture(captured.values())); List<RunDefinition.Item> items = new ArrayList<>();
        if (root.type() == AssetType.TEST_PLAN) {
            for (Asset item : graph.children(root.id())) {
                if (item.type() != AssetType.PLAN_ITEM) continue;
                Asset target = graph.get(Values.text(item.data(), "targetId", ""));
                String data = nonempty(Values.text(item.data(), "datasetId", ""), nonempty(datasetId, Values.text(root.data(), "datasetId", "")));
                expand(graph, target, data, Values.map(item.data().get("variables")), Values.text(item.data(), "executionMode", "AUTO"), items);
            }
        } else expand(graph, root, nonempty(datasetId, Values.text(root.data(), "datasetId", "")), Map.of(), "AUTO", items);
        if (items.isEmpty()) throw Problem.invalid("计划没有可运行项目，或数据集没有数据行");
        if (items.size() > 20000) throw Problem.invalid("单次执行最多 20000 个数据行/计划项组合，请拆分运行");
        return new RunDefinition(graph, items);
    }
    private void expand(AssetGraph graph, Asset target, String datasetId, Map<String, Object> overrides, String mode, List<RunDefinition.Item> result) {
        if (datasetId == null || datasetId.isBlank()) datasetId = Values.text(target.data(), "datasetId", "");
        List<Map<String, Object>> rows = List.of(Map.of()); boolean ddt = datasetId != null && !datasetId.isBlank();
        if (ddt) {
            Asset dataset = graph.get(datasetId); if (dataset.type() != AssetType.DATASET) throw Problem.invalid("数据集 ID 类型无效");
            rows = Values.objects(dataset.data().get("rows"));
        }
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> variables = new LinkedHashMap<>(); variables.putAll(rows.get(index));
            if (ddt) { variables.put("row", rows.get(index)); variables.put("rowIndex", index + 1); }
            variables.put("__planOverrides", overrides);
            result.add(new RunDefinition.Item(Ids.newId(), target.id(), target.name(), target.type(), result.size(), ddt ? index : null, variables, mode));
        }
    }
    private String nonempty(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
