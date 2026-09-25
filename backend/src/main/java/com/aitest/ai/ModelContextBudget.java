package com.aitest.ai;

import com.aitest.asset.Asset;
import com.aitest.common.JsonCodec;
import com.aitest.ai.pipeline.PipelineStageGenerator;
import java.util.*;

/**
 * Bounds what user-triggered AI calls send: the recent part of a conversation, OpenAPI documents cut down to the
 * operation they describe, and a character budget for asset lists (assets beyond it are listed by id and name).
 */
public final class ModelContextBudget {
    static final int HISTORY_MESSAGES = 12, MESSAGE_CHARS = 2_000;
    private ModelContextBudget() { }

    /** Recent turns only; raw model output is truncated and the parsed candidate (a second copy of it) is dropped. */
    public static List<Map<String, Object>> history(List<Map<String, Object>> messages) {
        return messages.stream().skip(Math.max(0, messages.size() - HISTORY_MESSAGES)).map(message -> {
            Map<String, Object> kept = new LinkedHashMap<>();
            for (String key : List.of("id", "role", "status", "createdAt", "baseVersion", "appliedVersion")) if (message.get(key) != null) kept.put(key, message.get(key));
            String content = Objects.toString(message.get("content"), "");
            kept.put("content", content.length() > MESSAGE_CHARS ? content.substring(0, MESSAGE_CHARS) + "…（已截断，原文 " + content.length() + " 字符）" : content);
            if (message.get("validation") instanceof Map<?, ?> validation && validation.get("message") != null) kept.put("validationMessage", validation.get("message"));
            return kept;
        }).toList();
    }

    /** Assets with their (slimmed) data until the budget is spent; the rest by id, type and name so they can still be referenced. */
    public static List<Map<String, Object>> assets(Collection<Asset> assets, int budget, JsonCodec json) {
        List<Map<String, Object>> result = new ArrayList<>(); int remaining = budget;
        for (Asset asset : assets) {
            Map<String, Object> full = slim(json.map(json.write(asset)));
            int length = json.write(full).length();
            if (length <= remaining) { result.add(full); remaining -= length; continue; }
            Map<String, Object> named = new LinkedHashMap<>();
            named.put("id", asset.id()); named.put("type", asset.type()); named.put("name", asset.name()); named.put("version", asset.version()); named.put("dataOmitted", true);
            result.add(named);
        }
        return result;
    }

    /** Replaces every embedded OpenAPI document with the operation, path item and referenced component schemas. */
    @SuppressWarnings("unchecked")
    public static <T> T slim(T tree) {
        if (tree instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(key.toString(), "schema".equals(key) && value instanceof Map<?, ?> schema && schema.containsKey("document") ? PipelineStageGenerator.slimApiSchema(value) : slim(value)));
            return (T) result;
        }
        if (tree instanceof List<?> list) return (T) list.stream().map(ModelContextBudget::slim).toList();
        return tree;
    }
}
