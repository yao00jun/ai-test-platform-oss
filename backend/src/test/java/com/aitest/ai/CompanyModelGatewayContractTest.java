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
    @Test void listsProviderModelsFromTheCompatibleModelsEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"object\":\"list\",\"data\":[{\"id\":\"qwen-plus\",\"object\":\"model\"},{\"id\":\"deepseek-chat\"},{\"id\":\"qwen-plus\"},{\"name\":\"ignored-without-id\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var models = new CompanyModelGateway().listModels("http://127.0.0.1:" + server.getAddress().getPort(), "list-key", 10);
            assertThat(models).containsExactly("deepseek-chat", "ignored-without-id", "qwen-plus");
            assertThat(authorization.get()).isEqualTo("Bearer list-key");
        } finally { server.stop(0); }
    }
    @Test void modelListingReportsMissingEndpointAndBadCredentialsDistinctly() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            int status = "Bearer good".equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 404 : 401;
            exchange.sendResponseHeaders(status, -1); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            assertThatThrownBy(() -> new CompanyModelGateway().listModels(base, "bad", 10))
                    .isInstanceOfSatisfying(com.aitest.common.Problem.class, problem -> assertThat(problem.code()).isEqualTo("MODEL_AUTHENTICATION_FAILED"));
            assertThatThrownBy(() -> new CompanyModelGateway().listModels(base, "good", 10))
                    .isInstanceOfSatisfying(com.aitest.common.Problem.class, problem -> assertThat(problem.code()).isEqualTo("MODEL_LIST_UNSUPPORTED"));
        } finally { server.stop(0); }
    }
    @Test void modelListParsingAcceptsCommonVariants() {
        assertThat(CompanyModelGateway.parseModels("{\"models\":[{\"name\":\"b\"},{\"id\":\"a\"}]}")).containsExactly("a", "b");
        assertThat(CompanyModelGateway.parseModels("[\"z\",\"y\",\"\"]")).containsExactly("y", "z");
        assertThatThrownBy(() -> CompanyModelGateway.parseModels("<html>")).isInstanceOf(com.aitest.common.Problem.class);
        assertThatThrownBy(() -> CompanyModelGateway.parseModels("{\"error\":\"nope\"}")).isInstanceOf(com.aitest.common.Problem.class);
    }
    @Test void selfSignedHttpsGatewayNeedsTheExplicitTrustSwitch() throws Exception {
        var keys = java.security.KeyStore.getInstance("PKCS12");
        try (var stream = getClass().getResourceAsStream("/tls/model-self-signed.p12")) { keys.load(stream, "fixture-pass".toCharArray()); }
        var keyManagers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()); keyManagers.init(keys, "fixture-pass".toCharArray());
        var ssl = javax.net.ssl.SSLContext.getInstance("TLS"); ssl.init(keyManagers.getKeyManagers(), null, null);
        var server = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ssl));
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = ("data: {\"id\":\"tls\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"内网可用\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/v1/models", exchange -> {
            byte[] bytes = "{\"data\":[{\"id\":\"intranet-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            String base = "https://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var strict = new ModelSettings(base, "fixture-key", "fixture", 0.2, 20, "1", false);
            assertThatThrownBy(() -> new CompanyModelGateway().complete(strict, "只输出正文", "开始", token -> { }, () -> { }))
                    .isInstanceOfSatisfying(com.aitest.common.Problem.class, problem -> assertThat(problem.getMessage()).contains("TLS 握手失败").contains("信任内部/自签名证书"));
            assertThatThrownBy(() -> new CompanyModelGateway().listModels(base, "fixture-key", 10, false))
                    .isInstanceOfSatisfying(com.aitest.common.Problem.class, problem -> assertThat(problem.getMessage()).contains("TLS 握手失败"));
            var trusting = new ModelSettings(base, "fixture-key", "fixture", 0.2, 20, "1", true);
            assertThat(new CompanyModelGateway().complete(trusting, "只输出正文", "开始", token -> { }, () -> { })).isEqualTo("内网可用");
            assertThat(new CompanyModelGateway().listModels(base, "fixture-key", 10, true)).containsExactly("intranet-model");
        } finally { server.stop(0); }
    }
    @Test void baseUrlNormalizationPreservesExistingVersionPrefix() {
        assertThat(ModelSettings.normalizeBaseUrl("https://model.company.test/v1/")).isEqualTo("https://model.company.test/v1");
        assertThat(ModelSettings.normalizeBaseUrl("https://model.company.test")).isEqualTo("https://model.company.test/v1");
    }
}
