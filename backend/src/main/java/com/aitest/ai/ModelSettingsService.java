package com.aitest.ai;

import com.aitest.common.Problem;
import com.aitest.common.SecretProtector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelSettingsService {
    private final JdbcTemplate jdbc;
    private final SecretProtector secrets;
    private final String fileBaseUrl, fileKey, fileModel;
    public ModelSettingsService(JdbcTemplate jdbc, SecretProtector secrets,
                                @Value("${aitest.model.base-url:}") String baseUrl,
                                @Value("${aitest.model.api-key:}") String apiKey,
                                @Value("${aitest.model.model-name:}") String modelName) {
        this.jdbc = jdbc; this.secrets = secrets; this.fileBaseUrl = baseUrl; this.fileKey = apiKey; this.fileModel = modelName;
    }
    public ModelSettings current() {
        ModelSettings settings = optional();
        if (settings == null || settings.apiKey().isBlank() || settings.modelName().isBlank() || settings.baseUrl().isBlank()) throw new Problem(503, "MODEL_NOT_CONFIGURED", "请在模型设置中配置 base-url、api-key 与 model-name");
        return settings;
    }
    private ModelSettings optional() {
        var rows = jdbc.queryForList("SELECT * FROM ai_model_config WHERE id='company-default'");
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            return new ModelSettings(row.get("base_url").toString(), secrets.decrypt(row.get("api_key").toString()), row.get("model_name").toString(), ((Number) row.get("temperature")).doubleValue(), ((Number) row.get("timeout_seconds")).intValue(), row.get("version").toString(), Boolean.TRUE.equals(row.get("trust_self_signed")) || Integer.valueOf(1).equals(row.get("trust_self_signed")), ((Number) row.get("requests_per_minute")).intValue());
        }
        if (fileBaseUrl.isBlank() || fileKey.isBlank() || fileModel.isBlank()) return null;
        return new ModelSettings(ModelSettings.normalizeBaseUrl(fileBaseUrl), fileKey, fileModel, ModelSettings.DEFAULT_TEMPERATURE, ModelSettings.DEFAULT_TIMEOUT_SECONDS, "file");
    }
    public Map<String, Object> view() {
        ModelSettings current = optional();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseUrl", current == null ? fileBaseUrl : current.baseUrl());
        result.put("modelName", current == null ? fileModel : current.modelName());
        result.put("hasApiKey", current != null && !current.apiKey().isBlank());
        result.put("temperature", current == null ? ModelSettings.DEFAULT_TEMPERATURE : current.temperature());
        result.put("timeoutSeconds", current == null ? ModelSettings.DEFAULT_TIMEOUT_SECONDS : current.timeoutSeconds());
        result.put("requestsPerMinute", current == null ? 0 : current.requestsPerMinute());
        result.put("trustSelfSigned", current != null && current.trustSelfSigned());
        return result;
    }
    /** Resolves the key to use for a request: a blank or masked value means the stored key. */
    public String resolveApiKey(String candidate) {
        if (candidate != null && !candidate.isBlank() && !candidate.equals(SecretProtector.MASK)) return candidate;
        ModelSettings previous = optional();
        return previous == null ? "" : previous.apiKey();
    }
    /** Fetches the provider's advertised models for a base URL and key that may not be saved yet. */
    public List<String> listModels(ModelListRequest request, CompanyModelGateway gateway) {
        String key = resolveApiKey(request.apiKey());
        if (key.isBlank()) throw Problem.invalid("请先填写 API Key 再获取模型列表");
        ModelSettings previous = optional();
        boolean trust = request.trustSelfSigned() != null ? request.trustSelfSigned() : previous != null && previous.trustSelfSigned();
        return gateway.listModels(request.baseUrl(), key, previous == null ? 30 : Math.min(previous.timeoutSeconds(), 60), trust, previous == null ? 0 : previous.requestsPerMinute());
    }
    @Transactional
    public Map<String, Object> save(Input input) {
        ModelSettings previous = optional();
        String key = resolveApiKey(input.apiKey());
        if (key.isBlank() || input.modelName() == null || input.modelName().isBlank()) throw Problem.invalid("模型名称和 API Key 必填");
        String baseUrl = ModelSettings.normalizeBaseUrl(input.baseUrl());
        // Omitted fields keep the saved value, so a client that only edits the address or key never resets the rest.
        double temperature = input.temperature() != null ? input.temperature() : previous != null ? previous.temperature() : ModelSettings.DEFAULT_TEMPERATURE;
        int timeout = input.timeoutSeconds() != null ? input.timeoutSeconds() : previous != null ? previous.timeoutSeconds() : ModelSettings.DEFAULT_TIMEOUT_SECONDS;
        int perMinute = input.requestsPerMinute() != null ? input.requestsPerMinute() : previous != null ? previous.requestsPerMinute() : 0;
        boolean trust = input.trustSelfSigned() != null ? input.trustSelfSigned() : previous != null && previous.trustSelfSigned();
        if (input.modelName().length() > 200 || key.length() > 16000) throw Problem.invalid("模型名称或 API Key 过长");
        if (!(temperature >= 0 && temperature <= 2)) throw Problem.invalid("温度需要在 0–2 之间");
        if (timeout < ModelSettings.MIN_TIMEOUT_SECONDS || timeout > ModelSettings.MAX_TIMEOUT_SECONDS) throw Problem.invalid("单次调用超时需要 " + ModelSettings.MIN_TIMEOUT_SECONDS + "–" + ModelSettings.MAX_TIMEOUT_SECONDS + " 秒");
        if (perMinute < 0 || perMinute > ModelSettings.MAX_REQUESTS_PER_MINUTE) throw Problem.invalid("每分钟最多请求数需要 0–" + ModelSettings.MAX_REQUESTS_PER_MINUTE + "，0 表示不限制");
        jdbc.update("INSERT INTO ai_model_config(id,base_url,api_key,model_name,temperature,timeout_seconds,trust_self_signed,requests_per_minute,updated_at) VALUES('company-default',?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE base_url=VALUES(base_url),api_key=VALUES(api_key),model_name=VALUES(model_name),temperature=VALUES(temperature),timeout_seconds=VALUES(timeout_seconds),trust_self_signed=VALUES(trust_self_signed),requests_per_minute=VALUES(requests_per_minute),version=version+1,updated_at=VALUES(updated_at)", baseUrl, secrets.encrypt(key), input.modelName().strip(), temperature, timeout, trust, perMinute, Timestamp.from(Instant.now()));
        return view();
    }
    public record Input(String baseUrl, String apiKey, String modelName, Double temperature, Integer timeoutSeconds, Boolean trustSelfSigned, Integer requestsPerMinute) {
        public Input(String baseUrl, String apiKey, String modelName, Double temperature, Integer timeoutSeconds) { this(baseUrl, apiKey, modelName, temperature, timeoutSeconds, null, null); }
        public Input(String baseUrl, String apiKey, String modelName, Double temperature, Integer timeoutSeconds, Boolean trustSelfSigned) { this(baseUrl, apiKey, modelName, temperature, timeoutSeconds, trustSelfSigned, null); }
    }
    public record ModelListRequest(String baseUrl, String apiKey, Boolean trustSelfSigned) { }
}
