package com.aitest.engine.http;

import com.aitest.common.JsonCodec;
import com.aitest.execution.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

class HttpExecutorIT {
    @Test void realHttpExchangesSupportTypedJsonFormsMultipartExtractionCookiesAndTimeouts() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/echo", request -> {
            byte[] input = request.getRequestBody().readAllBytes();
            request.getResponseHeaders().add("Set-Cookie", "session=fixture; Path=/");
            request.getResponseHeaders().add("X-Content-Type", request.getRequestHeaders().getFirst("Content-Type"));
            request.sendResponseHeaders(200, input.length);
            request.getResponseBody().write(input); request.close();
        });
        server.createContext("/slow", request -> {
            try { Thread.sleep(500); request.sendResponseHeaders(204, -1); } catch (Exception ignored) { } finally { request.close(); }
        });
        server.start();
        try {
            JsonCodec json = new JsonCodec(); VariableResolver variables = new VariableResolver(json);
            HttpTransport transport = new HttpTransport(json);
            HttpStepExecutor engine = new HttpStepExecutor(transport, variables, new AssertionEvaluator(json, variables), json);
            var context = new ExecutionContext(Map.of("amount", 42), () -> { });
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var jsonResult = engine.execute(Map.of("method", "POST", "path", "/echo", "bodyType", "JSON", "body", Map.of("amount", "${amount}"),
                    "extractors", List.of(Map.of("variable", "saved", "jsonpath", "$.amount")), "assertions", List.of(Map.of("type", "jsonpath", "path", "$.amount", "expected", 42))), base, Map.of(), Map.of(), context);
            assertThat(jsonResult.status()).isEqualTo("PASSED");
            assertThat(context.variables()).containsEntry("saved", 42);
            var form = engine.execute(Map.of("method", "POST", "path", "/echo", "bodyType", "FORM", "body", Map.of("text", "中文 + 空格")), base, Map.of(), Map.of(), context);
            assertThat(form.actual().get("body").toString()).contains("%E4%B8%AD", "%2B");
            var multipart = engine.execute(Map.of("method", "POST", "path", "/echo", "bodyType", "MULTIPART", "body", Map.of("text", "中文")), base, Map.of(), Map.of(), context);
            assertThat(multipart.actual().get("body").toString()).contains("name=\"text\"", "中文");
            assertThat(context.cookies().getCookieStore().getCookies()).hasSize(1);
            var timeout = engine.execute(Map.of("path", "/slow", "timeoutMs", 100), base, Map.of(), Map.of(), context);
            assertThat(timeout.status()).isEqualTo("ERROR");
            assertThat(timeout.error()).contains("超时");
        } finally { server.stop(0); }
    }
}
