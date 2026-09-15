package com.aitest.analysis;

import com.aitest.analysis.source.SourceFile;
import com.aitest.common.JsonCodec;
import com.aitest.common.SecretProtector;
import com.aitest.execution.Values;
import org.springframework.stereotype.Component;
import java.util.*;

/** Authenticates server-captured facts across preview, JSON persistence, exchange and later adoption. */
@Component
public final class GenerationEvidenceSeal {
    private final SecretProtector secrets;
    private final JsonCodec json;
    public GenerationEvidenceSeal(SecretProtector secrets, JsonCodec json) { this.secrets = secrets; this.json = json; }

    public Map<String, Object> sign(String project, Map<String, Object> evidence) {
        Map<String, Object> signed = new LinkedHashMap<>(evidence); signed.remove("seal");
        signed.put("seal", secrets.encrypt(digest(project, signed))); return signed;
    }
    public Map<String, Object> verified(String project, String source, Object value) {
        Map<String, Object> evidence = new LinkedHashMap<>(Values.map(value));
        Object seal = evidence.remove("seal");
        if (!(seal instanceof String text) || !source.equals(evidence.get("sourceSnapshotId"))) return Map.of();
        try { return secrets.decrypt(text).equals(digest(project, evidence)) ? evidence : Map.of(); }
        catch (IllegalStateException invalidSeal) { return Map.of(); }
    }
    private String digest(String project, Map<String, Object> evidence) { return project + ":" + SourceFile.hash(json.write(canonical(evidence))); }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) { Map<String, Object> ordered = new TreeMap<>(); map.forEach((key, item) -> ordered.put(key.toString(), canonical(item))); return ordered; }
        if (value instanceof Collection<?> collection) return collection.stream().map(GenerationEvidenceSeal::canonical).toList();
        return value;
    }
}
