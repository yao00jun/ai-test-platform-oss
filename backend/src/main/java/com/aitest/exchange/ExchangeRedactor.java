package com.aitest.exchange;

import com.aitest.asset.Asset;
import com.aitest.asset.AssetSecrets;
import com.aitest.common.SecretProtector;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redacts request pair arrays as well as named fields, while retaining JSON Schema/security definitions. */
@Component
public class ExchangeRedactor {
    private static final Set<String> EXAMPLES = Set.of("value", "example", "examples", "default", "const", "enum");
    private static final Set<String> STRUCTURAL = Set.of("type", "method", "bodyType", "documentVersion", "sourceFormat", "sourceVersion", "in", "scheme", "format", "$ref", "openapi", "swagger");
    private static final Pattern QUERY = Pattern.compile("([?&])([^=&#\\s]+)=([^&#\\s]*)");
    private record Scope(Set<String> names, List<String> credentials) {
        boolean sensitiveName(String name) { return sensitive(name) || names.contains(name.toLowerCase(Locale.ROOT)); }
    }

    public Asset asset(Asset asset) { return asset(asset, scope(asset.data())); }
    public List<Asset> assets(List<Asset> assets) {
        Map<String, Asset> byId = new LinkedHashMap<>(); assets.forEach(a -> byId.put(a.id(), a));
        return assets.stream().map(a -> {
            Asset definition = byId.get(a.data().get("apiDefinitionId"));
            return asset(a, scope(a.data(), definition == null ? Map.of() : definition.data()));
        }).toList();
    }
    private Asset asset(Asset asset, Scope scope) {
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) value("", asset.data(), scope);
        for (var field : asset.type().fields()) if (field.kind().equals("password")) data.put(field.key(), mask(asset.data().get(field.key())));
        return AssetSecrets.withData(asset, data);
    }
    public ExchangeBundle bundle(ExchangeBundle bundle) {
        Map<String, ExchangeNode> byKey = new LinkedHashMap<>(); bundle.nodes().forEach(n -> byKey.put(n.key(), n));
        var nodes = bundle.nodes().stream().map(n -> {
            ExchangeNode definition = byKey.get(n.references().get("apiDefinitionId"));
            Scope scope = scope(n.data(), definition == null ? Map.of() : definition.data());
            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) value("", n.data(), scope);
            for (var field : n.type().fields()) if (field.kind().equals("password")) data.put(field.key(), mask(n.data().get(field.key())));
            return n.withData(data);
        }).toList();
        @SuppressWarnings("unchecked") var metadata = (Map<String, Object>) value("", bundle.metadata());
        return new ExchangeBundle(bundle.formatVersion(), metadata, nodes, bundle.externalReferences(), bundle.warnings());
    }
    public Object value(String key, Object value) { return value(key, value, scope(value)); }
    private Object value(String key, Object value, Scope scope) {
        if (scope.sensitiveName(key)) {
            if (value instanceof Map<?, ?> map && isSchema(map)) return sensitiveSchema(map, scope);
            return mask(value);
        }
        if (value instanceof Map<?, ?> map) {
            if (key.equals("auth")) return auth(map, scope);
            Map<String, Object> result = new LinkedHashMap<>();
            boolean sensitivePair = scope.sensitiveName(pairKey(map));
            for (var entry : map.entrySet()) {
                String child = entry.getKey().toString(); Object item = entry.getValue();
                if (sensitivePair && EXAMPLES.contains(child)) result.put(child, maskTree(item));
                else if (sensitivePair && child.equals("schema") && item instanceof Map<?, ?> schema) result.put(child, sensitiveSchema(schema, scope));
                else result.put(child, value(child, item, scope));
            }
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(v -> value("", v, scope)).toList();
        if (value instanceof String text) {
            text = text.replaceAll("(?i)(https?://)[^/\\s:@]+:[^/\\s@]*@", "$1");
            Matcher query = QUERY.matcher(text); StringBuilder safe = new StringBuilder();
            while (query.find()) {
                String name = query.group(2);
                try { name = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8); } catch (IllegalArgumentException ignored) { }
                query.appendReplacement(safe, Matcher.quoteReplacement(scope.sensitiveName(name) ? query.group(1) + query.group(2) + "=" + mask(query.group(3)) : query.group()));
            }
            query.appendTail(safe); text = safe.toString();
            if (!STRUCTURAL.contains(key)) for (String credential : scope.credentials()) {
                if (text.equals(credential)) return SecretProtector.MASK;
                if (credential.length() >= 8) text = text.replace(credential, SecretProtector.MASK);
                else if (Set.of("raw", "text").contains(key) && text.contains(credential)) return SecretProtector.MASK;
            }
            return text;
        }
        return value;
    }
    private Object sensitiveSchema(Map<?, ?> schema, Scope scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        schema.forEach((key, item) -> result.put(key.toString(), EXAMPLES.contains(key.toString()) ? maskTree(item) : value(key.toString(), item, scope)));
        return result;
    }
    private Object auth(Map<?, ?> auth, Scope scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        auth.forEach((key, item) -> {
            String field = key.toString();
            if (Set.of("type", "in", "name", "scheme").contains(field)) result.put(field, item);
            else if (item instanceof List<?> parameters) result.put(field, parameters.stream().map(parameter -> {
                if (!(parameter instanceof Map<?, ?> pair)) return maskTree(parameter);
                Map<String, Object> safe = new LinkedHashMap<>();
                pair.forEach((k, v) -> safe.put(k.toString(), k.equals("value") && !(field.equals("apikey") && Set.of("key", "in").contains(pairKey(pair))) ? maskTree(v) : v));
                return safe;
            }).toList());
            else result.put(field, maskTree(item));
        });
        return result;
    }
    private static Scope scope(Object... roots) {
        Set<String> names = new LinkedHashSet<>(); for (Object root : roots) collectNames(root, names);
        Set<String> credentials = new LinkedHashSet<>(); Scope scope = new Scope(names, List.of());
        for (Object root : roots) collectCredentials("", root, scope, credentials);
        return new Scope(names, credentials.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList());
    }
    private static void collectNames(Object value, Set<String> names) {
        if (value instanceof Map<?, ?> map) {
            if ("apikey".equalsIgnoreCase(String.valueOf(map.get("type")))) {
                if (map.get("name") instanceof String name && !name.isBlank()) names.add(name.toLowerCase(Locale.ROOT));
                if (map.get("apikey") instanceof List<?> list) for (Object parameter : list)
                    if (parameter instanceof Map<?, ?> pair && "key".equals(pair.get("key")) && pair.get("value") instanceof String name && !name.isBlank()) names.add(name.toLowerCase(Locale.ROOT));
            }
            map.values().forEach(item -> collectNames(item, names));
        } else if (value instanceof List<?> list) list.forEach(item -> collectNames(item, names));
    }
    private static void collectCredentials(String key, Object value, Scope scope, Set<String> credentials) {
        if (scope.sensitiveName(key) && value instanceof String text) credential(text, credentials);
        if (value instanceof Map<?, ?> map) {
            if (scope.sensitiveName(pairKey(map))) for (String field : EXAMPLES) if (map.get(field) instanceof String text) credential(text, credentials);
            if (key.equals("auth")) map.forEach((kind, parameters) -> {
                if (parameters instanceof List<?> list) for (Object parameter : list) if (parameter instanceof Map<?, ?> pair
                        && !(kind.equals("apikey") && Set.of("key", "in").contains(pairKey(pair))) && pair.get("value") instanceof String text) credential(text, credentials);
            });
            map.forEach((child, item) -> collectCredentials(child.toString(), item, scope, credentials));
        } else if (value instanceof List<?> list) list.forEach(item -> collectCredentials("", item, scope, credentials));
    }
    private static void credential(String text, Set<String> credentials) { if (!text.equals(SecretProtector.MASK) && !text.equals(mask(text))) credentials.add(text); }
    private static String pairKey(Map<?, ?> map) { return map.get("name") instanceof String name ? name : map.get("key") instanceof String name ? name : ""; }
    private static Object maskTree(Object value) {
        if (value instanceof Map<?, ?> map) { Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key, item) -> result.put(key.toString(), maskTree(item))); return result; }
        if (value instanceof List<?> list) return list.stream().map(ExchangeRedactor::maskTree).toList();
        return mask(value);
    }
    public static boolean sensitive(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").matches(".*(password|passwd|authorization|apikey|secret|token|cookie|webhookurl).*" );
    }
    private static boolean isSchema(Map<?, ?> map) { return map.containsKey("type") || map.containsKey("$ref") || map.containsKey("properties") || map.containsKey("allOf") || map.containsKey("oneOf"); }
    private static Object mask(Object value) { return value == null || value instanceof String text && (text.isEmpty() || text.matches("(?:Bearer )?(?:\\$\\{[^}]+}|\\{\\{[^}]+}})")) ? value : SecretProtector.MASK; }
}
