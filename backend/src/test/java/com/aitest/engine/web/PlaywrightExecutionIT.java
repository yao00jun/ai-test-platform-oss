package com.aitest.engine.web;
import org.junit.jupiter.api.Tag;

import com.aitest.asset.*;
import com.aitest.common.JsonCodec;
import com.aitest.execution.*;
import com.aitest.job.JobService;
import com.aitest.storage.FileStorageService;
import com.aitest.support.MySqlIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Tag("slow")
class PlaywrightExecutionIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired ExecutionCoordinator coordinator;
    @Autowired RunRepository runs;
    @Autowired JobService jobs;
    @Autowired FileStorageService files;
    @Autowired JsonCodec json;

    @Test void realBrowserKeepsFrameAndPopupStepsAndFailureEvidence() throws Exception {
        String project = assets.createProject("Browser " + UUID.randomUUID(), Map.of()).id();
        try (Site site = new Site()) {
            Asset env = create(project, AssetType.ENVIRONMENT, "测试站点", null,
                    Map.of("baseUrl", site.url(), "webUrl", site.url(), "variables", Map.of("password", "Secret-browser-value-129")));
            Asset scenario = create(project, AssetType.UI_SCENARIO, "登录与子页面", null, Map.of("baseUrl", site.url()));
            step(project, scenario, "进入页面", Map.of("action", "navigate", "url", "/"));
            step(project, scenario, "填写账号", Map.of("action", "fill", "selector", "label=账号", "value", "测试员"));
            step(project, scenario, "填写密码", Map.of("action", "fill", "selector", "label=密码", "value", "${password}"));
            step(project, scenario, "登录", Map.of("action", "click", "selector", "role=button[name=登录]"));
            step(project, scenario, "确认登录", Map.of("action", "assertText", "selector", "testId=status", "expected", "欢迎 测试员"));
            step(project, scenario, "读取框架", Map.of("action", "assertText", "frame", "#inner", "selector", "#inside", "expected", "框架就绪"));
            step(project, scenario, "打开弹窗", Map.of("action", "popup", "selector", "text=打开详情", "saveAs", "details"));
            step(project, scenario, "检查弹窗", Map.of("action", "assertText", "pageAlias", "details", "selector", "h1", "expected", "订单详情"));
            step(project, scenario, "关闭弹窗", Map.of("action", "closePage", "pageAlias", "details"));
            var ok = coordinator.submit(project, new ExecutionCoordinator.Request(scenario.id(), env.id(), null, UUID.randomUUID().toString()));
            Map<String, Object> passed = finished(project, ok);
            assertThat(passed.get("status")).withFailMessage("%s", json.write(passed)).isEqualTo("PASSED");
            List<Map<String, Object>> passedSteps = Values.objects(Values.objects(passed.get("items")).getFirst().get("steps"));
            assertThat(passedSteps).hasSize(9).allSatisfy(result -> assertThat(result.get("status")).isEqualTo("PASSED"));
            assertThat(json.write(passed)).doesNotContain("Secret-browser-value-129");

            step(project, scenario, "失败证据", Map.of("action", "assertText", "selector", "testId=status", "expected", "不会出现的文本", "timeoutMs", 500));
            step(project, scenario, "失败后跳过", Map.of("action", "click", "selector", "#missing", "timeoutMs", 500));
            var bad = coordinator.submit(project, new ExecutionCoordinator.Request(scenario.id(), env.id(), null, UUID.randomUUID().toString()));
            Map<String, Object> failed = finished(project, bad);
            assertThat(failed.get("status")).isEqualTo("FAILED");
            List<Map<String, Object>> failedSteps = Values.objects(Values.objects(failed.get("items")).getFirst().get("steps"));
            assertThat(failedSteps).hasSize(11);
            assertThat(failedSteps.get(9).get("status")).isEqualTo("FAILED");
            assertThat(failedSteps.get(10).get("status")).isEqualTo("SKIPPED");
            Map<String, Object> failedResult = Values.map(failedSteps.get(9).get("result"));
            List<?> ids = (List<?>) failedResult.get("artifactIds");
            assertThat(ids).hasSizeGreaterThanOrEqualTo(3);
            boolean sawPng = false, sawTrace = false;
            for (Object id : ids) {
                var artifact = files.get(project, id.toString());
                assertThat(artifact.size()).isPositive();
                if (artifact.mediaType().equals("image/png")) {
                    sawPng = true;
                    assertThat(Files.readAllBytes(artifact.path())).startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
                } else if (artifact.name().endsWith(".zip")) {
                    sawTrace = true;
                    try (var zip = new ZipInputStream(Files.newInputStream(artifact.path()))) {
                        while (zip.getNextEntry() != null) assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).doesNotContain("Secret-browser-value-129");
                    }
                }
            }
            assertThat(sawPng).isTrue(); assertThat(sawTrace).isTrue();
        }
    }

    @Test void advancedActionsUploadOriginalFilenameAndPersistDownloadedContent() throws Exception {
        String project = assets.createProject("Browser controls " + UUID.randomUUID(), Map.of()).id();
        try (Site site = new Site()) {
            var upload = files.save(project, "附件.txt", "text/plain", "受管附件".getBytes(StandardCharsets.UTF_8));
            Asset scenario = create(project, AssetType.UI_SCENARIO, "常见录制动作", null, Map.of("baseUrl", site.url()));
            step(project, scenario, "打开控件", Map.of("action", "navigate", "url", "/advanced"));
            step(project, scenario, "双击", Map.of("action", "dblclick", "selector", "#double"));
            step(project, scenario, "校验双击", Map.of("action", "assertText", "selector", "#double", "expected", "双击完成"));
            step(project, scenario, "选择", Map.of("action", "selectOption", "selector", "select", "value", "b"));
            step(project, scenario, "校验选择", Map.of("action", "assertValue", "selector", "select", "expected", "b"));
            step(project, scenario, "数量", Map.of("action", "assertCount", "selector", "option", "expected", "2"));
            step(project, scenario, "等待", Map.of("action", "waitFor", "selector", "#upload"));
            step(project, scenario, "上传", Map.of("action", "upload", "selector", "#upload", "fileIds", List.of(upload.id())));
            step(project, scenario, "校验文件名", Map.of("action", "assertText", "selector", "#filename", "expected", "附件.txt"));
            step(project, scenario, "下载", Map.of("action", "download", "selector", "#download"));
            var submitted = coordinator.submit(project, new ExecutionCoordinator.Request(scenario.id(), null, null, UUID.randomUUID().toString()));
            var run = finished(project, submitted);
            assertThat(run.get("status")).withFailMessage("%s", json.write(run)).isEqualTo("PASSED");
            var steps = Values.objects(Values.objects(run.get("items")).getFirst().get("steps"));
            var downloads = (List<?>) Values.map(steps.getLast().get("result")).get("artifactIds");
            assertThat(downloads).hasSize(1);
            assertThat(Files.readString(files.get(project, downloads.getFirst().toString()).path())).isEqualTo("download-verification");
        }
    }

    @Test void cancellationReclaimsOnlyItsOwnWorkerAndAnotherRunCompletes() throws Exception {
        String project = assets.createProject("Browser cancellation " + UUID.randomUUID(), Map.of()).id();
        try (Site site = new Site()) {
            Asset stalled = create(project, AssetType.UI_SCENARIO, "取消等待", null, Map.of("baseUrl", site.url()));
            step(project, stalled, "进入", Map.of("action", "navigate", "url", "/"));
            step(project, stalled, "等待不存在按钮", Map.of("action", "click", "selector", "#absent", "timeoutMs", 120000));
            Set<Long> baseline = ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
            var first = coordinator.submit(project, new ExecutionCoordinator.Request(stalled.id(), null, null, UUID.randomUUID().toString()));
            try {
            await().atMost(Duration.ofSeconds(30)).until(() -> !Values.objects(Values.objects(runs.get(project, first.runId()).get("items")).getFirst().get("steps")).isEmpty());
            // ProcessHandle.Info.arguments is optional and empty on Windows; parentage is portable.
            Set<Long> owned = ProcessHandle.current().descendants().map(ProcessHandle::pid).filter(pid -> !baseline.contains(pid)).collect(java.util.stream.Collectors.toSet());
            assertThat(owned).isNotEmpty();
            Set<Long> descendants = new HashSet<>(owned);
            owned.forEach(pid -> ProcessHandle.of(pid).ifPresent(process -> process.descendants().forEach(child -> descendants.add(child.pid()))));
            Asset normal = create(project, AssetType.UI_SCENARIO, "并行正常", null, Map.of("baseUrl", site.url()));
            step(project, normal, "进入", Map.of("action", "navigate", "url", "/popup"));
            step(project, normal, "校验", Map.of("action", "assertText", "selector", "h1", "expected", "订单详情"));
            var second = coordinator.submit(project, new ExecutionCoordinator.Request(normal.id(), null, null, UUID.randomUUID().toString()));
            jobs.cancel(project, first.jobId());
            await().atMost(Duration.ofSeconds(10)).until(() -> descendants.stream().noneMatch(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)));
            assertThat(finished(project, first).get("status")).isEqualTo("CANCELLED");
            assertThat(finished(project, second).get("status")).isEqualTo("PASSED");
            } finally { jobs.cancel(project, first.jobId()); }
        }
    }

    private Map<String, Object> finished(String project, ExecutionCoordinator.Submission submission) {
        // cancel() marks the job terminal before the worker (or the 5 s reconciler) finishes the
        // run row, so wait for the run itself rather than the job.
        await().atMost(Duration.ofSeconds(90)).until(() -> jobs.get(project, submission.jobId()).terminal()
                && !Set.of("QUEUED", "RUNNING").contains(String.valueOf(runs.get(project, submission.runId()).get("status"))));
        return runs.get(project, submission.runId());
    }
    private Asset create(String project, AssetType type, String name, String parent, Map<String, Object> data) {
        return assets.create(project, type, parent, name, data, "MANUAL");
    }
    private void step(String project, Asset scenario, String name, Map<String, Object> data) { create(project, AssetType.UI_STEP, name, scenario.id(), data); }

    static final class Site implements AutoCloseable {
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Site() throws Exception {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/download")) {
                    byte[] content = "download-verification".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=verification.txt");
                    exchange.sendResponseHeaders(200, content.length); exchange.getResponseBody().write(content); exchange.close(); return;
                }
                String page = switch (exchange.getRequestURI().getPath()) {
                    case "/frame" -> "<p id='inside'>框架就绪</p>";
                    case "/popup" -> "<h1>订单详情</h1>";
                    case "/advanced" -> """
                            <meta charset="utf-8"><button id="double" ondblclick="this.textContent='双击完成'">双击</button>
                            <select><option value="a">A</option><option value="b">B</option></select>
                            <input id="upload" type="file" onchange="document.querySelector('#filename').textContent=this.files[0].name">
                            <p id="filename"></p><a id="download" href="/download">下载文件</a>
                            """;
                    default -> """
                            <!doctype html><meta charset="utf-8"><title>浏览器验收</title>
                            <label>账号<input id="user"></label><label>密码<input type="password"></label>
                            <button onclick="document.querySelector('[data-testid=status]').textContent='欢迎 '+document.querySelector('#user').value">登录</button>
                            <p data-testid="status">未登录</p><iframe id="inner" src="/frame"></iframe>
                            <a href="/popup" target="_blank">打开详情</a>
                            """;
                };
                byte[] body = page.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
