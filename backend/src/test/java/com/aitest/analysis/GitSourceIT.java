package com.aitest.analysis;

import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static com.aitest.support.GitFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class GitSourceIT extends ExchangeHttpTest {
    @TempDir Path root;
    @Autowired JobService jobs;

    @Test void explicitHeadAndBaselineCaptureCommitsWithoutCheckingOutOrExecutingHooks() throws Exception {
        Path repo = Files.createDirectory(root.resolve("版本源码"));
        run(repo, "init");
        Files.writeString(repo.resolve("Limit.java"), "package shop; class Limit { int minimum() { return 1; } }");
        String base = commit(repo, "baseline");
        Files.writeString(repo.resolve("Limit.java"), "package shop; class Limit { int minimum() { return 10; } }");
        String head = commit(repo, "head");
        Files.writeString(repo.resolve("Limit.java"), "package shop; class Limit { int minimum() { return 999; } }");
        Path marker = root.resolve("executed-hook");
        Path hooks = Files.createDirectories(repo.resolve("hooks"));
        Files.writeString(hooks.resolve("post-checkout"), "#!/bin/sh\necho bad > '" + marker.toString().replace('\\', '/') + "'\n");
        run(repo, "config", "core.hooksPath", hooks.toString());
        String project = project().id();
        var snapshot = analyze(project, Map.of("backendRepoPath", repo.toString(), "backendRef", head, "baselineRef", base, "idempotencyKey", "git-commits"));
        assertThat(snapshot.get("status")).isEqualTo("READY");
        var origin = map(map(map(snapshot.get("result")).get("origins")).get("BACKEND"));
        assertThat(origin).containsEntry("revision", head).containsEntry("baselineRevision", base);
        String id = snapshot.get("id").toString();
        assertThat(excerpt(project, id, "BACKEND")).contains("return 10").doesNotContain("return 999");
        assertThat(excerpt(project, id, "BASELINE")).contains("return 1;");
        assertThat(Files.readString(repo.resolve("Limit.java"))).contains("return 999");
        assertThat(marker).doesNotExist();
        assertThat(run(repo, "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(request("POST", path(project), Map.of("backendRepoPath", repo.toString(), "backendRef", "--upload-pack=bad", "idempotencyKey", "unsafe-ref")).statusCode()).isEqualTo(422);
    }

    @Test void httpRepositoryIsReadThroughGitObjectsAndNetworkFailureIsAnExplicitFailedJob() throws Exception {
        Path work = Files.createDirectory(root.resolve("working")); run(work, "init");
        Files.writeString(work.resolve("Remote.java"), "package shop; class Remote { String name() { return \"from-http\"; } }");
        String revision = commit(work, "remote fixture");
        Path bare = root.resolve("repo.git"); run(root, "clone", "--bare", work.toString(), bare.toString()); run(bare, "update-server-info");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repo.git/", exchange -> {
            requests.incrementAndGet();
            Path file = bare.resolve(exchange.getRequestURI().getPath().substring("/repo.git/".length())).normalize();
            if (!file.startsWith(bare) || !Files.isRegularFile(file)) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            byte[] bytes = Files.readAllBytes(file); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            String project = project().id();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/repo.git";
            var snapshot = analyze(project, Map.of("backendRepoPath", url, "idempotencyKey", "remote"));
            assertThat(snapshot.get("status")).isEqualTo("READY");
            assertThat(map(map(map(snapshot.get("result")).get("origins")).get("BACKEND"))).containsEntry("revision", revision);
            assertThat(json.write(snapshot)).contains("shop.Remote");
            assertThat(requests.get()).isGreaterThan(0);
            var response = request("POST", path(project), Map.of("backendRepoPath", url + "/missing", "idempotencyKey", "missing"));
            assertThat(response.statusCode()).isEqualTo(200);
            var submission = object(response);
            await().atMost(Duration.ofSeconds(30)).until(() -> jobs.get(project, submission.get("jobId").toString()).terminal());
            assertThat(jobs.get(project, submission.get("jobId").toString()).status()).isEqualTo("FAILED");
        } finally { server.stop(0); }
    }

    private String path(String project) { return "/api/projects/" + project + "/source-analyses"; }
    private Map<String, Object> analyze(String project, Map<String, Object> input) throws Exception {
        var response = request("POST", path(project), input);
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        var submission = object(response);
        await().atMost(Duration.ofSeconds(40)).until(() -> jobs.get(project, submission.get("jobId").toString()).terminal());
        assertThat(jobs.get(project, submission.get("jobId").toString()).status()).as(jobs.get(project, submission.get("jobId").toString()).error()).isEqualTo("SUCCEEDED");
        return object(request("GET", path(project) + "/" + submission.get("analysisId"), null));
    }
    private String excerpt(String project, String id, String kind) throws Exception { return object(request("GET", path(project) + "/" + id + "/file?kind=" + kind + "&path=Limit.java&from=1&to=1", null)).get("content").toString(); }
}
