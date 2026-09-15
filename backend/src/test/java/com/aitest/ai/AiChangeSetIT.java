package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AiChangeSetIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired AiConversationService conversations;
    @Autowired AiChangeSetService changes;

    @Test void onlyReferenceFieldsResolveLocalKeysAndApplicationIsIdempotent() {
        String project = assets.createProject("Changes " + UUID.randomUUID(), Map.of()).id();
        String conversation = conversations.ensure(null, project, "GLOBAL", null, null);
        String id = changes.create(project, conversation, "fixture", List.of(
                new AiChangeSetService.Proposal("ADD", AssetType.FUNCTIONAL_STEP, null, "@case", "step", null, "@literal name", Map.of("step", "@click", "expected", "@success")),
                new AiChangeSetService.Proposal("ADD", AssetType.FUNCTIONAL_CASE, null, null, "case", null, "@user", Map.of("precondition", "@login", "tags", List.of("@tag")))));
        List<String> selection = itemIds(project, id);
        changes.apply(project, id, selection);
        changes.apply(project, id, selection);
        Asset parent = assets.list(project, AssetType.FUNCTIONAL_CASE, null, "", 0, 10).items().getFirst();
        assertThat(parent.name()).isEqualTo("@user");
        assertThat(parent.data()).containsEntry("precondition", "@login");
        assertThat(assets.children(project, parent.id())).singleElement().satisfies(step -> {
            assertThat(step.name()).isEqualTo("@literal name");
            assertThat(step.data()).containsEntry("step", "@click");
        });
    }

    @Test void oneConflictRollsBackAllSelectedChangesAndConfirmedAssetsAreProtected() {
        String project = assets.createProject("Atomic changes " + UUID.randomUUID(), Map.of()).id();
        Asset first = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "first", Map.of(), "MANUAL");
        Asset second = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "second", Map.of(), "MANUAL");
        String conversation = conversations.ensure(null, project, "GLOBAL", null, null);
        var a = modify(first); var b = modify(second);
        String id = changes.create(project, conversation, "fixture", List.of(a, b));
        Asset human = assets.update(project, second.id(), second.version(), "human", Map.of(), true, "MANUAL");
        assertThatThrownBy(() -> changes.apply(project, id, itemIds(project, id))).isInstanceOf(Problem.class);
        assertThat(assets.get(project, first.id())).isEqualTo(first);
        assertThat(assets.get(project, second.id())).isEqualTo(human);
        assertThatThrownBy(() -> changes.create(project, conversation, "protected", List.of(modify(human)))).isInstanceOf(Problem.class);
    }
    private AiChangeSetService.Proposal modify(Asset a) {
        return new AiChangeSetService.Proposal("MODIFY", a.type(), a.id(), a.parentId(), null, a.version(), "AI", Map.of());
    }
    @SuppressWarnings("unchecked") private List<String> itemIds(String project, String id) {
        return ((List<Map<String, Object>>) changes.get(project, id).get("items")).stream().map(i -> i.get("id").toString()).toList();
    }
}
