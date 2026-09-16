package com.aitest.job;

import com.aitest.asset.AssetService;
import com.aitest.common.Ids;
import com.aitest.common.Problem;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class JobEventPageIT extends MySqlIntegrationTest {
    @Autowired JobService jobs;
    @Autowired JobSignals signals;
    @Autowired AssetService assets;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @Test void terminalHistoryCanDrainMultiplePagesAndStillChecksTheProject() {
        String project = assets.createProject("SSE 分页", Map.of()).id();
        String job = running(project);
        transactions.executeWithoutResult(tx -> {
            var rows = new ArrayList<Object[]>();
            for (int i = 0; i < 1005; i++) rows.add(new Object[]{job, i == 1004 ? "done" : "progress", "{}", Timestamp.from(Instant.now())});
            jdbc.batchUpdate("INSERT INTO job_event(job_id,event_type,payload,created_at) VALUES(?,?,?,?)", rows);
            jdbc.update("UPDATE job_task SET status='SUCCEEDED' WHERE id=?", job);
        });
        var first = jobs.eventPage(project, job, 0);
        assertThat(first.terminal()).isTrue(); assertThat(first.events()).hasSize(1000);
        var tail = jobs.eventPage(project, job, first.events().getLast().seq());
        assertThat(tail.events()).hasSize(5); assertThat(tail.events().getLast().type()).isEqualTo("done");
        assertThat(tail.events()).allMatch(event -> event.seq() > first.events().getLast().seq());
        assertThat(jobs.eventPage(project, job, tail.events().getLast().seq()).events()).isEmpty();
        String other = assets.createProject("其他项目", Map.of()).id();
        assertThatThrownBy(() -> jobs.eventPage(other, job, 0)).isInstanceOf(Problem.class);
        jdbc.update("UPDATE project SET deleted=TRUE WHERE id=?", project);
        assertThatThrownBy(() -> jobs.eventPage(project, job, 0)).isInstanceOf(Problem.class);
    }

    @Test void rolledBackEventsNeverWakeReadersAndCommittedEventsAreVisibleWhenSignalled() throws Exception {
        String project = assets.createProject("SSE 事务通知", Map.of()).id();
        String job = running(project);
        try (var subscription = signals.subscribe(job)) {
            long original = subscription.version();
            transactions.executeWithoutResult(tx -> {
                jobs.event(job, project, "progress", Map.of("progress", 10));
                assertThat(subscription.version()).isEqualTo(original);
                tx.setRollbackOnly();
            });
            assertThat(subscription.version()).isEqualTo(original);
            assertThat(jobs.eventPage(project, job, 0).events()).isEmpty();
            transactions.executeWithoutResult(tx -> {
                jobs.event(job, project, "progress", Map.of("progress", 20));
                assertThat(subscription.version()).isEqualTo(original);
            });
            subscription.awaitChange(original, Duration.ofSeconds(1));
            assertThat(subscription.version()).isGreaterThan(original);
            assertThat(jobs.eventPage(project, job, 0).events()).singleElement()
                    .satisfies(event -> assertThat(event.data()).containsEntry("progress", 20));
        }
    }

    private String running(String project) {
        String id = Ids.newId(); Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO job_task(id,project_id,kind,idempotency_key,input,status,owner,lease_until,created_at,updated_at) VALUES(?,?,?,?,'{}','RUNNING','event-test',?,?,?)",
                id, project, "TEST_EVENTS", id, Timestamp.from(Instant.now().plusSeconds(600)), now, now);
        return id;
    }
}
