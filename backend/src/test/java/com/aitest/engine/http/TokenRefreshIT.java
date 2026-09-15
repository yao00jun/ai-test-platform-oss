package com.aitest.engine.http;

import com.aitest.asset.*;
import com.aitest.common.JsonCodec;
import com.aitest.execution.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class TokenRefreshIT {
    @Test void concurrentRefreshIsSingleFlightAndEnvironmentsNeverShareTokens() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/login", request -> {
            byte[] body = ("{\"token\":\"token-" + logins.incrementAndGet() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            request.getRequestBody().readAllBytes(); request.sendResponseHeaders(200, body.length); request.getResponseBody().write(body); request.close();
        }); server.start();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            JsonCodec json = new JsonCodec(); VariableResolver resolver = new VariableResolver(json);
            GlobalAuthService auth = new GlobalAuthService(new HttpStepExecutor(new HttpTransport(json), resolver, new AssertionEvaluator(json, resolver), json), resolver, json);
            Asset environment = asset("env", AssetType.ENVIRONMENT, Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort()));
            Asset config = asset("auth", AssetType.AUTH_CONFIG, Map.of("loginUrl", "/login", "loginPayload", Map.of(), "tokenJsonPath", "$.token"));
            List<Callable<GlobalAuthService.Token>> calls = new ArrayList<>();
            for (int i=0;i<12;i++) calls.add(() -> auth.token(environment, config, new ExecutionContext(Map.of(), () -> { }), null));
            var results = workers.invokeAll(calls); String token = results.getFirst().get().value();
            for (var result : results) assertThat(result.get().value()).isEqualTo(token);
            assertThat(logins.get()).isEqualTo(1);
            calls.clear(); for (int i=0;i<12;i++) calls.add(() -> auth.token(environment, config, new ExecutionContext(Map.of(), () -> { }), token));
            for (var result : workers.invokeAll(calls)) assertThat(result.get().value()).isNotEqualTo(token);
            assertThat(logins.get()).isEqualTo(2);
            assertThat(auth.token(asset("other", AssetType.ENVIRONMENT, environment.data()), config, new ExecutionContext(Map.of(), () -> { }), null).value()).isEqualTo("token-3");
            assertThat(auth.canRetry(Map.of("method", "POST"), config)).isFalse();
        } finally { server.stop(0); }
    }
    private Asset asset(String id, AssetType type, Map<String, Object> data) { return new Asset(id, "project", type, null, id, "1", 0, "MANUAL", false, Instant.now(), Instant.now(), data); }
}
