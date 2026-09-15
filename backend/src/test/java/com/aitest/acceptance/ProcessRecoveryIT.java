package com.aitest.acceptance;

import com.aitest.support.IsolatedApplication;
import com.aitest.support.ModelFixtureServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Actual JVM crashes; no fake lease expiry or replacement Spring service is counted as a restart. */
class ProcessRecoveryIT {
    @Test void crashRecoversQueuedRefinementButNeverReplaysInFlightWritesAndSseResumesAtItsCursor() throws Exception {
        try (var app = new IsolatedApplication(); var model = new ModelFixtureServer()) {
            app.start(); app.model(model); String project = app.project();
            var target = app.asset(project, "FUNCTIONAL_CASE", null, "中断目标", Map.of());
            var queuedTarget = app.asset(project, "FUNCTIONAL_CASE", null, "排队目标", Map.of());
            var held = model.hold("{\"data\":{\"precondition\":\"不得采纳的迟到结果\"}}");
            var firstInput = refineInput(project, target);
            var first = app.call("POST", "/api/ai/refine-item", firstInput, 202);
            assertThat(held.entered().await(15, TimeUnit.SECONDS)).isTrue();
            model.enqueue("{\"data\":{\"precondition\":\"重启后的排队结果\"}}");
            var queuedInput = refineInput(project, queuedTarget);
            var queued = app.call("POST", "/api/ai/refine-item", queuedInput, 202);
            assertThat(app.job(project, queued.get("jobId").toString())).containsEntry("status", "QUEUED");
            long cursor = app.jdbc.queryForObject("SELECT MAX(seq) FROM job_event WHERE job_id=?", Long.class, first.get("jobId"));
            app.crash(); held.release().countDown(); app.start();
            app.finished(project, queued.get("jobId").toString(), "SUCCEEDED");
            app.finished(project, first.get("jobId").toString(), "INTERRUPTED");
            assertThat(app.asset(project, target.get("id").toString())).isEqualTo(target);
            var accepted = app.asset(project, queuedTarget.get("id").toString());
            assertThat(accepted).containsEntry("version", "2");
            assertThat(map(accepted.get("data"))).containsEntry("precondition", "重启后的排队结果");
            for (var entry : List.of(Map.entry(firstInput, first), Map.entry(queuedInput, queued))) {
                entry.getKey().put("conversationId", entry.getValue().get("conversationId"));
                assertThat(app.call("POST", "/api/ai/refine-item", entry.getKey(), 202)).isEqualTo(entry.getValue());
            }
            assertThat(app.jdbc.queryForObject("SELECT COUNT(*) FROM asset_revision WHERE asset_id=?", Long.class, target.get("id"))).isEqualTo(1);
            assertThat(app.jdbc.queryForObject("SELECT COUNT(*) FROM asset_revision WHERE asset_id=?", Long.class, queuedTarget.get("id"))).isEqualTo(2);
            assertThat(model.requests).hasSize(2);
            var response = app.http.send(HttpRequest.newBuilder(app.uri("/api/jobs/" + first.get("jobId") + "/events?projectId=" + project))
                    .header("Last-Event-ID", Long.toString(cursor)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("event:done", "INTERRUPTED").doesNotContain("排队中");
            assertThat(response.body().lines().filter(line -> line.startsWith("id:")).map(line -> Long.parseLong(line.substring(3).strip())).toList())
                    .isNotEmpty().allMatch(seq -> seq > cursor);
        }
    }

    @Test void acceptedWebhookWithoutAcknowledgementSurvivesCrashAsUncertainUntilExplicitRetry() throws Exception {
        try (var app = new IsolatedApplication(); var site = new Site()) {
            Map<String, String> settings = Map.of("aitest.notifications.enabled", "true", "aitest.notifications.poll-interval-ms", "100");
            app.start(settings); String project = app.project();
            var webhook = app.asset(project, "WEBHOOK", null, "本地恢复验收", Map.of("platform", "DINGTALK", "webhookUrl", site.url("/webhook"), "enabled", true, "failOnly", false));
            var api = app.asset(project, "API_CASE", null, "真实请求", Map.of("path", site.url("/business")));
            var plan = app.asset(project, "TEST_PLAN", null, "恢复计划", Map.of("diagnoseFailures", false));
            app.asset(project, "PLAN_ITEM", plan.get("id").toString(), "接口项", Map.of("targetId", api.get("id")));
            var run = app.call("POST", "/api/projects/" + project + "/runs", Map.of("assetId", plan.get("id"), "idempotencyKey", UUID.randomUUID().toString()), 202);
            app.finished(project, run.get("jobId").toString(), "SUCCEEDED");
            assertThat(site.webhookEntered.await(20, TimeUnit.SECONDS)).isTrue();
            String history = "/api/projects/" + project + "/webhooks/" + webhook.get("id") + "/deliveries";
            String deliveryId = latest(app, history).get("id").toString();
            assertThat(app.jdbc.queryForObject("SELECT status FROM notification_delivery WHERE id=?", String.class, deliveryId)).isEqualTo("SENDING");
            app.crash(); site.webhookRelease.countDown(); app.start(settings);
            await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> assertThat(latest(app, history)).containsEntry("status", "UNCERTAIN"));
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(site.deliveries).hasSize(1));
            String retry = history + "/" + deliveryId + "/retry";
            var body = new LinkedHashMap<String, Object>(Map.of("baseVersion", webhook.get("version"), "idempotencyKey", "confirmed-recovery"));
            assertThat(app.request("POST", retry, body).statusCode()).isEqualTo(409);
            body.put("acknowledgeUncertain", true);
            var submitted = app.call("POST", retry, body, 202);
            assertThat(app.call("POST", retry, body, 202)).isEqualTo(submitted);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(latest(app, history)).containsEntry("status", "DELIVERED").containsEntry("attemptCount", 2));
            assertThat(site.deliveries).containsExactly(deliveryId, deliveryId);
            assertThat(app.call("POST", retry, body, 202)).isEqualTo(submitted);
            assertThat(app.jdbc.queryForObject("SELECT COUNT(*) FROM test_run", Long.class)).isEqualTo(1);
            assertThat(site.businessRequests).hasValue(1);
        }
    }

    @Test void persistedOverdueScheduleAndMorningBriefDoNotDuplicateAcrossTwoProcessRestarts() throws Exception {
        try (var app = new IsolatedApplication(); var model = new ModelFixtureServer(); var site = new Site()) {
            var settings = Map.of("aitest.schedules.enabled", "true", "aitest.morning-brief.enabled", "true",
                    "aitest.schedules.poll-interval-ms", "100", "aitest.morning-brief.poll-interval-ms", "100");
            app.start(settings); app.model(model); String project = app.project();
            var api = app.asset(project, "API_CASE", null, "停机后巡检", Map.of("path", site.url("/business")));
            Instant future = Instant.now().plusSeconds(3600); ZonedDateTime utc = future.atZone(ZoneOffset.UTC);
            String cron = utc.getSecond() + " " + utc.getMinute() + " " + utc.getHour() + " * * *";
            var plan = app.asset(project, "TEST_PLAN", null, "持久化排期", Map.of("scheduleEnabled", true, "cronExpression", cron,
                    "timezone", "UTC", "misfirePolicy", "FIRE_ONCE", "overlapPolicy", "SKIP", "diagnoseFailures", false));
            app.asset(project, "PLAN_ITEM", plan.get("id").toString(), "巡检项", Map.of("targetId", api.get("id")));
            app.call("PUT", "/api/projects/" + project + "/morning-brief/schedule", Map.of("baseVersion", "0", "enabled", true,
                    "time", String.format(Locale.ROOT, "%02d:%02d", utc.getHour(), utc.getMinute()), "timezone", "UTC", "instruction", "仅总结实际事实", "maxRetries", 0), 200);
            await().atMost(Duration.ofSeconds(5)).until(() -> app.jdbc.queryForObject("SELECT COUNT(*) FROM plan_schedule_cursor", Long.class) == 1);
            app.crash();
            // Controlled missed-trigger fixture; the process, queue, execution and dedupe are real.
            Timestamp due = Timestamp.from(Instant.now().minusSeconds(120));
            app.jdbc.update("UPDATE plan_schedule_cursor SET next_fire_at=? WHERE plan_id=?", due, plan.get("id"));
            app.jdbc.update("UPDATE morning_brief_schedule SET next_fire_at=? WHERE project_id=?", due, project);
            model.enqueue("{\"changes\":[{\"operation\":\"ADD\",\"targetType\":\"QUALITY_BRIEF\",\"name\":\"恢复晨报\",\"data\":{\"content\":\"基于冻结事实生成的晨报\"}}]}");
            app.start(settings);
            String briefHistory = "/api/projects/" + project + "/morning-brief/history";
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(latest(app, briefHistory)).containsEntry("status", "SUCCEEDED"));
            await().atMost(Duration.ofSeconds(15)).until(() -> app.jdbc.queryForObject("SELECT COUNT(*) FROM test_run WHERE status='PASSED'", Long.class) == 1);
            var brief = latest(app, briefHistory);
            var runs = app.jdbc.queryForList("SELECT id FROM test_run", String.class);
            app.crash();
            // Re-present the exact already handled occurrence, including the daily brief key.
            app.jdbc.update("UPDATE plan_schedule_cursor SET next_fire_at=? WHERE plan_id=?", due, plan.get("id"));
            app.jdbc.update("UPDATE morning_brief_schedule SET next_fire_at=? WHERE project_id=?", due, project);
            app.start(settings);
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(app.jdbc.queryForList("SELECT id FROM test_run", String.class)).isEqualTo(runs);
                assertThat(latest(app, briefHistory)).containsEntry("id", brief.get("id")).containsEntry("assetId", brief.get("assetId")).containsEntry("attemptCount", 1);
                assertThat(app.jdbc.queryForObject("SELECT COUNT(*) FROM morning_brief_occurrence", Long.class)).isEqualTo(1);
                assertThat(app.jdbc.queryForObject("SELECT COUNT(*) FROM plan_schedule_occurrence", Long.class)).isEqualTo(1);
            });
            assertThat(site.businessRequests).hasValue(1); assertThat(model.requests).hasSize(1);
        }
    }

    @Test void killingApplicationReclaimsItsBrowserTreeWithinABoundAndNewRunsStillWork() throws Exception {
        try (var app = new IsolatedApplication(); var site = new Site()) {
            app.start(); String project = app.project();
            var scenario = app.asset(project, "UI_SCENARIO", null, "强杀中的浏览器", Map.of("baseUrl", site.url(""), "timeoutMs", 180000));
            app.asset(project, "UI_STEP", scenario.get("id").toString(), "打开页面", Map.of("action", "navigate", "url", "/page"));
            app.asset(project, "UI_STEP", scenario.get("id").toString(), "等待不存在的元素", Map.of("action", "wait", "selector", "#never-created", "timeoutMs", 120000));
            var run = app.call("POST", "/api/projects/" + project + "/runs", Map.of("assetId", scenario.get("id"), "idempotencyKey", "browser-crash"), 202);
            boolean opened = site.pageEntered.await(25, TimeUnit.SECONDS);
            if (!opened) {
                java.nio.file.Files.writeString(app.evidence.resolve("browser-preparation.json"), app.json.write(Map.of(
                        "job", app.job(project, run.get("jobId").toString()),
                        "run", app.call("GET", "/api/projects/" + project + "/runs/" + run.get("runId"), null, 200),
                        "children", app.descendants().stream().map(child -> Map.of("pid", child.pid(), "command", child.info().command().orElse(""))).toList())));
                opened = site.pageEntered.await(35, TimeUnit.SECONDS);
            }
            assertThat(opened).as("Browser preparation; inspect %s", app.evidence).isTrue();
            List<ProcessHandle> children = app.descendants();
            assertThat(children).hasSizeGreaterThanOrEqualTo(3);
            app.crash();
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(children.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList())
                    .as("Browser/driver/worker processes must exit after their owning application dies").isEmpty());
            app.start(); app.finished(project, run.get("jobId").toString(), "INTERRUPTED");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(app.call("GET", "/api/projects/" + project + "/runs/" + run.get("runId"), null, 200)).containsEntry("status", "INTERRUPTED"));
            var next = app.asset(project, "UI_SCENARIO", null, "重启后浏览器", Map.of("baseUrl", site.url("")));
            app.asset(project, "UI_STEP", next.get("id").toString(), "打开页面", Map.of("action", "navigate", "url", "/page"));
            var successful = app.call("POST", "/api/projects/" + project + "/runs", Map.of("assetId", next.get("id"), "idempotencyKey", "browser-recovered"), 202);
            Set<ProcessHandle> recoveredChildren = new LinkedHashSet<>();
            Map<Long, Map<String, Object>> originalIdentities = new LinkedHashMap<>();
            await().atMost(Duration.ofSeconds(50)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                recoveredChildren.addAll(app.workerDescendants());
                recoveredChildren.forEach(child -> originalIdentities.putIfAbsent(child.pid(), processIdentity(child)));
                assertThat(app.job(project, successful.get("jobId").toString())).containsEntry("status", "SUCCEEDED");
            });
            assertThat(app.call("GET", "/api/projects/" + project + "/runs/" + successful.get("runId"), null, 200)).containsEntry("status", "PASSED");
            try {
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    recoveredChildren.addAll(app.workerDescendants());
                    assertThat(recoveredChildren.stream().filter(ProcessHandle::isAlive).map(ProcessRecoveryIT::processIdentity).toList())
                            .as("A completed browser run must reclaim its worker, driver and browser tree").isEmpty();
                });
            } catch (RuntimeException | AssertionError failure) {
                java.nio.file.Files.writeString(app.evidence.resolve("browser-completion.json"), app.json.write(Map.of(
                        "observed", originalIdentities.values(),
                        "alive", recoveredChildren.stream().filter(ProcessHandle::isAlive).map(ProcessRecoveryIT::processIdentity).toList(),
                        "currentDescendants", app.descendants().stream().map(ProcessRecoveryIT::processIdentity).toList())));
                throw failure;
            }
        }
    }

    private static Map<String, Object> processIdentity(ProcessHandle process) {
        return Map.of("pid", process.pid(), "parentPid", process.parent().map(ProcessHandle::pid).orElse(-1L),
                "command", process.info().command().orElse(""), "started", process.info().startInstant().map(Object::toString).orElse(""));
    }

    private Map<String, Object> refineInput(String project, Map<String, Object> target) {
        return new LinkedHashMap<>(Map.of("projectId", project, "targetType", "FUNCTIONAL_CASE", "targetId", target.get("id"), "baseVersion", target.get("version"),
                "feedback", "完善前置条件", "targetFields", List.of("precondition"), "idempotencyKey", UUID.randomUUID().toString()));
    }
    private Map<String, Object> latest(IsolatedApplication app, String path) throws Exception {
        var items = objects(app.call("GET", path, null, 200).get("items")); assertThat(items).isNotEmpty(); return items.getFirst();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> objects(Object value) { return (List<Map<String, Object>>) value; }

    private static final class Site implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final CountDownLatch webhookEntered = new CountDownLatch(1), webhookRelease = new CountDownLatch(1), pageEntered = new CountDownLatch(1);
        final AtomicInteger businessRequests = new AtomicInteger();
        final List<String> deliveries = new CopyOnWriteArrayList<>();
        Site() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
            server.createContext("/business", exchange -> {
                businessRequests.incrementAndGet(); byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            server.createContext("/page", exchange -> {
                pageEntered.countDown(); byte[] body = "<html><body><h1>Recovery fixture</h1></body></html>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            server.createContext("/webhook", exchange -> {
                exchange.getRequestBody().readAllBytes(); deliveries.add(exchange.getRequestHeaders().getFirst("X-AITest-Delivery-Id")); webhookEntered.countDown();
                try {
                    webhookRelease.await(45, TimeUnit.SECONDS);
                    byte[] body = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
        }
        String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        @Override public void close() { webhookRelease.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
