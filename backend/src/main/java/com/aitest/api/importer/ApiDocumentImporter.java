package com.aitest.api.importer;

import com.aitest.asset.AssetType;
import com.aitest.common.JsonCodec;
import com.aitest.exchange.*;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.aitest.exchange.ExchangeIO.*;

@Component
public class ApiDocumentImporter {
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options");
    private final JsonCodec json;
    public ApiDocumentImporter(JsonCodec json) { this.json = json; }

    public ParsedExchange parse(String source, String format, byte[] bytes, AssetType requestedType) {
        List<ExchangeNode> definitions = new ArrayList<>(); List<ExchangeIssue> errors = new ArrayList<>(), warnings = new ArrayList<>();
        try {
            String text = utf8(bytes, source);
            if (format.equals("curl")) {
                for (var request : CurlParser.parse(text, source)) {
                    var schema = new LinkedHashMap<String, Object>(); schema.put("sourceFormat", "curl"); schema.put("options", request.details());
                    definitions.add(requestNode(definitions.size(), request.method(), request.url(), request.headers(), Map.of(), request.bodyType(), request.body(), schema, "", source, request.row(), null));
                }
            } else {
                Map<String, Object> root = map(format.equals("yaml") || format.equals("yml") ? yaml(text, source) : ExchangeIO.json(text, source), source, 1, "$document");
                if (root.containsKey("openapi") || root.containsKey("swagger")) openapi(root, source, definitions, errors, warnings);
                else if (root.containsKey("log")) har(root, source, definitions, errors);
                else if (root.containsKey("info") && root.containsKey("item")) postman(root, source, definitions, errors);
                else throw error(source, 1, "$document", "未识别 Swagger 2、OpenAPI 3、HAR 或 Postman collection");
            }
        } catch (ExchangeException e) { errors.add(e.issue()); }
        catch (RuntimeException e) { errors.add(new ExchangeIssue(source, 1, "$document", "API 文档结构无效，无法安全解析")); }
        if (definitions.isEmpty() && errors.isEmpty()) errors.add(new ExchangeIssue(source, 1, "paths", "文档不包含可导入的接口请求"));
        List<ExchangeNode> nodes = new ArrayList<>(definitions);
        if (requestedType == AssetType.API_CASE) for (var definition : definitions) {
            var data = new LinkedHashMap<>(definition.data()); data.remove("schema"); data.remove("documentVersion");
            nodes.add(new ExchangeNode(definition.key() + "_case", AssetType.API_CASE, null, definition.name(), definition.position(), data, Map.of("apiDefinitionId", definition.key())));
        }
        return new ParsedExchange(new ExchangeBundle(ExchangeBundle.VERSION, Map.of("sourceFormat", format, "normalizedApiDefinitions", definitions.size()), nodes, Map.of(), warnings), errors);
    }

    private void openapi(Map<String, Object> input, String source, List<ExchangeNode> nodes, List<ExchangeIssue> errors, List<ExchangeIssue> warnings) {
        rejectExternalRefs(input, input, source, "$document", 0);
        String version = String.valueOf(input.getOrDefault("openapi", input.getOrDefault("swagger", "")));
        if (!version.startsWith("3.") && !version.equals("2.0")) throw error(source, 1, "openapi", "仅支持 Swagger 2.0 与 OpenAPI 3.x");
        ParseOptions options = new ParseOptions(); options.setResolve(false); options.setResolveFully(false); options.setResolveCombinators(false);
        options.setResolveRequestBody(false); options.setResolveResponses(false); options.setFlatten(false); options.setValidateExternalRefs(false);
        options.setValidateInternalRefs(true); options.setSafelyResolveURL(true); options.setRemoteRefAllowList(List.of()); options.setRemoteRefBlockList(List.of(".*"));
        var result = new OpenAPIParser().readContents(json.write(input), null, options);
        if (result.getOpenAPI() == null) throw error(source, 1, "$document", "Swagger/OpenAPI 规范校验失败，请检查版本、info 和 paths");
        Map<String, Object> document = input;
        if (input.containsKey("swagger")) document = json.map(io.swagger.v3.core.util.Json.pretty(result.getOpenAPI()));
        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            // Parser diagnostics can include uploaded secrets. Expose a safe location and retain the original document privately.
            for (String message : result.getMessages()) {
                String field = message.replaceFirst("^(attribute|components|paths)\\s*", "").split("\\s")[0];
                if (!field.matches("[A-Za-z0-9_./{}$~-]{1,180}")) field = "$document";
                warnings.add(new ExchangeIssue(source, 1, field, "Swagger/OpenAPI 解析器提示此字段可能不完整或不受支持；已保留可识别的接口定义，请核对导入结果"));
            }
        }
        Map<String, Object> info = map(document.get("info"), source, 1, "info");
        String docVersion = string(info.get("version"), source, 1, "info.version");
        var global = new LinkedHashMap<>(document); global.remove("paths");
        Map<String, Object> paths = map(document.get("paths"), source, 1, "paths");
        int row = 0;
        for (var path : paths.entrySet()) {
            row++;
            if (path.getKey().startsWith("x-")) continue;
            Map<String, Object> pathItem = dereference(path.getValue(), document, source, "paths." + path.getKey());
            for (var entry : pathItem.entrySet()) {
                if (!METHODS.contains(entry.getKey())) {
                    if (entry.getKey().equals("trace")) errors.add(new ExchangeIssue(source, row, "paths." + path.getKey() + ".trace", "当前资产执行器不支持 TRACE 方法"));
                    continue;
                }
                try {
                    Map<String, Object> operation = map(entry.getValue(), source, row, "paths." + path.getKey() + "." + entry.getKey());
                    Map<String, Object> headers = new LinkedHashMap<>(), query = new LinkedHashMap<>();
                    Map<String, Map<String, Object>> parameters = new LinkedHashMap<>();
                    for (var container : List.of(pathItem, operation)) {
                        for (Object p : list(container.getOrDefault("parameters", List.of()), source, row, "parameters")) {
                            Map<String, Object> parameter = dereference(p, document, source, "parameters");
                            parameters.put(parameter.get("in") + ":" + parameter.get("name"), parameter);
                        }
                    }
                    for (var parameter : parameters.values()) {
                        String where = string(parameter.get("in"), source, row, "parameters.in");
                        String name = string(parameter.get("name"), source, row, "parameters.name");
                        Object example = parameter.containsKey("example") ? parameter.get("example") : sample(parameter.getOrDefault("schema", Map.of()), document, source, 0, new HashSet<>());
                        if (where.equals("header")) headers.put(name, example == null ? "" : example);
                        if (where.equals("query")) query.put(name, example == null ? "" : example);
                    }
                    String bodyType = "NONE"; Object body = Map.of();
                    if (operation.containsKey("requestBody")) {
                        Map<String, Object> requestBody = dereference(operation.get("requestBody"), document, source, "requestBody");
                        Map<String, Object> content = map(requestBody.get("content"), source, row, "requestBody.content");
                        if (content.isEmpty()) throw error(source, row, "requestBody.content", "请求体必须至少声明一种媒体类型");
                        String media = content.containsKey("application/json") ? "application/json" : content.keySet().iterator().next();
                        Map<String, Object> shape = map(content.get(media), source, row, "requestBody.content");
                        headers.putIfAbsent("Content-Type", media); bodyType = bodyType(media);
                        if (shape.containsKey("example")) body = shape.get("example");
                        else if (shape.get("examples") instanceof Map<?, ?> examples && !examples.isEmpty()) {
                            Map<String, Object> example = dereference(examples.values().iterator().next(), document, source, "examples");
                            if (example.containsKey("externalValue")) throw error(source, row, "examples.externalValue", "不会下载远程示例，请提供内联 value");
                            body = example.get("value");
                        } else body = sample(shape.getOrDefault("schema", Map.of()), document, source, 0, new HashSet<>());
                        if (bodyType.equals("RAW") && !(body instanceof String)) body = body == null ? "" : json.write(body);
                    }
                    var pathMetadata = new LinkedHashMap<>(pathItem); METHODS.forEach(pathMetadata::remove); pathMetadata.remove("trace");
                    var schema = new LinkedHashMap<String, Object>(); schema.put("sourceFormat", "openapi"); schema.put("sourceVersion", version);
                    schema.put("document", global); schema.put("pathItem", pathMetadata); schema.put("operation", operation);
                    String name = String.valueOf(operation.getOrDefault("summary", operation.getOrDefault("operationId", entry.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey())));
                    nodes.add(new ExchangeNode("api_" + (nodes.size() + 1), AssetType.API_DEFINITION, null, name, nodes.size(),
                            core(entry.getKey().toUpperCase(Locale.ROOT), path.getKey(), headers, query, bodyType, body, schema, docVersion), Map.of()));
                    if (nodes.size() > MAX_NODES) throw error(source, row, "paths", "接口数量超过 20000 条");
                } catch (ExchangeException e) { errors.add(e.issue()); }
            }
        }
    }
    private void rejectExternalRefs(Object value, Object root, String source, String path, int depth) {
        if (depth > 100) throw error(source, 1, path, "文档嵌套超过 100 层");
        if (value instanceof Map<?, ?> map) for (var entry : map.entrySet()) {
            String field = path + "." + entry.getKey();
            if (entry.getKey().equals("$ref")) {
                if (!(entry.getValue() instanceof String ref) || !ref.startsWith("#/")) throw error(source, 1, field, "禁止隐式解析远程或本地文件引用；请将依赖内联到文档中");
                pointer(root, ref, source, field);
            } else rejectExternalRefs(entry.getValue(), root, source, field, depth + 1);
        }
        else if (value instanceof List<?> list) for (int i = 0; i < list.size(); i++) rejectExternalRefs(list.get(i), root, source, path + "[" + i + "]", depth + 1);
    }
    private static Object pointer(Object root, String ref, String source, String field) {
        Object current = root;
        for (String part : ref.substring(2).split("/")) {
            String key = part.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map && map.containsKey(key)) current = map.get(key);
            else throw error(source, 1, field, "本地 $ref 的目标不存在");
        }
        return current;
    }
    private static Map<String, Object> dereference(Object value, Map<String, Object> root, String source, String field) {
        Map<String, Object> object = map(value, source, 1, field); Set<String> visited = new HashSet<>();
        while (object.get("$ref") instanceof String ref) {
            if (!ref.startsWith("#/") || !visited.add(ref) || visited.size() > 100) throw error(source, 1, field + ".$ref", "引用循环或不支持的外部引用");
            object = map(pointer(root, ref, source, field), source, 1, field);
        }
        return object;
    }
    private static Object sample(Object input, Map<String, Object> root, String source, int depth, Set<String> refs) {
        if (depth > 20) return null;
        Map<String, Object> shape = map(input, source, 1, "schema");
        if (shape.get("$ref") instanceof String ref) { if (!refs.add(ref)) return null; shape = dereference(shape, root, source, "schema"); }
        if (shape.containsKey("example")) return shape.get("example");
        if (shape.containsKey("default")) return shape.get("default");
        if (shape.containsKey("const")) return shape.get("const");
        if (shape.get("enum") instanceof List<?> values && !values.isEmpty()) return values.getFirst();
        if (shape.get("allOf") instanceof List<?> all) {
            Map<String, Object> combined = new LinkedHashMap<>();
            for (Object part : all) { Object example = sample(part, root, source, depth + 1, new HashSet<>(refs)); if (example instanceof Map<?, ?> map) map.forEach((k, v) -> combined.put(k.toString(), v)); }
            return combined;
        }
        for (String variant : List.of("oneOf", "anyOf")) if (shape.get(variant) instanceof List<?> list && !list.isEmpty()) return sample(list.getFirst(), root, source, depth + 1, refs);
        if (shape.get("properties") instanceof Map<?, ?> properties) {
            Map<String, Object> example = new LinkedHashMap<>();
            for (var property : properties.entrySet()) example.put(property.getKey().toString(), sample(property.getValue(), root, source, depth + 1, new HashSet<>(refs)));
            return example;
        }
        return switch (String.valueOf(shape.getOrDefault("type", "object"))) {
            case "string" -> ""; case "integer", "number" -> 0; case "boolean" -> false; case "array" -> List.of(); case "null" -> null; default -> new LinkedHashMap<>();
        };
    }

    private void har(Map<String, Object> root, String source, List<ExchangeNode> nodes, List<ExchangeIssue> errors) {
        var log = map(root.get("log"), source, 1, "log"); int row = 0;
        for (Object value : list(log.get("entries"), source, 1, "log.entries")) {
            row++;
            try {
                var entry = map(value, source, row, "entries"); var request = map(entry.get("request"), source, row, "request");
                Map<String, Object> headers = pairs(request.getOrDefault("headers", List.of()), "name", source, row);
                Map<String, Object> query = pairs(request.getOrDefault("queryString", List.of()), "name", source, row);
                Map<String, Object> cookies = pairs(request.getOrDefault("cookies", List.of()), "name", source, row);
                if (!cookies.isEmpty() && header(headers, "Cookie") == null) headers.put("Cookie", String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
                String bodyType = "NONE"; Object body = Map.of();
                if (request.containsKey("postData")) {
                    var post = map(request.get("postData"), source, row, "request.postData"); String media = string(post.get("mimeType"), source, row, "mimeType");
                    bodyType = bodyType(media); headers.putIfAbsent("Content-Type", media);
                    if (post.containsKey("text")) body = body(string(post.get("text"), source, row, "postData.text"), bodyType, source, row);
                    else if (post.containsKey("params")) {
                        for (Object item : list(post.get("params"), source, row, "postData.params")) if (map(item, source, row, "params").containsKey("fileName")) throw error(source, row, "postData.params.fileName", "HAR 文件上传没有可移植文件内容，请在导入后绑定受管文件");
                        body = pairs(post.get("params"), "name", source, row);
                    } else throw error(source, row, "postData", "HAR 请求体缺少 text 或 params");
                }
                nodes.add(requestNode(nodes.size(), string(request.get("method"), source, row, "method"), string(request.get("url"), source, row, "url"), headers, query, bodyType, body,
                        new LinkedHashMap<>(Map.of("sourceFormat", "har", "request", request)), String.valueOf(log.getOrDefault("version", "")), source, row, null));
            } catch (ExchangeException e) { errors.add(e.issue()); }
            if (row > MAX_NODES) throw error(source, row, "entries", "HAR 请求超过 20000 条");
        }
    }
    private void postman(Map<String, Object> root, String source, List<ExchangeNode> nodes, List<ExchangeIssue> errors) {
        var info = map(root.get("info"), source, 1, "info");
        String spec = string(info.get("schema"), source, 1, "info.schema");
        if (!spec.contains("/collection/v2.")) throw error(source, 1, "info.schema", "仅支持 Postman Collection 2.0/2.1");
        rejectScripts(root, source, 1);
        walkPostman(list(root.get("item"), source, 1, "item"), root.get("auth"), root.getOrDefault("variable", List.of()), source, nodes, errors, 0);
    }
    private void walkPostman(List<?> items, Object inheritedAuth, Object variables, String source, List<ExchangeNode> nodes, List<ExchangeIssue> errors, int depth) {
        if (depth > 40) throw error(source, 1, "item", "Postman 文件夹超过 40 层");
        for (Object value : items) {
            int row = nodes.size() + errors.size() + 1;
            try {
                var item = map(value, source, row, "item"); rejectScripts(item, source, row); Object auth = item.getOrDefault("auth", inheritedAuth);
                if (item.containsKey("item")) { walkPostman(list(item.get("item"), source, row, "item"), auth, variables, source, nodes, errors, depth + 1); continue; }
                var request = map(item.get("request"), source, row, "request"); auth = request.getOrDefault("auth", auth);
                var headers = pairs(request.getOrDefault("header", List.of()), "key", source, row); Map<String, Object> query = new LinkedHashMap<>();
                String url;
                if (request.get("url") instanceof String text) url = template(text);
                else {
                    var address = map(request.get("url"), source, row, "url"); url = template(string(address.get("raw"), source, row, "url.raw"));
                    query = pairs(address.getOrDefault("query", List.of()), "key", source, row);
                }
                applyPostmanAuth(auth, headers, query, source, row);
                String bodyType = "NONE"; Object body = Map.of();
                if (request.containsKey("body")) {
                    var payload = map(request.get("body"), source, row, "body"); String mode = string(payload.get("mode"), source, row, "body.mode");
                    switch (mode) {
                        case "raw" -> {
                            String media = header(headers, "Content-Type");
                            if (media == null) {
                                var options = payload.get("options") instanceof Map<?, ?> ? map(payload.get("options"), source, row, "body.options") : Map.<String, Object>of();
                                var raw = options.get("raw") instanceof Map<?, ?> ? map(options.get("raw"), source, row, "body.options.raw") : Map.<String, Object>of();
                                media = "json".equals(raw.get("language")) ? "application/json" : "text/plain"; headers.put("Content-Type", media);
                            }
                            bodyType = bodyType(media); body = body(template(string(payload.get("raw"), source, row, "body.raw")), bodyType, source, row);
                        }
                        case "urlencoded", "formdata" -> {
                            var fields = list(payload.get(mode), source, row, "body." + mode);
                            for (Object part : fields) if ("file".equals(map(part, source, row, "body").get("type"))) throw error(source, row, "body.src", "Postman 上传文件需要导入后绑定受管文件，不能读取本地路径");
                            bodyType = mode.equals("urlencoded") ? "FORM" : "MULTIPART"; body = pairs(fields, "key", source, row);
                            if (bodyType.equals("FORM")) headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
                        }
                        default -> throw error(source, row, "body.mode", "不支持此 Postman 请求体模式；支持 raw、urlencoded、formdata");
                    }
                }
                var schema = new LinkedHashMap<String, Object>(); schema.put("sourceFormat", "postman"); schema.put("request", request); schema.put("auth", auth); schema.put("variables", variables);
                nodes.add(requestNode(nodes.size(), string(request.get("method"), source, row, "method"), url, headers, query, bodyType, body, schema, "2.1", source, row, String.valueOf(item.getOrDefault("name", "API 请求"))));
            } catch (ExchangeException e) { errors.add(e.issue()); }
            if (nodes.size() + errors.size() > MAX_NODES) throw error(source, row, "item", "Postman 请求超过 20000 条");
        }
    }
    private static void rejectScripts(Map<String, Object> object, String source, int row) {
        if (object.get("event") instanceof List<?> events && events.stream().anyMatch(ApiDocumentImporter::hasScriptCode))
            throw error(source, row, "event", "Postman 前置/测试脚本不能安全映射；请先移除脚本并显式配置变量/断言");
    }
    private static boolean hasScriptCode(Object value) {
        if (!(value instanceof Map<?, ?> event) || !(event.get("script") instanceof Map<?, ?> script)) return false;
        Object exec = script.get("exec");
        if (exec instanceof String text) return !text.isBlank();
        return exec instanceof List<?> lines && lines.stream().anyMatch(line -> line instanceof String text && !text.isBlank());
    }
    private static void applyPostmanAuth(Object input, Map<String, Object> headers, Map<String, Object> query, String source, int row) {
        if (input == null) return;
        var auth = map(input, source, row, "auth"); String type = string(auth.get("type"), source, row, "auth.type");
        if (type.equals("noauth") || type.equals("inherit")) return;
        var values = pairs(auth.getOrDefault(type, List.of()), "key", source, row);
        switch (type) {
            case "bearer" -> headers.put("Authorization", "Bearer " + values.getOrDefault("token", ""));
            case "basic" -> headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((values.getOrDefault("username", "") + ":" + values.getOrDefault("password", "")).getBytes(StandardCharsets.UTF_8)));
            case "apikey" -> {
                String key = String.valueOf(values.getOrDefault("key", ""));
                if (key.isBlank()) throw error(source, row, "auth.apikey.key", "API key 缺少名称");
                ("query".equals(values.get("in")) ? query : headers).put(key, values.getOrDefault("value", ""));
            }
            default -> throw error(source, row, "auth.type", "此 Postman 鉴权类型无法静态导入；支持 bearer、basic、apikey、noauth");
        }
    }
    private static ExchangeNode requestNode(int index, String method, String url, Map<String, Object> headers, Map<String, Object> suppliedQuery, String bodyType, Object body,
                                            Map<String, Object> schema, String documentVersion, String source, int row, String name) {
        String path; Map<String, Object> query = new LinkedHashMap<>(); String server;
        try {
            String address = template(url);
            if (address.startsWith("${") && address.contains("}/")) { int split = address.indexOf("}/") + 1; server = address.substring(0, split); address = "https://template.invalid" + address.substring(split); }
            else server = "";
            URI uri = URI.create(address);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawFragment() != null) throw new IllegalArgumentException();
            if (uri.getUserInfo() != null) headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(uri.getUserInfo().getBytes(StandardCharsets.UTF_8)));
            if (server.isEmpty()) server = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) query.putAll(form(uri.getRawQuery(), source, row));
        } catch (IllegalArgumentException e) { throw error(source, row, "url", "URL 必须是有效 http/https 地址；不支持片段或未绑定的复杂 URL 表达式"); }
        query.putAll(suppliedQuery); schema.put("serverUrl", server);
        method = method.toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method.toLowerCase(Locale.ROOT))) throw error(source, row, "method", "不支持此 HTTP 方法");
        return new ExchangeNode("api_" + (index + 1), AssetType.API_DEFINITION, null, name == null ? method + " " + path : name, index,
                core(method, path, headers, query, bodyType, body, schema, documentVersion), Map.of());
    }
    private static Map<String, Object> core(String method, String path, Map<String, Object> headers, Map<String, Object> query, String bodyType, Object body, Map<String, Object> schema, String version) {
        var data = new LinkedHashMap<String, Object>(); data.put("method", method); data.put("path", path); data.put("headers", headers); data.put("queryParams", query);
        data.put("bodyType", bodyType); data.put("body", body); data.put("schema", schema); data.put("documentVersion", version); return data;
    }
    static String bodyType(String media) {
        String type = media.toLowerCase(Locale.ROOT).split(";")[0].strip();
        if (type.equals("application/json") || type.endsWith("+json")) return "JSON";
        if (type.equals("application/x-www-form-urlencoded")) return "FORM";
        if (type.equals("multipart/form-data")) return "MULTIPART";
        return "RAW";
    }
    static Object body(String text, String type, String source, int row) {
        try { return switch (type) { case "JSON" -> ExchangeIO.json(text, source); case "FORM" -> form(text, source, row); case "MULTIPART" -> throw error(source, row, "body", "multipart 原始字节无法映射；请提供结构化 params/formdata"); default -> text; }; }
        catch (ExchangeException e) { throw error(source, row, "body", "请求体与声明类型不符，或包含不受支持的文件引用"); }
    }
    static Map<String, Object> form(String text, String source, int row) {
        var result = new LinkedHashMap<String, Object>();
        if (text.isBlank()) return result;
        for (String pair : text.split("&", -1)) {
            int equal = pair.indexOf('=');
            try { add(result, URLDecoder.decode(equal < 0 ? pair : pair.substring(0, equal), StandardCharsets.UTF_8), URLDecoder.decode(equal < 0 ? "" : pair.substring(equal + 1), StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException e) { throw error(source, row, "queryParams", "URL/form 参数转义无效"); }
        }
        return result;
    }
    static String header(Map<String, Object> headers, String name) { return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> String.valueOf(e.getValue())).findFirst().orElse(null); }
    static void add(Map<String, Object> target, String key, Object value) {
        if (!target.containsKey(key)) target.put(key, value);
        else { List<Object> list = new ArrayList<>(); Object old = target.get(key); if (old instanceof List<?> values) list.addAll(values); else list.add(old); list.add(value); target.put(key, list); }
    }
    private static Map<String, Object> pairs(Object input, String keyField, String source, int row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Object entry : list(input, source, row, "parameters")) {
            var pair = map(entry, source, row, "parameters"); if (Boolean.TRUE.equals(pair.get("disabled"))) continue;
            String key = string(pair.get(keyField), source, row, "parameters." + keyField); Object value = pair.getOrDefault("value", "");
            if (value instanceof String text) value = template(text); add(result, key, value);
        }
        return result;
    }
    private static List<?> list(Object value, String source, int row, String field) { if (!(value instanceof List<?> list)) throw error(source, row, field, "此字段必须是数组"); return list; }
    private static String template(String value) { return value.replaceAll("\\{\\{([A-Za-z_][A-Za-z0-9_.-]*)}}", "\\${$1}"); }
}
