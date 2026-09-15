package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class PageEvidenceIT extends ExchangeHttpTest {
    @Autowired JobService jobs;
    @Test void capturesRealDynamicLocatorsWithoutPasswordsOrInventedPages() throws Exception {
        String project = project().id();
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", request -> {
            byte[] body = "<meta charset=utf-8><title>退款验收</title><h1>订单退款</h1><input id='password' type='password' value='never-expose-821'><div id='content'></div><script>document.getElementById('content').innerHTML='<button id=refund data-testid=refund-button>确认退款</button>'</script>".getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().add("Content-Type", "text/html;charset=utf-8"); request.sendResponseHeaders(200, body.length); request.getResponseBody().write(body); request.close();
        }); site.start();
        try {
            String url = "http://127.0.0.1:" + site.getAddress().getPort();
            Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "真实页面", Map.of("baseUrl", url, "webUrl", url), "MANUAL");
            var submission = request("POST", "/api/projects/" + project + "/environments/" + environment.id() + "/page-evidence", Map.of("idempotencyKey", "page"));
            assertThat(submission.statusCode()).as(new String(submission.body(), StandardCharsets.UTF_8)).isEqualTo(200);
            String jobId = object(submission).get("jobId").toString();
            await().atMost(Duration.ofSeconds(40)).until(() -> jobs.get(project, jobId).terminal());
            var job = jobs.get(project, jobId);
            assertThat(job.status()).withFailMessage("%s", job.error()).isEqualTo("SUCCEEDED");
            String captured = json.write(job.result());
            assertThat(captured).contains("退款验收", "确认退款", "#refund", "testId=refund-button").doesNotContain("never-expose-821");
            var file = request("GET", "/api/projects/" + project + "/files/" + job.result().get("fileId"), null);
            assertThat(file.statusCode()).isEqualTo(200);
            assertThat(new String(file.body(), StandardCharsets.UTF_8)).contains("#refund").doesNotContain("never-expose-821");
            assertThat(assets.list(project, AssetType.UI_SCENARIO, null, "", 0, 100).items()).isEmpty();
        } finally { site.stop(0); }
    }
}
