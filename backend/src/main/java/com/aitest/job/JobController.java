package com.aitest.job;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import com.aitest.security.PlatformSessionAccess;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobs;
    private final PlatformSessionAccess sessions;
    private final JobSignals signals;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
    public JobController(JobService jobs, PlatformSessionAccess sessions, JobSignals signals) { this.jobs = jobs; this.sessions = sessions; this.signals = signals; }
    @GetMapping("/{id}") Job get(@PathVariable String id, @RequestParam String projectId) { return jobs.get(projectId, id); }
    @PostMapping("/{id}/cancel") Job cancel(@PathVariable String id, @RequestBody Map<String, String> input) { return jobs.cancel(input.get("projectId"), id); }
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String id, @RequestParam String projectId, @RequestParam(defaultValue = "0") long after, @RequestHeader(value = "Last-Event-ID", required = false) String lastId, HttpServletRequest request) {
        jobs.get(projectId, id);
        long cursor = after;
        if (lastId != null) try { cursor = Math.max(cursor, Long.parseLong(lastId)); } catch (NumberFormatException ignored) { }
        SseEmitter emitter = new SseEmitter(0L);
        var authorized = sessions.capture(request);
        AtomicBoolean connected = new AtomicBoolean(true);
        var subscription = signals.subscribe(id);
        Runnable disconnect = () -> { connected.set(false); subscription.close(); };
        emitter.onCompletion(disconnect); emitter.onTimeout(disconnect); emitter.onError(e -> disconnect.run());
        long initial = cursor;
        try { streams.submit(() -> {
            long position = initial, lastHeartbeat = System.currentTimeMillis();
            try {
                while (connected.get()) {
                    if (!authorized.getAsBoolean()) { emitter.complete(); return; }
                    // Subscribe and capture the generation before the read: a commit during
                    // that read cannot be lost between draining the page and waiting.
                    long observed = subscription.version(), readStarted = System.nanoTime();
                    var page = jobs.eventPage(projectId, id, position);
                    for (JobEvent event : page.events()) {
                        if (!authorized.getAsBoolean()) { emitter.complete(); return; }
                        emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name(event.type()).data(event.data()));
                        position = event.seq();
                    }
                    if (page.terminal() && page.events().size() < JobEventPage.LIMIT) { emitter.complete(); return; }
                    if (System.currentTimeMillis() - lastHeartbeat > 15000) { emitter.send(SseEmitter.event().comment("heartbeat")); lastHeartbeat = System.currentTimeMillis(); }
                    if (page.events().size() == JobEventPage.LIMIT) continue;
                    // Periodic reads cover a missed notification or another application
                    // process. Coalesce token bursts so wakeups cannot flood the database.
                    subscription.awaitChange(observed, Duration.ofSeconds(1));
                    long remaining = TimeUnit.MILLISECONDS.toNanos(100) - (System.nanoTime() - readStarted);
                    if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                }
            } catch (IOException e) { connected.set(false); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); emitter.complete(); }
            catch (Exception e) { emitter.completeWithError(e); }
            finally { disconnect.run(); }
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) { disconnect.run(); throw stopped; }
        return emitter;
    }
    @PreDestroy void close() { streams.shutdownNow(); }
}
