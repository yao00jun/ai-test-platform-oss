package com.aitest.workbench;

import com.aitest.exchange.ExchangeHttpTest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MorningBriefContractIT extends ExchangeHttpTest {
    @Test void scheduleStartsDisabledAndConfigurationUsesCompareAndSet() throws Exception {
        String project = project().id(), path = path(project);
        var first = request("GET", path, null);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(object(first)).containsEntry("version", "0").containsEntry("enabled", false).containsEntry("nextFireAt", null);
        var saved = request("PUT", path, config("0", true));
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(object(saved)).containsEntry("version", "1").containsEntry("enabled", true).containsEntry("timezone", "Asia/Shanghai");
        assertThat(Instant.parse(object(saved).get("nextFireAt").toString())).isAfter(Instant.now());
        assertThat(request("PUT", path, config("0", false)).statusCode()).isEqualTo(409);
        var disabled = request("PUT", path, config("1", false));
        assertThat(disabled.statusCode()).isEqualTo(200);
        assertThat(object(disabled)).containsEntry("version", "2").containsEntry("enabled", false).containsEntry("nextFireAt", null);
    }

    @Test void invalidTimeTimezoneTypesAndUnknownFieldsCannotChangeSettings() throws Exception {
        String project = project().id(), path = path(project);
        assertThat(request("PUT", path, config("0", false)).statusCode()).isEqualTo(200);
        Map<String, Object> original = object(request("GET", path, null));
        for (var bad : List.of(Map.entry("time", "8:00"), Map.entry("time", "24:00"), Map.entry("timezone", "No/Such_Zone"),
                Map.entry("maxRetries", 4), Map.entry("maxRetries", 1.5), Map.entry("enabled", "true"), Map.entry("instruction", " "), Map.entry("modelName", "unauthorized-model"))) {
            var candidate = new LinkedHashMap<String, Object>(config("1", true)); candidate.put(bad.getKey(), bad.getValue());
            assertThat(request("PUT", path, candidate).statusCode()).as(bad.toString()).isEqualTo(422);
            assertThat(object(request("GET", path, null))).isEqualTo(original);
        }
    }

    @Test void historyIsEmptyUntilDispatchedAndScopedToAnActiveProject() throws Exception {
        String project = project().id();
        var response = request("GET", "/api/projects/" + project + "/morning-brief/history?offset=0&limit=20", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(object(response)).containsEntry("items", List.of()).containsEntry("total", 0);
        assertThat(request("GET", "/api/projects/" + project + "/morning-brief/history?limit=101", null).statusCode()).isEqualTo(422);
        assertThat(request("POST", "/api/projects/" + project + "/morning-brief/occurrences/unknown/retry", Map.of("idempotencyKey", "retry")).statusCode()).isEqualTo(404);
        var owner = assets.get(project, project); assets.delete(project, project, owner.version());
        assertThat(request("GET", path(project), null).statusCode()).isEqualTo(404);
    }

    private String path(String project) { return "/api/projects/" + project + "/morning-brief/schedule"; }
    private Map<String, Object> config(String version, boolean enabled) {
        return Map.of("baseVersion", version, "enabled", enabled, "time", "08:00", "timezone", "Asia/Shanghai", "instruction", "分析前一天的真实测试结果和待核验风险", "maxRetries", 2);
    }
}
