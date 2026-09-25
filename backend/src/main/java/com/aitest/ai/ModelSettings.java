package com.aitest.ai;

import com.aitest.asset.AssetValidator;
import java.net.URI;

/** {@code timeoutSeconds} bounds one model call end to end; {@code requestsPerMinute} = 0 means the gateway has no request cap. */
public record ModelSettings(String baseUrl, String apiKey, String modelName, double temperature,
                            int timeoutSeconds, String version, boolean trustSelfSigned, int requestsPerMinute) {
    public static final double DEFAULT_TEMPERATURE = 0.3;
    public static final int DEFAULT_TIMEOUT_SECONDS = 600, MIN_TIMEOUT_SECONDS = 5, MAX_TIMEOUT_SECONDS = 3600;
    public static final int MAX_REQUESTS_PER_MINUTE = 10_000;
    public ModelSettings(String baseUrl, String apiKey, String modelName, double temperature, int timeoutSeconds, String version) {
        this(baseUrl, apiKey, modelName, temperature, timeoutSeconds, version, false, 0);
    }
    public ModelSettings(String baseUrl, String apiKey, String modelName, double temperature, int timeoutSeconds, String version, boolean trustSelfSigned) {
        this(baseUrl, apiKey, modelName, temperature, timeoutSeconds, version, trustSelfSigned, 0);
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
