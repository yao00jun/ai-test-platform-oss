package com.aitest.exchange;

import com.aitest.asset.Asset;
import com.aitest.asset.AssetType;
import com.aitest.common.JsonCodec;
import org.springframework.stereotype.Component;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ApiExportCodec implements ExportCodec {
    private final JsonCodec json;
    public ApiExportCodec(JsonCodec json) { this.json = json; }
    @Override public Set<AssetType> assetTypes() { return Set.of(AssetType.API_CASE, AssetType.API_DEFINITION); }
    @Override public Set<String> formats() { return Set.of("openapi-json", "openapi-yaml", "curl"); }
    @Override public ExportFile export(ExportContext context, String format) {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (Asset asset : context.assets()) if (asset.type() == context.type() && (context.selectedIds().isEmpty() || context.selectedIds().contains(asset.id()))) {
            Map<String, Object> data = new LinkedHashMap<>(asset.data());
            if (asset.type() == AssetType.API_CASE && asset.data().get("apiDefinitionId") instanceof String id) context.assets().stream().filter(a -> a.id().equals(id)).findFirst().ifPresent(def -> { data.put("schema", def.data().get("schema")); data.put("documentVersion", def.data().get("documentVersion")); });
            data.put("name", asset.name()); definitions.add(data);
        }
        return encode(definitions, format, "api");
    }
    public ExportFile encode(List<Map<String, Object>> definitions, String format, String name) {
        if (definitions.isEmpty()) throw ExchangeIO.error("export", 1, "nodes", "没有可导出的接口请求");
        if (format.equals("curl")) return new ExportFile(name + ".curl", "text/plain; charset=utf-8", String.join("\n", definitions.stream().map(this::curl).toList()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> document = new LinkedHashMap<>(); document.put("openapi", "3.0.3"); document.put("info", Map.of("title", "AI Test Platform API", "version", "1.0.0"));
        Map<String, Object> paths = new LinkedHashMap<>(), components = new LinkedHashMap<>(); List<Object> servers = new ArrayList<>();
        for (var definition : definitions) {
            Map<String, Object> schema = object(definition.get("schema")), original = object(schema.get("document"));
            if (!original.isEmpty() && !document.containsKey("x-aitest-source-version")) {
                document.putAll(json.copy(original)); document.put("x-aitest-source-version", schema.getOrDefault("sourceVersion", original.getOrDefault("openapi", "3.0.3")));
            }
            mergeComponents(components, object(original.get("components")));
            if (original.get("servers") instanceof List<?> urls) for (Object server : urls) { if (!servers.contains(server)) servers.add(server); }
            else if (schema.get("serverUrl") instanceof String url && !url.isBlank()) { var server = Map.of("url", url); if (!servers.contains(server)) servers.add(server); }
            String path = definition.get("path").toString(), method = definition.get("method").toString().toLowerCase(java.util.Locale.ROOT);
            if (!path.startsWith("/")) throw ExchangeIO.error("export", 1, "path", "OpenAPI 导出需要以 / 开头的接口路径");
            Map<String, Object> item = object(paths.computeIfAbsent(path, ignored -> new LinkedHashMap<>()));
            if (item.containsKey(method)) throw ExchangeIO.error("export", 1, "path", "多个用例占用相同 method/path，OpenAPI 无法无损表示；请选择单个变体或使用便携 JSON/XLSX");
            item.putAll(object(schema.get("pathItem")));
            var operation = new LinkedHashMap<>(json.copy(object(schema.get("operation")))); operation.put("summary", definition.getOrDefault("name", method + " " + path));
            operation.putIfAbsent("responses", Map.of("200", Map.of("description", "成功响应")));
            if (!operation.containsKey("security") && original.containsKey("security")) operation.put("security", original.get("security"));
            List<Object> parameters = new ArrayList<>(operation.get("parameters") instanceof List<?> list ? list : List.of());
            for (var field : List.of(Map.entry("headers", "header"), Map.entry("queryParams", "query"))) for (var entry : object(definition.get(field.getKey())).entrySet()) {
                if (field.getValue().equals("header") && Set.of("content-type", "authorization", "cookie").contains(entry.getKey().toLowerCase(java.util.Locale.ROOT))) continue;
                Map<String, Object> parameter = parameters.stream().filter(p -> p instanceof Map<?, ?> map && entry.getKey().equals(map.get("name")) && field.getValue().equals(map.get("in"))).map(ApiExportCodec::object).findFirst().orElse(null);
                if (parameter == null) { parameter = new LinkedHashMap<>(Map.of("name", entry.getKey(), "in", field.getValue(), "schema", Map.of("type", entry.getValue() instanceof Number ? "number" : "string"))); parameters.add(parameter); }
                parameter.put("example", entry.getValue());
            }
            if (!parameters.isEmpty()) operation.put("parameters", parameters);
            String bodyType = String.valueOf(definition.getOrDefault("bodyType", "NONE"));
            if (!bodyType.equals("NONE")) {
                String media = header(object(definition.get("headers")), "Content-Type");
                if (media == null) media = switch (bodyType) { case "JSON" -> "application/json"; case "FORM" -> "application/x-www-form-urlencoded"; case "MULTIPART" -> "multipart/form-data"; default -> "text/plain"; };
                Map<String, Object> request = new LinkedHashMap<>(object(operation.get("requestBody"))), content = new LinkedHashMap<>(object(request.get("content"))), shape = new LinkedHashMap<>(object(content.get(media)));
                shape.put("example", definition.get("body")); shape.putIfAbsent("schema", Map.of("type", definition.get("body") instanceof Map<?, ?> ? "object" : definition.get("body") instanceof List<?> ? "array" : "string"));
                content.put(media, shape); request.put("content", content); operation.put("requestBody", request);
            } else operation.remove("requestBody");
            item.put(method, operation); paths.put(path, item);
        }
        document.remove("security"); document.put("paths", paths); if (!components.isEmpty()) document.put("components", components); if (!servers.isEmpty()) document.put("servers", servers);
        boolean yaml = format.equals("openapi-yaml"); byte[] bytes = (yaml ? ExchangeIO.yaml(document, json) : json.write(document)).getBytes(StandardCharsets.UTF_8);
        return new ExportFile(name + ".openapi." + (yaml ? "yaml" : "json"), yaml ? "application/yaml" : "application/json", bytes);
    }
    private void mergeComponents(Map<String, Object> target, Map<String, Object> source) {
        for (var category : source.entrySet()) {
            Map<String, Object> destination = object(target.computeIfAbsent(category.getKey(), ignored -> new LinkedHashMap<>()));
            for (var schema : object(category.getValue()).entrySet()) {
                if (destination.containsKey(schema.getKey()) && !json.write(destination.get(schema.getKey())).equals(json.write(schema.getValue()))) throw ExchangeIO.error("export", 1, "components", "同名 OpenAPI 组件定义冲突，请分批导出不同文档");
                destination.put(schema.getKey(), schema.getValue());
            }
            target.put(category.getKey(), destination);
        }
    }
    private String curl(Map<String, Object> definition) {
        var schema = object(definition.get("schema")); var document = object(schema.get("document")); String base = String.valueOf(schema.getOrDefault("serverUrl", "https://example.invalid"));
        if (document.get("servers") instanceof List<?> servers && !servers.isEmpty()) base = String.valueOf(object(servers.getFirst()).getOrDefault("url", base));
        String path = definition.get("path").toString(); StringBuilder url = new StringBuilder(path.startsWith("http://") || path.startsWith("https://") ? path : base.replaceAll("/$", "") + (path.startsWith("/") ? path : "/" + path));
        for (var query : object(definition.get("queryParams")).entrySet()) for (Object value : repeated(query.getValue())) url.append(url.indexOf("?") < 0 ? '?' : '&').append(encode(query.getKey())).append('=').append(encode(value == null ? "" : value.toString()));
        StringBuilder curl = new StringBuilder("curl -X ").append(definition.get("method")).append(' ').append(quote(url.toString()));
        for (var header : object(definition.get("headers")).entrySet()) for (Object value : repeated(header.getValue())) curl.append(" -H ").append(quote(header.getKey() + ": " + (value == null ? "" : value)));
        String bodyType = String.valueOf(definition.getOrDefault("bodyType", "NONE")); Object body = definition.get("body");
        switch (bodyType) {
            case "JSON" -> curl.append(" --data-raw ").append(quote(json.write(body)));
            case "RAW" -> curl.append(" --data-raw ").append(quote(body == null ? "" : body.toString()));
            case "FORM", "MULTIPART" -> { for (var field : object(body).entrySet()) for (Object value : repeated(field.getValue())) curl.append(bodyType.equals("FORM") ? " --data-urlencode " : " --form-string ").append(quote(field.getKey() + "=" + (value == null ? "" : value))); }
            default -> { }
        }
        return curl.toString();
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static List<?> repeated(Object value) { return value instanceof List<?> list ? list : java.util.Collections.singletonList(value); }
    private static String header(Map<String, Object> values, String name) { return values.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> String.valueOf(e.getValue())).findFirst().orElse(null); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>(); }
}
