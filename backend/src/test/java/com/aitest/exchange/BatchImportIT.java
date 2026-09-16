package com.aitest.exchange;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Import(BatchImportIT.ObserverConfiguration.class)
class BatchImportIT extends ExchangeHttpTest {
    @Autowired JdbcTemplate jdbc;
    @Test void mixedBatchPreservesExistingPositionsReferencesEncryptedRevisionsAndObserverEffects() throws Exception {
        var project = project();
        var existing = assets.create(project.id(), AssetType.FUNCTIONAL_CASE, null, "已有人工用例", Map.of("remark", "保持不变"), "MANUAL");
        var later = new LinkedHashMap<>(node("later", "FUNCTIONAL_CASE", "后导入用例", null, Map.of(), Map.of())); later.put("position", 10);
        var earlier = new LinkedHashMap<>(node("earlier", "FUNCTIONAL_CASE", "先导入用例", null, Map.of(), Map.of())); earlier.put("position", 2);
        String password = "batch-private-password";
        var preview = preview(project.id(), "FUNCTIONAL_CASE", "json", bundle(List.of(later, earlier,
                node("sql", "SQL_VALIDATION", "SQL 引用", null, Map.of("sql", "SELECT 1 AS value"), Map.of("databaseSourceId", "database")),
                node("database", "DATABASE_SOURCE", "批量数据源", null, Map.of("jdbcUrl", "jdbc:mysql://127.0.0.1/fixture", "username", "fixture", "password", password), Map.of()),
                node("webhook", "WEBHOOK", "关闭的通知", null, Map.of("webhookUrl", "https://example.test/hook", "enabled", false), Map.of()))));
        assertThat(preview.get("status")).isEqualTo("READY");
        var applied = apply(project.id(), preview); var ids = map(applied.get("keyToId"));
        assertThat(applied.get("createdCount")).isEqualTo(5);
        assertThat(apply(project.id(), preview)).isEqualTo(applied);
        assertThat(assets.get(project.id(), existing.id())).isEqualTo(existing);
        assertThat(assets.get(project.id(), ids.get("earlier").toString()).position()).isEqualTo(1);
        assertThat(assets.get(project.id(), ids.get("later").toString()).position()).isEqualTo(2);
        assertThat(assets.get(project.id(), ids.get("sql").toString()).data()).containsEntry("databaseSourceId", ids.get("database"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset_relation WHERE from_id=? AND to_id=?", Long.class, ids.get("sql"), ids.get("database"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_subscription WHERE webhook_id=? AND enabled=FALSE", Long.class, ids.get("webhook"))).isEqualTo(1);
        for (Object id : ids.values()) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset_revision WHERE asset_id=? AND operation='CREATE' AND version=1", Long.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE asset_id=? AND action='CREATE' AND source='IMPORT'", Long.class, id)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT snapshot FROM asset_revision WHERE asset_id=?", String.class, ids.get("database"))).doesNotContain(password);
    }

    @Test void failureInTheLastObserverRollsBackEveryChunkRevisionRelationAndImportReceipt() throws Exception {
        var project = project(); var nodes = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 301; i++) nodes.add(node("case_" + i, "FUNCTIONAL_CASE", i == 300 ? "batch-reject-last" : "batch-probe-" + i, null, Map.of(), Map.of()));
        var preview = preview(project.id(), "FUNCTIONAL_CASE", "json", bundle(nodes));
        assertThat(preview.get("status")).isEqualTo("READY");
        long audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE project_id=?", Long.class, project.id());
        var response = request("POST", "/api/projects/" + project.id() + "/imports/" + preview.get("id") + "/apply", Map.of());
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(assets.all(project.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset_revision WHERE project_id=?", Long.class, project.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE project_id=?", Long.class, project.id())).isEqualTo(audits);
        assertThat(jdbc.queryForObject("SELECT status FROM import_job WHERE id=?", String.class, preview.get("id"))).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT result IS NULL FROM import_job WHERE id=?", Boolean.class, preview.get("id"))).isTrue();
    }

    @TestConfiguration static class ObserverConfiguration {
        @Bean AssetObserver batchFailureObserver(JdbcTemplate jdbc) {
            return (previous, current) -> {
                if (current.name().startsWith("batch-probe-")) jdbc.update("INSERT INTO audit_event(project_id,asset_id,action,source,detail,created_at) VALUES(?,?,'OBSERVER_PROBE','IMPORT','{}',?)", current.projectId(), current.id(), Timestamp.from(Instant.now()));
                if (current.name().equals("batch-reject-last")) throw Problem.invalid("测试观察者拒绝最后一条资产");
            };
        }
    }
}
