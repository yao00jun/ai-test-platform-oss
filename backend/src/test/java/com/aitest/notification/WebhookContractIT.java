package com.aitest.notification;

import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.execution.ExecutionCoordinator;
import com.aitest.job.JobService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"aitest.notifications.poll-interval-ms=100", "aitest.notifications.retry-delay-seconds=1", "aitest.morning-brief.enabled=false"})
class WebhookContractIT extends ExchangeHttpTest {
    @Autowired ExecutionCoordinator execution;
    @Autowired JobService jobs;
    @Autowired JdbcTemplate jdbc;
    private HttpServer receiver;
    private ExecutorService pool;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    @BeforeEach void openReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); pool = Executors.newVirtualThreadPerTaskExecutor(); receiver.setExecutor(pool);
        receiver.createContext("/business", exchange -> { byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); });
        receiver.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestURI(), json.map(body), exchange.getRequestHeaders().getFirst("X-AITest-Delivery-Id")));
            Reply reply = replies.poll();
            try {
                if (reply != null) { reply.entered.countDown(); reply.release.await(10, TimeUnit.SECONDS); }
                String response = reply == null ? exchange.getRequestURI().getPath().equals("/feishu") ? "{\"code\":0}" : "{\"errcode\":0}" : reply.body;
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply == null ? 200 : reply.status, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); receiver.start();
    }
    @AfterEach void closeReceiver() { receiver.stop(0); pool.shutdownNow(); }

    @Test void newSubscriptionDoesNotBackfillAndAllThreeProtocolsSignActualFrozenRunFacts() throws Exception {
        String project = project().id(); String older = run(project, false);
        Asset ding = webhook(project, "DINGTALK", "/ding?access_token=not-for-history", "SEC-ding-fixture", true);
        assertThat(request("GET", historyPath(project, ding.id()), null).statusCode()).isEqualTo(200);
        Asset wechat = webhook(project, "WECHAT_WORK", "/wechat?key=private-wechat-key", "", true);
        Asset feishu = webhook(project, "FEISHU", "/feishu", "feishu-private-secret", true);
        String latest = run(project, true);
        for (Asset webhook : List.of(ding, wechat, feishu)) await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(latest(project, webhook.id())).containsEntry("status", "DELIVERED").containsEntry("runId", latest));
        assertThat(received).hasSize(3);
        assertThat(received.stream().map(r -> r.body.toString())).allSatisfy(body -> assertThat(body).contains("真实通知计划", "失败", "1").doesNotContain(older, "private-wechat-key", "SEC-ding-fixture", "feishu-private-secret"));
        for (Received message : received) assertThat(message.deliveryId).matches("[a-f0-9]{32}");
        Received dingMessage = received.stream().filter(r -> r.uri.getPath().equals("/ding")).findFirst().orElseThrow();
        var query = query(dingMessage.uri);
        assertThat(query).containsEntry("access_token", "not-for-history");
        assertThat(query.get("sign")).isEqualTo(hmac("SEC-ding-fixture", query.get("timestamp") + "\nSEC-ding-fixture"));
        assertThat(dingMessage.body).containsEntry("msgtype", "markdown");
        assertThat(map(dingMessage.body.get("at"))).containsEntry("isAtAll", false);
        Received feishuMessage = received.stream().filter(r -> r.uri.getPath().equals("/feishu")).findFirst().orElseThrow();
        assertThat(feishuMessage.body).containsEntry("msg_type", "interactive");
        assertThat(feishuMessage.body.get("sign")).isEqualTo(hmac(feishuMessage.body.get("timestamp") + "\nfeishu-private-secret", ""));
        String publicHistory = new String(request("GET", historyPath(project, ding.id()), null).body(), StandardCharsets.UTF_8);
        assertThat(publicHistory).doesNotContain("not-for-history", "SEC-ding-fixture", "webhookUrl", "privateConfig");
        assertThat(jdbc.queryForList("SELECT a.private_config FROM notification_attempt a JOIN notification_delivery d ON d.id=a.delivery_id WHERE d.project_id=?", String.class, project)).allSatisfy(value -> assertThat(value).startsWith("enc:v1:").doesNotContain("private", "SEC-ding-fixture"));
    }

    @Test void failOnlyConditionAndDisabledDestinationDoNotSendPassingRuns() throws Exception {
        String project = project().id();
        Asset onlyFailures = webhook(project, "DINGTALK", "/ding", "", true);
        assertThat(request("GET", historyPath(project, onlyFailures.id()), null).statusCode()).isEqualTo(200);
        Asset disabled = webhook(project, "WECHAT_WORK", "/wechat", "", false);
        run(project, false);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(latest(project, onlyFailures.id())).containsEntry("status", "SKIPPED_CONDITION"));
        assertThat(received).isEmpty(); assertThat(items(project, disabled.id())).isEmpty();
        Asset all = assets.update(project, onlyFailures.id(), onlyFailures.version(), null, Map.of("failOnly", false), null, "MANUAL");
        String next = run(project, false);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(latest(project, all.id())).containsEntry("status", "DELIVERED").containsEntry("runId", next));
        assertThat(received).hasSize(1);
    }

    @Test void rejectedDeliveryRetriesWithSameIdentityAndKeepsEveryAttempt() throws Exception {
        String project = project().id(); Asset webhook = webhook(project, "DINGTALK", "/ding", "", true);
        assertThat(request("GET", historyPath(project, webhook.id()), null).statusCode()).isEqualTo(200);
        replies.add(new Reply(503, "{\"error\":\"receiver temporarily unavailable\"}"));
        run(project, true);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(latest(project, webhook.id())).containsEntry("status", "DELIVERED").containsEntry("attemptCount", 2));
        var item = latest(project, webhook.id());
        assertThat(objects(item.get("attempts")).stream().map(a -> a.get("outcome"))).containsExactly("REJECTED", "DELIVERED");
        assertThat(received).hasSize(2); assertThat(received.get(0).deliveryId).isEqualTo(received.get(1).deliveryId);
        assertThat(received.get(0).body).isEqualTo(received.get(1).body);
    }

    @Test void ambiguousResponseIsNotReplayedAndManualRetryRequiresAcknowledgementAndCurrentConfig() throws Exception {
        String project = project().id(); Asset webhook = webhook(project, "DINGTALK", "/ding", "", true);
        assertThat(request("GET", historyPath(project, webhook.id()), null).statusCode()).isEqualTo(200);
        replies.add(new Reply(200, "unknown acknowledgement")); run(project, true);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(latest(project, webhook.id())).containsEntry("status", "UNCERTAIN"));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(received).hasSize(1));
        String id = latest(project, webhook.id()).get("id").toString(); String retryPath = historyPath(project, webhook.id()) + "/" + id + "/retry";
        Map<String, Object> request = Map.of("baseVersion", webhook.version(), "idempotencyKey", "explicit-retry");
        assertThat(request("POST", retryPath, request).statusCode()).isEqualTo(409);
        var acknowledged = new LinkedHashMap<String, Object>(request); acknowledged.put("acknowledgeUncertain", true);
        var submitted = request("POST", retryPath, acknowledged); assertThat(submitted.statusCode()).isEqualTo(202);
        assertThat(object(request("POST", retryPath, acknowledged))).isEqualTo(object(submitted));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(latest(project, webhook.id())).containsEntry("status", "DELIVERED"));
        assertThat(received).hasSize(2);
        assertThat(request("POST", retryPath, Map.of("baseVersion", webhook.version(), "idempotencyKey", "another", "acknowledgeUncertain", true)).statusCode()).isEqualTo(409);
        assertThat(request("GET", historyPath(project().id(), webhook.id()), null).statusCode()).isEqualTo(404);
    }

    @Test void configurationValidationRejectsUnsafeUrlsUnsupportedSigningAndInvalidRetryLimits() {
        String project = project().id();
        assertThatThrownBy(() -> webhook(project, "DINGTALK", "file:///tmp/message", "", true)).isInstanceOf(com.aitest.common.Problem.class);
        assertThatThrownBy(() -> webhook(project, "WECHAT_WORK", "/wechat", "unsupported-secret", true)).isInstanceOf(com.aitest.common.Problem.class);
        assertThatThrownBy(() -> assets.create(project, AssetType.WEBHOOK, null, "非法重试", Map.of("maxRetries", 6), "MANUAL")).isInstanceOf(com.aitest.common.Problem.class);
    }

    private Asset webhook(String project, String platform, String target, String secret, boolean enabled) {
        String url = target.startsWith("/") ? url(target) : target;
        return assets.create(project, AssetType.WEBHOOK, null, "通知 " + platform, Map.of("platform", platform, "webhookUrl", url, "secret", secret, "enabled", enabled, "failOnly", true), "MANUAL");
    }
    private String run(String project, boolean failed) {
        Asset api = assets.create(project, AssetType.API_CASE, null, "真实通知接口", Map.of("path", url("/business"), "assertions", List.of(Map.of("type", "status", "expected", failed ? 201 : 200))), "MANUAL");
        Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "真实通知计划", Map.of("diagnoseFailures", false), "MANUAL");
        assets.create(project, AssetType.PLAN_ITEM, plan.id(), "接口项", Map.of("targetId", api.id()), "MANUAL");
        var run = execution.submit(project, new ExecutionCoordinator.Request(plan.id(), null, null, UUID.randomUUID().toString()));
        await().atMost(Duration.ofSeconds(25)).until(() -> jobs.get(project, run.jobId()).terminal());
        assertThat(jobs.get(project, run.jobId()).status()).isEqualTo("SUCCEEDED"); return run.runId();
    }
    private String url(String path) { return "http://127.0.0.1:" + receiver.getAddress().getPort() + path; }
    private String historyPath(String project, String webhook) { return "/api/projects/" + project + "/webhooks/" + webhook + "/deliveries"; }
    private List<Map<String, Object>> items(String project, String webhook) throws Exception { var response = request("GET", historyPath(project, webhook), null); assertThat(response.statusCode()).isEqualTo(200); return objects(object(response).get("items")); }
    private Map<String, Object> latest(String project, String webhook) throws Exception { var list = items(project, webhook); assertThat(list).isNotEmpty(); return list.getFirst(); }
    private static Map<String, String> query(URI uri) { var out = new HashMap<String, String>(); for (String part : uri.getRawQuery().split("&")) { var pair = part.split("=", 2); out.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); } return out; }
    private static String hmac(String key, String data) throws Exception { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8))); }
    record Received(URI uri, Map<String, Object> body, String deliveryId) { }
    static class Reply { final int status; final String body; final CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0); Reply(int status, String body) { this.status = status; this.body = body; } }
}
