package com.aitest.job;

import com.aitest.asset.AssetRepository;
import com.aitest.common.Ids;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Service
public class JobService {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final TransactionTemplate transactions;
    private final TransactionTemplate leaseTransactions;
    private final AssetRepository assets;
    private final ObjectProvider<JobHandler> handlers;
    private final JobSignals signals;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots;
    private final String owner = Ids.newId();
    private final Map<String, Thread> running = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public JobService(JdbcTemplate jdbc, JsonCodec json, TransactionTemplate transactions, AssetRepository assets,
                      ObjectProvider<JobHandler> handlers, JobSignals signals, @Value("${aitest.execution.concurrency:8}") int concurrency) {
        this.jdbc = jdbc; this.json = json; this.transactions = transactions; this.assets = assets; this.handlers = handlers;
        this.signals = signals;
        this.leaseTransactions = new TransactionTemplate(transactions.getTransactionManager());
        this.leaseTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.slots = new Semaphore(Math.clamp(concurrency, 1, 64));
    }
    public Job submit(String projectId, String kind, String key, Map<String, Object> input) {
        assets.project(projectId);
        if (key == null || key.isBlank() || key.length() > 160) throw Problem.invalid("需要有效的 idempotencyKey");
        String id = Ids.newId(); Instant now = Instant.now();
        try {
            jdbc.update("INSERT INTO job_task(id,project_id,kind,idempotency_key,input,status,created_at,updated_at) VALUES(?,?,?,?,?,'QUEUED',?,?)", id, projectId, kind, key, json.write(input), Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException duplicate) {
            var rows = jdbc.queryForList("SELECT id,input FROM job_task WHERE project_id=? AND kind=? AND idempotency_key=?", projectId, kind, key);
            if (rows.isEmpty()) throw duplicate;
            Map<String, Object> row = rows.getFirst();
            if (!json.tree(row.get("input").toString()).equals(json.tree(json.write(input)))) throw new Problem(409, "IDEMPOTENCY_CONFLICT", "此幂等键已用于不同的请求");
            return get(projectId, row.get("id").toString());
        }
        event(id, projectId, "progress", Map.of("progress", 0, "message", "排队中"));
        return get(projectId, id);
    }
    public Job get(String projectId, String id) {
        assets.project(projectId);
        List<Job> jobs = jdbc.query("SELECT * FROM job_task WHERE id=? AND project_id=?", (rs, n) -> row(rs), id, projectId);
        if (jobs.isEmpty()) throw Problem.missing();
        return jobs.getFirst();
    }
    private Job row(ResultSet rs) throws SQLException {
        return new Job(rs.getString("id"), rs.getString("project_id"), rs.getString("kind"), rs.getString("status"), rs.getInt("progress"), rs.getString("message"), json.map(rs.getString("result")), rs.getString("error"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    public Job cancel(String projectId, String id) {
        Job result = transactions.execute(tx -> {
            lock(projectId, id);
            Job job = get(projectId, id);
            if (job.terminal()) return job;
            jdbc.update("UPDATE job_task SET cancel_requested=TRUE,status='CANCELLED',updated_at=? WHERE id=?", Timestamp.from(Instant.now()), id);
            event(id, projectId, "done", Map.of("status", "CANCELLED", "message", "任务已取消"));
            return get(projectId, id);
        });
        // Workers observe the durable cancellation at checkpoints. Interrupting an arbitrary
        // virtual thread can close a JDBC socket while its transaction is committing.
        return result;
    }
    public void checkpoint(String projectId, String id) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("任务已取消");
        Map<String, Object> row = state(projectId, id);
        if (!"RUNNING".equals(row.get("status")) || Boolean.TRUE.equals(row.get("cancel_requested"))) throw new CancellationException("任务已停止");
        Object leaseValue = row.get("lease_until");
        Instant deadline = leaseValue instanceof Timestamp timestamp ? timestamp.toInstant()
                : leaseValue instanceof java.time.LocalDateTime local ? local.toInstant(java.time.ZoneOffset.UTC) : Instant.MIN;
        if (!owner.equals(row.get("owner")) || !deadline.isAfter(Instant.now())) throw new LeaseLostException();
    }
    private Map<String, Object> state(String projectId, String id) {
        var rows = jdbc.queryForList("SELECT status,cancel_requested,owner,lease_until FROM job_task WHERE id=? AND project_id=?", id, projectId);
        if (rows.isEmpty()) throw Problem.missing(); return rows.getFirst();
    }
    private void lock(String projectId, String id) {
        var ids = jdbc.queryForList("SELECT id FROM job_task WHERE id=? AND project_id=? FOR UPDATE", String.class, id, projectId);
        if (ids.isEmpty()) throw Problem.missing();
    }
    public <T> T atomic(String projectId, String id, Supplier<T> work, boolean complete) {
        return transactions.execute(tx -> {
            lock(projectId, id); checkpoint(projectId, id);
            T value = work.get();
            if (complete) finishLocked(projectId, id, "SUCCEEDED", value, null);
            return value;
        });
    }
    public void progress(String projectId, String id, int progress, String message) {
        checkpoint(projectId, id);
        jdbc.update("UPDATE job_task SET progress=?,message=?,updated_at=? WHERE id=? AND status='RUNNING'", Math.clamp(progress, 0, 99), message, Timestamp.from(Instant.now()), id);
        event(id, projectId, "progress", Map.of("progress", Math.clamp(progress, 0, 99), "message", message));
    }
    public void event(String jobId, String projectId, String type, Map<String, Object> payload) {
        Map<String, Object> data = new LinkedHashMap<>(payload); data.put("jobId", jobId); data.put("projectId", projectId);
        jdbc.update("INSERT INTO job_event(job_id,event_type,payload,created_at) VALUES(?,?,?,?)", jobId, type, json.write(data), Timestamp.from(Instant.now()));
        signals.afterCommit(jobId);
    }
    public List<JobEvent> events(String projectId, String id, long after) {
        return eventPage(projectId, id, after).events();
    }
    public JobEventPage eventPage(String projectId, String id, long after) {
        return jdbc.query("""
                SELECT j.status,e.seq,e.event_type,e.payload,e.created_at
                FROM job_task j JOIN project p ON p.id=j.project_id AND p.deleted=FALSE
                LEFT JOIN job_event e ON e.job_id=j.id AND e.seq>?
                WHERE j.id=? AND j.project_id=? ORDER BY e.seq LIMIT ?
                """, rs -> {
            if (!rs.next()) throw Problem.missing();
            String status = rs.getString("status"); var events = new ArrayList<JobEvent>();
            do {
                long seq = rs.getLong("seq");
                if (!rs.wasNull()) events.add(new JobEvent(seq, rs.getString("event_type"), json.map(rs.getString("payload")), rs.getTimestamp("created_at").toInstant()));
            } while (rs.next());
            return new JobEventPage(status, List.copyOf(events));
        }, after, id, projectId, JobEventPage.LIMIT);
    }

    @Scheduled(fixedDelay = 250, initialDelay = 1000)
    public synchronized void dispatch() {
        if (stopping) return;
        while (slots.tryAcquire()) {
            Map<String, Object> task;
            try {
                task = transactions.execute(tx -> {
                    var rows = jdbc.queryForList("SELECT j.id,j.project_id,j.kind,j.input FROM job_task j JOIN project p ON p.id=j.project_id AND p.deleted=FALSE WHERE j.status='QUEUED' ORDER BY j.created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
                    if (rows.isEmpty()) return null;
                    var row = rows.getFirst();
                    jdbc.update("UPDATE job_task SET status='RUNNING',owner=?,lease_until=?,updated_at=? WHERE id=?", owner, Timestamp.from(Instant.now().plusSeconds(30)), Timestamp.from(Instant.now()), row.get("id"));
                    return row;
                });
            } catch (RuntimeException e) { slots.release(); throw e; }
            if (task == null) { slots.release(); break; }
            workers.submit(() -> run(task));
        }
    }
    private void run(Map<String, Object> task) {
        String id = task.get("id").toString(), projectId = task.get("project_id").toString();
        running.put(id, Thread.currentThread());
        try {
            JobContext context = new JobContext(id, projectId, this); context.checkpoint();
            JobHandler handler = handlers.stream().filter(h -> h.kind().equals(task.get("kind"))).findFirst().orElseThrow(() -> new Problem(422, "UNKNOWN_JOB", "无法识别此任务类型"));
            Map<String, Object> result = handler.execute(context, json.map(task.get("input").toString()));
            finish(projectId, id, "SUCCEEDED", result, null);
        } catch (LeaseLostException e) {
            finish(projectId, id, "INTERRUPTED", Map.of(), "任务租约已失效，未自动重放");
        } catch (CancellationException | InterruptedException e) {
            Thread.interrupted(); finish(projectId, id, "CANCELLED", Map.of(), "任务已取消");
        } catch (Exception e) {
            Thread.interrupted();
            String message = e instanceof Problem p ? p.getMessage() : "任务执行错误（" + e.getClass().getSimpleName() + "）";
            finish(projectId, id, "FAILED", Map.of(), message);
        } finally { running.remove(id); slots.release(); }
    }
    private void finish(String projectId, String id, String status, Object result, String error) {
        transactions.executeWithoutResult(tx -> {
            lock(projectId, id);
            // Internal completion must still persist after the owning project is deleted.
            // Public get() intentionally rejects deleted projects and cannot be used here.
            if (List.of("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED").contains(state(projectId, id).get("status"))) return;
            if (status.equals("SUCCEEDED")) checkpoint(projectId, id);
            finishLocked(projectId, id, status, result, error);
        });
    }
    private void finishLocked(String projectId, String id, String status, Object result, String error) {
        jdbc.update("UPDATE job_task SET status=?,progress=?,result=?,error=?,lease_until=NULL,updated_at=? WHERE id=?", status, status.equals("SUCCEEDED") ? 100 : 0, json.write(result), error, Timestamp.from(Instant.now()), id);
        if (error != null) event(id, projectId, "error", Map.of("message", error));
        if (status.equals("SUCCEEDED")) event(id, projectId, "result", Map.of("result", result));
        event(id, projectId, "done", Map.of("status", status, "result", result == null ? Map.of() : result));
    }
    @Scheduled(fixedDelay = 5000, initialDelayString = "${aitest.execution.lease-initial-delay-ms:2000}")
    public void leases() {
        // Discover without locks. A status-index range lock followed by a primary-key
        // lock can deadlock with atomic(), which locks the primary key before status.
        var candidates = jdbc.queryForList("SELECT id FROM job_task WHERE status='RUNNING' AND (owner=? OR lease_until<=? OR lease_until IS NULL) ORDER BY id",
                String.class, owner, Timestamp.from(Instant.now()));
        for (String id : candidates) leaseTransactions.executeWithoutResult(tx -> {
            var rows = jdbc.query("SELECT project_id,status,owner,lease_until FROM job_task WHERE id=? FOR UPDATE SKIP LOCKED", (rs, n) -> {
                Timestamp deadline = rs.getTimestamp("lease_until");
                return new LeaseState(rs.getString("project_id"), rs.getString("status"), rs.getString("owner"),
                        deadline == null ? Instant.MIN : deadline.toInstant());
            }, id);
            if (rows.isEmpty()) return;
            LeaseState task = rows.getFirst();
            if (!"RUNNING".equals(task.status())) return;
            Instant now = Instant.now();
            // Recheck under the row lock: the candidate may have finished, changed
            // ownership, or been renewed since discovery. Never revive an expired lease.
            if (!task.deadline().isAfter(now)) {
                finishLocked(task.projectId(), id, "INTERRUPTED", Map.of(), "服务中断，任务未自动重放；请检查已有结果后重试");
            } else if (owner.equals(task.owner())) {
                jdbc.update("UPDATE job_task SET lease_until=? WHERE id=?", Timestamp.from(now.plusSeconds(30)), id);
            }
        });
    }
    private record LeaseState(String projectId, String status, String owner, Instant deadline) { }
    @PreDestroy
    void close() {
        synchronized (this) { stopping = true; workers.shutdown(); }
        boolean interrupted = false;
        try {
            if (workers.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)) return;
        } catch (InterruptedException e) { interrupted = true; }
        // Locking first lets an in-flight atomic domain transaction finish. Once terminal,
        // no worker can enter another domain mutation, even if a remote call finishes late.
        transactions.executeWithoutResult(tx -> {
            var remaining = jdbc.queryForList("SELECT id,project_id FROM job_task WHERE owner=? AND status='RUNNING' FOR UPDATE", owner);
            for (var task : remaining) finishLocked(task.get("project_id").toString(), task.get("id").toString(), "INTERRUPTED", Map.of(), "服务关闭，执行结果可能不完整；未自动重放");
        });
        workers.shutdownNow();
        try { workers.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { interrupted = true; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    public static final class LeaseLostException extends RuntimeException { }
}
