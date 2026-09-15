package com.aitest.notification;

import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.execution.Values;
import com.aitest.job.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Durable completion subscriptions. All domain mutations acquire the project lock first. */
@Service
public class NotificationService {
    public static final String KIND = "WEBHOOK_DELIVERY";
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Set<String> ACTIVE = Set.of("QUEUED", "SENDING");
    private final AssetRepository assets;
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final SecretProtector secrets;
    private final TransactionTemplate transactions;
    private final JobService jobs;
    private final boolean senderEnabled;
    private final int retryDelaySeconds;

    public NotificationService(AssetRepository assets, JdbcTemplate jdbc, JsonCodec json, SecretProtector secrets, TransactionTemplate transactions, JobService jobs,
                               @Value("${aitest.notifications.enabled:true}") boolean senderEnabled,
                               @Value("${aitest.notifications.retry-delay-seconds:30}") int retryDelaySeconds) {
        this.assets = assets; this.jdbc = jdbc; this.json = json; this.secrets = secrets; this.transactions = transactions; this.jobs = jobs;
        this.senderEnabled = senderEnabled; this.retryDelaySeconds = Math.clamp(retryDelaySeconds, 1, 3600);
    }

    @Scheduled(fixedDelayString = "${aitest.notifications.poll-interval-ms:1000}", initialDelayString = "${aitest.notifications.initial-delay-ms:1000}")
    public void poll() { sweep(Instant.now()); }

    public void sweep(Instant now) {
        var projects = jdbc.queryForList("SELECT DISTINCT project_id FROM notification_subscription WHERE enabled=TRUE UNION SELECT DISTINCT project_id FROM notification_delivery WHERE status IN ('SUBMITTED','SENDING','RETRY_WAIT')", String.class);
        for (String project : projects) try { transactions.executeWithoutResult(tx -> advance(project, now)); }
        catch (RuntimeException failure) { log.warn("Notification state {} could not advance ({})", project, failure.getClass().getSimpleName()); }
    }

    private void advance(String project, Instant now) {
        boolean activeProject = lockProjectForAudit(project);
        var pending = jdbc.query("SELECT * FROM notification_delivery WHERE project_id=? AND status IN ('SUBMITTED','SENDING','RETRY_WAIT') ORDER BY event_seq,id FOR UPDATE", (rs, n) -> delivery(rs), project);
        for (Delivery delivery : pending) {
            reconcile(delivery, now);
            delivery = find(project, delivery.webhookId(), delivery.id());
            List<Attempt> attempts = attempts(delivery.id());
            if (attempts.isEmpty() || "DELIVERED".equals(delivery.status())) continue;
            Attempt last = attempts.getLast();
            boolean current = activeProject && senderEnabled && currentConfig(project, delivery.webhookId(), last.configVersion());
            if ("QUEUED".equals(last.outcome()) && !current) {
                finishAttempt(last.id(), "NOT_SENT", false, "DESTINATION_CHANGED", null, now);
                state(delivery.id(), "BLOCKED_CONFIG", null, now);
            } else if ("RETRY_WAIT".equals(delivery.status())) {
                if (!current) state(delivery.id(), "BLOCKED_CONFIG", null, now);
                else if (delivery.nextRetryAt() != null && !delivery.nextRetryAt().isAfter(now)) submit(delivery, assets.find(project, delivery.webhookId()), null, null, true, now);
            }
        }
        if (!activeProject || !senderEnabled) return;
        var subscriptions = jdbc.queryForList("SELECT webhook_id,event_cursor FROM notification_subscription WHERE project_id=? AND enabled=TRUE ORDER BY webhook_id FOR UPDATE", project);
        for (var subscription : subscriptions) {
            Asset config = assets.find(project, subscription.get("webhook_id").toString());
            var events = jdbc.queryForList("SELECT seq,run_id,failed,report FROM run_completion_event WHERE project_id=? AND seq>? ORDER BY seq LIMIT 100 FOR UPDATE", project, subscription.get("event_cursor"));
            for (var event : events) {
                boolean skip = Values.bool(config.data(), "failOnly", true) && !Boolean.TRUE.equals(event.get("failed"));
                String id = Ids.newId();
                jdbc.update("INSERT INTO notification_delivery(id,project_id,webhook_id,event_seq,run_id,report,status,max_retries,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        id, project, config.id(), event.get("seq"), event.get("run_id"), event.get("report").toString(), skip ? "SKIPPED_CONDITION" : "WAITING", Values.integer(config.data(), "maxRetries", 3, 0, 5), timestamp(now), timestamp(now));
                if (!skip) submit(find(project, config.id(), id), config, null, null, false, now);
                jdbc.update("UPDATE notification_subscription SET event_cursor=?,updated_at=? WHERE webhook_id=?", event.get("seq"), timestamp(now), config.id());
            }
        }
    }

    public Map<String, Object> history(String project, String webhookId, int offset, int limit) {
        webhook(project, webhookId);
        if (offset < 0 || limit < 1 || limit > 100) throw Problem.invalid("历史 offset 不能为负，limit 为 1–100");
        var deliveries = jdbc.query("SELECT * FROM notification_delivery WHERE project_id=? AND webhook_id=? ORDER BY event_seq DESC LIMIT ? OFFSET ?", (rs, n) -> delivery(rs), project, webhookId, limit, offset);
        var items = deliveries.stream().map(delivery -> {
            Map<String, Object> value = json.map(json.write(delivery));
            value.put("attempts", attempts(delivery.id())); return value;
        }).toList();
        return Map.of("items", items, "total", jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE project_id=? AND webhook_id=?", Long.class, project, webhookId), "senderEnabled", senderEnabled);
    }

    public Submission retry(String project, String webhookId, String id, Map<String, Object> input) {
        if (input == null || !Set.of("baseVersion", "idempotencyKey", "acknowledgeUncertain").containsAll(input.keySet())
                || !(input.get("baseVersion") instanceof String version) || !version.matches("[1-9][0-9]{0,18}")
                || !(input.get("idempotencyKey") instanceof String key) || key.isBlank() || key.length() > 160)
            throw Problem.invalid("手工重试需要当前 baseVersion 和 1–160 字符的 idempotencyKey");
        boolean acknowledge = Values.bool(input, "acknowledgeUncertain", false);
        String fingerprint = "version:" + version + ":uncertain:" + acknowledge;
        return transactions.execute(tx -> {
            assets.lockProject(project); Asset config = webhook(project, webhookId); Delivery delivery = find(project, webhookId, id);
            for (Attempt attempt : attempts(id)) if (key.equals(attempt.requestKey())) {
                String prior = jdbc.queryForObject("SELECT request_fingerprint FROM notification_attempt WHERE id=?", String.class, attempt.id());
                if (!fingerprint.equals(prior)) throw new Problem(409, "IDEMPOTENCY_CONFLICT", "此幂等键已用于不同的通知重试请求");
                return new Submission(id, attempt.id(), attempt.jobId());
            }
            AssetService.requireVersion(config, version);
            if (!senderEnabled || !Values.bool(config.data(), "enabled", false)) throw new Problem(409, "NOTIFICATION_DISABLED", "请先保存并启用当前群通知配置");
            reconcile(delivery, Instant.now()); delivery = find(project, webhookId, id);
            if (!Set.of("FAILED", "UNCERTAIN", "BLOCKED_CONFIG", "CANCELLED", "INTERRUPTED").contains(delivery.status())) throw new Problem(409, "NOTIFICATION_NOT_RETRYABLE", "只有发送失败、未发送或结果未知的通知可重试；成功和进行中的通知不会重复派发");
            if ("UNCERTAIN".equals(delivery.status()) && !acknowledge) throw new Problem(409, "NOTIFICATION_UNCERTAIN", "上次可能已送达；请检查群消息并确认接受重复通知风险后重试");
            return submit(delivery, config, key, fingerprint, false, Instant.now());
        });
    }

    private Submission submit(Delivery delivery, Asset config, String key, String fingerprint, boolean automaticRetry, Instant now) {
        WebhookProtocol.validate(config.data());
        int number = delivery.attemptCount() + 1; String id = Ids.newId();
        Job job = jobs.submit(delivery.projectId(), KIND, "notify:" + delivery.id() + ":" + number, Map.of("deliveryId", delivery.id(), "attemptId", id));
        jdbc.update("INSERT INTO notification_attempt(id,delivery_id,attempt_number,job_id,request_key,request_fingerprint,origin,config_version,private_config,outcome,created_at) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?)",
                id, delivery.id(), number, job.id(), key, fingerprint, key == null ? "AUTOMATIC" : "MANUAL", Long.parseLong(config.version()), secrets.encrypt(json.write(config.data())), timestamp(now));
        jdbc.update("UPDATE notification_delivery SET attempt_count=?,automatic_retries=automatic_retries+?,status='SUBMITTED',next_retry_at=NULL,updated_at=? WHERE id=?", number, automaticRetry ? 1 : 0, timestamp(now), delivery.id());
        return new Submission(delivery.id(), id, job.id());
    }

    /** The committed SENDING marker survives process death; it is never auto-replayed. */
    Send begin(JobContext job, String deliveryId, String attemptId) {
        job.checkpoint();
        return transactions.execute(tx -> {
            boolean active = lockProjectForAudit(job.projectId());
            Delivery delivery = findByJob(job, deliveryId, attemptId);
            Attempt attempt = attempts(deliveryId).stream().filter(a -> a.id().equals(attemptId)).findFirst().orElseThrow(Problem::missing);
            if (!"QUEUED".equals(attempt.outcome())) return null;
            if (!active || !senderEnabled || "DELIVERED".equals(delivery.status()) || delivery.attemptCount() != attempt.number() || !currentConfig(job.projectId(), delivery.webhookId(), attempt.configVersion())) {
                finishAttempt(attemptId, "NOT_SENT", false, "DESTINATION_CHANGED", null, Instant.now());
                if (!"DELIVERED".equals(delivery.status())) state(deliveryId, "BLOCKED_CONFIG", null, Instant.now());
                return null;
            }
            job.checkpoint();
            Map<String, Object> config = json.map(secrets.decrypt(jdbc.queryForObject("SELECT private_config FROM notification_attempt WHERE id=?", String.class, attemptId)));
            jdbc.update("UPDATE notification_attempt SET outcome='SENDING',started_at=? WHERE id=?", timestamp(Instant.now()), attemptId);
            state(deliveryId, "SENDING", null, Instant.now());
            return new Send(delivery.id(), attemptId, config, delivery.report());
        });
    }

    /** Audit must not depend on an active job, an undeleted project or unchanged credentials. */
    void completed(JobContext job, String deliveryId, String attemptId, WebhookProtocol.Result result) {
        transactions.executeWithoutResult(tx -> {
            lockProjectForAudit(job.projectId()); Delivery delivery = findByJob(job, deliveryId, attemptId); Instant now = Instant.now();
            finishAttempt(attemptId, result.outcome(), result.retryable(), result.code(), result.httpStatus(), now);
            if ("DELIVERED".equals(result.outcome())) { state(deliveryId, "DELIVERED", null, now); return; }
            Attempt last = attempts(deliveryId).getLast();
            if (!last.id().equals(attemptId) || "DELIVERED".equals(delivery.status())) return;
            boolean retry = result.retryable() && delivery.automaticRetries() < delivery.maxRetries();
            String status = "UNCERTAIN".equals(result.outcome()) ? "UNCERTAIN" : retry ? "RETRY_WAIT" : "FAILED";
            state(deliveryId, status, retry ? now.plusSeconds((long) retryDelaySeconds << delivery.automaticRetries()) : null, now);
        });
    }

    private void reconcile(Delivery delivery, Instant now) {
        var attempts = attempts(delivery.id()); if (attempts.isEmpty()) return;
        Attempt last = attempts.getLast();
        if (!ACTIVE.contains(last.outcome()) || Set.of("QUEUED", "RUNNING").contains(last.jobStatus())) return;
        boolean uncertain = "SENDING".equals(last.outcome());
        String outcome = uncertain ? "UNCERTAIN" : Set.of("CANCELLED", "INTERRUPTED").contains(last.jobStatus()) ? last.jobStatus() : "NOT_SENT";
        finishAttempt(last.id(), outcome, false, uncertain ? "SEND_INTERRUPTED" : "JOB_STOPPED_BEFORE_SEND", null, now);
        if (!"DELIVERED".equals(delivery.status())) state(delivery.id(), uncertain ? "UNCERTAIN" : outcome.equals("NOT_SENT") ? "FAILED" : outcome, null, now);
    }

    private Asset webhook(String project, String id) { assets.project(project); Asset asset = assets.find(project, id); if (asset.type() != AssetType.WEBHOOK) throw Problem.missing(); return asset; }
    private boolean lockProjectForAudit(String project) {
        var rows = jdbc.queryForList("SELECT deleted FROM project WHERE id=? FOR UPDATE", project); if (rows.isEmpty()) throw Problem.missing(); return !Boolean.TRUE.equals(rows.getFirst().get("deleted"));
    }
    private boolean currentConfig(String project, String id, String version) {
        // Current reads also fence a configuration edit committed during dispatch.
        var rows = jdbc.queryForList("SELECT a.version,a.deleted,w.enabled FROM asset a JOIN project_webhook_notice w ON w.asset_id=a.id WHERE a.id=? AND a.project_id=? FOR UPDATE", id, project);
        return !rows.isEmpty() && !Boolean.TRUE.equals(rows.getFirst().get("deleted")) && Boolean.TRUE.equals(rows.getFirst().get("enabled")) && version.equals(rows.getFirst().get("version").toString());
    }
    private Delivery findByJob(JobContext job, String deliveryId, String attemptId) {
        var rows = jdbc.query("SELECT * FROM notification_delivery WHERE id=? AND project_id=? FOR UPDATE", (rs, n) -> delivery(rs), deliveryId, job.projectId());
        if (rows.isEmpty() || jdbc.queryForObject("SELECT COUNT(*) FROM notification_attempt WHERE id=? AND delivery_id=? AND job_id=?", Long.class, attemptId, deliveryId, job.id()) != 1) throw Problem.missing();
        return rows.getFirst();
    }
    private Delivery find(String project, String webhook, String id) {
        var rows = jdbc.query("SELECT * FROM notification_delivery WHERE id=? AND project_id=? AND webhook_id=? FOR UPDATE", (rs, n) -> delivery(rs), id, project, webhook);
        if (rows.isEmpty()) throw Problem.missing(); return rows.getFirst();
    }
    private void state(String id, String status, Instant retryAt, Instant now) { jdbc.update("UPDATE notification_delivery SET status=?,next_retry_at=?,updated_at=? WHERE id=?", status, timestamp(retryAt), timestamp(now), id); }
    private void finishAttempt(String id, String outcome, boolean retryable, String code, Integer httpStatus, Instant now) {
        jdbc.update("UPDATE notification_attempt SET outcome=?,retryable=?,diagnostic_code=?,http_status=?,completed_at=? WHERE id=?", outcome, retryable, code, httpStatus, timestamp(now), id);
    }
    private List<Attempt> attempts(String id) {
        return jdbc.query("SELECT a.id,a.attempt_number,a.job_id,a.request_key,a.origin,a.config_version,a.outcome,a.retryable,a.diagnostic_code,a.http_status,a.created_at,a.started_at,a.completed_at,j.status AS job_status FROM notification_attempt a JOIN job_task j ON j.id=a.job_id WHERE a.delivery_id=? ORDER BY a.attempt_number",
                (rs, n) -> new Attempt(rs.getString("id"), rs.getInt("attempt_number"), rs.getString("job_id"), rs.getString("request_key"), rs.getString("origin"), rs.getString("config_version"), rs.getString("outcome"), rs.getString("job_status"), rs.getBoolean("retryable"), rs.getString("diagnostic_code"), (Integer) rs.getObject("http_status"), instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "completed_at")), id);
    }
    private Delivery delivery(ResultSet rs) throws SQLException {
        return new Delivery(rs.getString("id"), rs.getString("project_id"), rs.getString("webhook_id"), rs.getString("run_id"), rs.getString("status"), json.map(rs.getString("report")), rs.getInt("attempt_count"), rs.getInt("automatic_retries"), rs.getInt("max_retries"), instant(rs, "next_retry_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }
    private static Instant instant(ResultSet rs, String key) throws SQLException { Timestamp value = rs.getTimestamp(key); return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    public record Submission(String deliveryId, String attemptId, String jobId) { }
    public record Delivery(String id, String projectId, String webhookId, String runId, String status, Map<String, Object> report, int attemptCount, int automaticRetries, int maxRetries, Instant nextRetryAt, Instant createdAt, Instant updatedAt) { }
    public record Attempt(String id, int number, String jobId, String requestKey, String origin, String configVersion, String outcome, String jobStatus, boolean retryable, String diagnosticCode, Integer httpStatus, Instant createdAt, Instant startedAt, Instant completedAt) { }
    record Send(String deliveryId, String attemptId, Map<String, Object> config, Map<String, Object> report) { }
}
