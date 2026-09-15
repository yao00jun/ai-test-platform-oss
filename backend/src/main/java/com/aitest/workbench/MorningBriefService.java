package com.aitest.workbench;

import com.aitest.ai.AiConversationService;
import com.aitest.asset.AssetRepository;
import com.aitest.common.*;
import com.aitest.job.*;
import com.aitest.report.QualityMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Durable daily occurrences. Project locks serialize settings, dispatch and asset publication. */
@Service
public class MorningBriefService {
    public static final String KIND = "MORNING_BRIEF";
    private static final Logger log = LoggerFactory.getLogger(MorningBriefService.class);
    private final AssetRepository projects;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonCodec json;
    private final JobService jobs;
    private final AiConversationService conversations;
    private final QualityMetricsService metrics;
    private final boolean schedulerEnabled;
    private final int retryDelaySeconds;

    public MorningBriefService(AssetRepository projects, JdbcTemplate jdbc, TransactionTemplate transactions, JsonCodec json,
                               JobService jobs, AiConversationService conversations, QualityMetricsService metrics,
                               @Value("${aitest.morning-brief.enabled:true}") boolean schedulerEnabled,
                               @Value("${aitest.morning-brief.retry-delay-seconds:60}") int retryDelaySeconds) {
        this.projects = projects; this.jdbc = jdbc; this.transactions = transactions; this.json = json;
        this.jobs = jobs; this.conversations = conversations; this.metrics = metrics; this.schedulerEnabled = schedulerEnabled;
        this.retryDelaySeconds = Math.clamp(retryDelaySeconds, 1, 3600);
    }

    public Schedule settings(String project) {
        projects.project(project);
        var rows = jdbc.query("SELECT * FROM morning_brief_schedule WHERE project_id=?", (rs, n) -> new Schedule(project, rs.getString("version"),
                rs.getBoolean("enabled"), rs.getString("local_time"), rs.getString("timezone"), rs.getString("instruction"),
                rs.getInt("max_retries"), instant(rs, "next_fire_at"), schedulerEnabled), project);
        return rows.isEmpty() ? new Schedule(project, "0", false, "08:00", "Asia/Shanghai", "分析前一天的真实测试结果、失败原因与待核验风险。", 1, null, schedulerEnabled) : rows.getFirst();
    }

    public Schedule configure(String project, Map<String, Object> input) {
        Set<String> fields = Set.of("baseVersion", "enabled", "time", "timezone", "instruction", "maxRetries");
        if (input == null || !input.keySet().equals(fields) || !(input.get("baseVersion") instanceof String version)
                || !version.matches("0|[1-9][0-9]{0,18}") || !(input.get("enabled") instanceof Boolean enabled)
                || !(input.get("time") instanceof String time) || !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
                || !(input.get("timezone") instanceof String zone) || !ZoneId.getAvailableZoneIds().contains(zone)
                || !(input.get("instruction") instanceof String instruction) || instruction.isBlank() || instruction.length() > 16000
                || !(input.get("maxRetries") instanceof Number retries) || retries.doubleValue() != retries.intValue() || retries.intValue() < 0 || retries.intValue() > 3)
            throw Problem.invalid("晨报设置需要当前版本、启用状态、HH:mm 时间、IANA 时区、1–16000 字符要求及 0–3 次重试；不能包含其他字段");
        return transactions.execute(tx -> {
            projects.lockProject(project);
            Schedule current = settings(project);
            if (!version.equals(current.version())) throw Problem.conflict("晨报设置已变化，请刷新后合并修改");
            Instant now = Instant.now(), due = enabled ? nextFire(LocalTime.parse(time), ZoneId.of(zone), now) : null;
            long nextVersion = Math.addExact(Long.parseLong(current.version()), 1);
            jdbc.update("INSERT INTO morning_brief_schedule(project_id,version,enabled,local_time,timezone,instruction,max_retries,next_fire_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE version=VALUES(version),enabled=VALUES(enabled),local_time=VALUES(local_time),timezone=VALUES(timezone),instruction=VALUES(instruction),max_retries=VALUES(max_retries),next_fire_at=VALUES(next_fire_at),updated_at=VALUES(updated_at)",
                    project, nextVersion, enabled, time, zone, instruction, retries.intValue(), timestamp(due), timestamp(now));
            return settings(project);
        });
    }

    @Scheduled(fixedDelayString = "${aitest.morning-brief.poll-interval-ms:1000}", initialDelay = 1000)
    public void poll() { if (schedulerEnabled) sweep(Instant.now()); }

    public void sweep(Instant now) {
        var ids = jdbc.queryForList("SELECT s.project_id FROM morning_brief_schedule s JOIN project p ON p.id=s.project_id AND p.deleted=FALSE WHERE s.enabled=TRUE OR EXISTS(SELECT 1 FROM morning_brief_occurrence o WHERE o.project_id=s.project_id AND o.status IN ('SUBMITTED','RETRY_WAIT')) ORDER BY s.project_id", String.class);
        for (String project : ids) {
            try { transactions.executeWithoutResult(tx -> advance(project, now)); }
            catch (Problem failure) { if (failure.status() != 404) log.warn("Morning brief schedule {} could not advance ({})", project, failure.code()); }
            catch (RuntimeException failure) { log.warn("Morning brief schedule {} could not advance ({})", project, failure.getClass().getSimpleName()); }
        }
    }

    private void advance(String project, Instant now) {
        projects.lockProject(project);
        Schedule schedule = settings(project);
        for (Occurrence occurrence : jdbc.query("SELECT * FROM morning_brief_occurrence WHERE project_id=? AND status IN ('SUBMITTED','RETRY_WAIT') ORDER BY local_date,id", (rs, n) -> occurrence(rs), project))
            advanceAttempt(occurrence, schedule.enabled(), now);
        if (!schedule.enabled() || schedule.nextFireAt() == null || schedule.nextFireAt().isAfter(now)) return;
        ZoneId zone = ZoneId.of(schedule.timezone()); LocalTime time = LocalTime.parse(schedule.time());
        LocalDate latest = latestDate(time, zone, now), first = schedule.nextFireAt().atZone(zone).toLocalDate();
        // The database unique key remains authoritative even after timezone edits or clock rollback.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM morning_brief_occurrence WHERE project_id=? AND local_date=?", Long.class, project, latest) == 0) {
            String id = Ids.newId();
            String conversation = conversations.ensure(null, project, KIND, id, "QUALITY_BRIEF");
            jdbc.update("INSERT INTO morning_brief_occurrence(id,project_id,local_date,timezone,scheduled_for,missed_from,window_from,window_to,instruction,max_retries,status,conversation_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,'WAITING',?,?,?)",
                    id, project, latest, zone.getId(), timestamp(fire(latest, time, zone)), first.isBefore(latest) ? first : null,
                    timestamp(latest.minusDays(1).atStartOfDay(zone).toInstant()), timestamp(latest.atStartOfDay(zone).toInstant()),
                    schedule.instruction(), schedule.maxRetries(), conversation, timestamp(now), timestamp(now));
            submit(find(project, id, false), null, false, now);
        }
        jdbc.update("UPDATE morning_brief_schedule SET next_fire_at=? WHERE project_id=?", timestamp(nextFire(time, zone, now)), project);
    }

    private void advanceAttempt(Occurrence occurrence, boolean enabled, Instant now) {
        Attempt last = attempts(occurrence.id()).getLast();
        if (Set.of("QUEUED", "RUNNING").contains(last.status())) return;
        boolean canRetry = "FAILED".equals(last.status()) && last.retryable() && occurrence.automaticRetries() < occurrence.maxRetries();
        Instant retryAt = last.nextRetryAt();
        if (canRetry && retryAt == null) {
            retryAt = last.updatedAt().plusSeconds((long) retryDelaySeconds << occurrence.automaticRetries());
            jdbc.update("UPDATE morning_brief_attempt SET next_retry_at=? WHERE id=?", timestamp(retryAt), last.id());
        }
        String status = canRetry ? "RETRY_WAIT" : last.status();
        if (!status.equals(occurrence.status())) jdbc.update("UPDATE morning_brief_occurrence SET status=?,updated_at=? WHERE id=?", status, timestamp(now), occurrence.id());
        if (canRetry && enabled && !retryAt.isAfter(now)) submit(occurrence, null, true, now);
    }

    public Submission retry(String project, String id, Map<String, Object> input) {
        if (input == null || !input.keySet().equals(Set.of("idempotencyKey")) || !(input.get("idempotencyKey") instanceof String key) || key.isBlank() || key.length() > 160)
            throw Problem.invalid("手工重试需要 1–160 字符的 idempotencyKey");
        return transactions.execute(tx -> {
            projects.lockProject(project);
            Occurrence occurrence = find(project, id, true);
            var attempts = attempts(id);
            for (Attempt attempt : attempts) if (key.equals(attempt.requestKey())) return new Submission(id, attempt.jobId());
            if (occurrence.assetId() != null || attempts.isEmpty() || !Set.of("FAILED", "CANCELLED", "INTERRUPTED").contains(attempts.getLast().status()))
                throw new Problem(409, "MORNING_BRIEF_NOT_RETRYABLE", "只有失败、取消或中断的晨报可以重试；已完成或进行中的晨报不会重复创建");
            return submit(occurrence, key, false, Instant.now());
        });
    }

    private Submission submit(Occurrence occurrence, String key, boolean automaticRetry, Instant now) {
        int number = occurrence.attemptCount() + 1;
        Job job = jobs.submit(occurrence.projectId(), KIND, "morning:" + occurrence.id() + ":" + number, Map.of("occurrenceId", occurrence.id(), "attempt", number));
        jdbc.update("INSERT INTO morning_brief_attempt(id,occurrence_id,attempt_number,job_id,request_key,origin,created_at) VALUES(?,?,?,?,?,?,?)",
                Ids.newId(), occurrence.id(), number, job.id(), key, key == null ? "AUTOMATIC" : "MANUAL", timestamp(now));
        jdbc.update("UPDATE morning_brief_occurrence SET attempt_count=?,automatic_retries=automatic_retries+?,status='SUBMITTED',updated_at=? WHERE id=?",
                number, automaticRetry ? 1 : 0, timestamp(now), occurrence.id());
        return new Submission(occurrence.id(), job.id());
    }

    public Map<String, Object> history(String project, int offset, int limit) {
        projects.project(project);
        if (offset < 0 || limit < 1 || limit > 100) throw Problem.invalid("历史 offset 不能为负，limit 需在 1–100 之间");
        var rows = jdbc.query("SELECT * FROM morning_brief_occurrence WHERE project_id=? ORDER BY local_date DESC,created_at DESC,id DESC LIMIT ? OFFSET ?", (rs, n) -> occurrence(rs), project, limit, offset);
        var items = rows.stream().map(occurrence -> {
            Map<String, Object> item = json.map(json.write(occurrence));
            List<Attempt> attempts = attempts(occurrence.id());
            item.put("attempts", attempts);
            if (occurrence.status().equals("SUBMITTED") && !attempts.isEmpty()) item.put("status", attempts.getLast().status());
            return item;
        }).toList();
        return Map.of("items", items, "total", jdbc.queryForObject("SELECT COUNT(*) FROM morning_brief_occurrence WHERE project_id=?", Long.class, project));
    }

    /** Aggregation happens outside the write barrier; exactly one captured result is then persisted. */
    Occurrence freeze(JobContext job, String id) {
        job.checkpoint(); projects.project(job.projectId());
        Occurrence before = find(job.projectId(), id, false);
        Map<String, Object> captured = before.metrics().isEmpty() ? metrics.captureRange(job.projectId(), before.windowFrom(), before.windowTo()) : before.metrics();
        return job.atomic(() -> {
            projects.lockProject(job.projectId());
            Occurrence current = requireCurrent(job, id);
            if (current.metrics().isEmpty()) jdbc.update("UPDATE morning_brief_occurrence SET metrics=? WHERE id=?", json.write(captured), id);
            return find(job.projectId(), id, false);
        });
    }

    Occurrence requireCurrent(JobContext job, String id) {
        Occurrence current = find(job.projectId(), id, true);
        var attempts = attempts(id);
        if (current.assetId() != null || attempts.isEmpty() || !attempts.getLast().jobId().equals(job.id()))
            throw new Problem(409, "MORNING_BRIEF_ATTEMPT_CHANGED", "本次晨报已完成或有更新的尝试，未写入重复资产");
        return current;
    }

    void published(JobContext job, String id, String assetId) {
        projects.lockProject(job.projectId()); requireCurrent(job, id);
        jdbc.update("UPDATE morning_brief_occurrence SET status='SUCCEEDED',asset_id=?,updated_at=? WHERE id=? AND asset_id IS NULL", assetId, timestamp(Instant.now()), id);
    }

    void failed(String jobId, RuntimeException error) {
        String code = error instanceof Problem p ? p.code() : error.getClass().getSimpleName();
        boolean retryable = Set.of("MODEL_RATE_LIMITED", "MODEL_REQUEST_FAILED", "MODEL_REQUEST_TIMEOUT", "MODEL_OUTPUT_TRUNCATED", "MODEL_EMPTY_RESPONSE", "AI_OUTPUT_INVALID", "MORNING_BRIEF_OUTPUT_INVALID", "AI_REPORT_FACTS_FORBIDDEN", "VALIDATION_FAILED").contains(code);
        jdbc.update("UPDATE morning_brief_attempt SET retryable=?,diagnostic_code=? WHERE job_id=?", retryable, code, jobId);
    }

    private Occurrence find(String project, String id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM morning_brief_occurrence WHERE id=? AND project_id=?" + (lock ? " FOR UPDATE" : ""), (rs, n) -> occurrence(rs), id, project);
        if (rows.isEmpty()) throw Problem.missing(); return rows.getFirst();
    }
    private Occurrence occurrence(ResultSet rs) throws SQLException {
        var missed = rs.getDate("missed_from");
        return new Occurrence(rs.getString("id"), rs.getString("project_id"), rs.getDate("local_date").toLocalDate(), rs.getString("timezone"), instant(rs, "scheduled_for"),
                missed == null ? null : missed.toLocalDate(), instant(rs, "window_from"), instant(rs, "window_to"), rs.getString("instruction"), rs.getInt("max_retries"),
                rs.getInt("automatic_retries"), rs.getInt("attempt_count"), json.map(rs.getString("metrics")), rs.getString("status"), rs.getString("asset_id"), rs.getString("conversation_id"), instant(rs, "created_at"));
    }
    private List<Attempt> attempts(String id) {
        return jdbc.query("SELECT a.*,j.status,j.error,j.updated_at FROM morning_brief_attempt a JOIN job_task j ON j.id=a.job_id WHERE a.occurrence_id=? ORDER BY a.attempt_number", (rs, n) -> new Attempt(rs.getString("id"), rs.getInt("attempt_number"), rs.getString("job_id"), rs.getString("status"), rs.getString("error"),
                rs.getString("origin"), rs.getString("request_key"), rs.getBoolean("retryable"), rs.getString("diagnostic_code"), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "next_retry_at")), id);
    }
    static Instant fire(LocalDate date, LocalTime time, ZoneId zone) { return ZonedDateTime.of(date, time, zone).toInstant(); }
    static LocalDate latestDate(LocalTime time, ZoneId zone, Instant now) { LocalDate date = now.atZone(zone).toLocalDate(); return fire(date, time, zone).isAfter(now) ? date.minusDays(1) : date; }
    static Instant nextFire(LocalTime time, ZoneId zone, Instant now) { LocalDate date = now.atZone(zone).toLocalDate(); Instant due = fire(date, time, zone); return due.isAfter(now) ? due : fire(date.plusDays(1), time, zone); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }

    public record Schedule(String projectId, String version, boolean enabled, String time, String timezone, String instruction, int maxRetries, Instant nextFireAt, boolean schedulerEnabled) { }
    public record Submission(String occurrenceId, String jobId) { }
    record Occurrence(String id, String projectId, LocalDate localDate, String timezone, Instant scheduledFor, LocalDate missedFrom, Instant windowFrom, Instant windowTo,
                      String instruction, int maxRetries, int automaticRetries, int attemptCount, Map<String, Object> metrics, String status, String assetId, String conversationId, Instant createdAt) { }
    record Attempt(String id, int number, String jobId, String status, String error, String origin, String requestKey, boolean retryable, String diagnosticCode, Instant createdAt, Instant updatedAt, Instant nextRetryAt) { }
}
