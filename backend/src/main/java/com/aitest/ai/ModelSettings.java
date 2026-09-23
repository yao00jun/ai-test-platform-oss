package com.aitest.ai;

import com.aitest.asset.AssetValidator;
import java.net.URI;

public record ModelSettings(String baseUrl, String apiKey, String modelName, double temperature,
                            int timeoutSeconds, String version, boolean trustSelfSigned) {
    public ModelSettings(String baseUrl, String apiKey, String modelName, double temperature, int timeoutSeconds, String version) {
        this(baseUrl, apiKey, modelName, temperature, timeoutSeconds, version, false);
    }
    public static String normalizeBaseUrl(String value) {
        AssetValidator.httpUrl(value);
        String result = value.replaceAll("/+$", "");
        URI uri = URI.create(result);
        if (uri.getRawQuery() != null || uri.getFragment() != null) throw com.aitest.common.Problem.invalid("模型 base-url 不应包含 query 或 fragment");
        if (uri.getPath() == null || uri.getPath().isBlank()) result += "/v1";
        return result;
    }
    @Override public String toString() { return "ModelSettings[model=" + modelName + ", version=" + version + ", key=REDACTED]"; }
}
