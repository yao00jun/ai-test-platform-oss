package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import java.util.*;

public record AssetGraph(String rootId, String environmentId, Map<String, Asset> assets, List<Map<String, Object>> sourceEvidence) {
    public AssetGraph { sourceEvidence = sourceEvidence == null ? List.of() : List.copyOf(sourceEvidence); }
    public AssetGraph(String rootId, String environmentId, Map<String, Asset> assets) { this(rootId, environmentId, assets, List.of()); }
    public Asset get(String id) { Asset asset = assets.get(id); if (asset == null) throw Problem.invalid("运行快照缺少资产: " + id); return asset; }
    public Asset root() { return get(rootId); }
    public Asset environment() { return environmentId == null || environmentId.isBlank() ? null : get(environmentId); }
    public List<Asset> children(String id) {
        return assets.values().stream().filter(asset -> Objects.equals(asset.parentId(), id)).sorted(Comparator.comparingInt(Asset::position).thenComparing(Asset::createdAt).thenComparing(Asset::id)).toList();
    }
    public Map<String, Object> environmentVariables() {
        if (environment() == null) return Map.of();
        Map<String, Object> variables = new LinkedHashMap<>(Values.map(environment().data().get("variables")));
        variables.putIfAbsent("baseUrl", Values.text(environment().data(), "baseUrl", ""));
        variables.putIfAbsent("webUrl", Values.text(environment().data(), "webUrl", "")); return variables;
    }
}
