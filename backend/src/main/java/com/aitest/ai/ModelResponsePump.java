package com.aitest.ai;

import com.aitest.common.Problem;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.errors.OpenAIServiceException;
import org.reactivestreams.Subscription;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.BaseSubscriber;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Delivers tokens on the caller's thread, with cancellation even during a silent connection. */
final class ModelResponsePump {
    private static final String STREAM_FEATURE = "(?:stream(?:ing)?|['\"`]stream(?:ing)?['\"`])";
    private static final Pattern STREAM_REJECTION = Pattern.compile(
            "^(?:(?:(?:the\\s+)?(?:parameter|argument)\\s+)?" + STREAM_FEATURE
                    + "(?:\\s+(?:mode|responses?))?\\s+(?:(?:is|are)\\s+)?(?:not\\s+supported|unsupported|disabled)\\b"
                    + "|(?:(?:this|the)\\s+)?(?:model|endpoint|api|server|deployment)\\s+(?:does\\s+not|doesn't|cannot)\\s+support\\s+" + STREAM_FEATURE + "(?=\\s|[.,;:!?]|$)"
                    + "|unsupported\\s+(?:parameter|argument|mode)\\s*:?\\s*" + STREAM_FEATURE + "(?=\\s|[.,;:!?]|$))",
            Pattern.CASE_INSENSITIVE);
    private static final String USAGE_FEATURE = "(?:['\"`]?stream_options(?:\\.include_usage)?['\"`]?)";
    private static final Pattern USAGE_REJECTION = Pattern.compile(
            "^(?:(?:(?:the\\s+)?(?:parameter|argument)\\s+)?" + USAGE_FEATURE
                    + "\\s+(?:(?:is|are)\\s+)?(?:not\\s+supported|unsupported|disabled)\\b"
                    + "|unsupported\\s+(?:parameter|argument)\\s*:?\\s*" + USAGE_FEATURE + "(?=\\s|[.,;:!?]|$))", Pattern.CASE_INSENSITIVE);
    private ModelResponsePump() { }

    static String complete(OpenAiChatModel model, Prompt prompt, Consumer<String> onToken, Runnable checkpoint, Runnable abort, Duration timeout, ModelCallTelemetry telemetry, Runnable disableUsage) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Output output = new Output(onToken, telemetry);
        boolean streaming = true;
        boolean usageRequested = true;
        int rateLimitRetries = 0;
        while (true) {
            check(checkpoint, deadline);
            try {
                if (streaming) stream(model, prompt, output, checkpoint, abort, deadline);
                else call(model, prompt, output, checkpoint, abort, deadline);
                check(checkpoint, deadline);
                return output.complete();
            } catch (Problem | CancellationException error) { throw error; }
            catch (RuntimeException error) {
                OpenAIServiceException service = serviceError(error);
                // A partially delivered generation is never automatically replayed.
                if (output.text.isEmpty() && !telemetry.usageReported() && service != null) {
                    if (streaming && usageRequested && rejectsUsage(service)) {
                        prompt = withoutUsage(prompt); disableUsage.run(); usageRequested = false; continue;
                    }
                    if (streaming && rejectsStreaming(service)) { streaming = false; prompt = withoutUsage(prompt); disableUsage.run(); continue; }
                    if (service.statusCode() == 429 && rateLimitRetries < 2) {
                        waitForRetry(retryDelay(service, ++rateLimitRetries), checkpoint, deadline);
                        continue;
                    }
                }
                throw requestFailure(error);
            }
        }
    }

    private static Prompt withoutUsage(Prompt prompt) {
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options)) throw new IllegalStateException("Missing company model options");
        return new Prompt(prompt.getInstructions(), options.mutate().streamUsage(false).build());
    }

    private static boolean rejectsUsage(OpenAIServiceException error) {
        if (error.statusCode() != 400 && error.statusCode() != 422) return false;
        var parameter = error.param().map(String::strip).filter(value -> !value.isEmpty());
        if (parameter.isPresent()) return List.of("stream_options", "stream_options.include_usage").contains(parameter.get());
        try { return USAGE_REJECTION.matcher(error.body().convert(JsonNode.class).path("message").asText("").strip()).find(); }
        catch (RuntimeException malformedBody) { return false; }
    }

    private static void stream(OpenAiChatModel model, Prompt prompt, Output output, Runnable checkpoint, Runnable abort, long deadline) {
        ResponseSubscriber subscriber = new ResponseSubscriber();
        try {
            model.stream(prompt).subscribe(subscriber);
            while (true) {
                check(checkpoint, deadline);
                ChatResponse response;
                try { response = subscriber.responses.poll(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException("模型请求已取消"); }
                if (response != null) { output.accept(response); subscriber.request(1); }
                if (subscriber.terminal && subscriber.responses.isEmpty()) {
                    if (subscriber.error != null) throw runtime(subscriber.error);
                    return;
                }
            }
        } finally { abort.run(); subscriber.cancel(); }
    }

    private static void call(OpenAiChatModel model, Prompt prompt, Output output, Runnable checkpoint, Runnable abort, long deadline) {
        FutureTask<ChatResponse> pending = new FutureTask<>(() -> model.call(prompt));
        Thread.ofVirtual().name("company-model-call").start(pending);
        try {
            while (true) {
                check(checkpoint, deadline);
                try { output.accept(pending.get(100, TimeUnit.MILLISECONDS)); return; }
                catch (TimeoutException waiting) { /* The polling boundary checks durable cancellation. */ }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("模型请求已取消"); }
                catch (ExecutionException error) { throw runtime(error.getCause()); }
            }
        } finally { abort.run(); pending.cancel(true); }
    }

    private static void check(Runnable checkpoint, long deadline) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("模型请求已取消");
        checkpoint.run();
        if (System.nanoTime() >= deadline) throw new Problem(504, "MODEL_REQUEST_TIMEOUT", "模型请求超时，现有资产保持不变");
    }

    private static boolean rejectsStreaming(OpenAIServiceException error) {
        if (error.statusCode() != 400 && error.statusCode() != 422) return false;
        var parameter = error.param().map(String::strip).filter(value -> !value.isEmpty());
        if (parameter.isPresent()) return parameter.get().equals("stream");
        // Only the structured provider message can establish a missing-param rejection.
        // Model names, error types and request echoes must never enable request replay.
        try {
            // The SDK exposes the unwrapped error object as body().
            String message = error.body().convert(JsonNode.class).path("message").asText("");
            // Preserve quoted identifiers and match the statement's subject/object, not
            // an arbitrary substring inside a rejected model name.
            return STREAM_REJECTION.matcher(message.strip()).find();
        } catch (RuntimeException malformedBody) { return false; }
    }

    private static long retryDelay(OpenAIServiceException error, int retry) {
        List<String> headers = error.headers().values("retry-after");
        if (!headers.isEmpty()) {
            String value = headers.getFirst().strip();
            try { return Math.clamp(Math.round(Double.parseDouble(value) * 1000), 0, 600_000); }
            catch (NumberFormatException ignored) {
                try { return Math.clamp(Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis(), 0, 600_000); }
                catch (RuntimeException invalidDate) { /* Fall back to a bounded delay for malformed Retry-After. */ }
            }
        }
        return retry * 500L;
    }

    private static void waitForRetry(long delay, Runnable checkpoint, long deadline) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        while (true) {
            check(checkpoint, deadline);
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return;
            try { TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(100), remaining)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException("模型请求已取消"); }
        }
    }

    private static OpenAIServiceException serviceError(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (current instanceof OpenAIServiceException error) return error;
        return null;
    }

    private static RuntimeException runtime(Throwable error) { return error instanceof RuntimeException runtime ? runtime : new CompletionException(error); }

    private static Problem requestFailure(RuntimeException failure) {
        OpenAIServiceException service = serviceError(failure);
        if (service != null) return switch (service.statusCode()) {
            case 401, 403 -> new Problem(502, "MODEL_AUTHENTICATION_FAILED", "模型认证失败，请检查 API Key");
            case 429 -> new Problem(429, "MODEL_RATE_LIMITED", "模型请求持续受到限流，请稍后重试");
            default -> new Problem(502, "MODEL_REQUEST_FAILED", "模型服务返回 HTTP " + service.statusCode() + "，请检查服务配置");
        };
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        String kind = root.getClass().getSimpleName();
        return new Problem(502, "MODEL_REQUEST_FAILED", kind.contains("Timeout") ? "模型请求超时，现有资产保持不变" : "模型请求失败（" + kind + "），请检查模型服务和网络");
    }

    private static final class ResponseSubscriber extends BaseSubscriber<ChatResponse> {
        private final BlockingQueue<ChatResponse> responses = new ArrayBlockingQueue<>(1);
        private volatile boolean terminal;
        private volatile Throwable error;
        @Override protected void hookOnSubscribe(Subscription subscription) { request(1); }
        @Override protected void hookOnNext(ChatResponse response) { responses.add(response); }
        @Override protected void hookOnComplete() { terminal = true; }
        @Override protected void hookOnError(Throwable failure) { error = failure; terminal = true; }
    }

    private static final class Output {
        private final StringBuilder text = new StringBuilder();
        private final Consumer<String> onToken;
        private final ModelCallTelemetry telemetry;
        private String finishReason;
        private Output(Consumer<String> onToken, ModelCallTelemetry telemetry) { this.onToken = onToken; this.telemetry = telemetry; }
        private void accept(ChatResponse response) {
            telemetry.observe(response);
            var generation = response.getResult();
            if (generation == null) return;
            String token = generation.getOutput().getText();
            if (token != null && !token.isEmpty()) {
                if (text.length() + token.length() > 2_000_000) throw Problem.invalid("模型输出超过 200 万字符限制");
                text.append(token); onToken.accept(token);
            }
            if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null && !generation.getMetadata().getFinishReason().isBlank()) finishReason = generation.getMetadata().getFinishReason();
        }
        private String complete() {
            if (finishReason == null || "length".equalsIgnoreCase(finishReason)) throw new Problem(422, "MODEL_OUTPUT_TRUNCATED", "模型输出不完整，未替换现有资产");
            if (!"stop".equalsIgnoreCase(finishReason)) throw new Problem(422, "MODEL_OUTPUT_REJECTED", "模型未正常完成正文输出，未替换现有资产");
            if (text.isEmpty()) throw new Problem(502, "MODEL_EMPTY_RESPONSE", "模型没有返回有效内容");
            return text.toString();
        }
    }
}
