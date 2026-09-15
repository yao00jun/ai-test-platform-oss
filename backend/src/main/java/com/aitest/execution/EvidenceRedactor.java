package com.aitest.execution;

import com.aitest.asset.AssetSecrets;
import com.aitest.common.JsonCodec;
import com.aitest.common.SecretProtector;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class EvidenceRedactor {
    private final AssetSecrets assets;
    private final JsonCodec json;
    public EvidenceRedactor(AssetSecrets assets, JsonCodec json) { this.assets = assets; this.json = json; }
    public Object redact(Object input, Map<String, Object> variables) {
        Set<String> secrets = new HashSet<>(); collect(variables, secrets);
        return walk("", input, secrets);
    }
    private void collect(Map<?, ?> values, Set<String> secrets) {
        values.forEach((key, value) -> {
            if (SecretProtector.MASK.equals(assets.redactValue(key.toString(), value)) && value instanceof String text && text.length() >= 4) secrets.add(text);
            if (value instanceof Map<?, ?> map) collect(map, secrets);
        });
    }
    private Object walk(String key, Object input, Set<String> secrets) {
        if (SecretProtector.MASK.equals(assets.redactValue(key, input))) return SecretProtector.MASK;
        if (input instanceof Map<?, ?> map) { Map<String, Object> output = new LinkedHashMap<>(); map.forEach((k, v) -> output.put(k.toString(), walk(k.toString(), v, secrets))); return output; }
        if (input instanceof List<?> list) return list.stream().map(value -> walk("", value, secrets)).toList();
        if (input instanceof String text) {
            String normalized = text.strip();
            if (normalized.startsWith("{") || normalized.startsWith("[")) try { return json.write(walk("", json.tree(text), secrets)); } catch (RuntimeException ignored) { }
            for (String secret : secrets) text = text.replace(secret, SecretProtector.MASK);
            return text.replaceAll("(?i)([?&](?:token|api[-_]?key|password|secret)=)[^&\\s]*", "$1" + SecretProtector.MASK);
        }
        return input;
    }
}
