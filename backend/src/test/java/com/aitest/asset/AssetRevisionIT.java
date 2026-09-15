package com.aitest.asset;

import com.aitest.common.Problem;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class AssetRevisionIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;

    @Test
    void nullExecutableTargetsCannotBeSavedOrCreateNewRevisions() {
        Asset project = assets.createProject("Null target " + UUID.randomUUID(), Map.of());
        Asset scenario = assets.create(project.id(), AssetType.SCENARIO, null, "Scenario", Map.of(), "MANUAL");
        Map<String, Object> input = new java.util.HashMap<>(); input.put("stepType", "HTTP"); input.put("targetId", null);
        assertThatThrownBy(() -> assets.create(project.id(), AssetType.SCENARIO_STEP, scenario.id(), "Invalid", input, "MANUAL")).isInstanceOf(Problem.class);
        assertThat(assets.children(project.id(), scenario.id())).isEmpty();
    }

    @Test
    void updatingOneCaseLeavesNinetyNineSiblingsByteForByteUnchanged() {
        Asset project = assets.createProject("Isolation " + UUID.randomUUID(), Map.of("description", "revision fixture"));
        for (int i = 0; i < 100; i++) assets.create(project.id(), AssetType.FUNCTIONAL_CASE, null, "Case " + i, Map.of("priority", "P1"), "MANUAL");
        List<Asset> before = assets.list(project.id(), AssetType.FUNCTIONAL_CASE, null, "", 0, 200).items();
        Asset target = before.get(41);
        Asset updated = assets.update(project.id(), target.id(), target.version(), null, Map.of("precondition", "用户已登录"), null, "AI");
        List<Asset> after = assets.list(project.id(), AssetType.FUNCTIONAL_CASE, null, "", 0, 200).items();
        assertThat(updated.id()).isEqualTo(target.id());
        assertThat(updated.position()).isEqualTo(target.position());
        assertThat(updated.version()).isEqualTo("2");
        for (int i = 0; i < 100; i++) if (i != 41) assertThat(after.get(i)).isEqualTo(before.get(i));
    }

    @Test
    void concurrentUpdatesToSameVersionHaveExactlyOneWinner() throws Exception {
        Asset project = assets.createProject("CAS " + UUID.randomUUID(), Map.of());
        Asset target = assets.create(project.id(), AssetType.FUNCTIONAL_CASE, null, "Case", Map.of(), "MANUAL");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> edit = () -> {
                try { assets.update(project.id(), target.id(), "1", "Changed", Map.of(), null, "MANUAL"); return true; }
                catch (Problem problem) { assertThat(problem.status()).isEqualTo(409); return false; }
            };
            var results = executor.invokeAll(List.of(edit, edit));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(assets.history(project.id(), target.id())).hasSize(2);
    }

    @Test
    void staleUndoAndCrossProjectReadDoNotOverwriteOrLeakAssets() {
        Asset project = assets.createProject("Undo " + UUID.randomUUID(), Map.of());
        Asset other = assets.createProject("Other " + UUID.randomUUID(), Map.of());
        Asset item = assets.create(project.id(), AssetType.FUNCTIONAL_CASE, null, "Original", Map.of(), "MANUAL");
        Revision original = assets.history(project.id(), item.id()).getFirst();
        assets.update(project.id(), item.id(), "1", "Human change", Map.of(), null, "MANUAL");
        assertThatThrownBy(() -> assets.undo(project.id(), item.id(), "1", original.id())).isInstanceOf(Problem.class);
        assertThat(assets.get(project.id(), item.id()).name()).isEqualTo("Human change");
        assertThatThrownBy(() -> assets.get(other.id(), item.id())).isInstanceOf(Problem.class);
        Asset restored = assets.undo(project.id(), item.id(), "2", original.id());
        assertThat(restored.name()).isEqualTo("Original");
        assertThat(restored.version()).isEqualTo("3");
    }
}
