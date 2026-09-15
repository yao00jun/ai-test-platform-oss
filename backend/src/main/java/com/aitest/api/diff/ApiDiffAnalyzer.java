package com.aitest.api.diff;

import com.aitest.asset.*;
import com.aitest.common.Ids;
import com.aitest.common.Problem;
import com.aitest.exchange.ExchangeNode;

import java.util.*;
import java.util.function.Function;

/** Matches declared identities without guessing, and compares operation-local schema evidence. */
final class ApiDiffAnalyzer {
    private ApiDiffAnalyzer() { }
    record Candidate(String key, String name, String parentId, Map<String, Object> data) { }
    record Item(String id, String kind, String match, String importKey, String definitionId, Asset before,
                Candidate candidate, List<String> matchCandidates) { }
    record FieldChange(String path, String kind, Object before, Object after) { }
    record Link(String fromId, String toId, String field) { }
    record Impact(List<String> assetIds, List<Link> links) { }

    static List<Item> match(List<Asset> definitions, List<ExchangeNode> incoming, String parentId, Map<String, String> mappings) {
        Map<String, Asset> byId = new LinkedHashMap<>(); definitions.forEach(a -> byId.put(a.id(), a));
        Map<String, ExchangeNode> byKey = new LinkedHashMap<>(); incoming.forEach(n -> byKey.put(n.key(), n));
        Map<String, Asset> matched = new LinkedHashMap<>(); Map<String, String> reasons = new HashMap<>();
        Map<String, List<String>> ambiguous = new LinkedHashMap<>(); Set<String> used = new HashSet<>();
        for (var mapping : mappings.entrySet()) {
            if (!byKey.containsKey(mapping.getKey())) throw Problem.invalid("映射包含未导入的接口 key");
            Asset target = byId.get(mapping.getValue());
            if (target == null) throw Problem.missing();
            if (!used.add(target.id())) throw Problem.invalid("多个导入接口不能映射到同一现有定义");
            matched.put(mapping.getKey(), target); reasons.put(mapping.getKey(), "EXPLICIT");
        }
        matchPass(definitions, incoming, matched, reasons, ambiguous, used, ApiDiffAnalyzer::operationId, "OPERATION_ID");
        matchPass(definitions, incoming, matched, reasons, ambiguous, used, ApiDiffAnalyzer::endpoint, "METHOD_PATH");
        List<Item> result = new ArrayList<>(); Set<String> uncertain = new HashSet<>();
        for (ExchangeNode node : incoming) {
            Asset previous = matched.get(node.key());
            Candidate candidate = new Candidate(node.key(), node.name(), previous == null ? parentId : previous.parentId(), node.data());
            List<String> choices = ambiguous.getOrDefault(node.key(), List.of()); uncertain.addAll(choices);
            String kind = previous != null ? fields(previous.name(), previous.data(), node.name(), node.data()).isEmpty() ? "UNCHANGED" : "CHANGED"
                    : choices.isEmpty() ? "ADDED" : "AMBIGUOUS";
            result.add(new Item(Ids.newId(), kind, reasons.getOrDefault(node.key(), "NONE"), node.key(), previous == null ? null : previous.id(), previous, candidate, choices));
        }
        for (Asset definition : definitions) if (!used.contains(definition.id()) && !uncertain.contains(definition.id()))
            result.add(new Item(Ids.newId(), "REMOVED", "ABSENT", null, definition.id(), definition, null, List.of()));
        return result;
    }
    private static void matchPass(List<Asset> definitions, List<ExchangeNode> incoming, Map<String, Asset> matched,
                                  Map<String, String> reasons, Map<String, List<String>> ambiguous, Set<String> used,
                                  Function<Map<String, Object>, String> identity, String reason) {
        Map<String, List<Asset>> oldGroups = new LinkedHashMap<>(); Map<String, Integer> newCounts = new HashMap<>();
        for (Asset a : definitions) { String key = identity.apply(a.data()); if (!key.isBlank()) oldGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(a); }
        for (ExchangeNode n : incoming) { String key = identity.apply(n.data()); if (!key.isBlank()) newCounts.merge(key, 1, Integer::sum); }
        for (ExchangeNode node : incoming) {
            if (matched.containsKey(node.key()) || ambiguous.containsKey(node.key())) continue;
            String key = identity.apply(node.data()); List<Asset> choices = oldGroups.getOrDefault(key, List.of());
            if (key.isBlank() || choices.isEmpty()) continue;
            if (choices.size() == 1 && newCounts.get(key) == 1 && used.add(choices.getFirst().id())) {
                matched.put(node.key(), choices.getFirst()); reasons.put(node.key(), reason);
            } else ambiguous.put(node.key(), choices.stream().map(Asset::id).toList());
        }
    }
    static String operationId(Map<String, Object> data) { return Objects.toString(map(map(data.get("schema")).get("operation")).get("operationId"), "").strip(); }
    static String endpoint(Map<String, Object> data) { return Objects.toString(data.get("method"), "").toUpperCase(Locale.ROOT) + " " + Objects.toString(data.get("path"), ""); }

    static List<FieldChange> fields(String oldName, Map<String, Object> before, String newName, Map<String, Object> after) {
        Map<String, Object> left = semantic(before), right = semantic(after); left.put("name", oldName); right.put("name", newName);
        List<FieldChange> result = new ArrayList<>(); compare("", left, right, result); return List.copyOf(result);
    }
    private static Map<String, Object> semantic(Map<String, Object> data) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (String key : List.of("method", "path", "headers", "queryParams", "bodyType", "body")) value.put(key, data.get(key));
        Map<String, Object> schema = map(data.get("schema")), operation = map(schema.get("operation")), document = map(schema.get("document")), path = map(schema.get("pathItem"));
        if (!"openapi".equals(schema.get("sourceFormat"))) { value.put("schema", schema); return value; }
        for (String field : List.of("operationId", "summary", "description", "deprecated")) value.put(field, operation.get(field));
        Map<String, Object> parameters = new TreeMap<>();
        for (Map<String, Object> container : List.of(path, operation)) if (container.get("parameters") instanceof List<?> list) for (Object raw : list) {
            Map<String, Object> parameter = map(resolve(raw, document, new HashSet<>(), 0));
            parameters.put(Objects.toString(parameter.get("in"), "") + ":" + Objects.toString(parameter.get("name"), ""), parameter);
        }
        value.put("parameters", parameters);
        value.put("requestBody", resolve(operation.get("requestBody"), document, new HashSet<>(), 0));
        value.put("responses", resolve(operation.get("responses"), document, new HashSet<>(), 0));
        Object security = operation.containsKey("security") ? operation.get("security") : document.get("security");
        value.put("security", security);
        Map<String, Object> schemes = map(map(document.get("components")).get("securitySchemes")), relevant = new TreeMap<>();
        if (security instanceof List<?> list) for (Object requirement : list) for (String name : map(requirement).keySet()) relevant.put(name, resolve(schemes.get(name), document, new HashSet<>(), 0));
        value.put("securitySchemes", relevant);
        value.put("servers", operation.containsKey("servers") ? operation.get("servers") : path.containsKey("servers") ? path.get("servers") : document.get("servers"));
        return value;
    }
    private static Object resolve(Object value, Map<String, Object> document, Set<String> visited, int depth) {
        if (depth > 100) return Map.of("$limit", "schema-depth");
        if (value instanceof Map<?, ?> object) {
            if (object.get("$ref") instanceof String ref && ref.startsWith("#/") && !visited.contains(ref)) {
                Object target = document;
                for (String part : ref.substring(2).split("/")) target = map(target).get(part.replace("~1", "/").replace("~0", "~"));
                if (target != null) {
                    Set<String> next = new HashSet<>(visited); next.add(ref);
                    Map<String, Object> merged = new LinkedHashMap<>(map(resolve(target, document, next, depth + 1)));
                    object.forEach((k, v) -> { if (!k.equals("$ref")) merged.put(k.toString(), resolve(v, document, next, depth + 1)); });
                    return merged;
                }
            }
            Map<String, Object> result = new TreeMap<>();
            object.forEach((k, v) -> result.put(k.toString(), resolve(v, document, new HashSet<>(visited), depth + 1))); return result;
        }
        if (value instanceof List<?> list) return list.stream().map(v -> resolve(v, document, new HashSet<>(visited), depth + 1)).toList();
        return value;
    }
    private static void compare(String path, Object before, Object after, List<FieldChange> result) {
        if (Objects.equals(before, after)) return;
        if (before instanceof Map<?, ?> left && after instanceof Map<?, ?> right) {
            Set<String> keys = new TreeSet<>(); left.keySet().forEach(k -> keys.add(k.toString())); right.keySet().forEach(k -> keys.add(k.toString()));
            for (String key : keys) {
                String child = path + "/" + key.replace("~", "~0").replace("/", "~1");
                if (!left.containsKey(key)) result.add(new FieldChange(child, "ADDED", null, right.get(key)));
                else if (!right.containsKey(key)) result.add(new FieldChange(child, "REMOVED", left.get(key), null));
                else compare(child, left.get(key), right.get(key), result);
            }
        } else result.add(new FieldChange(path, before == null ? "ADDED" : after == null ? "REMOVED" : "CHANGED", before, after));
    }
    static Impact impact(Collection<String> definitions, List<Asset> all) {
        Set<String> roots = new LinkedHashSet<>(definitions), affected = new LinkedHashSet<>(roots);
        List<Link> links = new ArrayList<>();
        for (Asset asset : all) {
            for (String field : AssetReferences.FIELDS) if (asset.data().get(field) instanceof String id && !id.isBlank()) links.add(new Link(asset.id(), id, field));
            if (asset.parentId() != null) {
                if (asset.type() == AssetType.SQL_VALIDATION) links.add(new Link(asset.id(), asset.parentId(), "parentId"));
                if (Set.of(AssetType.SCENARIO_STEP, AssetType.PLAN_ITEM).contains(asset.type())) links.add(new Link(asset.parentId(), asset.id(), "contains"));
            }
        }
        Map<String, List<Link>> incoming = new LinkedHashMap<>(); links.forEach(link -> incoming.computeIfAbsent(link.toId(), ignored -> new ArrayList<>()).add(link));
        ArrayDeque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) for (Link link : incoming.getOrDefault(queue.removeFirst(), List.of())) if (affected.add(link.fromId())) queue.add(link.fromId());
        return new Impact(affected.stream().filter(id -> !roots.contains(id)).toList(), links.stream().filter(link -> affected.contains(link.toId()) && affected.contains(link.fromId())).toList());
    }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }
}
