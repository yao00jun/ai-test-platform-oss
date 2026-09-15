package com.aitest.ai;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/** Lazy, task-scoped Spring AI adapter for standard company OpenAI-compatible gateways. */
@Component
public class CompanyModelGateway {
    public String complete(ModelSettings settings, String system, String user, Consumer<String> onToken, Runnable checkpoint) {
        return complete(settings, system, user, onToken, checkpoint, new ModelCallTelemetry());
    }
    public String complete(ModelSettings settings, String system, String user, Consumer<String> onToken, Runnable checkpoint, ModelCallTelemetry telemetry) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(ModelSettings.normalizeBaseUrl(settings.baseUrl())).apiKey(settings.apiKey())
                .model(settings.modelName()).temperature(settings.temperature())
                .timeout(Duration.ofSeconds(settings.timeoutSeconds())).maxRetries(0).streamUsage(true).build();
        Set<okhttp3.Call> requests = ConcurrentHashMap.newKeySet();
        AtomicBoolean includeUsage = new AtomicBoolean(true);
        SpringAiOpenAiHttpClient transport = SpringAiOpenAiHttpClient.builder().timeout(Duration.ofSeconds(settings.timeoutSeconds()))
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
                .timeout(Duration.ofSeconds(settings.timeoutSeconds())).build());
        OpenAiChatModel model = OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async()).options(options).build();
        try {
            return ModelResponsePump.complete(model, new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options),
                    onToken, checkpoint, abort, Duration.ofSeconds(settings.timeoutSeconds()), telemetry, () -> includeUsage.set(false));
        } finally {
            abort.run();
            client.close();
        }
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
