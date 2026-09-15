package com.aitest.ai;

import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

class ModelUsageProtocolTest {
    private final JsonCodec json = new JsonCodec();

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"param\":\"stream_options\",\"message\":\"Unsupported parameter\"}",
            "{\"param\":\"stream_options.include_usage\",\"message\":\"Unsupported parameter\"}",
            "{\"message\":\"Unsupported parameter: 'stream_options'\"}",
            "{\"message\":\"stream_options is not supported\"}"
    })
    void explicitlyUnsupportedUsageRetriesOnceWithoutDisablingStreaming(String error) throws Exception {
        List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        HttpServer server = server(requests, error);
        try {
            assertThat(new CompanyModelGateway().complete(settings(server), "正文", "开始", token -> { }, () -> { })).isEqualTo("OK");
            assertThat(requests).hasSize(2);
            assertThat(requests.getFirst()).containsEntry("stream", true);
            assertThat(requests.getFirst().get("stream_options")).isEqualTo(Map.of("include_usage", true));
            assertThat(requests.getLast()).containsEntry("stream", true).doesNotContainKey("stream_options");
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"param\":\"model\",\"message\":\"stream_options is not supported\"}",
            "{\"message\":\"Model 'stream_options' is not supported\"}",
            "{\"message\":\"Unsupported model stream_options\"}",
            "{\"message\":\"Unsupported argument\",\"request\":{\"stream_options\":{\"include_usage\":true}}}"
    })
    void unrelatedErrorsCannotAuthorizeAnAdditionalBilledInvocation(String error) throws Exception {
        List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        HttpServer server = server(requests, error);
        try {
            assertThatThrownBy(() -> new CompanyModelGateway().complete(settings(server), "正文", "开始", token -> { }, () -> { })).isInstanceOf(Problem.class);
            assertThat(requests).hasSize(1);
        } finally { server.stop(0); }
    }

    private HttpServer server(List<Map<String, Object>> requests, String error) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(json.map(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            boolean rejected = requests.size() == 1;
            String body = rejected ? "{\"error\":" + error + "}"
                    : "data: {\"id\":\"fixture\",\"created\":1,\"object\":\"chat.completion.chunk\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", rejected ? "application/json" : "text/event-stream");
            exchange.sendResponseHeaders(rejected ? 400 : 200, bytes.length);
            try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        server.start(); return server;
    }
    private ModelSettings settings(HttpServer server) { return new ModelSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture-key", "fixture", 0.2, 15, "1"); }
}
