package com.aitest.ai;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/** Lazy, task-scoped Spring AI adapter for standard company OpenAI-compatible gateways. */
@Component
public class CompanyModelGateway {
    /**
     * The pump owns each call's time budget. Transport limits (OkHttp call/read/write, Spring AI's per-request timeout)
     * sit this far above it, so they only catch a wedged socket and never race the pump into reporting a timeout as a
     * dropped connection.
     */
    static final int TRANSPORT_MARGIN_SECONDS = 30;
    private final ModelRequestLimiter limiter;
    /** Keyed by gateway, model and settings version, so saving the settings starts from a clean slate. */
    private final Map<String, ModelResponsePump.Compatibility> compatibility = new ConcurrentHashMap<>();
    public CompanyModelGateway() { this(new ModelRequestLimiter()); }
    CompanyModelGateway(ModelRequestLimiter limiter) { this.limiter = limiter; }

    public String complete(ModelSettings settings, String system, String user, Consumer<String> onToken, Runnable checkpoint) {
        return complete(settings, system, user, onToken, checkpoint, new ModelCallTelemetry());
    }
    public String complete(ModelSettings settings, String system, String user, Consumer<String> onToken, Runnable checkpoint, ModelCallTelemetry telemetry) {
        return complete(settings, system, user, onToken, checkpoint, telemetry, status -> { });
    }
    /** {@code onStatus} hears plain-language waits (e.g. queueing for the provider's request cap) that produce no tokens. */
    public String complete(ModelSettings settings, String system, String user, Consumer<String> onToken, Runnable checkpoint, ModelCallTelemetry telemetry, Consumer<String> onStatus) {
        Duration transportTimeout = Duration.ofSeconds(settings.timeoutSeconds() + TRANSPORT_MARGIN_SECONDS);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(ModelSettings.normalizeBaseUrl(settings.baseUrl())).apiKey(settings.apiKey())
                .model(settings.modelName()).temperature(settings.temperature())
                .timeout(transportTimeout).maxRetries(0).streamUsage(true).build();
        Set<okhttp3.Call> requests = ConcurrentHashMap.newKeySet();
        AtomicBoolean includeUsage = new AtomicBoolean(true);
        SpringAiOpenAiHttpClient.Builder transportBuilder = SpringAiOpenAiHttpClient.builder().timeout(transportTimeout);
        if (settings.trustSelfSigned()) transportBuilder.sslSocketFactory(ModelTls.trustAllSocketFactory()).trustManager(ModelTls.TRUST_ALL).hostnameVerifier(ModelTls.ANY_HOST);
        SpringAiOpenAiHttpClient transport = transportBuilder
                .interceptor(chain -> {
                    var request = usageOptions(chain.request(), includeUsage.get());
                    telemetry.attempted(); requests.add(chain.call());
                    var response = chain.proceed(request);
                    return response.body() == null ? response : response.newBuilder().body(ModelWireUsage.wrap(response.body(), telemetry)).build();
                }).build();
        Runnable abort = () -> {
            // OkHttp removes an asynchronous call from its dispatcher after headers arrive.
            // Retain the task's Call handles so a blocked SSE reader can still be cancelled.
            requests.forEach(okhttp3.Call::cancel);
            transport.getOkHttpClient().dispatcher().cancelAll();
        };
        OpenAIClientImpl client = new OpenAIClientImpl(ClientOptions.builder().httpClient(transport)
                .baseUrl(options.getBaseUrl()).apiKey(settings.apiKey()).maxRetries(0)
                .timeout(transportTimeout).build());
        OpenAiChatModel model = OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async()).options(options).build();
        ModelResponsePump.Admission admission = () -> {
            long waited = limiter.acquire(settings.requestsPerMinute(), checkpoint,
                    pause -> onStatus.accept("模型服务限制每分钟最多 " + settings.requestsPerMinute() + " 次请求，正在排队，约 " + Math.max(1, TimeUnit.NANOSECONDS.toSeconds(pause)) + " 秒后发出"));
            if (waited > 0) onStatus.accept("已排到请求名额，正在调用模型");
            return waited;
        };
        try {
            return ModelResponsePump.complete(model, new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options),
                    onToken, checkpoint, abort, Duration.ofSeconds(settings.timeoutSeconds()), telemetry, () -> includeUsage.set(false), settings.trustSelfSigned(), admission,
                    compatibility.computeIfAbsent(options.getBaseUrl() + "\n" + settings.modelName() + "\n" + settings.version(), key -> new ModelResponsePump.Compatibility()));
        } finally {
            abort.run();
            client.close();
        }
    }
    /** Lists the model identifiers the provider advertises on the OpenAI-compatible GET {baseUrl}/models endpoint. */
    public List<String> listModels(String baseUrl, String apiKey, int timeoutSeconds) { return listModels(baseUrl, apiKey, timeoutSeconds, false); }
    public List<String> listModels(String baseUrl, String apiKey, int timeoutSeconds, boolean trustSelfSigned) { return listModels(baseUrl, apiKey, timeoutSeconds, trustSelfSigned, 0); }
    public List<String> listModels(String baseUrl, String apiKey, int timeoutSeconds, boolean trustSelfSigned, int requestsPerMinute) {
        String url = ModelSettings.normalizeBaseUrl(baseUrl) + "/models";
        // Providers that cap requests per minute usually count the listing too.
        limiter.acquire(requestsPerMinute, () -> { }, pause -> { });
        okhttp3.OkHttpClient.Builder builder = new okhttp3.OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds)).callTimeout(Duration.ofSeconds(timeoutSeconds));
        if (trustSelfSigned) builder.sslSocketFactory(ModelTls.trustAllSocketFactory(), ModelTls.TRUST_ALL).hostnameVerifier(ModelTls.ANY_HOST);
        okhttp3.OkHttpClient client = builder.build();
        okhttp3.Request request = new okhttp3.Request.Builder().url(url).get().header("Authorization", "Bearer " + apiKey).header("Accept", "application/json").build();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (response.code() == 401 || response.code() == 403) throw new com.aitest.common.Problem(502, "MODEL_AUTHENTICATION_FAILED", "模型认证失败，请检查 API Key");
            if (response.code() == 404 || response.code() == 405) throw new com.aitest.common.Problem(502, "MODEL_LIST_UNSUPPORTED", "该服务未提供模型列表接口（/models），请手动填写模型名称");
            if (!response.isSuccessful()) throw new com.aitest.common.Problem(502, "MODEL_REQUEST_FAILED", "模型服务返回 HTTP " + response.code() + "，请检查服务配置");
            okhttp3.ResponseBody body = response.body();
            if (body == null) throw new com.aitest.common.Problem(502, "MODEL_LIST_UNSUPPORTED", "模型列表接口返回空响应，请手动填写模型名称");
            return parseModels(body.source().readString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            org.slf4j.LoggerFactory.getLogger(CompanyModelGateway.class).warn("Model listing failed for {}: {}", url, root.toString());
            String detail = root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
            if (root instanceof javax.net.ssl.SSLException || failure instanceof javax.net.ssl.SSLException) throw new com.aitest.common.Problem(502, "MODEL_REQUEST_FAILED", "获取模型列表时 TLS 握手失败（" + detail + "）。" + ModelResponsePump.tlsHint(trustSelfSigned));
            for (Throwable current = failure; current != null; current = current.getCause())
                if (current instanceof java.io.InterruptedIOException) throw new com.aitest.common.Problem(504, "MODEL_REQUEST_TIMEOUT", "获取模型列表超时（" + timeoutSeconds + " 秒内没有响应），请检查服务地址、代理和网络");
            throw new com.aitest.common.Problem(502, "MODEL_REQUEST_FAILED", "获取模型列表失败（" + detail + "），请检查服务地址、代理和网络");
        } finally {
            client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll();
        }
    }
    /** Accepts the OpenAI shape ({@code data:[{id}]}) and the common variants ({@code models:[...]}, bare arrays, {@code name}). */
    static List<String> parseModels(String payload) {
        Object tree;
        try { tree = new com.aitest.common.JsonCodec().tree(payload); }
        catch (RuntimeException malformed) { throw new com.aitest.common.Problem(502, "MODEL_LIST_UNSUPPORTED", "模型列表接口返回的不是 JSON，请手动填写模型名称"); }
        Object data = tree instanceof java.util.Map<?, ?> map ? (map.containsKey("data") ? map.get("data") : map.get("models")) : tree;
        if (!(data instanceof List<?> items)) throw new com.aitest.common.Problem(502, "MODEL_LIST_UNSUPPORTED", "模型列表接口返回格式无法识别，请手动填写模型名称");
        Set<String> names = new TreeSet<>();
        for (Object item : items) {
            Object id = item instanceof java.util.Map<?, ?> map ? (map.containsKey("id") ? map.get("id") : map.get("name")) : item;
            if (id instanceof String name && !name.isBlank() && name.length() <= 200) names.add(name.strip());
        }
        return new ArrayList<>(names);
    }
    private static okhttp3.Request usageOptions(okhttp3.Request request, boolean enabled) throws java.io.IOException {
        if (request.body() == null) return request;
        var buffer = new okio.Buffer(); request.body().writeTo(buffer);
        var json = new com.aitest.common.JsonCodec(); var body = json.map(buffer.readUtf8());
        if (!body.containsKey("stream_options")) return request;
        // Spring AI 2.0 always serializes StreamOptions, including include_obfuscation=false.
        // Keep the interoperable usage field, and truly omit the object after explicit rejection.
        if (enabled) body.put("stream_options", java.util.Map.of("include_usage", true)); else body.remove("stream_options");
        return request.newBuilder().removeHeader("Content-Length").method(request.method(),
                okhttp3.RequestBody.create(json.write(body), okhttp3.MediaType.get("application/json"))).build();
    }
}
