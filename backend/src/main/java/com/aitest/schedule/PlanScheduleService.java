package com.aitest.schedule;

import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.execution.ExecutionCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PlanScheduleService {
    private static final Logger log = LoggerFactory.getLogger(PlanScheduleService.class);
    private final AssetRepository repository;
    private final AssetService assets;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ExecutionCoordinator execution;
    private final JsonCodec json;
    private final String defaultZone;
    private final int graceSeconds, maxQueued;
    private final boolean enabled;

    public PlanScheduleService(AssetRepository repository, AssetService assets, JdbcTemplate jdbc, TransactionTemplate transactions,
                               ExecutionCoordinator execution, JsonCodec json, @Value("${aitest.schedules.default-timezone:Asia/Shanghai}") String defaultZone,
                               @Value("${aitest.schedules.misfire-grace-seconds:30}") int graceSeconds,
                               @Value("${aitest.schedules.max-queued-per-plan:100}") int maxQueued,
                               @Value("${aitest.schedules.enabled:true}") boolean enabled) {
        this.repository = repository; this.assets = assets; this.jdbc = jdbc; this.transactions = transactions;
        this.execution = execution; this.json = json; this.defaultZone = ZoneId.of(defaultZone).getId();
        this.graceSeconds = Math.clamp(graceSeconds, 1, 3600); this.maxQueued = Math.clamp(maxQueued, 1, 1000); this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${aitest.schedules.poll-interval-ms:1000}", initialDelay = 1000)
    public void poll() { if (enabled) sweep(Instant.now()); }

    /** Each project transaction has the same lock order as manual asset edits. No remote IO is performed here. */
    public void sweep(Instant now) {
        var plans = jdbc.queryForList("SELECT a.id,a.project_id FROM asset a JOIN project p ON p.id=a.project_id JOIN test_plan t ON t.asset_id=a.id LEFT JOIN plan_schedule_cursor c ON c.plan_id=a.id WHERE (a.deleted=FALSE AND p.deleted=FALSE AND t.schedule_enabled=TRUE) OR c.enabled=TRUE ORDER BY a.project_id,a.id");
        for (var plan : plans) {
            String project = plan.get("project_id").toString(), id = plan.get("id").toString();
            try {
                transactions.executeWithoutResult(tx -> reconcile(project, id, now));
                dispatch(project, id, now);
            } catch (RuntimeException failure) {
                // Keep the durable cursor unchanged on infrastructure failure; retry on the next sweep.
                log.warn("Could not advance plan schedule {} ({})", id, failure.getClass().getSimpleName());
            }
        }
    }

    private void reconcile(String project, String id, Instant now) {
        if (!lockActivePlan(project, id)) { retire(id); return; }
        Asset plan = plan(project, id);
        Configuration configuration = configuration(plan.data());
        Cursor cursor = cursor(id);
        String fingerprint = fingerprint(configuration);
        if (!configuration.enabled() || cursor == null || !cursor.configurationHash().equals(fingerprint)) {
            cancelWaiting(id, configuration.enabled() ? "排期已修改，旧排队触发已取消" : "排期已停用");
            Instant next = configuration.enabled() ? next(configuration, now) : null;
            if (cursor == null) jdbc.update("INSERT INTO plan_schedule_cursor(plan_id,project_id,configuration_hash,configuration,enabled,next_fire_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                    id, project, fingerprint, json.write(configuration), configuration.enabled(), timestamp(next), timestamp(now));
            else jdbc.update("UPDATE plan_schedule_cursor SET configuration_hash=?,configuration=?,enabled=?,next_fire_at=?,updated_at=? WHERE plan_id=?",
                    fingerprint, json.write(configuration), configuration.enabled(), timestamp(next), timestamp(now), id);
            return;
        }
        Instant due = cursor.nextFireAt();
        if (due == null || due.isAfter(now)) return;
        boolean misfire = due.plusSeconds(graceSeconds).isBefore(now);
        Instant followingDue = next(configuration, due);
        boolean coalesced = misfire || followingDue != null && !followingDue.isAfter(now);
        String status = "WAITING", reason = misfire ? "错过的触发合并补跑一次" : "等待派发";
        if (misfire && configuration.misfirePolicy().equals("SKIP")) { status = "SKIPPED_MISFIRE"; reason = "已跳过错过的触发区间"; }
        else if (configuration.overlapPolicy().equals("SKIP") && active(project, id)) { status = "SKIPPED_OVERLAP"; reason = "前次运行尚未结束"; }
        else if (waiting(id) >= maxQueued) { status = "SKIPPED_CAPACITY"; reason = "待运行触发已达到 " + maxQueued + " 条上限"; }
        jdbc.update("INSERT INTO plan_schedule_occurrence(id,project_id,plan_id,scheduled_for,misfire_through,configuration_hash,configuration,plan_version,status,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                Ids.newId(), project, id, timestamp(due), coalesced ? timestamp(now) : null, fingerprint, json.write(configuration), Long.parseLong(plan.version()), status, reason, timestamp(now));
        jdbc.update("UPDATE plan_schedule_cursor SET next_fire_at=?,last_fire_at=?,updated_at=? WHERE plan_id=?", timestamp(next(configuration, now)), timestamp(due), timestamp(now), id);
    }

    private void dispatch(String project, String id, Instant now) {
        AtomicReference<String> selected = new AtomicReference<>();
        try {
            transactions.executeWithoutResult(tx -> {
                if (!lockActivePlan(project, id)) { retire(id); return; }
                Asset plan = plan(project, id);
                Configuration configuration = configuration(plan.data());
                Cursor cursor = cursor(id);
                if (!configuration.enabled() || cursor == null || !cursor.configurationHash().equals(fingerprint(configuration))) return;
                var pending = jdbc.queryForList("SELECT id FROM plan_schedule_occurrence WHERE plan_id=? AND status='WAITING' AND configuration_hash=? ORDER BY scheduled_for,id LIMIT 1 FOR UPDATE", String.class, id, cursor.configurationHash());
                if (pending.isEmpty()) return;
                String occurrence = pending.getFirst();
                if (active(project, id)) {
                    if (configuration.overlapPolicy().equals("SKIP")) jdbc.update("UPDATE plan_schedule_occurrence SET status='SKIPPED_OVERLAP',reason='前次运行尚未结束' WHERE id=?", occurrence);
                    return;
                }
                selected.set(occurrence);
                var submission = execution.submit(project, new ExecutionCoordinator.Request(id, null, null, "schedule:" + occurrence));
                jdbc.update("UPDATE plan_schedule_occurrence SET status='SUBMITTED',reason=CASE WHEN misfire_through IS NULL THEN '已派发，执行结果见来源运行' ELSE '触发区间已合并并派发，执行结果见来源运行' END,run_id=?,job_id=?,dispatched_at=?,dispatched_plan_version=? WHERE id=? AND status='WAITING'",
                        submission.runId(), submission.jobId(), timestamp(now), Long.parseLong(plan.version()), occurrence);
            });
        } catch (Problem failure) {
            if (selected.get() == null) throw failure;
            // A rejected snapshot rolls back the run/job transaction. Record its failed dispatch separately.
            transactions.executeWithoutResult(tx -> {
                repository.lockProject(project);
                jdbc.update("UPDATE plan_schedule_occurrence SET status='FAILED',reason=? WHERE id=? AND status='WAITING'", limited(failure.getMessage()), selected.get());
            });
        }
    }

    public Asset configure(String project, String id, Map<String, Object> input) {
        plan(project, id);
        Set<String> allowed = Set.of("baseVersion", "cronExpression", "timezone", "scheduleEnabled", "overlapPolicy", "misfirePolicy");
        if (!allowed.containsAll(input.keySet()) || !(input.get("baseVersion") instanceof String version) || version.isBlank()) throw Problem.invalid("排期需要当前 baseVersion，且只接受排期字段");
        Map<String, Object> patch = new LinkedHashMap<>(input); patch.remove("baseVersion");
        return assets.update(project, id, version, null, patch, null, "MANUAL");
    }

    public Map<String, Object> preview(String project, String id, Map<String, Object> input) {
        Asset plan = plan(project, id);
        if (!Set.of("cronExpression", "timezone", "from").containsAll(input.keySet())) throw Problem.invalid("预览只接受 Cron、时区和起始时间");
        Map<String, Object> data = new LinkedHashMap<>(plan.data());
        input.forEach((key, value) -> { if (!key.equals("from")) data.put(key, value); });
        Configuration configuration = configuration(data);
        if (configuration.cronExpression().isBlank()) throw Problem.invalid("请输入六字段 Cron 表达式");
        Instant from;
        try { from = input.get("from") == null ? Instant.now() : Instant.parse(input.get("from").toString()); }
        catch (RuntimeException invalid) { throw Problem.invalid("起始时间需要带时区的 ISO 时间"); }
        return Map.of("timezone", configuration.timezone(), "from", from.toString(), "nextFireTimes", nextTimes(configuration, from));
    }

    public Map<String, Object> state(String project, String id, int offset, int limit) {
        Asset plan = plan(project, id);
        if (offset < 0 || limit < 1 || limit > 100) throw Problem.invalid("排期历史 limit 为 1–100，offset 不能为负");
        Configuration configuration = configuration(plan.data());
        Cursor cursor = cursor(id);
        boolean current = cursor != null && cursor.configurationHash().equals(fingerprint(configuration));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("planId", id); state.put("planVersion", plan.version()); state.put("configuration", configuration);
        state.put("schedulerEnabled", enabled); state.put("misfireGraceSeconds", graceSeconds); state.put("maxQueued", maxQueued);
        state.put("nextFireAt", current && configuration.enabled() ? cursor.nextFireAt() : null);
        state.put("lastFireAt", cursor == null ? null : cursor.lastFireAt()); state.put("queuedCount", waiting(id));
        state.put("nextFireTimes", configuration.cronExpression().isBlank() ? List.of() : nextTimes(configuration, Instant.now()));
        state.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM plan_schedule_occurrence WHERE plan_id=? AND project_id=?", Long.class, id, project));
        state.put("items", jdbc.query("SELECT o.*,r.status AS run_status FROM plan_schedule_occurrence o LEFT JOIN test_run r ON r.id=o.run_id WHERE o.plan_id=? AND o.project_id=? ORDER BY o.scheduled_for DESC,o.id LIMIT ? OFFSET ?", (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getString("id")); item.put("status", rs.getString("status")); item.put("reason", rs.getString("reason"));
            item.put("scheduledFor", rs.getTimestamp("scheduled_for").toInstant()); item.put("misfireThrough", instant(rs.getTimestamp("misfire_through")));
            item.put("planVersion", rs.getString("plan_version")); item.put("dispatchedPlanVersion", rs.getString("dispatched_plan_version"));
            item.put("configuration", json.map(rs.getString("configuration"))); item.put("runId", rs.getString("run_id")); item.put("jobId", rs.getString("job_id")); item.put("runStatus", rs.getString("run_status"));
            item.put("createdAt", rs.getTimestamp("created_at").toInstant()); item.put("dispatchedAt", instant(rs.getTimestamp("dispatched_at"))); return item;
        }, id, project, limit, offset));
        return state;
    }

    private Asset plan(String project, String id) { Asset plan = assets.getInternal(project, id); if (plan.type() != AssetType.TEST_PLAN) throw Problem.invalid("此记录不是测试计划"); return plan; }
    private boolean lockActivePlan(String project, String id) {
        // Include deleted owners while retiring their pending work, but still lock the project first.
        var deleted = jdbc.queryForList("SELECT deleted FROM project WHERE id=? FOR UPDATE", Boolean.class, project);
        return !deleted.isEmpty() && !deleted.getFirst() && jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE id=? AND project_id=? AND deleted=FALSE", Long.class, id, project) > 0;
    }
    private void retire(String id) { cancelWaiting(id, "计划或项目已删除"); jdbc.update("UPDATE plan_schedule_cursor SET enabled=FALSE,next_fire_at=NULL WHERE plan_id=?", id); }
    private boolean active(String project, String plan) { return jdbc.queryForObject("SELECT COUNT(*) FROM test_run WHERE project_id=? AND asset_id=? AND status IN ('QUEUED','RUNNING')", Long.class, project, plan) > 0; }
    private long waiting(String plan) { return jdbc.queryForObject("SELECT COUNT(*) FROM plan_schedule_occurrence WHERE plan_id=? AND status='WAITING'", Long.class, plan); }
    private void cancelWaiting(String plan, String reason) { jdbc.update("UPDATE plan_schedule_occurrence SET status='CANCELLED',reason=? WHERE plan_id=? AND status='WAITING'", reason, plan); }
    private Cursor cursor(String id) {
        var rows = jdbc.query("SELECT configuration_hash,next_fire_at,last_fire_at FROM plan_schedule_cursor WHERE plan_id=?", (rs, n) -> new Cursor(rs.getString("configuration_hash"), instant(rs.getTimestamp("next_fire_at")), instant(rs.getTimestamp("last_fire_at"))), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Configuration configuration(Map<String, Object> data) {
        String cron = Objects.toString(data.get("cronExpression"), "").strip(), zone = Objects.toString(data.get("timezone"), "").strip();
        if (zone.isBlank()) zone = defaultZone;
        try { ZoneId.of(zone); if (!cron.isBlank()) CronExpression.parse(cron); }
        catch (IllegalArgumentException failure) { throw Problem.invalid("Cron 或时区无效，请使用六字段 Cron 和标准时区名称"); }
        return new Configuration(Boolean.TRUE.equals(data.get("scheduleEnabled")), cron, zone, Objects.toString(data.get("overlapPolicy"), "SKIP"), Objects.toString(data.get("misfirePolicy"), "SKIP"));
    }
    private Instant next(Configuration configuration, Instant after) {
        if (configuration.cronExpression().isBlank()) return null;
        ZonedDateTime next = CronExpression.parse(configuration.cronExpression()).next(after.atZone(ZoneId.of(configuration.timezone())));
        return next == null ? null : next.toInstant();
    }
    private List<String> nextTimes(Configuration configuration, Instant after) {
        List<String> times = new ArrayList<>();
        for (int i = 0; i < 5; i++) { after = next(configuration, after); if (after == null) break; times.add(after.toString()); }
        return times;
    }
    private String fingerprint(Configuration configuration) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.write(configuration).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static String limited(String message) { return message == null ? "派发失败" : message.substring(0, Math.min(message.length(), 500)); }
    private record Cursor(String configurationHash, Instant nextFireAt, Instant lastFireAt) { }
    public record Configuration(boolean enabled, String cronExpression, String timezone, String overlapPolicy, String misfirePolicy) { }
}
