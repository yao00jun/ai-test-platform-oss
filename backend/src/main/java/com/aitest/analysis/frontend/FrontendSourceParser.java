package com.aitest.analysis.frontend;

import com.aitest.analysis.SourceDiagnostic;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded literal extraction. Every selector is static evidence requiring runtime verification. */
@Component
public final class FrontendSourceParser {
    private static final Pattern TAG = Pattern.compile("</?([A-Za-z][A-Za-z0-9_.:-]*)");
    private static final Pattern ROUTE = Pattern.compile("\\bpath\\s*:");
    private static final Set<String> VOID = Set.of("input", "img", "br", "hr", "meta", "link", "area", "base", "embed", "param", "source", "track", "wbr");
    private static final Set<String> TARGETS = Set.of("data-testid", "id", "placeholder", "aria-label", "role", "title", "alt");
    public Map<String, List<Map<String, Object>>> parse(String path, String source, List<SourceDiagnostic> diagnostics) {
        List<Map<String, Object>> selectors = new ArrayList<>(), routes = new ArrayList<>(), validations = new ArrayList<>();
        boolean vue = path.toLowerCase(Locale.ROOT).endsWith(".vue"), html = path.toLowerCase(Locale.ROOT).endsWith(".html");
        int[] lines = java.util.stream.IntStream.concat(java.util.stream.IntStream.of(0), java.util.stream.IntStream.range(0, source.length()).filter(i -> source.charAt(i) == '\n').map(i -> i + 1)).toArray();
        int begin = 0, end = source.length();
        if (vue) {
            int[] range = templateRange(source); begin = range[0]; end = range[1];
        }
        int depth = 0, expressionDepth = 0;
        Deque<Boolean> conditions = new ArrayDeque<>();
        for (int offset = begin; offset < end;) {
            if (source.startsWith("<!--", offset)) { int close = source.indexOf("-->", offset + 4); offset = close < 0 ? end : close + 3; continue; }
            char current = source.charAt(offset);
            boolean code = !vue && !html && (depth == 0 || expressionDepth > 0);
            if (code && (current == '\'' || current == '"' || current == '`')) { offset = quotedEnd(source, offset) + 1; continue; }
            if (code && source.startsWith("//", offset)) { int close = source.indexOf('\n', offset); offset = close < 0 ? end : close + 1; continue; }
            if (code && source.startsWith("/*", offset)) { int close = source.indexOf("*/", offset + 2); offset = close < 0 ? end : close + 2; continue; }
            if (!vue && !html && current == '{' && depth > 0) expressionDepth++;
            if (!vue && !html && current == '}' && expressionDepth > 0) expressionDepth--;
            if (current != '<') { offset++; continue; }
            if (source.startsWith("<>", offset)) { depth++; conditions.push(expressionDepth > 0 || !conditions.isEmpty() && conditions.peek()); offset += 2; continue; }
            if (source.startsWith("</>", offset)) { depth = Math.max(0, depth - 1); if (!conditions.isEmpty()) conditions.pop(); offset += 3; continue; }
            var tag = TAG.matcher(source); tag.region(offset, end);
            if (!tag.lookingAt()) { offset++; continue; }
            int close = tagEnd(source, offset); if (close < 0 || close >= end) break;
            boolean closing = source.startsWith("</", offset);
            String name = tag.group(1);
            if (!closing) {
                if (Set.of("script", "style").contains(name.toLowerCase(Locale.ROOT))) { int rawEnd = source.toLowerCase(Locale.ROOT).indexOf("</" + name.toLowerCase(Locale.ROOT), close + 1); offset = rawEnd < 0 ? end : tagEnd(source, rawEnd) + 1; continue; }
                Map<String, String> attributes = attributes(source.substring(tag.end(), close), path, line(lines, offset), diagnostics);
                boolean conditional = attributes.containsKey("v-if") || attributes.containsKey("v-else") || attributes.containsKey("v-else-if") || attributes.containsKey("v-for") || attributes.containsKey("v-show") || expressionDepth > 0 || !conditions.isEmpty() && conditions.peek();
                for (String attribute : TARGETS) {
                    String value = attributes.get(attribute); if (value == null || value.isBlank() || value.length() > 2048) continue;
                    String locator = switch (attribute) {
                        case "data-testid" -> "testId=" + value;
                        case "placeholder", "title", "alt" -> attribute + "=" + value;
                        case "aria-label" -> "label=" + value;
                        case "role" -> "role=" + value + (attributes.containsKey("aria-label") ? "[name=" + attributes.get("aria-label") + "]" : "");
                        default -> "[id=\"" + css(value) + "\"]";
                    };
                    selectors.add(row("selector", locator, "attribute", attribute, "value", value, "tag", name, "sourcePath", path, "line", line(lines, offset), "evidenceLevel", "STATIC", "requiresRuntimeVerification", true, "conditional", conditional));
                }
                for (String field : List.of("required", "min", "max", "minlength", "maxlength", "minLength", "maxLength", "pattern")) if (attributes.containsKey(field))
                    validations.add(row("field", attributes.getOrDefault("name", attributes.getOrDefault("id", name)), "rule", field, "value", attributes.get(field), "sourcePath", path, "line", line(lines, offset)));
                if (!source.substring(offset, close).stripTrailing().endsWith("/") && !VOID.contains(name.toLowerCase(Locale.ROOT))) { depth++; conditions.push(conditional); }
            } else { depth = Math.max(0, depth - 1); if (!conditions.isEmpty()) conditions.pop(); }
            offset = close + 1;
        }
        var route = ROUTE.matcher(codeMask(source));
        while (route.find()) {
            int start = route.end(); while (start < source.length() && Character.isWhitespace(source.charAt(start))) start++;
            if (start >= source.length() || source.charAt(start) != '\'' && source.charAt(start) != '"') continue;
            int close = quotedEnd(source, start);
            String value = source.substring(start + 1, close);
            if (value.startsWith("/") && value.length() <= 512 && !value.contains("\\")) routes.add(row("path", value, "sourcePath", path, "line", line(lines, route.start()), "evidenceLevel", "STATIC"));
        }
        return Map.of("selectors", selectors, "routes", routes, "validations", validations);
    }
    private static int[] templateRange(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        int begin = 0, nesting = 0;
        for (int offset = 0; offset < source.length();) {
            if (source.startsWith("<!--", offset)) { int close = source.indexOf("-->", offset + 4); offset = close < 0 ? source.length() : close + 3; continue; }
            if (source.charAt(offset) != '<') { offset++; continue; }
            var tag = TAG.matcher(source); tag.region(offset, source.length());
            if (!tag.lookingAt()) { offset++; continue; }
            int close = tagEnd(source, offset); if (close < 0) break;
            String name = tag.group(1).toLowerCase(Locale.ROOT);
            boolean closing = source.startsWith("</", offset);
            if (!closing && Set.of("script", "style").contains(name)) {
                int end = lower.indexOf("</" + name, close + 1); if (end < 0) break;
                int rawClose = tagEnd(source, end); if (rawClose < 0) break;
                offset = rawClose + 1; continue;
            }
            if (name.equals("template")) {
                if (closing && nesting > 0 && --nesting == 0) return new int[]{begin, offset};
                if (!closing) { if (nesting == 0) begin = close + 1; nesting++; }
            }
            offset = close + 1;
        }
        return nesting > 0 ? new int[]{begin, source.length()} : new int[]{0, 0};
    }
    /** Mask literals and comments without moving positions, so route-like prose is not code. */
    private static String codeMask(String source) {
        char[] mask = source.toCharArray();
        for (int offset = 0; offset < source.length(); offset++) {
            int end = offset;
            char current = source.charAt(offset);
            if (current == '\'' || current == '"' || current == '`') end = quotedEnd(source, offset);
            else if (source.startsWith("//", offset)) { end = source.indexOf('\n', offset); if (end < 0) end = source.length() - 1; }
            else if (source.startsWith("/*", offset)) { int close = source.indexOf("*/", offset + 2); end = close < 0 ? source.length() - 1 : close + 1; }
            else if (source.startsWith("<!--", offset)) { int close = source.indexOf("-->", offset + 4); end = close < 0 ? source.length() - 1 : close + 2; }
            else continue;
            Arrays.fill(mask, offset, end + 1, ' '); offset = end;
        }
        return new String(mask);
    }
    private Map<String, String> attributes(String source, String path, int line, List<SourceDiagnostic> diagnostics) {
        Map<String, String> result = new LinkedHashMap<>(); int index = 0;
        while (index < source.length()) {
            while (index < source.length() && (Character.isWhitespace(source.charAt(index)) || source.charAt(index) == '/')) index++;
            int start = index;
            while (index < source.length() && !Character.isWhitespace(source.charAt(index)) && source.charAt(index) != '=' && source.charAt(index) != '/') index++;
            if (start == index) { index++; continue; }
            String key = source.substring(start, index);
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
            String value = "true";
            if (index < source.length() && source.charAt(index) == '=') {
                index++; while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
                if (index >= source.length()) break;
                char quote = source.charAt(index); start = index;
                if (quote == '\'' || quote == '"') { index = Math.min(source.length(), quotedEnd(source, index) + 1); value = index - start >= 2 ? source.substring(start + 1, index - 1) : ""; }
                else if (quote == '{') {
                    index = balancedEnd(source, index) + 1;
                    String expression = source.substring(start + 1, Math.min(index - 1, source.length())).strip();
                    if (expression.length() >= 2 && (expression.charAt(0) == '\'' || expression.charAt(0) == '"') && quotedEnd(expression, 0) == expression.length() - 1) value = expression.substring(1, expression.length() - 1);
                    else value = null;
                } else { while (index < source.length() && !Character.isWhitespace(source.charAt(index))) index++; value = source.substring(start, index); }
            }
            String normalized = key.replaceFirst("^(?::|v-bind:)", "");
            boolean dynamic = !normalized.equals(key) || value == null || value.contains("{{") || value.contains("${");
            if (TARGETS.contains(normalized) && dynamic) {
                diagnostics.add(new SourceDiagnostic("WARNING", "DYNAMIC_SELECTOR", "FRONTEND", path, line, "属性 " + normalized + " 来自动态表达式，未猜测为定位器")); continue;
            }
            if (value != null) result.put(key, value.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"));
        }
        return result;
    }
    private static int tagEnd(String source, int begin) {
        for (int index = begin + 1; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '\'' || character == '"') index = quotedEnd(source, index);
            else if (character == '{') index = balancedEnd(source, index);
            else if (character == '>') return index;
        }
        return -1;
    }
    private static int quotedEnd(String source, int begin) {
        char quote = source.charAt(begin);
        for (int index = begin + 1; index < source.length(); index++) {
            if (source.charAt(index) == '\\') index++;
            else if (source.charAt(index) == quote) return index;
        }
        return source.length() - 1;
    }
    private static int balancedEnd(String source, int begin) {
        int depth = 1;
        for (int index = begin + 1; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '\'' || character == '"' || character == '`') index = quotedEnd(source, index);
            else if (character == '{') depth++;
            else if (character == '}' && --depth == 0) return index;
        }
        return source.length() - 1;
    }
    private static int line(int[] starts, int offset) { int position = Arrays.binarySearch(starts, offset); return position >= 0 ? position + 1 : -position - 1; }
    private static String css(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\a ").replace("\r", ""); }
    private static Map<String, Object> row(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) result.put(values[i].toString(), values[i + 1]); return result; }
}
