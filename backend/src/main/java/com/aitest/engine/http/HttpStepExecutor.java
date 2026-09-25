package com.aitest.engine.http;

import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.execution.*;
import com.jayway.jsonpath.JsonPath;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

@Component
public final class HttpStepExecutor {
    private final HttpTransport transport;
    private final VariableResolver resolver;
    private final AssertionEvaluator assertions;
    private final JsonCodec json;
    public HttpStepExecutor(HttpTransport transport, VariableResolver resolver, AssertionEvaluator assertions, JsonCodec json) {
        this.transport = transport; this.resolver = resolver; this.assertions = assertions; this.json = json;
    }
    public StepResult execute(Map<String, Object> spec, String baseUrl, Map<String, Object> environmentHeaders,
                              Map<String, Object> options, ExecutionContext context) {
        long started = System.nanoTime(); Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> response = Map.of(); List<AssertionResult> checks = List.of();
        try {
            context.checkpoint();
            String method = Values.text(spec, "method", "GET");
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method)) throw Problem.invalid("不支持的 HTTP 方法");
            String path = resolver.text(spec.get("path"), context.variables());
            String url = path.startsWith("http://") || path.startsWith("https://") ? path : baseUrl.replaceAll("/$", "") + (path.startsWith("/") ? "" : "/") + path;
            Map<String, Object> query = Values.map(resolver.resolve(spec.get("queryParams"), context.variables()));
            String queryText = query(query);
            if (!queryText.isEmpty()) url += (url.contains("?") ? "&" : "?") + queryText;
            Map<String, Object> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); headers.putAll(environmentHeaders);
            headers.putAll(Values.map(spec.get("headers")));
            headers = new LinkedHashMap<>(Values.map(resolver.resolve(headers, context.variables())));
            Object body = resolver.resolve(spec.get("body"), context.variables());
            String bodyType = Values.text(spec, "bodyType", "NONE"); byte[] bytes;
            switch (bodyType) {
                case "NONE" -> bytes = new byte[0];
                case "JSON" -> { bytes = json.write(body).getBytes(StandardCharsets.UTF_8); putHeader(headers, "Content-Type", "application/json; charset=UTF-8"); }
                case "FORM" -> { bytes = query(Values.map(body)).getBytes(StandardCharsets.UTF_8); putHeader(headers, "Content-Type", "application/x-www-form-urlencoded"); }
                case "RAW" -> bytes = Objects.toString(body, "").getBytes(StandardCharsets.UTF_8);
                case "MULTIPART" -> {
                    String boundary = "AITest" + UUID.randomUUID().toString().replace("-", "");
                    bytes = multipart(Values.map(body), boundary); headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
                }
                default -> throw Problem.invalid("不支持的请求体类型");
            }
            if (bytes.length > 32 * 1024 * 1024) throw Problem.invalid("HTTP 请求体超过 32 MB");
            request.put("method", method); request.put("url", url); request.put("headers", headers); request.put("body", body == null ? "" : body);
            response = transport.exchange(method, url, headers, bytes, Values.integer(spec, "timeoutMs", 30000, ExecutionLimits.HTTP_TIMEOUT_MIN_MS, ExecutionLimits.HTTP_TIMEOUT_MAX_MS), options, context);
            checks = assertions.evaluate(Values.objects(spec.get("assertions")), response, context.variables());
            int responseStatus = ((Number) response.get("status")).intValue();
            Map<String, Object> exports = new LinkedHashMap<>();
            boolean extractionAllowed = responseStatus < 400;
            for (var extractor : Values.objects(spec.get("extractors"))) {
                String name = Values.text(extractor, "variable", "");
                if (!name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) throw Problem.invalid("变量提取器需要有效的 variable 名称");
                if (!extractionAllowed) continue;
                Object value;
                String type = Values.text(extractor, "type", "jsonpath");
                if (type.equals("header")) {
                    String header = Values.text(extractor, "path", "");
                    value = Values.map(response.get("headers")).entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(header)).findFirst().map(Map.Entry::getValue).map(v -> v instanceof List<?> l ? l.getFirst() : v).orElse(null);
                } else if (type.equals("jsonpath")) value = JsonPath.read(response.get("body").toString(), Values.text(extractor, "jsonpath", Values.text(extractor, "path", "$")));
                else throw Problem.invalid("不支持的变量提取器类型");
                if (value == null) throw Problem.invalid("提取变量 " + name + " 的结果为空");
                exports.put(name, value);
            }
            if (extractionAllowed) context.publish(exports);
            boolean passed = responseStatus < 400 && checks.stream().allMatch(AssertionResult::passed);
            return new StepResult(passed ? "PASSED" : "FAILED", elapsed(started), request, response, checks, exports, List.of(), passed ? null : "HTTP 响应或断言失败");
        } catch (CancellationException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("HTTP 执行已中断"); }
        catch (HttpTimeoutException e) { return StepResult.error("HTTP 请求超时", elapsed(started), request); }
        catch (Exception e) {
            String message = e instanceof Problem ? e.getMessage() : e instanceof com.jayway.jsonpath.PathNotFoundException ? "变量提取路径不存在" : "HTTP 执行失败（" + e.getClass().getSimpleName() + "）";
            return new StepResult("ERROR", elapsed(started), request, response, checks, Map.of(), List.of(), message);
        }
    }
    private void putHeader(Map<String, Object> headers, String key, String value) {
        if (headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase(key))) headers.put(key, value);
    }
    private String query(Map<String, Object> input) {
        List<String> parts = new ArrayList<>();
        input.forEach((key, value) -> {
            if (value instanceof List<?> list) list.forEach(v -> parts.add(encode(key) + "=" + encode(Objects.toString(v, ""))));
            else parts.add(encode(key) + "=" + encode(Objects.toString(value, "")));
        });
        return String.join("&", parts);
    }
    private byte[] multipart(Map<String, Object> values, String boundary) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        values.forEach((key, value) -> {
            String safeKey = token(key); byte[] content;
            String disposition = "Content-Disposition: form-data; name=\"" + safeKey + "\"";
            String contentType = "";
            if (value instanceof Map<?, ?>) {
                Map<String, Object> part = Values.map(value);
                if (part.containsKey("fileId")) throw Problem.invalid("附件需通过执行上下文解析为文件内容");
                disposition += "; filename=\"" + token(Values.text(part, "filename", "upload.bin")) + "\"";
                contentType = "Content-Type: " + token(Values.text(part, "contentType", "application/octet-stream")) + "\r\n";
                content = Base64.getDecoder().decode(Values.text(part, "base64", ""));
            } else content = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
            output.writeBytes(("--" + boundary + "\r\n" + disposition + "\r\n" + contentType + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.writeBytes(content); output.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        });
        output.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)); return output.toByteArray();
    }
    private String token(String value) { if (value.matches(".*[\r\n\"].*")) throw Problem.invalid("Multipart 字段包含非法字符"); return value; }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
}
