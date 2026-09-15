package com.aitest.support;

import com.aitest.common.JsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real application JVM and disposable MySQL schema, with no in-test Spring scheduler. */
public final class IsolatedApplication implements AutoCloseable {
    public final JsonCodec json = new JsonCodec();
    public final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public final Path root = Path.of("..").toAbsolutePath().normalize();
    public final Path evidence;
    public final JdbcTemplate jdbc;
    private final String schema = "ai_test_acceptance_" + UUID.randomUUID().toString().replace("-", "");
    private final String dbUrl, username, password, adminUrl, adminUser, adminPassword;
    private final boolean ownsSchema;
    private final int port;
    private final Set<ProcessHandle> observedChildren = ConcurrentHashMap.newKeySet();
    private Set<ProcessHandle> launchChildren = Set.of();
    private Process process;
    private int starts;
    private final String shutdownToken = UUID.randomUUID().toString() + UUID.randomUUID();

    public IsolatedApplication() throws Exception {
        evidence = Files.createDirectories(root.resolve(".runtime/acceptance/" + schema));
        String supplied = System.getenv("AI_TEST_PROCESS_DB_URL");
        if (supplied != null && !supplied.isBlank()) {
            // An explicitly supplied schema is never dropped. Require it to be empty before use.
            dbUrl = supplied; username = Objects.requireNonNull(System.getenv("AI_TEST_DB_USER"));
            password = Objects.requireNonNull(System.getenv("AI_TEST_DB_PASSWORD"));
            adminUrl = adminUser = adminPassword = null; ownsSchema = false;
            try (Connection connection = DriverManager.getConnection(dbUrl, username, password);
                 ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                if (tables.next()) throw new IllegalStateException("AI_TEST_PROCESS_DB_URL must name an empty, isolated test schema");
            }
        } else {
            Path configPath = root.resolve(".runtime/mysql/connection.json"), adminPath = root.resolve(".runtime/mysql/admin.cnf");
            if (!Files.isRegularFile(configPath) || !Files.isRegularFile(adminPath))
                throw new IllegalStateException("Run scripts/bootstrap-mysql.ps1 or provide an empty AI_TEST_PROCESS_DB_URL schema");
            Map<String, Object> config = json.map(Files.readString(configPath));
            username = config.get("username").toString(); password = config.get("password").toString();
            if (!username.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Unexpected local test account name");
            Properties admin = new Properties();
            for (String line : Files.readAllLines(adminPath)) {
                int split = line.indexOf('=');
                if (split > 0) admin.setProperty(line.substring(0, split).strip(), line.substring(split + 1).strip());
            }
            String prefix = "jdbc:mysql://127.0.0.1:" + config.get("port") + "/";
            String options = "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8";
            adminUrl = prefix + "mysql" + options; adminUser = admin.getProperty("user"); adminPassword = admin.getProperty("password");
            dbUrl = prefix + schema + options; ownsSchema = true;
            try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                statement.execute("GRANT ALL ON `" + schema + "`.* TO '" + username + "'@'localhost'");
            }
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(dbUrl, username, password));
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
    }

    public void start() throws Exception { start(Map.of()); }
    public void start(Map<String, String> properties) throws Exception { start(properties, 512); }
    public void start(Map<String, String> properties, int heapMb) throws Exception {
        if (process != null && process.isAlive()) throw new IllegalStateException("Application already running");
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(path -> Path.of(path).toAbsolutePath().normalize()).filter(path -> !path.endsWith(Path.of("target/test-classes")))
                .map(Path::toString).reduce((left, right) -> left + File.pathSeparator + right).orElseThrow();
        Path args = evidence.resolve("application.args");
        Files.writeString(args, "-Xms128m\n-Xmx" + heapMb + "m\n-XX:ActiveProcessorCount=4\n-Dfile.encoding=UTF-8\n-cp\n\""
                + classpath.replace("\\", "/").replace("\"", "\\\"") + "\"\ncom.aitest.AiTestApplication\n");
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = new ArrayList<>(List.of(java, "@" + args));
        var configuration = new LinkedHashMap<>(Map.of("aitest.schedules.enabled", "false", "aitest.morning-brief.enabled", "false", "aitest.notifications.enabled", "false",
                "spring.datasource.hikari.maximum-pool-size", "8", "management.endpoints.web.exposure.include", "health,metrics"));
        configuration.putAll(properties); configuration.forEach((key, value) -> command.add("--" + key + "=" + value));
        Path log = evidence.resolve("application-" + (++starts) + ".log");
        var builder = new ProcessBuilder(command).directory(root.resolve("backend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var environment = builder.environment();
        environment.keySet().removeIf(name -> name.startsWith("AI_TEST_") || name.startsWith("SPRING_"));
        environment.putAll(Map.of("AI_TEST_DB_URL", dbUrl, "AI_TEST_DB_USER", username, "AI_TEST_DB_PASSWORD", password,
                "AI_TEST_STORAGE", evidence.resolve("storage").toString(), "AI_TEST_PORT", Integer.toString(port), "AI_TEST_BIND", "127.0.0.1",
                "AI_TEST_CONCURRENCY", "1", "AI_TEST_BROWSER_WORKERS", "1", "AI_TEST_BROWSER_PATH", root.resolve(".tools/playwright-1.62.0").toString()));
        environment.put("AI_TEST_SHUTDOWN_TOKEN", shutdownToken);
        process = builder.start();
        Files.writeString(evidence.resolve("processes.txt"), process.pid() + " " + log.getFileName() + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        await().atMost(Duration.ofSeconds(starts == 1 ? 180 : 60)).pollInterval(Duration.ofMillis(200)).ignoreExceptionsMatching(error -> error instanceof java.io.IOException).untilAsserted(() -> {
            assertThat(process.isAlive()).as("Application exited; inspect %s", log).isTrue();
            assertThat(request("GET", "/actuator/health", null).statusCode()).isEqualTo(200);
        });
        // Windows creates a console host for the application JVM itself. It legitimately
        // lives until that JVM exits and must not be counted as a browser worker leak.
        launchChildren = Set.copyOf(descendants());
    }

    public List<ProcessHandle> descendants() {
        List<ProcessHandle> children = process.descendants().toList(); observedChildren.addAll(children); return children;
    }
    public List<ProcessHandle> workerDescendants() {
        return descendants().stream().filter(child -> !launchChildren.contains(child)).toList();
    }
    public List<ProcessHandle> crash() throws Exception {
        List<ProcessHandle> children = descendants();
        process.destroyForcibly(); assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        return children; // Deliberately leave children alone: recovery tests must catch orphan leaks.
    }
    public URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    public HttpResponse<String> shutdown(boolean validToken) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/internal/lifecycle/stop")).timeout(Duration.ofSeconds(10))
                .header("X-AITest-Shutdown-Token", validToken ? shutdownToken : "invalid-token")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    public int awaitExit(Duration timeout) throws Exception {
        assertThat(process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)).as("Application must exit within %s", timeout).isTrue();
        return process.exitValue();
    }
    public HttpResponse<String> request(String method, String path, Object body) throws Exception {
        return request(method, path, body, Duration.ofSeconds(45));
    }
    public HttpResponse<String> request(String method, String path, Object body, Duration timeout) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(timeout).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    public Map<String, Object> call(String method, String path, Object body, int status) throws Exception {
        var response = request(method, path, body);
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
        return json.map(response.body());
    }
    public String project() throws Exception { return call("POST", "/api/projects", Map.of("name", "独立进程验收"), 201).get("id").toString(); }
    public Map<String, Object> asset(String project, String type, String parent, String name, Map<String, Object> data) throws Exception {
        var input = new LinkedHashMap<String, Object>(Map.of("type", type, "name", name, "data", data));
        if (parent != null) input.put("parentId", parent);
        return call("POST", "/api/projects/" + project + "/assets", input, 201);
    }
    public Map<String, Object> asset(String project, String id) throws Exception { return call("GET", "/api/projects/" + project + "/assets/" + id, null, 200); }
    public Map<String, Object> job(String project, String id) throws Exception { return call("GET", "/api/jobs/" + id + "?projectId=" + project, null, 200); }
    public void finished(String project, String id, String status) {
        await().atMost(Duration.ofSeconds(50)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> assertThat(job(project, id)).containsEntry("status", status));
    }
    public void model(ModelFixtureServer model) throws Exception {
        call("PUT", "/api/settings/model", Map.of("baseUrl", model.url(), "apiKey", "isolated-fixture", "modelName", "fixture", "temperature", 0.1, "timeoutSeconds", 45), 200);
    }
    public Map<String, Object> database(String project, int maxPoolSize) throws Exception {
        return asset(project, "DATABASE_SOURCE", null, "隔离验收数据库", Map.of("jdbcUrl", dbUrl, "username", username, "password", password, "maxPoolSize", maxPoolSize));
    }
    public Map<String, Object> preview(String project, String type, String format, byte[] bytes) throws Exception {
        String boundary = "Acceptance" + UUID.randomUUID(); ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (var field : Map.of("type", type, "format", format).entrySet())
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"acceptance." + format + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes); out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var response = http.send(HttpRequest.newBuilder(uri("/api/projects/" + project + "/imports/preview")).timeout(Duration.ofMinutes(3))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return json.map(response.body());
    }
    private Connection adminConnection() throws SQLException { return DriverManager.getConnection(adminUrl, adminUser, adminPassword); }
    @Override public void close() throws Exception {
        if (process != null) {
            observedChildren.addAll(process.descendants().toList());
            observedChildren.stream().toList().reversed().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            observedChildren.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
        }
        http.close();
        if (ownsSchema) {
            if (!schema.matches("ai_test_acceptance_[a-f0-9]{32}")) throw new IllegalStateException("Invalid disposable schema identity");
            try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
                statement.execute("REVOKE ALL ON `" + schema + "`.* FROM '" + username + "'@'localhost'");
                statement.execute("DROP DATABASE `" + schema + "`");
            }
        }
    }
}
