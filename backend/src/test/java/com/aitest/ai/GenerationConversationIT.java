package com.aitest.ai;

import com.aitest.asset.AssetService;
import com.aitest.common.JsonCodec;
import com.aitest.job.JobService;
import com.aitest.support.ModelFixtureServer;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class GenerationConversationIT extends MySqlIntegrationTest {
    static final ModelFixtureServer model;
    static { try { model = new ModelFixtureServer(); } catch (Exception failure) { throw new ExceptionInInitializerError(failure); } }
    @Autowired AssetService assets;
    @Autowired ModelSettingsService settings;
    @Autowired JobService jobs;
    @Autowired AiConversationService conversations;
    @Autowired JsonCodec json;
    @LocalServerPort int port;
    @AfterAll static void closeModel() { model.close(); }

    @Test void explicitGenerationConversationsAreIndependentAndCannotCrossTheirProjectOrType() throws Exception {
        settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30));
        var project = assets.createProject("Conversation " + UUID.randomUUID(), Map.of());
        String oldId = "generation_" + UUID.randomUUID(), newId = "generation_" + UUID.randomUUID();
        for (String id : List.of(oldId, newId)) {
            model.enqueue(json.write(Map.of("changes", List.of(Map.of("operation", "ADD", "targetType", "QUALITY_BRIEF", "localKey", "brief", "name", "生成结果", "data", Map.of("content", "普通内容"))))));
            var response = post(Map.of("projectId", project.id(), "type", "QUALITY_BRIEF", "instruction", id, "conversationId", id, "idempotencyKey", id));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            var accepted = json.map(response.body()); String jobId = accepted.get("jobId").toString();
            await().atMost(Duration.ofSeconds(30)).until(() -> jobs.get(project.id(), jobId).terminal());
            assertThat(jobs.get(project.id(), jobId).status()).as(jobs.get(project.id(), jobId).error()).isEqualTo("SUCCEEDED");
            assertThat(accepted.get("conversationId")).isEqualTo(id);
            assertThat(conversations.messages(project.id(), id).stream().filter(message -> message.get("role").equals("user")).map(message -> message.get("content"))).containsExactly(id);
        }
        assertThat(model.requests.getLast()).doesNotContain(oldId);
        var other = assets.createProject("Other " + UUID.randomUUID(), Map.of());
        assertThat(post(Map.of("projectId", other.id(), "type", "QUALITY_BRIEF", "instruction", "越界", "conversationId", newId, "idempotencyKey", "other-project")).statusCode()).isIn(404, 409);
        assertThat(post(Map.of("projectId", project.id(), "type", "BUG", "instruction", "越界", "conversationId", newId, "idempotencyKey", "other-type")).statusCode()).isEqualTo(409);
        assertThat(model.requests).hasSize(2);
    }
    private HttpResponse<String> post(Object body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/generate")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
