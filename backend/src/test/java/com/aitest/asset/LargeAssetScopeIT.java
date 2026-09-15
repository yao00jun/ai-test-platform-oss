package com.aitest.asset;

import com.aitest.common.Ids;
import com.aitest.common.Problem;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class LargeAssetScopeIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @Test void deletingTenThousandAndOneChildrenLeavesNoActiveOrphansAndIncompleteReorderFails() {
        String project = assets.createProject("Large scope " + UUID.randomUUID(), Map.of()).id();
        Asset parent = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "Parent", Map.of(), "MANUAL");
        List<Object[]> metadata = new ArrayList<>(), content = new ArrayList<>();
        Timestamp now = Timestamp.from(Instant.now());
        List<AssetService.OrderItem> incomplete = new ArrayList<>();
        for (int i = 0; i < 10001; i++) {
            String id = Ids.newId();
            metadata.add(new Object[]{id, project, parent.id(), "Step " + i, i, now, now});
            content.add(new Object[]{id, "Do " + i, "Expected " + i});
            if (i < 10000) incomplete.add(new AssetService.OrderItem(id, "1"));
        }
        transactions.executeWithoutResult(tx -> {
            jdbc.batchUpdate("INSERT INTO asset(id,project_id,asset_type,parent_id,name,version,position,source,confirmed,created_at,updated_at) VALUES(?,?,'FUNCTIONAL_STEP',?,?,1,?,'MANUAL',FALSE,?,?)", metadata);
            jdbc.batchUpdate("INSERT INTO functional_case_step(asset_id,step,expected) VALUES(?,?,?)", content);
        });
        assertThat(assets.children(project, parent.id())).hasSize(10001);
        assertThatThrownBy(() -> assets.reorder(project, AssetType.FUNCTIONAL_STEP, parent.id(), incomplete)).isInstanceOf(Problem.class);
        assets.delete(project, parent.id(), parent.version());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE project_id=? AND deleted=FALSE", Long.class, project)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset_revision WHERE project_id=? AND operation='DELETE'", Long.class, project)).isEqualTo(10002);
    }
}
