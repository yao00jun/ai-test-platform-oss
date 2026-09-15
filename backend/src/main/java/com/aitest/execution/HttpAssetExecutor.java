package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.engine.http.*;
import com.aitest.storage.FileStorageService;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.util.*;

@Component
public final class HttpAssetExecutor implements AssetExecutor {
    private final HttpStepExecutor http;
    private final GlobalAuthService auth;
    private final FileStorageService files;
    public HttpAssetExecutor(HttpStepExecutor http, GlobalAuthService auth, FileStorageService files) { this.http = http; this.auth = auth; this.files = files; }
    public AssetType type() { return AssetType.API_CASE; }
    public StepResult execute(Asset asset, AssetGraph graph, ExecutionContext context) {
        Asset environment = graph.environment();
        Map<String, Object> headers = new LinkedHashMap<>(); String base = "";
        Map<String, Object> options = Map.of();
        if (environment != null) { headers.putAll(Values.map(environment.data().get("headers"))); base = Values.text(environment.data(), "baseUrl", ""); options = Values.map(environment.data().get("httpOptions")); }
        Map<String, Object> spec = new LinkedHashMap<>(asset.data());
        if (Values.text(spec, "bodyType", "NONE").equals("MULTIPART")) {
            Map<String, Object> parts = new LinkedHashMap<>(Values.map(spec.get("body")));
            for (var entry : List.copyOf(parts.entrySet())) if (entry.getValue() instanceof Map<?, ?> part && part.containsKey("fileId")) {
                var file = files.get(asset.projectId(), part.get("fileId").toString());
                try { parts.put(entry.getKey(), Map.of("filename", file.name(), "contentType", file.mediaType(), "base64", Base64.getEncoder().encodeToString(Files.readAllBytes(file.path())))); }
                catch (java.io.IOException e) { throw Problem.invalid("无法读取 multipart 附件"); }
            }
            spec.put("body", parts);
        }
        Asset authConfig = null; GlobalAuthService.Token token = null;
        if (environment != null && Values.bool(spec, "useGlobalAuth", true)) {
            var configurations = graph.assets().values().stream().filter(item -> item.type() == AssetType.AUTH_CONFIG && Values.bool(item.data(), "enabled", true) && environment.id().equals(item.data().get("environmentId"))).toList();
            if (configurations.size() > 1) throw Problem.invalid("环境存在多份启用的鉴权配置，请保留一份");
            if (!configurations.isEmpty()) {
                authConfig = configurations.getFirst(); token = auth.token(environment, authConfig, context, null);
                headers.put(token.header(), token.headerValue()); context.variables().put("token", token.value());
            }
        }
        StepResult result = http.execute(spec, base, headers, options, context);
        if (token != null && Objects.equals(result.actual().get("status"), 401) && auth.canRetry(spec, authConfig)) {
            token = auth.token(environment, authConfig, context, token.value()); headers.put(token.header(), token.headerValue()); context.variables().put("token", token.value());
            result = http.execute(spec, base, headers, options, context);
        }
        return result;
    }
}
