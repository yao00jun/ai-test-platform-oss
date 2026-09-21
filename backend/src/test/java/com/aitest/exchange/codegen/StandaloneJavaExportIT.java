package com.aitest.exchange.codegen;

import com.aitest.asset.*;
import com.aitest.exchange.*;
import com.aitest.support.MySqlIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class StandaloneJavaExportIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired ExchangeService exchange;
    @Autowired com.aitest.execution.ExecutionCoordinator coordinator;
    @Autowired com.aitest.execution.RunRepository runs;
    @Autowired com.aitest.job.JobService jobs;
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path output;

    @Test void exportedProjectCompilesAndActuallyRunsOutsideThePlatform() throws Exception {
        String project = assets.createProject("Independent Java " + UUID.randomUUID(), Map.of()).id();
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger visits = new AtomicInteger();
        try (ExecutorService serverThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            site.setExecutor(serverThreads);
            site.createContext("/", request -> {
                visits.incrementAndGet();
                byte[] page = "<meta charset='utf-8'><label>账号（必填）<input id='user'></label><button onclick=\"document.querySelector('#status').textContent=document.querySelector('#user').value\">登录</button><p id='status'>未登录</p>".getBytes(StandardCharsets.UTF_8);
                request.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                request.sendResponseHeaders(200, page.length); request.getResponseBody().write(page); request.close();
            });
            site.start();
            String base = "http://127.0.0.1:" + site.getAddress().getPort();
            Asset scenario = assets.create(project, AssetType.UI_SCENARIO, null, "独立登录", Map.of("baseUrl", base), "MANUAL");
            step(project, scenario, Map.of("action", "navigate", "url", "/"));
            step(project, scenario, Map.of("action", "fill", "selector", "label=账号", "exactMatch", false, "value", "${username}"));
            step(project, scenario, Map.of("action", "click", "selector", "role=button[name=登录]"));
            Asset assertion = step(project, scenario, Map.of("action", "assertText", "selector", "#status", "expected", "测试员", "timeoutMs", 1000));
            Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "原生语义校验", Map.of("baseUrl", base, "variables", Map.of("username", "测试员")), "MANUAL");
            var nativeRun = coordinator.submit(project, new com.aitest.execution.ExecutionCoordinator.Request(scenario.id(), environment.id(), null, UUID.randomUUID().toString()));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> jobs.get(project, nativeRun.jobId()).terminal());
            assertThat(runs.get(project, nativeRun.runId()).get("status")).as("Native execution must preserve imported substring matching").isEqualTo("PASSED");
            ExportFile exported = assertDoesExport(project, scenario, "java-project");
            Map<String, byte[]> files = ExchangeIO.unzip(exported.bytes(), "standalone.zip");
            assertThat(files).containsKeys("pom.xml", "src/main/java/ExportedTest.java", "README.md");
            for (var entry : files.entrySet()) {
                Path file = output.resolve(entry.getKey()).normalize();
                assertThat(file.startsWith(output)).as("ZIP entry must remain in its destination").isTrue();
                Files.createDirectories(file.getParent()); Files.write(file, entry.getValue());
            }
            Path classes = output.resolve("classes"); Files.createDirectories(classes);
            String dependencies = Arrays.stream(System.getProperty("surefire.test.class.path").split(java.io.File.pathSeparator))
                    .filter(path -> path.replace('\\', '/').contains("/com/microsoft/playwright/") || path.replace('\\', '/').contains("/com/google/code/gson/") || path.replace('\\', '/').contains("/org/opentest4j/"))
                    .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            assertThat(dependencies).isNotBlank();
            assertThat(run(List.of(javaTool("javac"), "--release", "21", "-encoding", "UTF-8", "-cp", dependencies, "-d", classes.toString(), output.resolve("src/main/java/ExportedTest.java").toString()), "compile.log")).isZero();
            // Pass the Chinese variable through a UTF-8 JSON file: on an English-locale Windows (GitHub runners) a non-ASCII
            // command-line -D value is mangled by the ANSI code page before the exported program sees it.
            Files.writeString(output.resolve("variables.json"), "{\"username\":\"测试员\"}", java.nio.charset.StandardCharsets.UTF_8);
            List<String> command = List.of(javaTool("java"), "-Daitest.variables=" + output.resolve("variables.json"), "-cp", classes + java.io.File.pathSeparator + dependencies, "ExportedTest");
            assertThat(run(command, "run.log")).withFailMessage(Files.readString(output.resolve("run.log"))).isZero();
            assertThat(visits).hasValueGreaterThan(0);
            assets.update(project, assertion.id(), assertion.version(), null, Map.of("expected", "必定失败"), null, "MANUAL");
            Files.write(output.resolve("src/main/java/ExportedTest.java"), assertDoesExport(project, scenario, "java").bytes());
            assertThat(run(List.of(javaTool("javac"), "--release", "21", "-encoding", "UTF-8", "-cp", dependencies, "-d", classes.toString(), output.resolve("src/main/java/ExportedTest.java").toString()), "compile-failure.log")).isZero();
            assertThat(run(command, "failed-run.log")).isNotZero();
        } finally { site.stop(0); }
    }

    private ExportFile assertDoesExport(String project, Asset scenario, String format) {
        final ExportFile[] result = new ExportFile[1];
        assertThatCode(() -> result[0] = exchange.export(project, new ExchangeService.ExportRequest(List.of(scenario.id()), AssetType.UI_SCENARIO, format, Map.of()))).doesNotThrowAnyException();
        return result[0];
    }
    private Asset step(String project, Asset scenario, Map<String, Object> data) { return assets.create(project, AssetType.UI_STEP, scenario.id(), data.get("action").toString(), data, "MANUAL"); }
    private String javaTool(String name) { return Path.of(System.getProperty("java.home"), "bin", name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString(); }
    private int run(List<String> command, String log) throws Exception {
        ProcessBuilder process = new ProcessBuilder(command).directory(output.toFile()).redirectErrorStream(true).redirectOutput(output.resolve(log).toFile());
        process.environment().put("PLAYWRIGHT_BROWSERS_PATH", Path.of("../.tools/playwright-1.62.0").toAbsolutePath().normalize().toString());
        // The exported program only launches Chromium; never let Playwright.create() download Firefox/WebKit inside the 45 s budget (CI installs Chromium only).
        process.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        Process child = process.start();
        try {
            boolean completed = child.waitFor(45, TimeUnit.SECONDS);
            assertThat(completed).withFailMessage("Exported process exceeded deadline. Artifacts: %s%n%s", output, Files.readString(output.resolve(log))).isTrue();
            return child.exitValue();
        }
        finally { child.descendants().forEach(ProcessHandle::destroyForcibly); if (child.isAlive()) child.destroyForcibly(); }
    }
}
