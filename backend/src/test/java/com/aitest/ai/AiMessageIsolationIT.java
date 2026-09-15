package com.aitest.ai;

import com.aitest.asset.AssetService;
import com.aitest.asset.AssetType;
import com.aitest.common.Ids;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** A decision in one conversation must not scan and lock another conversation's messages. */
@TestPropertySource(properties = {
        "aitest.schedules.enabled=false", "aitest.morning-brief.enabled=false", "aitest.notifications.enabled=false"
})
class AiMessageIsolationIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired AiConversationService conversations;
    @Autowired AiChangeSetService changes;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @ParameterizedTest
    @ValueSource(strings = {"APPLY", "REJECT"})
    void decidingADraftDoesNotWaitForAnUnrelatedLockedConversation(String decision) throws Exception {
        String project = assets.createProject("Message isolation " + Ids.newId(), Map.of()).id();
        String otherProject = assets.createProject("Unrelated conversation " + Ids.newId(), Map.of()).id();
        String conversation = conversations.ensure(null, project, "GLOBAL", null, null);
        String otherConversation = conversations.ensure(null, otherProject, "GLOBAL", null, null);
        String jobId = Ids.newId();
        String changeId = changes.create(project, conversation, jobId, List.of(
                new AiChangeSetService.Proposal("ADD", AssetType.FUNCTIONAL_CASE, null, null,
                        "case", null, "Selected case", Map.of("precondition", "Human choice"))));
        conversations.recordAssistant(conversation, jobId, "Candidate", "PREVIEW", null, null,
                Map.of(), Map.of("valid", true), "fixture", "fixture");

        // The old PRIMARY scan visits this unrelated row before ordinary UUID rows.
        String otherMessageId = "00000000" + Ids.newId().substring(0, 24);
        jdbc.update("INSERT INTO ai_message(id,conversation_id,job_id,role,content,status,created_at) "
                        + "VALUES(?,?,?,'assistant','Keep unrelated draft','PREVIEW',UTC_TIMESTAMP(3))",
                otherMessageId, otherConversation, Ids.newId());
        Map<String, Object> original = jdbc.queryForMap("SELECT * FROM ai_message WHERE id=?", otherMessageId);
        var originalAsset = assets.get(otherProject, otherProject);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = workers.submit(() -> transactions.executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM ai_message WHERE id=? FOR UPDATE", String.class, otherMessageId);
                locked.countDown();
                try { assertThat(release.await(40, TimeUnit.SECONDS)).as("Test must release its own row lock").isTrue(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var selected = AiGenerationService.changeIds(changes.get(project, changeId));
                var applied = workers.submit(() -> transactions.executeWithoutResult(tx -> {
                    Integer previousTimeout = jdbc.queryForObject("SELECT @@session.innodb_lock_wait_timeout", Integer.class);
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                    try {
                        if (decision.equals("APPLY")) changes.apply(project, changeId, selected);
                        else changes.reject(project, changeId);
                    } finally { jdbc.execute("SET SESSION innodb_lock_wait_timeout=" + previousTimeout); }
                }));
                // The short database lock timeout distinguishes cross-conversation locking
                // from general machine speed. The future has a separate, larger watchdog.
                assertThatCode(() -> applied.get(20, TimeUnit.SECONDS))
                        .as("A draft decision must complete while an unrelated conversation remains locked")
                        .doesNotThrowAnyException();
                String expected = decision.equals("APPLY") ? "APPLIED" : "REJECTED";
                assertThat(changes.get(project, changeId)).containsEntry("status", expected);
                assertThat(jdbc.queryForObject("SELECT status FROM ai_message WHERE job_id=? AND role='assistant'", String.class, jobId)).isEqualTo(expected);
                assertThat(assets.list(project, AssetType.FUNCTIONAL_CASE, null, "", 0, 10).total()).isEqualTo(decision.equals("APPLY") ? 1 : 0);
            } finally { release.countDown(); holder.get(10, TimeUnit.SECONDS); }
        }
        assertThat(jdbc.queryForMap("SELECT * FROM ai_message WHERE id=?", otherMessageId)).isEqualTo(original);
        assertThat(assets.get(otherProject, otherProject)).isEqualTo(originalAsset);
    }
}
