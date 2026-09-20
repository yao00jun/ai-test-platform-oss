package com.aitest.security;

import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.common.Ids;
import com.aitest.job.JobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the actual servlet filter chain, cookies, HTTP endpoints and MySQL writes. */
@TestPropertySource(properties = {
        "aitest.security.enabled=true", "aitest.security.username=workspace-owner",
        "aitest.security.password=fixture-password-2026!",
        "spring.web.resources.static-locations=classpath:/spa-fixture/",
        "aitest.schedules.enabled=false", "aitest.morning-brief.enabled=false", "aitest.notifications.enabled=false"
})
class PlatformAuthenticationIT extends ExchangeHttpTest {
    private static final String PASSWORD = "fixture-password-2026!";
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient browser = HttpClient.newBuilder().cookieHandler(cookies).build();
    @Autowired JobService jobs;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"/api/projects", "/api/catalog", "/api/settings/model",
            "/api/exchange/capabilities", "/api/projects/unknown/exports/unknown/download",
            "/api/jobs/unknown/events?projectId=unknown", "/actuator/info"})
    void anonymousRequestsCannotReadAssetsSettingsDownloadsOrEventStreams(String path) throws Exception {
        var response = request("GET", path, null);
        assertThat(response.statusCode()).as(path).isEqualTo(401);
        assertThat(object(response)).containsEntry("code", "AUTHENTICATION_REQUIRED");
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test void sessionBootstrapProvidesCsrfWithoutDisclosingAccountOrCredentials() throws Exception {
        var response = send("GET", "/api/auth/session", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(object(response)).containsEntry("enabled", true).containsEntry("authenticated", false)
                .containsEntry("username", null).containsEntry("csrfHeader", "X-CSRF-TOKEN");
        assertThat(object(response).get("csrfToken")).isInstanceOf(String.class).asString().isNotBlank();
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).doesNotContain(PASSWORD, "workspace-owner");
    }

    @Test void staticLoginShellAndHealthRemainReachableButLifecycleKeepsItsOwnTokenGuard() throws Exception {
        assertThat(request("GET", "/projects", null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/favicon.svg", null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/internal/lifecycle/stop", null).statusCode()).isEqualTo(404);
    }

    @Test void loginRequiresCsrfAndIncorrectCredentialsNeverCreateAnAuthenticatedSession() throws Exception {
        String csrf = csrf();
        assertThat(login(PASSWORD, null).statusCode()).isEqualTo(403);
        var wrong = login("incorrect-password", csrf);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(object(wrong)).containsEntry("code", "INVALID_CREDENTIALS");
        assertThat(object(send("GET", "/api/auth/session", null, null))).containsEntry("authenticated", false);
        assertThat(send("GET", "/api/projects", null, null).statusCode()).isEqualTo(401);
    }

    @Test void oversizedLoginInputIsRejectedAsCredentialsInsteadOfAnInternalServerError() throws Exception {
        var response = login("x".repeat(1000), csrf());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(object(response)).containsEntry("code", "INVALID_CREDENTIALS");
    }

    @Test void loginRotatesSessionAndAllowsRealWritesOnlyWithTheNewCsrfToken() throws Exception {
        String beforeToken = csrf();
        String beforeSession = sessionCookie();
        assertThat(login(PASSWORD, beforeToken).statusCode()).isEqualTo(200);
        String afterToken = csrf();
        assertThat(sessionCookie()).isNotEqualTo(beforeSession);
        assertThat(object(send("GET", "/api/auth/session", null, null)))
                .containsEntry("authenticated", true).containsEntry("username", "workspace-owner");
        assertThat(send("GET", "/api/projects", null, null).statusCode()).isEqualTo(200);
        var body = Map.of("name", "Authenticated project");
        assertThat(send("POST", "/api/projects", body, null).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/projects", body, beforeToken).statusCode()).isEqualTo(403);
        var created = send("POST", "/api/projects", body, afterToken);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = object(created).get("id").toString();
        assertThat(assets.get(id, id).name()).isEqualTo("Authenticated project");
        assets.delete(id, id, assets.get(id, id).version());
    }

    @Test void logoutInvalidatesTheOldCookieAndNeverSucceedsWithoutCsrf() throws Exception {
        assertThat(login(PASSWORD, csrf()).statusCode()).isEqualTo(200);
        String token = csrf();
        String cookie = sessionCookie();
        assertThat(send("POST", "/api/auth/logout", null, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/projects", null, null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/auth/logout", null, token).statusCode()).isEqualTo(204);
        var stale = http.send(HttpRequest.newBuilder(uri("/api/projects"))
                .header("Cookie", "AI_TEST_SESSION=" + cookie).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(stale.statusCode()).isEqualTo(401);
        assertThat(object(send("GET", "/api/auth/session", null, null))).containsEntry("authenticated", false);
    }

    @Test void unauthenticatedWritesReturnAnActionableSessionErrorAndDoNotMutate() throws Exception {
        String name = "Forbidden write " + UUID.randomUUID();
        var response = send("POST", "/api/projects", Map.of("name", name), null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(object(response)).containsEntry("code", "AUTHENTICATION_REQUIRED");
        assertThat(assets.projects()).noneMatch(project -> project.name().equals(name));
    }

    @Test void logoutClosesAnAlreadyOpenEventStreamWithoutCancellingThePersistedJob() throws Exception {
        assertThat(login(PASSWORD, csrf()).statusCode()).isEqualTo(200);
        String token = csrf();
        var project = project();
        String jobId = Ids.newId();
        jdbc.update("INSERT INTO job_task(id,project_id,kind,idempotency_key,input,status,owner,lease_until,created_at,updated_at) "
                        + "VALUES(?,?,'AUTH_STREAM',?,'{}','RUNNING','stream-fixture',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 HOUR),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                jobId, project.id(), UUID.randomUUID().toString());
        jobs.event(jobId, project.id(), "progress", Map.of("message", "before logout"));
        var response = browser.send(HttpRequest.newBuilder(uri("/api/jobs/" + jobId + "/events?projectId=" + project.id()))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            assertThat(response.statusCode()).isEqualTo(200);
            var content = CompletableFuture.supplyAsync(() -> {
                try { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            assertThat(send("POST", "/api/auth/logout", null, token).statusCode()).isEqualTo(204);
            jobs.event(jobId, project.id(), "progress", Map.of("message", "private after logout"));
            assertThat(content).succeedsWithin(Duration.ofSeconds(5)).asString()
                    .contains("before logout").doesNotContain("private after logout");
            assertThat(jobs.get(project.id(), jobId).status()).isEqualTo("RUNNING");
        } finally {
            jobs.cancel(project.id(), jobId);
            assets.delete(project.id(), project.id(), assets.get(project.id(), project.id()).version());
        }
    }

    @Test void theLocalOperationsClientAuthenticatesAndLogsOutThroughTheProtectedApi() throws Exception {
        Path script = Files.createTempFile(Path.of("../.runtime"), "auth-operations-", ".ps1");
        String common = Path.of("../scripts/aitest.ps1").toAbsolutePath().normalize().toString().replace("'", "''");
        Files.writeString(script, """
                $ErrorActionPreference = 'Stop'
                . '%s'
                $settings = [pscustomobject]@{ Port = %d; Config = @{security=@{enabled=$true; username='workspace-owner'; password='fixture-password-2026!'}} }
                $base = 'http://127.0.0.1:%d'
                $headers = New-AiTestApiHeaders -Settings $settings -BaseUrl $base
                $session = Invoke-RestMethod -Uri "$base/api/auth/session" -Headers $headers -NoProxy
                if (-not $session.authenticated -or $session.username -ne 'workspace-owner') { throw 'Operations authentication failed' }
                $null = Invoke-RestMethod -Uri "$base/api/auth/logout" -Method Post -Headers $headers -NoProxy
                $expired = Invoke-WebRequest -Uri "$base/api/projects" -Headers $headers -SkipHttpErrorCheck -NoProxy
                if ($expired.StatusCode -ne 401) { throw 'Operations session remained usable after logout' }
                Write-Output 'OPERATIONS_AUTHENTICATED_AND_CLOSED'
                """.formatted(common, port, port));
        Process process = new ProcessBuilder("pwsh.exe", "-NoProfile", "-NonInteractive", "-File", script.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("OPERATIONS_AUTHENTICATED_AND_CLOSED").doesNotContain(PASSWORD);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(script);
        }
    }

    private String csrf() throws Exception {
        var response = send("GET", "/api/auth/session", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return object(response).get("csrfToken").toString();
    }
    private String sessionCookie() {
        return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("AI_TEST_SESSION"))
                .findFirst().orElseThrow().getValue();
    }
    private HttpResponse<byte[]> login(String password, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(uri("/api/auth/login")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
        return browser.send(builder.POST(HttpRequest.BodyPublishers.ofString("username=workspace-owner&password="
                + URLEncoder.encode(password, StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private HttpResponse<byte[]> send(String method, String path, Object body, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(body)));
        return browser.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
