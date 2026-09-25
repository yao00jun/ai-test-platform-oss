package com.aitest.ai;

import com.aitest.asset.Asset;
import com.aitest.asset.AssetType;
import com.aitest.common.JsonCodec;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ModelContextBudgetTest {
    private final JsonCodec json = new JsonCodec();

    @Test void historyKeepsTheRecentTurnsAndTruncatesRawOutput() {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) messages.add(Map.of("id", "m" + i, "role", i % 2 == 0 ? "user" : "assistant", "status", "APPLIED", "content", "x".repeat(i == 29 ? 5000 : 10), "candidate", Map.of("changes", List.of())));
        var history = ModelContextBudget.history(messages);
        assertThat(history).hasSize(ModelContextBudget.HISTORY_MESSAGES);
        assertThat(history.getFirst()).containsEntry("id", "m18").doesNotContainKey("candidate");
        assertThat(history.getLast().get("content").toString()).startsWith("x".repeat(ModelContextBudget.MESSAGE_CHARS)).contains("已截断").hasSizeLessThan(2100);
    }

    @Test void assetsBeyondTheBudgetAreListedByNameAndOpenApiDocumentsAreSlimmed() {
        Map<String, Object> schema = Map.of("sourceFormat", "openapi", "operation", Map.of("summary", "分页"), "document", Map.of("paths", Map.of("/huge", "y".repeat(20_000))));
        Asset definition = asset("a1", AssetType.API_DEFINITION, Map.of("method", "POST", "path", "/p", "schema", schema));
        Asset large = asset("a2", AssetType.REQUIREMENT, Map.of("content", "z".repeat(5_000)));
        var listed = ModelContextBudget.assets(List.of(definition, large), 2_000, json);
        assertThat(json.write(listed.getFirst())).doesNotContain("/huge").contains("分页");
        assertThat(listed.get(1)).containsEntry("id", "a2").containsEntry("dataOmitted", true).doesNotContainKey("data");
    }

    private static Asset asset(String id, AssetType type, Map<String, Object> data) {
        return new Asset(id, "p", type, null, id, "1", 0, "MANUAL", false, Instant.EPOCH, Instant.EPOCH, data);
    }
}
