package com.aitest.engine.http;

import com.aitest.asset.AssetValidator;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.execution.ExecutionContext;
import com.aitest.execution.Values;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Component
public final class HttpTransport {
    public HttpTransport(JsonCodec json) { }
    public Map<String, Object> exchange(String method, String url, Map<String, Object> headers, byte[] body,
                                         int timeoutMs, Map<String, Object> options, ExecutionContext context) throws Exception {
        context.checkpoint(); AssetValidator.httpUrl(url);
        URI uri = URI.create(url);
        HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Values.integer(options, "connectTimeoutMs", 10000, 100, 120000)))
                .followRedirects(Values.bool(options, "followRedirects", false) ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                .cookieHandler(context.cookies());
        String proxy = Values.text(options, "proxyUrl", "");
        if (!proxy.isBlank()) {
            AssetValidator.httpUrl(proxy); URI address = URI.create(proxy);
            client.proxy(ProxySelector.of(new InetSocketAddress(address.getHost(), address.getPort() < 0 ? 8080 : address.getPort())));
        }
        if (Values.bool(options, "trustSelfSigned", false)) {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String auth) { }
                public void checkServerTrusted(X509Certificate[] chain, String auth) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            // Hostname verification remains enabled, even for explicitly trusted test certificates.
            client.sslContext(ssl);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs));
        headers.forEach((name, value) -> {
            if (value instanceof List<?> list) list.forEach(v -> request.header(name, Objects.toString(v, "")));
            else request.header(name, Objects.toString(value, ""));
        });
        request.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        int limit = Values.integer(options, "maxResponseBytes", 2 * 1024 * 1024, 1024, 32 * 1024 * 1024);
        long started = System.nanoTime();
        HttpClient owned = client.build();
        CompletableFuture<HttpResponse<byte[]>> exchange = owned.sendAsync(request.build(), info -> new LimitedBodySubscriber(limit));
        try {
            long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            HttpResponse<byte[]> response;
            while (true) {
                context.checkpoint();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new HttpTimeoutException("HTTP response body deadline exceeded");
                try { response = exchange.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); break; }
                catch (TimeoutException waiting) { /* Check the durable cancellation and total deadline again. */ }
                catch (ExecutionException failed) {
                    if (failed.getCause() instanceof Exception cause) throw cause;
                    throw new IllegalStateException("HTTP response failed", failed.getCause());
                }
            }
            context.checkpoint();
            Charset charset = StandardCharsets.UTF_8;
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            var match = java.util.regex.Pattern.compile("(?i)charset=\"?([A-Za-z0-9_-]+)").matcher(contentType);
            if (match.find()) try { charset = Charset.forName(match.group(1)); } catch (Exception ignored) { }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", response.statusCode()); result.put("headers", response.headers().map());
            result.put("body", new String(response.body(), charset)); result.put("byteSize", response.body().length);
            result.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            result.put("url", response.uri().toString()); return result;
        } finally {
            exchange.cancel(true); owned.shutdownNow();
            try { owned.awaitTermination(Duration.ofMillis(500)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBodySubscriber(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > limit) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("HTTP 响应超过大小限制")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
