package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AiGraphChangesIT extends MySqlIntegrationTest {
    @Autowired AssetService assets; @Autowired AiConversationService conversations; @Autowired AiChangeSetService changes;
    @Test void generationTypesHaveSeparateStableConversationsInTheSameParent() {
        String project = assets.createProject("Generation identities " + UUID.randomUUID(), Map.of()).id();
        String cases = conversations.ensure(null, project, "GENERATE", null, "FUNCTIONAL_CASE");
        String apis = conversations.ensure(null, project, "GENERATE", null, "API_CASE");
        assertThat(apis).isNotEqualTo(cases);
        assertThat(conversations.ensure(null, project, "GENERATE", null, "FUNCTIONAL_CASE")).isEqualTo(cases);
    }
    @Test void coordinatedExportRenameRequiresAllConsumersAndAppliesAtomically() {
        String project = assets.createProject("Global graph " + UUID.randomUUID(), Map.of()).id();
        Asset producer = assets.create(project, AssetType.API_CASE, null, "Producer", Map.of("path", "/order", "extractors", List.of(Map.of("variable", "oldId", "jsonpath", "$.id"))), "MANUAL");
        Asset consumer = assets.create(project, AssetType.API_CASE, null, "Consumer", Map.of("path", "/order/${oldId}"), "MANUAL");
        String conversation = conversations.ensure(null, project, "GLOBAL", null, null);
        String id = changes.create(project, conversation, "graph-fixture", List.of(
                new AiChangeSetService.Proposal("MODIFY", producer.type(), producer.id(), null, null, producer.version(), null, Map.of("extractors", List.of(Map.of("variable", "newId", "jsonpath", "$.id")))),
                new AiChangeSetService.Proposal("MODIFY", consumer.type(), consumer.id(), null, null, consumer.version(), null, Map.of("path", "/order/${newId}"))));
        List<String> selected = AiGenerationService.changeIds(changes.get(project, id));
        assertThatThrownBy(() -> changes.apply(project, id, selected.subList(0, 1))).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> changes.apply(project, id, selected.subList(1, 2))).isInstanceOf(Problem.class);
        assertThat(assets.get(project, producer.id())).isEqualTo(producer); assertThat(assets.get(project, consumer.id())).isEqualTo(consumer);
        changes.apply(project, id, selected);
        assertThat(assets.get(project, producer.id()).data().get("extractors")).isEqualTo(List.of(Map.of("variable", "newId", "jsonpath", "$.id")));
        assertThat(assets.get(project, consumer.id()).data()).containsEntry("path", "/order/${newId}");
        assertThat(assets.get(project, producer.id()).version()).isEqualTo("2");
    }
}
