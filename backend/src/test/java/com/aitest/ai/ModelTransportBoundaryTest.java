package com.aitest.ai;

import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ModelTransportBoundaryTest {
    private final JsonCodec json = new JsonCodec();

    @Test void fallsBackToNonStreamingOnlyWhenTheProviderExplicitlyRejectsStream() throws Exception {
        List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        try (GatewayFixture server = new GatewayFixture(exchange -> {
            Map<String, Object> request = json.map(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            if (Boolean.TRUE.equals(request.get("stream"))) {
                reply(exchange, 400, "application/json", "{\"error\":{\"message\":\"stream is not supported\",\"type\":\"invalid_request_error\",\"param\":\"stream\",\"code\":\"unsupported_parameter\"}}");
            } else {
                reply(exchange, 200, "application/json", "{\"id\":\"fixture\",\"created\":1,\"object\":\"chat.completion\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"兼容成功\"},\"finish_reason\":\"stop\"}]}");
            }
        })) {
            List<String> tokens = new ArrayList<>();
            assertThatCode(() -> assertThat(new CompanyModelGateway().complete(server.settings(), "输出正文", "开始", tokens::add, () -> { })).isEqualTo("兼容成功")).doesNotThrowAnyException();
            assertThat(requests).hasSize(2);
            assertThat(requests.getFirst()).containsEntry("stream", true);
            assertThat(requests.getLast().get("stream")).isNotEqualTo(true);
            assertThat(tokens).containsExactly("兼容成功");
        }
    }

    @Test void retriesRateLimitBeforeAnyContentWithoutDuplicatingTokens() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (GatewayFixture server = new GatewayFixture(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                reply(exchange, 429, "application/json", "{\"error\":{\"message\":\"temporarily busy\",\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"}}");
            } else reply(exchange, 200, "text/event-stream", stream("完成", "stop"));
        })) {
            List<String> tokens = new ArrayList<>();
            assertThatCode(() -> assertThat(new CompanyModelGateway().complete(server.settings(), "输出正文", "开始", tokens::add, () -> { })).isEqualTo("完成")).doesNotThrowAnyException();
            assertThat(requests).hasValue(2);
            assertThat(tokens).containsExactly("完成");
        }
    }

    @Test void cancellationInterruptsAStreamThatProducesNoTokens() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Thread> caller = new AtomicReference<>();
        try (GatewayFixture server = new GatewayFixture(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            try { exchange.getResponseBody().write(stream("延迟内容", "stop").getBytes(StandardCharsets.UTF_8)); }
            finally { exchange.close(); }
        }); ExecutorService client = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Throwable> outcome = client.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    new CompanyModelGateway().complete(server.settings(), "输出正文", "开始", token -> { }, () -> {
                        if (cancelled.get()) throw new CancellationException("test cancellation");
                    });
                    return null;
                } catch (Throwable failure) { return failure; }
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                cancelled.set(true);
                try { assertThat(outcome.get(2, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class); }
                catch (TimeoutException timeout) { fail("Cancellation must finish without waiting for the next model token: " + java.util.Arrays.toString(caller.get().getStackTrace()), timeout); }
            } finally { release.countDown(); }
        }
    }

    @Test void aConnectionDroppedBeforeAnyResponseIsReplayedExactlyOnce() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (GatewayFixture server = new GatewayFixture(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() == 1) { exchange.close(); return; }
            reply(exchange, 200, "text/event-stream", stream("恢复", "stop"));
        })) {
            var telemetry = new ModelCallTelemetry();
            assertThat(new CompanyModelGateway().complete(server.settings(), "输出正文", "开始", token -> { }, () -> { }, telemetry)).isEqualTo("恢复");
            assertThat(requests).hasValue(2);
            assertThat(telemetry.httpAttempts()).isEqualTo(2);
        }
    }

    @Test void aConnectionThatKeepsDroppingFailsWithAnActionableMessage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (GatewayFixture server = new GatewayFixture(exchange -> { exchange.getRequestBody().readAllBytes(); requests.incrementAndGet(); exchange.close(); })) {
            assertThatThrownBy(() -> new CompanyModelGateway().complete(server.settings(), "输出正文", "开始", token -> { }, () -> { }))
                    .isInstanceOfSatisfying(Problem.class, problem -> { assertThat(problem.code()).isEqualTo("MODEL_REQUEST_FAILED"); assertThat(problem.getMessage()).contains("连接中断"); });
            assertThat(requests).hasValue(2);
        }
    }

    @Test void anErrorObjectEmbeddedInASuccessfulStreamIsRetriedOnceAndThenExplained() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (GatewayFixture server = new GatewayFixture(exchange -> {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
            reply(exchange, 200, "text/event-stream", "data: {\"error\":{\"message\":\"upstream channel busy, please retry\",\"type\":\"relay_error\"}}\n\n");
        })) {
            assertThatThrownBy(() -> new CompanyModelGateway().complete(server.settings(), "输出正文", "开始", token -> { }, () -> { }))
                    .isInstanceOfSatisfying(Problem.class, problem -> { assertThat(problem.code()).isEqualTo("MODEL_REQUEST_FAILED"); assertThat(problem.getMessage()).contains("流式响应").contains("upstream channel busy"); });
            assertThat(requests).hasValue(2);
        }
    }

    @Test void truncatedStreamCannotBecomeAValidDraftOrTriggerAnotherGeneration() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (GatewayFixture server = new GatewayFixture(exchange -> {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
            reply(exchange, 200, "text/event-stream", stream("未完成", null));
        })) {
            assertThatThrownBy(() -> new CompanyModelGateway().complete(server.settings(), "输出正文", "开始", token -> { }, () -> { }))
                    .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.code()).isEqualTo("MODEL_OUTPUT_TRUNCATED"));
            assertThat(requests).hasValue(1);
        }
    }

    @Test void otherInvalidRequestsDoNotTriggerCompatibilityFallback() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (GatewayFixture server = new GatewayFixture(exchange -> {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
            reply(exchange, 400, "application/json", "{\"error\":{\"message\":\"model does not exist; secret-private-message\",\"type\":\"invalid_request_error\",\"param\":\"model\",\"code\":\"invalid_model\"}}");
        })) {
            assertThatThrownBy(() -> new CompanyModelGateway().complete(server.settings(), "输出正文", "开始", token -> { }, () -> { }))
                    .isInstanceOf(Problem.class).hasMessageNotContaining("secret-private-message");
            assertThat(requests).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"message\":\"Unsupported model 'upstream-small'\",\"param\":\"model\",\"type\":\"invalid_request_error\"}",
            "{\"message\":\"Streaming is not supported for this model\",\"param\":\"model\"}",
            "{\"message\":\"Unsupported model 'upstream-small'\",\"type\":\"invalid_request_error\"}",
            "{\"message\":\"Unsupported argument\",\"type\":\"upstream_error\",\"request\":{\"stream\":true}}"
    })
    void unrelatedStructuredErrorsNeverReplayEvenWhenTheyContainStreamText(String error) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (GatewayFixture server = new GatewayFixture(exchange -> {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
            reply(exchange, 400, "application/json", "{\"error\":" + error + "}");
        })) {
            assertThatThrownBy(() -> new CompanyModelGateway().complete(server.settings(), "输出正文", "开始", token -> { }, () -> { }))
                    .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.code()).isEqualTo("MODEL_REQUEST_FAILED"));
            assertThat(requests).as("An unrelated provider rejection must consume exactly one request").hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Model 'stream' is not supported", "Model \"stream\" is not supported", "Model stream is not supported", "Model 'streaming' is not supported"})
    void missingParamModelNamesDoNotAuthorizeCompatibilityReplay(String message) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (GatewayFixture server = new GatewayFixture(exchange -> {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
            reply(exchange, 400, "application/json", json.write(Map.of("error", Map.of("message", message, "type", "invalid_request_error"))));
        })) {
            assertThatThrownBy(() -> new CompanyModelGateway().complete(server.settings(), "输出正文", "开始", token -> { }, () -> { }))
                    .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.code()).isEqualTo("MODEL_REQUEST_FAILED"));
            assertThat(requests).as("An unsupported model identifier must not authorize a replay").hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Streaming is not supported for this model", "stream is not supported", "'stream' is not supported", "Unsupported parameter: 'stream'", "This model does not support streaming", "Streaming mode is disabled"})
    void explicitStreamingRejectionWithoutAParamStillSupportsCompatibilityFallback(String message) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (GatewayFixture server = new GatewayFixture(exchange -> {
            Map<String, Object> request = json.map(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.incrementAndGet();
            if (Boolean.TRUE.equals(request.get("stream"))) {
                reply(exchange, 422, "application/json", json.write(Map.of("error", Map.of("message", message, "type", "invalid_request_error"))));
            } else reply(exchange, 200, "application/json", "{\"id\":\"fixture\",\"created\":1,\"object\":\"chat.completion\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"兼容成功\"},\"finish_reason\":\"stop\"}]}");
        })) {
            assertThat(new CompanyModelGateway().complete(server.settings(), "输出正文", "开始", token -> { }, () -> { })).isEqualTo("兼容成功");
            assertThat(requests).hasValue(2);
        }
    }

    private static String stream(String content, String finishReason) {
        return "data: {\"id\":\"fixture\",\"created\":1,\"object\":\"chat.completion.chunk\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":" + (finishReason == null ? "null" : "\"" + finishReason + "\"") + "}]}\n\ndata: [DONE]\n\n";
    }

    private static void reply(HttpExchange exchange, int status, String mediaType, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", mediaType);
        exchange.sendResponseHeaders(status, bytes.length);
        try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
    }

    private static final class GatewayFixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        GatewayFixture(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1/chat/completions", handler);
            server.start();
        }
        ModelSettings settings() { return new ModelSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture-key", "fixture", 0.2, 20, "1"); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
