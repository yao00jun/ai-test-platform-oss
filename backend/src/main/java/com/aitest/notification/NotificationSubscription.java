package com.aitest.notification;

import com.aitest.asset.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;

/** AssetService already holds the project lock for every mutation, including imports. */
@Component
public final class NotificationSubscription implements AssetObserver {
    private final JdbcTemplate jdbc;
    public NotificationSubscription(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void changed(Asset previous, Asset current) {
        if (current.type() != AssetType.WEBHOOK) return;
        boolean enabled = Boolean.TRUE.equals(current.data().get("enabled"));
        var rows = jdbc.queryForList("SELECT event_cursor,enabled FROM notification_subscription WHERE webhook_id=? FOR UPDATE", current.id());
        boolean continuing = !rows.isEmpty() && enabled && Boolean.TRUE.equals(rows.getFirst().get("enabled"));
        var newest = continuing ? java.util.List.<Long>of() : jdbc.queryForList("SELECT seq FROM run_completion_event WHERE project_id=? ORDER BY seq DESC LIMIT 1 FOR UPDATE", Long.class, current.projectId());
        long cursor = continuing ? ((Number) rows.getFirst().get("event_cursor")).longValue() : newest.isEmpty() ? 0 : newest.getFirst();
        jdbc.update("INSERT INTO notification_subscription(webhook_id,project_id,enabled,config_version,event_cursor,updated_at) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),config_version=VALUES(config_version),event_cursor=VALUES(event_cursor),updated_at=VALUES(updated_at)",
                current.id(), current.projectId(), enabled, Long.parseLong(current.version()), cursor, Timestamp.from(Instant.now()));
    }

    @Override public void deleted(Asset asset) {
        if (asset.type() == AssetType.PROJECT) jdbc.update("UPDATE notification_subscription SET enabled=FALSE,updated_at=? WHERE project_id=?", Timestamp.from(Instant.now()), asset.id());
        else if (asset.type() == AssetType.WEBHOOK) jdbc.update("UPDATE notification_subscription SET enabled=FALSE,config_version=?,updated_at=? WHERE webhook_id=?", Long.parseLong(asset.version()), Timestamp.from(Instant.now()), asset.id());
    }
}
