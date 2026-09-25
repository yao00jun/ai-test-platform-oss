package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import java.util.*;

/** Checks draft configuration before any sibling HTTP/SQL/browser side effect is dispatched. */
public final class ExecutionConfiguration {
    public static List<Map<String, Object>> errors(RunDefinition definition) {
        List<Map<String, Object>> errors = new ArrayList<>(); Set<String> visited = new HashSet<>();
        ArrayDeque<Asset> pending = new ArrayDeque<>();
        for (var item : definition.items()) if (!item.mode().equals("MANUAL") && item.type() != AssetType.FUNCTIONAL_CASE) pending.add(definition.graph().get(item.assetId()));
        while (!pending.isEmpty()) {
            Asset asset = pending.removeFirst(); if (!visited.add(asset.id())) continue;
            if (definition.graph().environment() == null && needsEnvironment(asset, definition.graph()))
                errors.add(gap(asset, "environmentId", "ENVIRONMENT_REQUIRED", "自动化执行需要先选择环境；环境提供 API 基础地址、公共请求头和运行变量"));
            if (asset.type() == AssetType.SQL_VALIDATION && Values.text(asset.data(), "databaseSourceId", "").isBlank())
                errors.add(gap(asset, "databaseSourceId", "DATABASE_SOURCE_REQUIRED", "SQL 草稿尚未绑定业务数据源，未发起任何执行"));
            if (asset.type() == AssetType.UI_SCENARIO && !Values.text(asset.data(), "sourceSnapshotId", "").isBlank()) {
                String environmentBase = definition.graph().environment() == null ? "" : Values.text(definition.graph().environment().data(), "webUrl", "");
                boolean navigation = definition.graph().children(asset.id()).stream().anyMatch(step -> "navigate".equals(step.data().get("action")) && Values.text(step.data(), "url", "").matches("https?://.+"));
                if (Values.text(asset.data(), "baseUrl", "").isBlank() && environmentBase.isBlank() && !navigation)
                    errors.add(gap(asset, "baseUrl", "WEB_BASE_URL_REQUIRED", "源码 UI 草稿尚未配置有效页面地址，未发起任何执行"));
            }
            pending.addAll(definition.graph().children(asset.id()));
            if (asset.type() == AssetType.SCENARIO_STEP && !Values.text(asset.data(), "stepType", "HTTP").equals("WAIT")) pending.add(definition.graph().get(Values.text(asset.data(), "targetId", "")));
        }
        return errors;
    }
    public static void requireReady(RunDefinition definition) {
        var errors = errors(definition); if (!errors.isEmpty()) throw new Problem(422, "EXECUTION_CONFIGURATION_REQUIRED", "测试资产仍有未配置的执行条件", errors);
    }
    public static boolean onlyConfigurationGaps(Map<String, Object> validation) {
        return Values.objects(validation.get("errors")).stream().allMatch(error -> Set.of("ENVIRONMENT_REQUIRED", "DATABASE_SOURCE_REQUIRED", "WEB_BASE_URL_REQUIRED", "RUNTIME_VARIABLE_REQUIRED").contains(Objects.toString(error.get("code"), "")));
    }
    public static Map<String, Object> data(Asset asset) {
        Map<String, Object> data = new LinkedHashMap<>(asset.data()); data.remove("generationEvidence"); return data;
    }
    private static Map<String, Object> gap(Asset asset, String field, String code, String message) { return Map.of("assetId", asset.id(), "name", asset.name(), "field", field, "code", code, "message", message); }
    private static boolean needsEnvironment(Asset asset, AssetGraph graph) {
        if (asset.type() == AssetType.API_CASE) return !Values.text(asset.data(), "path", "").matches("https?://.+");
        if (asset.type() == AssetType.UI_SCENARIO) return Values.text(asset.data(), "baseUrl", "").isBlank()
                && graph.children(asset.id()).stream().noneMatch(step -> "navigate".equals(step.data().get("action")) && Values.text(step.data(), "url", "").matches("https?://.+"));
        // The referenced API/UI asset is also queued and owns the address check. A
        // scenario step itself has no URL and must not create a duplicate gap.
        return false;
    }
    private ExecutionConfiguration() { }
}
