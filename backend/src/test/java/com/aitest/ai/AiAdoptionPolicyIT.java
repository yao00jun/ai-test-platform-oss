package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties = "aitest.schedules.enabled=false")
class AiAdoptionPolicyIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired AiConversationService conversations;
    @Autowired AiChangeSetService changes;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;

    @Test void candidatesSavedBeforeAProtectionUpgradeAreCheckedAgainAtAdoption() {
        String project = assets.createProject("Adoption policy " + UUID.randomUUID(), Map.of()).id();
        String conversation = conversations.ensure(null, project, "GLOBAL", null, null);
        for (AssetType type : List.of(AssetType.TEST_PLAN, AssetType.QUALITY_BRIEF)) {
            Asset original = assets.create(project, type, null, "已保存资产", type == AssetType.TEST_PLAN ? Map.of("cronExpression", "0 0 9 * * *") : Map.of("metrics", Map.of("itemCount", 3)), "MANUAL");
            String id = changes.create(project, conversation, "fixture", List.of(new AiChangeSetService.Proposal("MODIFY", type, original.id(), null, null, original.version(), "文字调优", Map.of())));
            // Represents a draft persisted by the older policy, before activation/facts became protected.
            var oldData = type == AssetType.TEST_PLAN ? Map.of("scheduleEnabled", true) : Map.of("metrics", Map.of("itemCount", 999));
            jdbc.update("UPDATE ai_change_item SET after_snapshot=? WHERE change_set_id=?", json.write(Map.of("name", "旧版本的候选", "data", oldData)), id);
            var selection = jdbc.queryForList("SELECT id FROM ai_change_item WHERE change_set_id=?", String.class, id);
            assertThatThrownBy(() -> changes.apply(project, id, selection)).isInstanceOf(Problem.class);
            assertThat(assets.get(project, original.id())).isEqualTo(original);
        }
    }
}
