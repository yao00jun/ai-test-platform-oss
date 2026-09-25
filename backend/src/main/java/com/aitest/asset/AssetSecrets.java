package com.aitest.asset;

import com.aitest.common.JsonCodec;
import com.aitest.common.SecretProtector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AssetSecrets {
    private final SecretProtector secrets;
    private final JsonCodec json;
    public AssetSecrets(SecretProtector secrets, JsonCodec json) { this.secrets = secrets; this.json = json; }

    public static boolean encrypted(AssetType type, FieldDefinition field) {
        return field.kind().equals("password") || (type == AssetType.AUTH_CONFIG && field.key().equals("loginPayload"))
                || (type == AssetType.ENVIRONMENT && List.of("headers", "variables").contains(field.key()));
    }
    public Map<String, Object> encode(AssetType type, Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>(data);
        for (FieldDefinition field : type.fields()) if (encrypted(type, field) && data.get(field.key()) != null) {
            Object value = data.get(field.key());
            result.put(field.key(), secrets.encrypt(field.structured() ? json.write(value) : value.toString()));
        }
        return result;
    }
    public Map<String, Object> decode(AssetType type, Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>(data);
        for (FieldDefinition field : type.fields()) if (encrypted(type, field) && data.get(field.key()) != null) {
            String value = secrets.decrypt(data.get(field.key()).toString());
            result.put(field.key(), field.structured() ? json.tree(value) : value);
        }
        return result;
    }
    public Asset redact(Asset asset) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (FieldDefinition field : asset.type().fields()) {
            Object value = asset.data().get(field.key());
            data.put(field.key(), field.kind().equals("password") ? (value == null || value.toString().isEmpty() ? "" : SecretProtector.MASK) : redactValue(field.key(), value));
        }
        return withData(asset, data);
    }
    public Object redactValue(String key, Object value) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        // A path to a token (tokenJsonPath) is configuration, not a credential; masking it made it impossible to review.
        if (!normalized.endsWith("path") && normalized.matches(".*(password|passwd|authorization|apikey|secret|token|cookie).*")) {
            return value == null || value.toString().isEmpty() ? value : SecretProtector.MASK;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(k.toString(), redactValue(k.toString(), v)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(v -> redactValue("", v)).toList();
        return value;
    }
    public Object preserveMasks(Object patch, Object previous) {
        if (SecretProtector.MASK.equals(patch)) return previous;
        if (patch instanceof Map<?, ?> map) {
            Map<?, ?> old = previous instanceof Map<?, ?> p ? p : Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(k.toString(), preserveMasks(v, old.get(k))));
            return result;
        }
        if (patch instanceof List<?> list) {
            List<?> old = previous instanceof List<?> p ? p : List.of();
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) result.add(preserveMasks(list.get(i), i < old.size() ? old.get(i) : null));
            return result;
        }
        return patch;
    }
    public static Asset withData(Asset a, Map<String, Object> data) {
        return new Asset(a.id(), a.projectId(), a.type(), a.parentId(), a.name(), a.version(), a.position(), a.source(), a.confirmed(), a.createdAt(), a.updatedAt(), data);
    }
}
