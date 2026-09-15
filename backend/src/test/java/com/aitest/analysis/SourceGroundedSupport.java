package com.aitest.analysis;

import com.aitest.ai.ModelSettingsService;
import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.*;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

abstract class SourceGroundedSupport extends ExchangeHttpTest {
    @TempDir Path temporary;
    @Autowired JobService jobs;
    @Autowired ModelSettingsService settings;
    protected String snapshot(String project) throws Exception {
        return snapshot(project, "CREATE TABLE orders(id BIGINT PRIMARY KEY, amount DECIMAL(12,2), state VARCHAR(20)); CREATE TABLE refunds(id BIGINT PRIMARY KEY, order_id BIGINT, amount DECIMAL(12,2));");
    }
    protected String snapshot(String project, String ddl) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(UUID.randomUUID().toString()));
        Files.writeString(root.resolve("Refund.java"), """
                package shop;
                @RestController class Refund {
                    @PostMapping("/refund") void refund(@Min(1) int amount) {
                        if (amount > 5000) throw new IllegalArgumentException("manual review required");
                    }
                }
                """);
        Files.writeString(root.resolve("Refund.vue"), """
                <template><input placeholder="退款金额" />
                <button v-if="allowed" data-testid="refund-submit">退款</button>
                <div data-testid="refund-target">完成</div></template>
                """);
        var response = request("POST", "/api/projects/" + project + "/source-analyses", Map.of("backendRepoPath", root.toString(), "frontendRepoPath", root.toString(),
                "ddlText", ddl, "idempotencyKey", UUID.randomUUID().toString()));
        assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
        var submission = object(response); assertThat(finished(project, submission.get("jobId").toString()).status()).isEqualTo("SUCCEEDED");
        Files.writeString(root.resolve("Refund.java"), "class LaterSource { void changed() {} }");
        Files.writeString(root.resolve("Refund.vue"), "<template><button data-testid=\"later-only\">Later</button></template>");
        return submission.get("analysisId").toString();
    }
    protected void configure(ModelFixtureServer model) { settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30)); }
    protected Job finished(String project, String id) { await().atMost(Duration.ofSeconds(40)).until(() -> jobs.get(project, id).terminal()); return jobs.get(project, id); }
    protected Map<String, Object> add(String type, String key, String parent, String name, Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("operation", "ADD"); result.put("targetType", type); result.put("localKey", key); result.put("parentId", parent); result.put("name", name); result.put("data", data); return result;
    }
    protected String changes(Map<String, Object>... proposals) { return json.write(Map.of("changes", List.of(proposals))); }
    protected Map<String, Object> pipeline(String project, String id) throws Exception { return object(request("GET", "/api/ai/pipelines/" + id + "?projectId=" + project, null)); }
    protected Map<String, Object> terminalPipeline(String project, String id) {
        await().atMost(Duration.ofSeconds(75)).until(() -> Set.of("COMPLETED", "COMPLETED_WITH_GAPS", "FAILED", "CANCELLED", "INTERRUPTED").contains(pipeline(project, id).get("status")));
        try { return pipeline(project, id); } catch (Exception error) { throw new AssertionError(error); }
    }
}
