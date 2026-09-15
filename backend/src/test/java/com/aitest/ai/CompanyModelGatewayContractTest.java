package com.aitest.ai;

import com.aitest.common.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class CompanyModelGatewayContractTest {
    @Test void standardCompatibleGatewayStreamsWithoutRequiringJsonSchemaOrDuplicateV1() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"测试\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"通过\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var config = new ModelSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture-key", "fixture", 0.2, 20, "1");
            var tokens = new ArrayList<String>();
            String result = new CompanyModelGateway().complete(config, "只输出正文", "开始", tokens::add, () -> { });
            assertThat(result).isEqualTo("测试通过");
            assertThat(String.join("", tokens)).isEqualTo(result);
            assertThat(authorization.get()).isEqualTo("Bearer fixture-key");
            Map<String, Object> body = new JsonCodec().map(request.get());
            assertThat(body).containsEntry("model", "fixture").containsEntry("stream", true).doesNotContainKey("response_format");
        } finally { server.stop(0); }
    }
    @Test void baseUrlNormalizationPreservesExistingVersionPrefix() {
        assertThat(ModelSettings.normalizeBaseUrl("https://model.company.test/v1/")).isEqualTo("https://model.company.test/v1");
        assertThat(ModelSettings.normalizeBaseUrl("https://model.company.test")).isEqualTo("https://model.company.test/v1");
    }
}
