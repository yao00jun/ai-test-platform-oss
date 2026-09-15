package com.aitest.analysis.impact;

import com.aitest.asset.*;
import com.aitest.execution.Values;
import java.net.URI;
import java.util.*;

/** Maps executable assets through actual endpoint contracts and references, keeping a frozen dependency manifest. */
final class ImpactCandidates {
    private ImpactCandidates() { }
    static Map<String, Object> map(List<Asset> assets, List<Map<String, Object>> endpoints) {
        Map<String, Asset> all = new LinkedHashMap<>(); assets.forEach(asset -> all.put(asset.id(), asset));
        Map<String, Set<String>> reasons = new LinkedHashMap<>(); Set<String> definitions = new LinkedHashSet<>();
        for (Asset asset : assets) if (Set.of(AssetType.API_DEFINITION, AssetType.API_CASE).contains(asset.type())) {
            for (var endpoint : endpoints) if (matches(asset, endpoint)) {
                String reason = endpoint.get("method") + " " + endpoint.get("path") + " ← " + endpoint.get("nodeId");
                reasons.computeIfAbsent(asset.id(), key -> new LinkedHashSet<>()).add(reason);
                if (asset.type() == AssetType.API_DEFINITION) definitions.add(asset.id());
            }
        }
        for (Asset asset : assets) if (asset.type() == AssetType.API_CASE && definitions.contains(Values.text(asset.data(), "apiDefinitionId", "")))
            reasons.computeIfAbsent(asset.id(), key -> new LinkedHashSet<>()).add("引用受影响接口定义 " + asset.data().get("apiDefinitionId"));
        for (Asset step : assets) if (step.type() == AssetType.SCENARIO_STEP && reasons.containsKey(Values.text(step.data(), "targetId", "")))
            reasons.computeIfAbsent(step.parentId(), key -> new LinkedHashSet<>()).add("步骤 " + step.name() + " 引用受影响用例 " + step.data().get("targetId"));
        for (Asset item : assets) if (item.type() == AssetType.PLAN_ITEM && reasons.containsKey(Values.text(item.data(), "targetId", "")))
            reasons.computeIfAbsent(item.id(), key -> new LinkedHashSet<>()).add("计划项引用受影响测试 " + item.data().get("targetId"));
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (var entry : reasons.entrySet()) {
            Asset asset = all.get(entry.getKey());
            if (asset == null || !Set.of(AssetType.API_CASE, AssetType.SCENARIO, AssetType.PLAN_ITEM).contains(asset.type())) continue;
            var candidate = new LinkedHashMap<String, Object>();
            candidate.put("assetId", asset.id()); candidate.put("assetType", asset.type().name()); candidate.put("name", asset.name()); candidate.put("version", asset.version());
            candidate.put("reasons", entry.getValue()); candidate.put("dependencies", dependencies(asset.id(), all));
            if (asset.type() == AssetType.PLAN_ITEM && all.containsKey(asset.parentId())) candidate.put("defaultEnvironmentId", Values.text(all.get(asset.parentId()).data(), "environmentId", ""));
            candidates.add(candidate);
        }
        List<Map<String, Object>> unmapped = endpoints.stream().filter(endpoint -> assets.stream().noneMatch(asset -> Set.of(AssetType.API_CASE, AssetType.API_DEFINITION).contains(asset.type()) && matches(asset, endpoint))).toList();
        return Map.of("candidates", candidates, "mappedDefinitionIds", definitions, "unmappedEndpoints", unmapped);
    }
    static Map<String, Object> dependencies(String id, Map<String, Asset> all) {
        Map<String, String> versions = new TreeMap<>(); Map<String, List<String>> children = new TreeMap<>(); ArrayDeque<String> pending = new ArrayDeque<>(); pending.add(id);
        Asset root = all.get(id);
        if (root != null && root.type() == AssetType.PLAN_ITEM && all.containsKey(root.parentId())) {
            // Capture inherited plan configuration without pulling all unselected plan siblings into the run.
            Asset parent = all.get(root.parentId()); versions.put(parent.id(), parent.version());
            for (String field : List.of("environmentId", "datasetId")) { String ref = Values.text(parent.data(), field, ""); if (!ref.isBlank()) pending.add(ref); }
        }
        Map<String, List<Asset>> byParent = new HashMap<>();
        all.values().forEach(asset -> { if (asset.parentId() != null) byParent.computeIfAbsent(asset.parentId(), key -> new ArrayList<>()).add(asset); });
        while (!pending.isEmpty()) {
            String next = pending.removeFirst(); if (versions.containsKey(next)) continue;
            Asset asset = all.get(next); if (asset == null) { versions.put(next, "MISSING"); continue; }
            versions.put(next, asset.version());
            if (!asset.type().childTypes().isEmpty()) {
                var descendants = byParent.getOrDefault(next, List.of()).stream().map(Asset::id).sorted().toList();
                children.put(next, descendants); pending.addAll(descendants);
            }
            for (String field : AssetReferences.FIELDS) { String reference = Values.text(asset.data(), field, ""); if (!reference.isBlank()) pending.add(reference); }
        }
        return Map.of("versions", versions, "children", children);
    }
    private static boolean matches(Asset asset, Map<String, Object> endpoint) {
        String method = Values.text(asset.data(), "method", "GET");
        return ("ANY".equals(endpoint.get("method")) || method.equals(endpoint.get("method"))) && path(Values.text(asset.data(), "path", "")).equals(path(endpoint.get("path").toString()));
    }
    private static String path(String raw) {
        String value = raw.strip();
        if (value.startsWith("http://") || value.startsWith("https://")) { try { value = URI.create(value.replaceAll("\\{[^}]+}", "_path_parameter_")).getPath().replace("_path_parameter_", "{}"); } catch (IllegalArgumentException ignored) { return value; } }
        int query = value.indexOf('?'); if (query >= 0) value = value.substring(0, query);
        value = value.replaceAll("\\{[^}]*}", "{}").replaceAll("/{2,}", "/");
        if (!value.startsWith("/")) value = "/" + value;
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
