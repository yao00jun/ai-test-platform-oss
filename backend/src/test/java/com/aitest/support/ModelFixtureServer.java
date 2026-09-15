package com.aitest.support;

import com.aitest.common.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** An actual HTTP protocol fixture; production always uses the configured company gateway. */
public final class ModelFixtureServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    public final List<String> requests = new CopyOnWriteArrayList<>();
    private final JsonCodec json = new JsonCodec();
    public ModelFixtureServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Reply reply = replies.poll(10, TimeUnit.SECONDS);
                if (reply == null) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
                reply.entered().countDown(); reply.release().await(20, TimeUnit.SECONDS);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                for (int i = 0; i < reply.content().length(); i += 16) {
                    String token = reply.content().substring(i, Math.min(i + 16, reply.content().length()));
                    String event = json.write(Map.of("id", "fixture", "object", "chat.completion.chunk", "created", 1, "model", "fixture", "choices", List.of(Map.of("index", 0, "delta", Map.of("content", token)))));
                    exchange.getResponseBody().write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
                }
                String end = "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
                if (reply.usage() != null) end += "data: " + json.write(Map.of("id", "fixture", "object", "chat.completion.chunk", "created", 1, "model", "fixture", "choices", List.of(), "usage", reply.usage())) + "\n\n";
                end += "data: [DONE]\n\n";
                exchange.getResponseBody().write(end.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    public void enqueue(String content) { enqueueWithUsage(content, null); }
    public void enqueueWithUsage(String content, Map<String, Object> usage) { replies.add(new Reply(content, new CountDownLatch(0), new CountDownLatch(0), usage)); }
    public Reply hold(String content) { Reply reply = new Reply(content, new CountDownLatch(1), new CountDownLatch(1), null); replies.add(reply); return reply; }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
    public record Reply(String content, CountDownLatch entered, CountDownLatch release, Map<String, Object> usage) { }
}
