package com.aitest.engine.http;

import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.execution.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class HttpBoundaryRegressionTest {
    private final JsonCodec json = new JsonCodec();
    private final VariableResolver variables = new VariableResolver(json);
    private final HttpStepExecutor http = new HttpStepExecutor(new HttpTransport(json), variables, new AssertionEvaluator(json, variables), json);

    @Test void deadlineAndCancellationCoverAResponseBodyAfterHeadersArrive() throws Exception {
        try (Fixture site = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1);
            site.server.createContext("/body", request -> {
                // Only the cancellation request is awaited: on a cold JVM the 150 ms deadline of the first one can expire before it
                // even reaches the server, and a count taken after a failed write would never happen at all.
                if ("await=cancel".equals(request.getRequestURI().getRawQuery())) entered.countDown();
                try {
                    request.sendResponseHeaders(200, 0); request.getResponseBody().write('x'); request.getResponseBody().flush();
                    Thread.sleep(1600); request.getResponseBody().write('y');
                } catch (Exception disconnected) { } finally { request.close(); }
            });
            StepResult result = http.execute(Map.of("path", "/body", "timeoutMs", 150), site.url(), Map.of(), Map.of(), new ExecutionContext(Map.of(), () -> {}));
            assertThat(result.status()).isEqualTo("ERROR"); assertThat(result.error()).contains("超时"); assertThat(result.durationMs()).isLessThan(1000);
            AtomicBoolean cancel = new AtomicBoolean();
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> running = workers.submit(() -> http.execute(Map.of("path", "/body?await=cancel", "timeoutMs", 30000), site.url(), Map.of(), Map.of(), new ExecutionContext(Map.of(), () -> { if (cancel.get()) throw new CancellationException(); })));
                assertThat(entered.await(5, TimeUnit.SECONDS)).as("the second request reached the server").isTrue(); cancel.set(true);
                assertThatThrownBy(() -> running.get(800, TimeUnit.MILLISECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CancellationException.class);
            }
        }
    }

    @Test void missingExtractorKeepsReceivedStatusAndBodyForAuthRetryAndDiagnosis() throws Exception {
        try (Fixture site = new Fixture()) {
            site.server.createContext("/expired", request -> site.reply(request, 401, "{\"error\":\"expired\"}"));
            var result = http.execute(Map.of("path", "/expired", "extractors", List.of(Map.of("variable", "id", "jsonpath", "$.id"))), site.url(), Map.of(), Map.of(), new ExecutionContext(Map.of(), () -> {}));
            assertThat(result.actual()).containsEntry("status", 401).containsEntry("body", "{\"error\":\"expired\"}");
            assertThat(result.exports()).isEmpty(); assertThat(result.status()).isEqualTo("FAILED");
        }
    }

    @Test void differentResolvedLoginHeadersNeverReuseAnotherRowsToken() throws Exception {
        try (Fixture site = new Fixture()) {
            site.server.createContext("/login", request -> site.reply(request, 200, "{\"token\":\"token-" + request.getRequestHeaders().getFirst("X-Tenant") + "\"}"));
            var auth = new GlobalAuthService(http, variables, json);
            Asset env = asset("env", AssetType.ENVIRONMENT, Map.of("baseUrl", site.url(), "headers", Map.of("X-Tenant", "${tenant}")));
            Asset config = asset("auth", AssetType.AUTH_CONFIG, Map.of("loginUrl", "/login", "loginPayload", Map.of()));
            assertThat(auth.token(env, config, new ExecutionContext(Map.of("tenant", "alpha"), () -> {}), null).value()).isEqualTo("token-alpha");
            assertThat(auth.token(env, config, new ExecutionContext(Map.of("tenant", "beta"), () -> {}), null).value()).isEqualTo("token-beta");
        }
    }

    @Test void failedCredentialAttemptsDoNotPermanentlyExhaustTheAuthenticationCache() throws Exception {
        try (Fixture site = new Fixture()) {
            site.server.createContext("/login", request -> {
                String body = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (body.contains("valid-user")) site.reply(request, 200, "{\"token\":\"valid-token\"}");
                else site.reply(request, 403, "{\"error\":\"bad credentials\"}");
            });
            var auth = new GlobalAuthService(http, variables, json);
            Asset env = asset("env", AssetType.ENVIRONMENT, Map.of("baseUrl", site.url()));
            Asset config = asset("auth", AssetType.AUTH_CONFIG, Map.of("loginUrl", "/login", "loginPayload", Map.of("username", "${username}")));
            for (int i = 0; i < 1030; i++) {
                var context = new ExecutionContext(Map.of("username", "failed-" + i), () -> {});
                assertThatThrownBy(() -> auth.token(env, config, context, null)).isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.code()).isEqualTo("AUTH_FAILED"));
            }
            assertThat(auth.token(env, config, new ExecutionContext(Map.of("username", "valid-user"), () -> {}), null).value()).isEqualTo("valid-token");
        }
    }

    @Test void customTokenHeadersCarryTheBareTokenGetLoginsUseTheQueryAndFailuresSayWhy() throws Exception {
        try (Fixture site = new Fixture()) {
            site.server.createContext("/login", request -> {
                request.getRequestBody().readAllBytes();
                if (request.getRequestMethod().equals("GET") && "u=tester".equals(request.getRequestURI().getRawQuery())) site.reply(request, 200, "{\"code\":0,\"data\":{\"token\":\"t-1\"}}");
                else site.reply(request, 401, "{\"msg\":\"账号不存在\"}");
            });
            var auth = new GlobalAuthService(http, variables, json);
            Asset env = asset("env", AssetType.ENVIRONMENT, Map.of("baseUrl", site.url()));
            Map<String, Object> login = Map.of("loginUrl", "/login", "loginMethod", "GET", "loginPayload", Map.of("u", "tester"), "tokenJsonPath", "$.data.token");
            var custom = new java.util.LinkedHashMap<>(login); custom.put("headerKey", "X-Access-Token"); custom.put("headerPrefix", "");
            var bare = auth.token(env, asset("custom", AssetType.AUTH_CONFIG, custom), new ExecutionContext(Map.of(), () -> {}), null);
            assertThat(bare.header()).isEqualTo("X-Access-Token");
            assertThat(bare.headerValue()).isEqualTo("t-1");
            assertThat(auth.token(env, asset("standard", AssetType.AUTH_CONFIG, login), new ExecutionContext(Map.of(), () -> {}), null).headerValue()).isEqualTo("Bearer t-1");

            var rejected = new java.util.LinkedHashMap<>(login); rejected.put("loginPayload", Map.of("u", "nobody"));
            assertThatThrownBy(() -> auth.token(env, asset("rejected", AssetType.AUTH_CONFIG, rejected), new ExecutionContext(Map.of(), () -> {}), null))
                    .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.getMessage()).contains("HTTP 401").contains("账号不存在"));
            var wrongPath = new java.util.LinkedHashMap<>(login); wrongPath.put("tokenJsonPath", "$.token");
            assertThatThrownBy(() -> auth.token(env, asset("wrong-path", AssetType.AUTH_CONFIG, wrongPath), new ExecutionContext(Map.of(), () -> {}), null))
                    .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.getMessage()).contains("$.token").contains("data").as("the response carries the token").doesNotContain("t-1"));
        }
    }

    @Test void loginCookiesPreserveOneSessionPerContextWithoutRepeatedLogin() throws Exception {
        try (Fixture site = new Fixture()) {
            AtomicInteger logins = new AtomicInteger();
            site.server.createContext("/login", request -> {
                int session = logins.incrementAndGet();
                request.getResponseHeaders().set("Set-Cookie", "session=session-" + session + "; Path=/; HttpOnly");
                site.reply(request, 200, "{\"token\":\"token-" + session + "\"}");
            });
            var auth = new GlobalAuthService(http, variables, json);
            Asset environment = asset("cookie-env", AssetType.ENVIRONMENT, Map.of("baseUrl", site.url()));
            Asset config = asset("cookie-auth", AssetType.AUTH_CONFIG, Map.of("loginUrl", "/login", "loginPayload", Map.of()));
            ExecutionContext first = new ExecutionContext(Map.of(), () -> {}), second = new ExecutionContext(Map.of(), () -> {});
            assertThat(auth.token(environment, config, first, null).value()).isEqualTo("token-1");
            assertThat(auth.token(environment, config, first, null).value()).isEqualTo("token-1");
            assertThat(auth.token(environment, config, second, null).value()).isEqualTo("token-2");
            assertThat(auth.token(environment, config, first, null).value()).isEqualTo("token-1");
            assertThat(auth.token(environment, config, second, null).value()).isEqualTo("token-2");
            assertThat(logins).hasValue(2);
            assertThat(first.cookies().getCookieStore().getCookies()).anySatisfy(cookie -> assertThat(cookie.getValue()).isEqualTo("session-1"));
            assertThat(second.cookies().getCookieStore().getCookies()).anySatisfy(cookie -> assertThat(cookie.getValue()).isEqualTo("session-2"));
        }
    }

    private Asset asset(String id, AssetType type, Map<String, Object> data) { return new Asset(id, "project", type, null, id, "1", 0, "MANUAL", false, Instant.now(), Instant.now(), data); }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        Fixture() throws Exception { server.setExecutor(workers); server.start(); }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void reply(com.sun.net.httpserver.HttpExchange request, int status, String body) throws java.io.IOException {
            request.getRequestBody().readAllBytes(); byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(status, bytes.length); request.getResponseBody().write(bytes); request.close();
        }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }
}
