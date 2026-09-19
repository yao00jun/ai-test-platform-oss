package com.aitest.acceptance;
import org.junit.jupiter.api.Tag;

import com.aitest.common.Ids;
import com.aitest.support.IsolatedApplication;
import com.aitest.support.MySqlDiagnostics;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Identical workload before and after optimization; durations are observations, not portable SLAs. */
@Tag("slow")
class PerformanceMeasurementIT {
    @Test void recordsQueryWriteAndDiskCostsWithoutChangingDurability() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        try (var app = new IsolatedApplication()) {
            app.start(Map.of("spring.datasource.hikari.maximum-pool-size", "8", "spring.datasource.hikari.minimum-idle", "8"), 1024);
            report.put("at", Instant.now().toString());
            report.put("revision", System.getProperty("aitest.measure.revision", "working-tree"));
            report.put("java", System.getProperty("java.version"));
            report.put("os", System.getProperty("os.name"));
            report.put("configuration", Map.of("heapMiB", 1024, "activeProcessors", 4, "poolSize", 8,
                    "importRowsPerRound", 1000, "importRounds", 3, "summaryRequests", 20, "sseSubscribers", 12));
            report.put("scope", "Disposable MySQL schema; application-session counters include its idle scheduler. Disk probe includes OS/controller caching; no database durability settings are changed.");
            try (var diagnostics = new MySqlDiagnostics(app)) {
                report.put("mysql", diagnostics.configuration());
                String project = app.project();
                // Warm the API, connection pool and serializers before each measured workload.
                for (int i = 0; i < 3; i++) app.call("GET", "/api/projects/" + project + "/summary", null, 200);
                assertThat(diagnostics.snapshot().connections()).as("Performance Schema must observe the application pool").isNotEmpty();
                var imports = new ArrayList<Map<String, Object>>();
                for (int round = 0; round < 3; round++) imports.add(importCases(app, diagnostics));
                report.put("imports", imports);
                seedSummary(app, project);
                for (int i = 0; i < 3; i++) app.call("GET", "/api/projects/" + project + "/summary", null, 200);
                var before = diagnostics.snapshot(); var times = new ArrayList<Double>();
                for (int i = 0; i < 20; i++) {
                    long start = System.nanoTime();
                    var summary = app.call("GET", "/api/projects/" + project + "/summary", null, 200);
                    times.add(elapsed(start));
                    assertThat(summary.get("openBugCount")).isEqualTo(10);
                    assertThat(objects(summary.get("recentRuns"))).hasSize(10);
                    assertThat(objects(summary.get("recentBugs"))).hasSize(10);
                    assertThat(summary.get("runSummary")).isEqualTo(Map.of("PASSED", 1000));
                }
                report.put("summary", Map.of("latencyMs", distribution(times), "database", MySqlDiagnostics.difference(before, diagnostics.snapshot())));
                report.put("summaryPlans", Map.of(
                        "recentBugs", diagnostics.explain("SELECT id FROM $schema.asset WHERE project_id=? AND asset_type='BUG' AND deleted=FALSE ORDER BY updated_at DESC,id DESC LIMIT 10", project),
                        "runCounts", diagnostics.explain("SELECT i.status,COUNT(*) AS total FROM $schema.test_run_item i JOIN $schema.test_run r ON i.run_id=r.id WHERE r.project_id=? GROUP BY i.status", project)));
                report.put("sse", measureSse(app, diagnostics, project));
                report.put("disk", disk(app.evidence));
                if (!Boolean.getBoolean("aitest.measure.baseline")) verifyQueryBudgets(report);
            } finally {
                Path output = Path.of(System.getProperty("aitest.measure.output", app.evidence.resolve("performance.json").toString())).toAbsolutePath().normalize();
                Files.createDirectories(output.getParent()); Files.writeString(output, app.json.write(report));
                System.out.println("Performance measurements: " + output);
            }
        }
    }

    private void verifyQueryBudgets(Map<String, Object> report) {
        org.assertj.core.api.SoftAssertions.assertSoftly(checks -> {
            for (var imported : objects(report.get("imports"))) {
                var database = object(imported.get("database"));
                checks.assertThat(database.get("stableConnections")).as("Comparable import pool sessions").isEqualTo(true);
                checks.assertThat(number(object(database.get("tableOperations")), "asset.COUNT_FETCH"))
                        .as("1,000 new independent assets must not scan their growing siblings quadratically").isLessThan(10_000);
                checks.assertThat(number(object(database.get("sessionCounters")), "SQL_insert"))
                        .as("JDBC batches must reach MySQL as bounded multi-row inserts").isBetween(1L, 100L);
                checks.assertThat(number(object(database.get("sessionCounters")), "SQL_select"))
                        .as("Import preflight must not re-read the same project for every row").isBetween(1L, 250L);
                for (String table : List.of("asset", "functional_case", "asset_revision", "audit_event"))
                    checks.assertThat(number(object(database.get("tableOperations")), table + ".COUNT_INSERT"))
                            .as("Batching preserves every row in %s", table).isEqualTo(1000L);
            }
            var summary = object(object(report.get("summary")).get("database"));
            checks.assertThat(summary.get("stableConnections")).as("Comparable summary pool sessions").isEqualTo(true);
            checks.assertThat(number(object(summary.get("sessionCounters")), "SQL_select"))
                    .as("20 summaries with ten recent runs/bugs use bounded batched reads").isBetween(1L, 400L);
            var sse = object(object(report.get("sse")).get("idleDatabase"));
            checks.assertThat(sse.get("stableConnections")).as("Comparable SSE pool sessions").isEqualTo(true);
            checks.assertThat(number(object(sse.get("sessionCounters")), "SQL_select"))
                    .as("12 idle subscriptions over three seconds must avoid per-client 250ms multi-query polling").isBetween(1L, 200L);
        });
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    private static long number(Map<String, Object> values, String key) { return ((Number) values.getOrDefault(key, 0L)).longValue(); }

    private Map<String, Object> importCases(IsolatedApplication app, MySqlDiagnostics diagnostics) throws Exception {
        String project = app.project(); var nodes = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 1000; i++) nodes.add(Map.of("key", "case_" + i, "type", "FUNCTIONAL_CASE", "name", "批量性能 " + i,
                "position", i, "data", Map.of("precondition", "测试条件", "remark", "保留修订与审计"), "references", Map.of()));
        byte[] bytes = app.json.write(Map.of("formatVersion", "aitest.exchange/v1", "nodes", nodes,
                "metadata", Map.of(), "externalReferences", Map.of(), "warnings", List.of())).getBytes(StandardCharsets.UTF_8);
        long start = System.nanoTime(); var preview = app.preview(project, "FUNCTIONAL_CASE", "json", bytes);
        double previewMs = elapsed(start); assertThat(preview.get("errors")).isEqualTo(List.of());
        var before = diagnostics.snapshot(); start = System.nanoTime();
        var response = app.request("POST", "/api/projects/" + project + "/imports/" + preview.get("id") + "/apply", Map.of(), Duration.ofMinutes(3));
        double applyMs = elapsed(start); var after = diagnostics.snapshot();
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(app.json.map(response.body())).containsEntry("createdCount", 1000);
        assertThat(app.jdbc.queryForObject("SELECT COUNT(*) FROM asset_revision WHERE project_id=? AND operation='CREATE'", Long.class, project)).isEqualTo(1001);
        assertThat(app.jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE project_id=? AND action='CREATE'", Long.class, project)).isEqualTo(1001);
        var page = app.call("GET", "/api/projects/" + project + "/assets?type=FUNCTIONAL_CASE&limit=100&offset=900", null, 200);
        assertThat(page).containsEntry("total", 1000); assertThat(objects(page.get("items"))).hasSize(100);
        return Map.of("previewMs", previewMs, "applyMs", applyMs, "database", MySqlDiagnostics.difference(before, after));
    }

    private void seedSummary(IsolatedApplication app, String project) throws Exception {
        for (int i = 0; i < 10; i++) app.asset(project, "BUG", null, "统计缺陷 " + i, Map.of("status", "OPEN"));
        Timestamp now = Timestamp.from(Instant.now());
        for (int r = 0; r < 10; r++) {
            String job = Ids.newId(), run = Ids.newId();
            app.jdbc.update("INSERT INTO job_task(id,project_id,kind,idempotency_key,input,status,created_at,updated_at) VALUES(?,?,? ,?,'{}','SUCCEEDED',?,?)", job, project, "MEASUREMENT", job, now, now);
            app.jdbc.update("INSERT INTO test_run(id,project_id,asset_id,job_id,name,status,snapshot,private_snapshot,summary,created_at) VALUES(?,?,?,?,?,'PASSED','{}','measurement','{}',?)", run, project, project, job, "统计运行 " + r, now);
            // Fixture preparation is outside timing. One transaction avoids measuring 1,000 fixture fsyncs.
            var transactions = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(app.jdbc.getDataSource())));
            transactions.executeWithoutResult(tx -> {
                var items = new ArrayList<Object[]>();
                for (int i = 0; i < 100; i++) items.add(new Object[]{Ids.newId(), run, project, "统计行 " + i, i, i});
                app.jdbc.batchUpdate("INSERT INTO test_run_item(id,run_id,asset_id,name,asset_type,position,row_index,variables,status) VALUES(?,?,?,?,'FUNCTIONAL_CASE',?,?,'{}','PASSED')", items);
            });
        }
    }

    private Map<String, Object> measureSse(IsolatedApplication app, MySqlDiagnostics diagnostics, String project) throws Exception {
        String job = Ids.newId(); Timestamp now = Timestamp.from(Instant.now());
        app.jdbc.update("INSERT INTO job_task(id,project_id,kind,idempotency_key,input,status,owner,lease_until,created_at,updated_at) VALUES(?,?,?,?,'{}','RUNNING','measurement',?,?,?)", job, project, "MEASUREMENT", job, Timestamp.from(Instant.now().plusSeconds(600)), now, now);
        app.jdbc.update("INSERT INTO job_event(job_id,event_type,payload,created_at) VALUES(?,'progress','{\"progress\":0}',?)", job, now);
        CountDownLatch ready = new CountDownLatch(12); var futures = new ArrayList<Future<String>>();
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        var bodies = new CopyOnWriteArrayList<java.io.InputStream>();
        try {
            for (int i = 0; i < 12; i++) futures.add(readers.submit(() -> {
                var request = HttpRequest.newBuilder(app.uri("/api/jobs/" + job + "/events?projectId=" + project)).timeout(Duration.ofSeconds(30)).GET().build();
                var response = app.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                assertThat(response.statusCode()).isEqualTo(200);
                bodies.add(response.body());
                try (var body = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    StringBuilder text = new StringBuilder(); String line; boolean observed = false;
                    while ((line = body.readLine()) != null) {
                        text.append(line).append('\n');
                        if (!observed && line.startsWith("data:")) { observed = true; ready.countDown(); }
                    }
                    return text.toString();
                }
            }));
            assertThat(ready.await(15, TimeUnit.SECONDS)).as("All SSE streams connected").isTrue();
            var before = diagnostics.snapshot(); long start = System.nanoTime();
            TimeUnit.SECONDS.sleep(3);
            double idleMs = elapsed(start); var idle = MySqlDiagnostics.difference(before, diagnostics.snapshot());
            start = System.nanoTime(); app.call("POST", "/api/jobs/" + job + "/cancel", Map.of("projectId", project), 200);
            for (var future : futures) assertThat(future.get(5, TimeUnit.SECONDS)).contains("event:done", "CANCELLED");
            return Map.of("subscribers", 12, "idleWindowMs", idleMs, "idleDatabase", idle, "cancelDeliveredToAllMs", elapsed(start));
        } finally {
            futures.forEach(future -> future.cancel(true));
            for (var body : bodies) body.close();
            readers.shutdownNow(); readers.awaitTermination(5, TimeUnit.SECONDS);
            app.jdbc.update("UPDATE job_task SET status='CANCELLED' WHERE id=?", job);
        }
    }

    private Map<String, Object> disk(Path directory) throws Exception {
        Path file = Files.createTempFile(directory, "disk-probe-", ".bin");
        var forces = new ArrayList<Double>(); double sequential;
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ByteBuffer chunk = ByteBuffer.allocate(1024 * 1024);
            new Random(42).nextBytes(chunk.array()); long start = System.nanoTime();
            for (int i = 0; i < 32; i++) { chunk.rewind(); while (chunk.hasRemaining()) channel.write(chunk); }
            channel.force(true); sequential = elapsed(start);
            chunk.limit(4096);
            for (int i = 0; i < 64; i++) {
                chunk.rewind(); start = System.nanoTime(); while (chunk.hasRemaining()) channel.write(chunk);
                channel.force(false); forces.add(elapsed(start));
            }
        } finally { Files.deleteIfExists(file); }
        return Map.of("directory", directory.toString(), "sequentialBytes", 32L * 1024 * 1024, "sequentialWithForceMs", sequential,
                "appendBytesPerForce", 4096, "forceLatencyMs", distribution(forces), "fileRemoved", !Files.exists(file));
    }

    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000d; }
    private static Map<String, Object> distribution(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        return Map.of("samples", sorted.size(), "median", sorted.get(sorted.size() / 2), "p95", sorted.get((int) Math.ceil(sorted.size() * .95) - 1), "max", sorted.getLast(), "values", values);
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> objects(Object value) { return (List<Map<String, Object>>) value; }
}
