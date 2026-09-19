package com.aitest.notification;
import org.junit.jupiter.api.Tag;

import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.execution.*;
import com.aitest.job.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"aitest.notifications.initial-delay-ms=3600000", "aitest.notifications.retry-delay-seconds=1", "aitest.execution.concurrency=1", "aitest.morning-brief.enabled=false"})
@Tag("slow")
class WebhookBoundaryIT extends ExchangeHttpTest {
    @Autowired NotificationService notifications;
    @Autowired ExecutionCoordinator execution;
    @Autowired RunRepository runs;
    @Autowired JobService jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired Gate gate;
    @Autowired AssetRepository repository;
    @Autowired SecretProtector secrets;
    @Autowired TransactionTemplate transactions;
    private HttpServer server;
    private ExecutorService pool;
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final AtomicInteger received = new AtomicInteger(), redirected = new AtomicInteger();
    private final List<String> projects = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); pool = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(pool);
        server.createContext("/business", exchange -> { byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); });
        server.createContext("/destination", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close(); });
        server.createContext("/robot", exchange -> {
            exchange.getRequestBody().readAllBytes(); received.incrementAndGet(); Reply reply = replies.poll();
            try {
                if (reply != null) { reply.entered.countDown(); reply.release.await(15, TimeUnit.SECONDS); }
                if (reply != null && reply.status == 302) exchange.getResponseHeaders().set("Location", url("/destination"));
                byte[] bytes = (reply == null ? "{\"errcode\":0}" : reply.body).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply == null ? 200 : reply.status, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start(); gate.reset();
    }
    @AfterEach void stop() {
        gate.release.countDown();
        for (String project : projects) try { Asset current = assets.get(project, project); assets.delete(project, project, current.version()); } catch (Problem ignored) { }
        server.stop(0); pool.shutdownNow();
    }

    @Test void manualCompletionCreatesOneFrozenEventOnlyAfterEveryItemFinishes() {
        String project = newProject(); Asset webhook = webhook(project, Map.of());
        Asset manual = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "人工确认", Map.of(), "MANUAL");
        String run = run(project, manual);
        notifications.sweep(Instant.now()); assertThat(items(project, webhook.id())).isEmpty();
        var item = Values.objects(runs.get(project, run).get("items")).stream().filter(row -> "MANUAL_PENDING".equals(row.get("status"))).findFirst().orElseThrow();
        runs.manual(project, run, item.get("id").toString(), "1", "PASSED", "人工验收");
        notifications.sweep(Instant.now()); awaitOutcome(project, webhook.id(), "DELIVERED");
        Object frozen = latest(project, webhook.id()).get("report");
        runs.manual(project, run, item.get("id").toString(), "2", "BLOCKED", "后来更正");
        notifications.sweep(Instant.now());
        assertThat(items(project, webhook.id())).hasSize(1); assertThat(latest(project, webhook.id()).get("report")).isEqualTo(frozen);
        assertThat(received.get()).isEqualTo(1);
    }

    @Test void parallelSweepsAndAReplacementServiceDoNotDuplicateCompletion() throws Exception {
        String project = newProject(); Asset webhook = webhook(project, Map.of()); run(project, null);
        NotificationService replacement = new NotificationService(repository, jdbc, json, secrets, transactions, jobs, true, 1);
        try (var workers = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) { int index = i; tasks.add(workers.submit(() -> (index % 2 == 0 ? notifications : replacement).sweep(Instant.now()))); }
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        awaitOutcome(project, webhook.id(), "DELIVERED"); assertThat(items(project, webhook.id())).hasSize(1); assertThat(received.get()).isEqualTo(1);
    }

    @Test void queuedDestinationChangeRequiresCurrentHumanVersionAndNeverSendsOldConfig() throws Exception {
        String project = newProject(); Asset webhook = webhook(project, Map.of()); run(project, null); holdWorker(project);
        notifications.sweep(Instant.now()); var pending = latest(project, webhook.id());
        Asset changed = assets.update(project, webhook.id(), webhook.version(), null, Map.of("secret", "new-local-secret"), null, "MANUAL");
        gate.release.countDown(); awaitOutcome(project, webhook.id(), "BLOCKED_CONFIG"); assertThat(received.get()).isZero();
        String delivery = pending.get("id").toString();
        assertThatThrownBy(() -> notifications.retry(project, webhook.id(), delivery, Map.of("baseVersion", webhook.version(), "idempotencyKey", "old"))).isInstanceOf(Problem.class);
        var retry = Map.<String, Object>of("baseVersion", changed.version(), "idempotencyKey", "changed-config");
        var submitted = notifications.retry(project, webhook.id(), delivery, retry); awaitOutcome(project, webhook.id(), "DELIVERED");
        assertThat(notifications.retry(project, webhook.id(), delivery, retry)).isEqualTo(submitted);
        assertThatThrownBy(() -> notifications.retry(project, webhook.id(), delivery, Map.of("baseVersion", changed.version(), "idempotencyKey", "changed-config", "acknowledgeUncertain", true))).isInstanceOf(Problem.class);
        assertThat(received.get()).isEqualTo(1);
    }

    @Test void cancellationBeforeAndAfterSendingHaveDifferentAuditableOutcomes() throws Exception {
        String project = newProject(); Asset webhook = webhook(project, Map.of()); run(project, null); holdWorker(project);
        notifications.sweep(Instant.now()); var queued = latest(project, webhook.id()); String queuedJob = attempts(queued).getFirst().get("jobId").toString();
        jobs.cancel(project, queuedJob); notifications.sweep(Instant.now()); gate.release.countDown();
        assertThat(latest(project, webhook.id())).containsEntry("status", "CANCELLED"); assertThat(received.get()).isZero();
        Reply held = new Reply(200, "{\"errcode\":0}", true); replies.add(held); run(project, null); notifications.sweep(Instant.now());
        assertThat(held.entered.await(10, TimeUnit.SECONDS)).isTrue(); var sending = latest(project, webhook.id()); String inFlight = attempts(sending).getFirst().get("jobId").toString();
        jobs.cancel(project, inFlight); held.release.countDown(); awaitOutcome(project, webhook.id(), "UNCERTAIN");
        notifications.sweep(Instant.now().plusSeconds(100)); assertThat(received.get()).isEqualTo(1); assertThat(latest(project, webhook.id())).containsEntry("attemptCount", 1);
    }

    @Test void deletedProjectStillRetainsTheActualInFlightDeliveryAudit() throws Exception {
        String project = newProject(); Asset webhook = webhook(project, Map.of()); Reply held = new Reply(200, "{\"errcode\":0}", true); replies.add(held);
        run(project, null); notifications.sweep(Instant.now()); assertThat(held.entered.await(10, TimeUnit.SECONDS)).isTrue();
        var sending = latest(project, webhook.id()); String job = attempts(sending).getFirst().get("jobId").toString();
        Asset current = assets.get(project, project); assets.delete(project, project, current.version()); held.release.countDown();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT outcome FROM notification_attempt WHERE job_id=?", String.class, job)).isEqualTo("DELIVERED"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT status FROM job_task WHERE id=?", String.class, job)).isEqualTo("SUCCEEDED"));
        assertThatThrownBy(() -> notifications.history(project, webhook.id(), 0, 20)).isInstanceOf(Problem.class);
    }

    @Test void retryBudgetAndDisabledConfigurationBoundAutomaticDelivery() {
        String project = newProject(); Asset webhook = webhook(project, Map.of("maxRetries", 1));
        replies.add(new Reply(503, "private receiver error", false)); replies.add(new Reply(503, "private receiver error", false));
        run(project, null); notifications.sweep(Instant.now()); awaitOutcome(project, webhook.id(), "RETRY_WAIT");
        notifications.sweep(Instant.now().plusSeconds(10)); awaitOutcome(project, webhook.id(), "FAILED");
        notifications.sweep(Instant.now().plusSeconds(100)); assertThat(received.get()).isEqualTo(2);
        assertThat(latest(project, webhook.id())).containsEntry("attemptCount", 2).containsEntry("automaticRetries", 1);
        assertThat(json.write(items(project, webhook.id()))).doesNotContain("private receiver error");
        Asset disabled = assets.update(project, webhook.id(), webhook.version(), null, Map.of("enabled", false), null, "MANUAL");
        assertThatThrownBy(() -> notifications.retry(project, webhook.id(), latest(project, webhook.id()).get("id").toString(), Map.of("baseVersion", disabled.version(), "idempotencyKey", "disabled"))).isInstanceOf(Problem.class);
    }

    @Test void timeoutOversizedBodyAndRedirectNeverTriggerUnsafeReplay() throws Exception {
        String project = newProject(); Asset webhook = webhook(project, Map.of("timeoutSeconds", 1));
        Reply held = new Reply(200, "{\"errcode\":0}", true); replies.add(held); run(project, null); notifications.sweep(Instant.now());
        assertThat(held.entered.await(10, TimeUnit.SECONDS)).isTrue(); awaitOutcome(project, webhook.id(), "UNCERTAIN"); held.release.countDown();
        notifications.sweep(Instant.now().plusSeconds(100)); assertThat(received.get()).isEqualTo(1);
        replies.add(new Reply(200, "x".repeat(17000), false)); run(project, null); notifications.sweep(Instant.now()); awaitOutcome(project, webhook.id(), "UNCERTAIN");
        replies.add(new Reply(302, "", false)); run(project, null); notifications.sweep(Instant.now()); awaitOutcome(project, webhook.id(), "FAILED");
        assertThat(redirected.get()).isZero(); assertThat(received.get()).isEqualTo(3);
        assertThat(attempts(latest(project, webhook.id())).getFirst()).containsEntry("diagnosticCode", "HTTP_REJECTED");
    }

    @Test void enableCursorIgnoresDisabledCompletionsAndUndoRespectsTheSameBoundary() {
        String project = newProject(); Asset webhook = webhook(project, Map.of("enabled", false)); run(project, null);
        Asset enabled = assets.update(project, webhook.id(), webhook.version(), null, Map.of("enabled", true), null, "MANUAL");
        notifications.sweep(Instant.now()); assertThat(items(project, webhook.id())).isEmpty();
        Asset disabled = assets.update(project, webhook.id(), enabled.version(), null, Map.of("enabled", false), null, "MANUAL"); run(project, null);
        String enabledRevision = assets.history(project, webhook.id()).stream().filter(r -> r.version().equals(enabled.version())).findFirst().orElseThrow().id();
        assets.undo(project, webhook.id(), disabled.version(), enabledRevision); notifications.sweep(Instant.now()); assertThat(items(project, webhook.id())).isEmpty();
        run(project, null); notifications.sweep(Instant.now()); awaitOutcome(project, webhook.id(), "DELIVERED"); assertThat(received.get()).isEqualTo(1);
    }

    private String newProject() { String id = project().id(); projects.add(id); return id; }
    private String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
    private Asset webhook(String project, Map<String, Object> extra) {
        var data = new LinkedHashMap<String, Object>(Map.of("enabled", true, "webhookUrl", url("/robot?key=not-public"), "maxRetries", 2)); data.putAll(extra);
        return assets.create(project, AssetType.WEBHOOK, null, "测试通知", data, "MANUAL");
    }
    private String run(String project, Asset manual) {
        Asset api = assets.create(project, AssetType.API_CASE, null, "失败接口", Map.of("path", url("/business"), "assertions", List.of(Map.of("type", "status", "expected", 201))), "MANUAL");
        Asset plan = assets.create(project, AssetType.TEST_PLAN, null, "通知边界计划", Map.of("diagnoseFailures", false), "MANUAL");
        assets.create(project, AssetType.PLAN_ITEM, plan.id(), "接口", Map.of("targetId", api.id()), "MANUAL");
        if (manual != null) assets.create(project, AssetType.PLAN_ITEM, plan.id(), "人工", Map.of("targetId", manual.id(), "executionMode", "MANUAL"), "MANUAL");
        var submission = execution.submit(project, new ExecutionCoordinator.Request(plan.id(), null, null, UUID.randomUUID().toString()));
        await().atMost(Duration.ofSeconds(20)).until(() -> jobs.get(project, submission.jobId()).terminal());
        assertThat(jobs.get(project, submission.jobId()).status()).isEqualTo("SUCCEEDED"); return submission.runId();
    }
    private void holdWorker(String project) throws Exception { jobs.submit(project, "NOTIFICATION_TEST_GATE", UUID.randomUUID().toString(), Map.of()); assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue(); }
    private List<Map<String, Object>> items(String project, String webhook) { return Values.objects(notifications.history(project, webhook, 0, 100).get("items")); }
    private Map<String, Object> latest(String project, String webhook) { return items(project, webhook).getFirst(); }
    private List<Map<String, Object>> attempts(Map<String, Object> item) { return Values.objects(json.map(json.write(item)).get("attempts")); }
    private void awaitOutcome(String project, String webhook, String status) { await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(latest(project, webhook)).containsEntry("status", status)); }
    static class Reply {
        final int status; final String body; final CountDownLatch entered = new CountDownLatch(1), release;
        Reply(int status, String body, boolean held) { this.status = status; this.body = body; this.release = new CountDownLatch(held ? 1 : 0); }
    }
    static class Gate implements JobHandler {
        volatile CountDownLatch entered, release;
        void reset() { entered = new CountDownLatch(1); release = new CountDownLatch(1); }
        @Override public String kind() { return "NOTIFICATION_TEST_GATE"; }
        @Override public Map<String, Object> execute(JobContext context, Map<String, Object> input) throws Exception { entered.countDown(); if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Gate timed out"); return Map.of(); }
    }
    @TestConfiguration static class Config { @Bean Gate notificationGate() { return new Gate(); } }
}
