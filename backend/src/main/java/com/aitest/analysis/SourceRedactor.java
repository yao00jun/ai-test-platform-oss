package com.aitest.analysis;

import com.aitest.common.SecretProtector;
import java.util.*;
import java.util.regex.Pattern;

/** Keep literal credentials out of source previews and model contexts, while retaining line numbers. */
public final class SourceRedactor {
    private static final Pattern LITERALS = Pattern.compile("(?i)[\\\"']?\\b[A-Za-z0-9_]*(?:password|passwd|secret|api_?key|access_?token|auth_?token|private_?key)[A-Za-z0-9_]*[\\\"']?\\s*(?:=|:)\\s*([\\\"'])((?:\\\\.|(?!\\1)[^\\\\\\r\\n])*)\\1");
    private SourceRedactor() { }
    public static Set<String> literals(Collection<String> contents) {
        Set<String> values = new HashSet<>();
        for (String content : contents) {
            var matcher = LITERALS.matcher(content);
            while (matcher.find()) if (matcher.group(2).length() >= 4) values.add(matcher.group(2));
        }
        return values;
    }
    public static Object redact(Object value, Set<String> secrets) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(key.toString(), redact(item, secrets)));
            return result;
        }
        if (value instanceof Collection<?> list) return list.stream().map(item -> redact(item, secrets)).toList();
        if (value instanceof String text) {
            for (String secret : secrets) text = text.replace(secret, SecretProtector.MASK);
            return text.replaceAll("(?i)([?&](?:token|api[-_]?key|password|secret)=)[^&\\s]*", "$1" + SecretProtector.MASK);
        }
        return value;
    }
}
