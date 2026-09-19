package com.aitest.acceptance;
import org.junit.jupiter.api.Tag;

import com.aitest.support.IsolatedApplication;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Measurements use the public API of a fixed-size JVM, real MySQL, HTTP and Chromium. */
@Tag("slow")
class CapacityAcceptanceIT {
    private static final int BROWSER_SCENARIO_TIMEOUT_MS = 120_000;
    private static final int BROWSER_STEP_TIMEOUT_MS = 30_000;

    @Test void tenThousandAssetsAndThousandDdtRowsStayBoundedAndReleaseExecutionResources() throws Exception {
        try (var app = new IsolatedApplication(); var site = new Site()) {
            app.start(Map.of("aitest.execution.concurrency", "8", "aitest.execution.browser-workers", "2", "spring.datasource.hikari.maximum-pool-size", "16"), 1024);
            String project = app.project();
            var results = new LinkedHashMap<String, Object>();
            results.put("at", Instant.now().toString()); results.put("java", System.getProperty("java.version"));
            results.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            results.put("configuration", Map.of("heapMaxMiB", 1024, "activeProcessors", 4, "executionConcurrency", 8, "scenarioConcurrency", 4,
                    "platformPoolMax", 16, "businessPoolMax", 4, "browserWorkers", 2,
                    "browserScenarioTimeoutMs", BROWSER_SCENARIO_TIMEOUT_MS, "browserStepTimeoutMs", BROWSER_STEP_TIMEOUT_MS));
            var resources = new Resources(app);
            try (resources) {
                var nodes = new ArrayList<Map<String, Object>>();
                for (int index = 0; index < 10000; index++) nodes.add(Map.of("key", "case_" + index, "type", "FUNCTIONAL_CASE", "name", "容量用例 " + index,
                        "position", index, "data", Map.of("precondition", "固定条件 " + index, "remark", "原样保存"), "references", Map.of()));
                byte[] input = app.json.write(Map.of("formatVersion", "aitest.exchange/v1", "nodes", nodes, "metadata", Map.of(), "externalReferences", Map.of(), "warnings", List.of())).getBytes(StandardCharsets.UTF_8);
                results.put("importBytes", input.length);
                long started = System.nanoTime(); var preview = app.preview(project, "FUNCTIONAL_CASE", "json", input);
                results.put("previewMs", elapsed(started)); assertThat(preview.get("errors")).isEqualTo(List.of());
                started = System.nanoTime();
                var response = app.request("POST", "/api/projects/" + project + "/imports/" + preview.get("id") + "/apply", Map.of(), Duration.ofMinutes(3));
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200); results.put("applyMs", elapsed(started));
                assertThat(app.json.map(response.body())).containsEntry("createdCount", 10000);
                assertThat(app.jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE project_id=? AND asset_type='FUNCTIONAL_CASE'", Long.class, project)).isEqualTo(10000);
                var pageTimes = new ArrayList<Long>();
                for (int page = 0; page < 20; page++) {
                    started = System.nanoTime();
                    var listing = app.call("GET", "/api/projects/" + project + "/assets?type=FUNCTIONAL_CASE&limit=100&offset=" + page * 500, null, 200);
                    pageTimes.add(elapsed(started)); assertThat(listing).containsEntry("total", 10000);
                    assertThat(objects(listing.get("items"))).hasSize(100);
                }
                results.put("page100LatencyMs", distribution(pageTimes));
                var first = objects(app.call("GET", "/api/projects/" + project + "/assets?type=FUNCTIONAL_CASE&limit=2", null, 200).get("items"));
                var sibling = first.getLast(); started = System.nanoTime();
                app.call("PATCH", "/api/projects/" + project + "/assets/" + first.getFirst().get("id"), Map.of("baseVersion", "1", "data", Map.of("remark", "万条资产中的单项人工编辑")), 200);
                results.put("singleEditMs", elapsed(started)); assertThat(app.asset(project, sibling.get("id").toString())).isEqualTo(sibling);

                var rows = new ArrayList<Map<String, Object>>(); for (int row = 1; row <= 1000; row++) rows.add(Map.of("rowNumber", row));
                var dataset = app.asset(project, "DATASET", null, "一千行真实执行", Map.of("columns", List.of("rowNumber"), "rows", rows));
                var database = app.database(project, 4);
                var api = app.asset(project, "API_CASE", null, "逐行 HTTP", Map.of("method", "POST", "path", site.url("/echo"), "bodyType", "JSON", "body", Map.of("rowNumber", "${rowNumber}"),
                        "extractors", List.of(Map.of("variable", "returned", "jsonpath", "$.rowNumber")), "assertions", List.of(Map.of("type", "status", "expected", 200))));
                var sql = app.asset(project, "SQL_VALIDATION", null, "逐行 SQL", Map.of("databaseSourceId", database.get("id"), "sql", "SELECT :returned AS ddt_value",
                        "assertions", List.of(Map.of("field", "ddt_value", "expected", "${rowNumber}"))));
                var scenario = app.asset(project, "SCENARIO", null, "HTTP SQL DDT", Map.of("datasetId", dataset.get("id")));
                app.asset(project, "SCENARIO_STEP", scenario.get("id").toString(), "HTTP", Map.of("stepType", "HTTP", "targetId", api.get("id")));
                app.asset(project, "SCENARIO_STEP", scenario.get("id").toString(), "SQL", Map.of("stepType", "SQL", "targetId", sql.get("id")));
                started = System.nanoTime();
                var submitted = app.call("POST", "/api/projects/" + project + "/runs", Map.of("assetId", scenario.get("id"), "idempotencyKey", "capacity-ddt"), 202);
                results.put("runSubmissionMs", elapsed(started)); long executionStart = System.nanoTime();
                await().atMost(Duration.ofMinutes(4)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> assertThat(app.job(project, submitted.get("jobId").toString())).containsEntry("status", "SUCCEEDED"));
                results.put("ddt1000Ms", elapsed(executionStart));
                var run = app.call("GET", "/api/projects/" + project + "/runs/" + submitted.get("runId"), null, 200);
                assertThat(run.get("status")).as("DDT run %s; inspect its persisted step evidence", submitted.get("runId")).isEqualTo("PASSED");
                assertThat(objects(run.get("items"))).hasSize(1000);
                assertThat(objects(run.get("items"))).allSatisfy(item -> assertThat(objects(item.get("steps"))).hasSize(2));
                assertThat(site.returnedRows).hasSize(1000); assertThat(site.requests).hasValue(1000);
                assertThat(site.peak.get()).isBetween(1, 4); results.put("httpPeakConcurrent", site.peak.get());
                results.put("ddtRowsPerSecond", 1000000.0 / ((Number) results.get("ddt1000Ms")).doubleValue());

                var browser = app.asset(project, "UI_SCENARIO", null, "并行浏览器容量", Map.of("baseUrl", site.url(""), "timeoutMs", BROWSER_SCENARIO_TIMEOUT_MS));
                app.asset(project, "UI_STEP", browser.get("id").toString(), "本地页面", Map.of("action", "navigate", "url", "/page", "timeoutMs", BROWSER_STEP_TIMEOUT_MS));
                app.asset(project, "UI_STEP", browser.get("id").toString(), "保存证据", Map.of("action", "screenshot"));
                var browserRuns = new ArrayList<Map<String, Object>>(); started = System.nanoTime(); site.browserStarted = started;
                for (int i = 0; i < 2; i++) browserRuns.add(app.call("POST", "/api/projects/" + project + "/runs", Map.of("assetId", browser.get("id"), "idempotencyKey", "capacity-browser-" + i), 202));
                // Readiness includes JVM/driver/browser preparation; the browser launch alone
                // has a 30-second budget. Observe the configured scenario deadline here, then
                // require actual overlapping requests and successful steps independently.
                boolean bothEntered = site.browsersEntered.await(BROWSER_SCENARIO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!bothEntered) {
                    Files.writeString(app.evidence.resolve("browser-readiness.json"), app.json.write(browserState(app, site, project, browserRuns)));
                }
                assertThat(bothEntered).as("Both browser pages within the configured scenario deadline; inspect browser-readiness.json").isTrue();
                assertThat(site.browserPeak).as("Both browser page requests must overlap, not run serially").hasValue(2);
                resources.sample(); site.releaseBrowsers.countDown();
                for (var browserRun : browserRuns) {
                    app.finished(project, browserRun.get("jobId").toString(), "SUCCEEDED");
                    assertThat(app.call("GET", "/api/projects/" + project + "/runs/" + browserRun.get("runId"), null, 200)).containsEntry("status", "PASSED");
                }
                results.put("twoBrowserRunsMs", elapsed(started));
                await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                    assertThat(app.workerDescendants()).isEmpty();
                    assertThat(resources.browserChildren.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList()).isEmpty();
                });
                resources.sample();
                assertThat(resources.failures).isEmpty(); assertThat(resources.samples).isNotEmpty();
                results.put("resources", resources.report());
                assertThat(resources.peak("heapBytes")).isLessThan(1024d * 1024 * 1024);
                assertThat(resources.peak("platformPoolActive")).isLessThanOrEqualTo(16);
                assertThat(resources.peak("databaseConnectionsIncludingSampler")).isLessThanOrEqualTo(21);
                assertThat(resources.samples.getLast().get("browserDescendants")).isEqualTo(0d);
            } finally {
                results.put("browserPageEnteredMs", List.copyOf(site.browserEntries));
                results.put("browserRequestsPeakConcurrent", site.browserPeak.get());
                if (!resources.samples.isEmpty()) results.put("resources", resources.report());
                Files.writeString(app.evidence.resolve("capacity.json"), app.json.write(results));
                System.out.println("Capacity evidence: " + app.evidence.resolve("capacity.json"));
            }
            app.crash();
            await().atMost(Duration.ofSeconds(5)).until(() -> app.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.processlist WHERE DB=DATABASE()", Long.class) == 1);
        }
    }

    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static Map<String, Object> browserState(IsolatedApplication app, Site site, String project,
                                                    List<Map<String, Object>> runs) throws Exception {
        var states = new ArrayList<Map<String, Object>>();
        for (var run : runs) states.add(Map.of("job", app.job(project, run.get("jobId").toString()),
                "run", app.call("GET", "/api/projects/" + project + "/runs/" + run.get("runId"), null, 200)));
        return Map.of("elapsedMs", elapsed(site.browserStarted), "pageEnteredMs", List.copyOf(site.browserEntries),
                "peakConcurrentPageRequests", site.browserPeak.get(), "states", states,
                "workers", app.workerDescendants().stream().map(child -> Map.of("pid", child.pid(),
                        "startedAt", child.info().startInstant().map(Instant::toString).orElse("unknown"),
                        "command", child.info().command().orElse("unknown"))).toList());
    }
    private static Map<String, Object> distribution(List<Long> values) {
        var sorted = values.stream().sorted().toList(); return Map.of("samples", sorted.size(), "median", sorted.get(sorted.size() / 2), "p95", sorted.get((int) Math.ceil(sorted.size() * .95) - 1), "max", sorted.getLast());
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> objects(Object value) { return (List<Map<String, Object>>) value; }

    private static final class Resources implements AutoCloseable {
        final IsolatedApplication app;
        final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        final List<Map<String, Double>> samples = new CopyOnWriteArrayList<>();
        final List<String> failures = new CopyOnWriteArrayList<>();
        final Set<ProcessHandle> browserChildren = ConcurrentHashMap.newKeySet();
        Resources(IsolatedApplication app) { this.app = app; sampler.scheduleWithFixedDelay(this::sample, 0, 1, TimeUnit.SECONDS); }
        synchronized void sample() {
            try {
                var sample = new LinkedHashMap<String, Double>();
                sample.put("heapBytes", metric("jvm.memory.used?tag=area:heap"));
                sample.put("platformThreads", metric("jvm.threads.live"));
                sample.put("platformPoolActive", metric("hikaricp.connections.active"));
                sample.put("platformPoolPending", metric("hikaricp.connections.pending"));
                sample.put("databaseConnectionsIncludingSampler", app.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.processlist WHERE DB=DATABASE()", Double.class));
                var descendants = app.workerDescendants(); browserChildren.addAll(descendants);
                sample.put("browserDescendants", (double) descendants.size()); samples.add(sample);
            } catch (Exception | AssertionError failure) { failures.add(failure.getClass().getSimpleName()); }
        }
        double metric(String name) throws Exception {
            return ((Number) objects(app.call("GET", "/actuator/metrics/" + name, null, 200).get("measurements")).getFirst().get("value")).doubleValue();
        }
        double peak(String name) { return samples.stream().mapToDouble(sample -> sample.get(name)).max().orElse(0); }
        Map<String, Object> report() {
            var peak = new LinkedHashMap<String, Double>(); if (!samples.isEmpty()) samples.getFirst().keySet().forEach(key -> peak.put(key, peak(key)));
            return Map.of("intervalSeconds", 1, "sampleCount", samples.size(), "before", samples.getFirst(), "peak", peak, "after", samples.getLast(), "samples", samples);
        }
        public void close() throws InterruptedException { sampler.shutdownNow(); sampler.awaitTermination(5, TimeUnit.SECONDS); }
    }
    private static final class Site implements AutoCloseable {
        final HttpServer server; final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger requests = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
        final Set<Integer> returnedRows = ConcurrentHashMap.newKeySet();
        final CountDownLatch browsersEntered = new CountDownLatch(2), releaseBrowsers = new CountDownLatch(1);
        final List<Long> browserEntries = new CopyOnWriteArrayList<>();
        final AtomicInteger browserActive = new AtomicInteger(), browserPeak = new AtomicInteger();
        volatile long browserStarted;
        Site() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
            server.createContext("/echo", exchange -> {
                int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max); requests.incrementAndGet();
                try {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    returnedRows.add(((Number) new com.aitest.common.JsonCodec().map(new String(body, StandardCharsets.UTF_8)).get("rowNumber")).intValue());
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } finally { active.decrementAndGet(); exchange.close(); }
            });
            server.createContext("/page", exchange -> {
                browserEntries.add(elapsed(browserStarted));
                browserPeak.accumulateAndGet(browserActive.incrementAndGet(), Math::max);
                browsersEntered.countDown();
                try {
                    releaseBrowsers.await(25, TimeUnit.SECONDS); byte[] body = "<html><body><h1>Capacity evidence</h1></body></html>".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { browserActive.decrementAndGet(); exchange.close(); }
            }); server.start();
        }
        String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        public void close() { releaseBrowsers.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
