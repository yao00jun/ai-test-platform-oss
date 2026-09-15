package com.aitest.ai;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptCatalog {
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    public String load(String name) {
        if (!name.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid prompt name");
        return cache.computeIfAbsent(name, key -> {
            try { return new ClassPathResource("prompts/" + key + ".st").getContentAsString(StandardCharsets.UTF_8); }
            catch (IOException e) { throw new IllegalArgumentException("Prompt not found: " + key, e); }
        });
    }
    public String render(String name, Map<String, String> variables) { return renderText(load(name), variables); }
    public static String renderText(String template, Map<String, String> variables) {
        // One pass: inserted user text is never parsed again as a template or executable syntax.
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?<!\\$)\\{([A-Za-z][A-Za-z0-9_]*)}").matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = variables.get(matcher.group(1));
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(result); return result.toString();
    }
    public String version(String name) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(load(name).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
