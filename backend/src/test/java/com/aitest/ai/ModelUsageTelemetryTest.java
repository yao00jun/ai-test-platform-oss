package com.aitest.ai;

import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ModelUsageTelemetryTest {
    @Test void nonStreamingFallbackRetainsWireUsageAndCountsBothHttpAttempts() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes(); boolean first = requests.incrementAndGet() == 1;
            String body = first ? "{\"error\":{\"param\":\"stream\",\"message\":\"stream is not supported\"}}"
                    : "{\"id\":\"fixture\",\"created\":1,\"object\":\"chat.completion\",\"model\":\"actual-response-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":3,\"total_tokens\":11}}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(first ? 400 : 200, bytes.length);
            try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        }); server.start();
        try {
            var telemetry = new ModelCallTelemetry();
            assertThat(new CompanyModelGateway().complete(new ModelSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture-key", "requested", 0.1, 15, "1"), "正文", "开始", token -> { }, () -> { }, telemetry)).isEqualTo("OK");
            assertThat(telemetry.usageReported()).isTrue(); assertThat(telemetry.promptTokens()).isEqualTo(8); assertThat(telemetry.completionTokens()).isEqualTo(3); assertThat(telemetry.totalTokens()).isEqualTo(11);
            assertThat(telemetry.httpAttempts()).isEqualTo(2); assertThat(telemetry.responseModel()).isEqualTo("actual-response-model");
        } finally { server.stop(0); }
    }
    @Test void absentWireUsageIsNotTheSdkSynthesizedZeroUsage() throws Exception {
        try (var server = new ModelFixtureServer()) {
            server.enqueue("OK"); var telemetry = new ModelCallTelemetry();
            new CompanyModelGateway().complete(new ModelSettings(server.url(), "fixture-key", "fixture", 0.1, 15, "1"), "正文", "开始", token -> { }, () -> { }, telemetry);
            assertThat(telemetry.usageReported()).isFalse();
            assertThat(telemetry.promptTokens()).isNull(); assertThat(telemetry.completionTokens()).isNull(); assertThat(telemetry.totalTokens()).isNull();
            assertThat(telemetry.httpAttempts()).isEqualTo(1);
        }
    }
    @Test void actualZeroReportRemainsMeasuredAndUsageOnlyChunksAreCountedOnce() throws Exception {
        try (var server = new ModelFixtureServer()) {
            for (int count : new int[]{0, 7}) {
                server.enqueueWithUsage("OK", Map.of("prompt_tokens", count, "completion_tokens", count, "total_tokens", count * 2));
                var telemetry = new ModelCallTelemetry();
                new CompanyModelGateway().complete(new ModelSettings(server.url(), "fixture-key", "fixture", 0.1, 15, "1"), "正文", "开始", token -> { }, () -> { }, telemetry);
                assertThat(telemetry.usageReported()).isTrue();
                assertThat(telemetry.promptTokens()).isEqualTo(count); assertThat(telemetry.completionTokens()).isEqualTo(count); assertThat(telemetry.totalTokens()).isEqualTo(count * 2);
            }
        }
    }
}
