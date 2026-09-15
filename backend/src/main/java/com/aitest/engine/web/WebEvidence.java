package com.aitest.engine.web;

import com.aitest.common.JsonCodec;
import com.aitest.common.SecretProtector;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Worker evidence is scrubbed before it crosses the process boundary or becomes an attachment. */
final class WebEvidence {
    private final Set<String> secrets = new LinkedHashSet<>();
    private final JsonCodec json;
    WebEvidence(JsonCodec json, Map<String, Object> variables) { this.json = json; register(variables); }
    void secret(String value) { if (value != null && !value.isEmpty()) secrets.add(value); }
    boolean sensitive(String key) { return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").matches(".*(password|passwd|authorization|apikey|secret|token|cookie).*"); }
    void register(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (sensitive(key.toString()) && item instanceof String text) registerSecret(key.toString(), text);
                register(item);
            });
            if (map.get("name") instanceof String name && sensitive(name) && map.get("value") instanceof String text) registerSecret(name, text);
        } else if (value instanceof List<?> list) list.forEach(this::register);
    }
    private void registerSecret(String name, String value) {
        secret(value);
        if (name.equalsIgnoreCase("authorization") || name.equalsIgnoreCase("proxy-authorization")) {
            var token = java.util.regex.Pattern.compile("(?i)^(?:Bearer|Basic)\\s+(.+)$").matcher(value);
            if (token.matches()) secret(token.group(1));
        }
        if (name.equalsIgnoreCase("cookie")) for (String cookie : value.split(";")) {
            int equals = cookie.indexOf('='); if (equals >= 0) secret(cookie.substring(equals + 1).strip());
        }
    }
    Object scrub(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> output = new LinkedHashMap<>();
            map.forEach((key, item) -> output.put(key.toString(), sensitive(key.toString()) ? SecretProtector.MASK : scrub(item)));
            return output;
        }
        if (value instanceof List<?> list) return list.stream().map(this::scrub).toList();
        if (value instanceof String text) {
            for (String secret : secrets) text = text.replace(secret, SecretProtector.MASK);
            return text.replaceAll("(?i)([?&](?:token|api[-_]?key|password|secret)=)[^&\\s]*", "$1" + SecretProtector.MASK);
        }
        return value;
    }
    void sanitizeTrace(Path source, Path destination) throws IOException {
        long total = 0;
        try (var input = new ZipInputStream(Files.newInputStream(source)); var output = new ZipOutputStream(Files.newOutputStream(destination))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] data = input.readNBytes(32 * 1024 * 1024 + 1); total += data.length;
                if (data.length > 32 * 1024 * 1024 || total > 64 * 1024 * 1024) throw new IOException("Trace size limit exceeded");
                // Trace screenshots/snapshots/sources are disabled. Only structured trace events are retained.
                if (!(entry.getName().endsWith(".trace") || entry.getName().endsWith(".network") || entry.getName().endsWith(".stacks"))) continue;
                StringBuilder sanitized = new StringBuilder();
                for (String line : new String(data, StandardCharsets.UTF_8).split("\\R")) {
                    if (line.isBlank()) continue;
                    sanitized.append(json.write(scrub(json.tree(line)))).append('\n');
                }
                output.putNextEntry(new ZipEntry(entry.getName())); output.write(sanitized.toString().getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            }
        }
        Files.deleteIfExists(source);
    }
}
