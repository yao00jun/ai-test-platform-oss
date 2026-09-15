package com.aitest.workbench;

import com.aitest.ai.ModelSettingsService;
import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.*;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class DashboardCardsIT extends ExchangeHttpTest {
    static final ModelFixtureServer model;
    static { try { model = new ModelFixtureServer(); } catch (Exception failure) { throw new ExceptionInInitializerError(failure); } }
    @Autowired ModelSettingsService settings;
    @Autowired JobService jobs;
    @AfterAll static void closeModel() { model.close(); }

    @Test void legacyTemplateCardsAreNormalizedWithoutChangingTheirIdentityOrOrder() {
        String project = project().id();
        Asset layout = layout(project, List.of(Map.of("id", "cases", "type", "metric", "title", "测试用例", "metric", "cases"),
                Map.of("id", "bugs", "type", "metric", "title", "待处理缺陷", "metric", "openBugs")));
        var cards = objects(layout.data().get("cards"));
        assertThat(cards).extracting(value -> value.get("id")).containsExactly("cases", "bugs");
        assertThat(cards.getFirst()).containsEntry("schemaVersion", "aitest.dashboard-card/v1").containsEntry("size", "small").containsEntry("visible", true).containsEntry("fields", List.of("value"));
    }

    @Test void invalidCardConfigurationCannotOverwriteTheSavedLayout() {
        String project = project().id();
        Asset original = layout(project, List.of(card("q", "quality", List.of("runCount", "passRatePercent"))));
        var valid = card("q", "quality", List.of("runCount"));
        List<List<?>> invalid = new ArrayList<>();
        invalid.add(List.of(valid, valid));
        invalid.add(List.of(override(valid, "type", "arbitrary-html")));
        invalid.add(List.of(override(valid, "schemaVersion", "aitest.dashboard-card/v99")));
        invalid.add(List.of(override(valid, "fields", List.of("forgedPassRate"))));
        invalid.add(List.of(override(valid, "fields", List.of("runCount", "runCount"))));
        invalid.add(List.of(override(valid, "visible", "false")));
        invalid.add(List.of(override(valid, "value", 100)));
        invalid.add(List.of(override(valid, "id", "")));
        invalid.add(List.of(override(valid, "title", " ")));
        invalid.add(List.of(override(valid, "size", "10000px")));
        invalid.add(Collections.nCopies(51, valid));
        for (List<?> cards : invalid) {
            assertThatThrownBy(() -> assets.update(project, original.id(), original.version(), null, Map.of("cards", cards), null, "MANUAL")).isInstanceOf(Problem.class);
            assertThat(assets.get(project, original.id())).isEqualTo(original);
        }
    }

    @Test void cardsRoundTripInJsonAndMarkdownWithTheirDisplaySettingsAndOrder() throws Exception {
        String project = project().id();
        Asset layout = layout(project, List.of(card("eval", "evalops", List.of("invocations", "totalTokens")), override(card("quality", "quality", List.of("passRatePercent")), "visible", false)));
        for (String format : List.of("json", "md")) {
            var exported = request("POST", "/api/projects/" + project + "/exports", Map.of("type", "DASHBOARD", "assetIds", List.of(layout.id()), "format", format));
            assertThat(exported.statusCode()).isEqualTo(200);
            String destination = project().id();
            var preview = preview(destination, "DASHBOARD", format, "layout." + format, exported.body(), Map.of());
            assertThat(objects(preview.get("errors"))).isEmpty(); apply(destination, preview);
            assertThat(assets.list(destination, AssetType.DASHBOARD, null, "", 0, 10).items().getFirst().data()).isEqualTo(layout.data());
        }
    }

    @Test void summaryOpenBugMetricExcludesResolvedClosedAndOtherProjects() throws Exception {
        String project = project().id(), other = project().id();
        for (String status : List.of("OPEN", "IN_PROGRESS", "REOPENED", "RESOLVED", "CLOSED")) assets.create(project, AssetType.BUG, null, status, Map.of("status", status), "MANUAL");
        assets.create(other, AssetType.BUG, null, "不属于本项目", Map.of(), "MANUAL");
        Map<String, Object> summary = object(request("GET", "/api/projects/" + project + "/summary", null));
        assertThat(summary).containsEntry("openBugCount", 3);
    }

    @Test void consecutiveLayoutFeedbackUsesTheCurrentVersionAndCannotModifyAnotherLayout() throws Exception {
        settings.save(new ModelSettingsService.Input(model.url(), "dashboard-fixture", "fixture", 0.1, 30));
        String project = project().id();
        Asset original = layout(project, List.of(card("q", "quality", List.of("runCount")), card("a", "assets", List.of("FUNCTIONAL_CASE"))));
        Asset sibling = layout(project, List.of(card("b", "bugs", List.of("name", "status"))));
        Asset current = original;
        for (int round = 1; round <= 3; round++) {
            var cards = objects(current.data().get("cards"));
            List<Map<String, Object>> candidate = List.of(override(cards.getFirst(), "title", "质量概览第 " + round + " 轮"), cards.get(1));
            model.enqueue(json.write(Map.of("data", Map.of("cards", candidate))));
            var response = request("POST", "/api/ai/refine-item", Map.of("projectId", project, "targetType", "DASHBOARD", "targetId", current.id(), "baseVersion", current.version(), "feedback", "优化当前布局标题 " + round, "targetFields", List.of("cards"), "idempotencyKey", UUID.randomUUID().toString()));
            assertThat(response.statusCode()).as(object(response).toString()).isEqualTo(202);
            var submission = object(response);
            String jobId = submission.get("jobId").toString();
            await().atMost(Duration.ofSeconds(30)).until(() -> jobs.get(project, jobId).terminal());
            assertThat(jobs.get(project, jobId).status()).as(jobs.get(project, jobId).error()).isEqualTo("SUCCEEDED");
            current = assets.get(project, current.id());
            assertThat(current.id()).isEqualTo(original.id());
            assertThat(objects(current.data().get("cards"))).extracting(value -> value.get("id")).containsExactly("q", "a");
            assertThat(objects(current.data().get("cards")).get(1)).isEqualTo(objects(original.data().get("cards")).get(1));
            assertThat(assets.get(project, sibling.id())).isEqualTo(sibling);
        }
    }

    private Asset layout(String project, List<?> cards) { return assets.create(project, AssetType.DASHBOARD, null, "质量布局", Map.of("cards", cards, "notes", "人工维护"), "MANUAL"); }
    private Map<String, Object> card(String id, String type, List<String> fields) { return Map.of("schemaVersion", "aitest.dashboard-card/v1", "id", id, "type", type, "title", "卡片 " + id, "size", "wide", "visible", true, "fields", fields); }
    private Map<String, Object> override(Map<String, Object> source, String key, Object value) { var result = new LinkedHashMap<>(source); result.put(key, value); return result; }
}
