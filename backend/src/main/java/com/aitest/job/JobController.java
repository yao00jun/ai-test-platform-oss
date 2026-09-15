package com.aitest.job;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import com.aitest.security.PlatformSessionAccess;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobs;
    private final PlatformSessionAccess sessions;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
    public JobController(JobService jobs, PlatformSessionAccess sessions) { this.jobs = jobs; this.sessions = sessions; }
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
        emitter.onCompletion(() -> connected.set(false)); emitter.onTimeout(() -> connected.set(false)); emitter.onError(e -> connected.set(false));
        long initial = cursor;
        streams.submit(() -> {
            long position = initial, lastHeartbeat = System.currentTimeMillis();
            try {
                while (connected.get()) {
                    if (!authorized.getAsBoolean()) { emitter.complete(); return; }
                    // Observe terminal state before draining events, so a concurrent commit cannot be missed.
                    boolean terminalBeforeDrain = jobs.get(projectId, id).terminal();
                    var batch = jobs.events(projectId, id, position);
                    for (JobEvent event : batch) {
                        if (!authorized.getAsBoolean()) { emitter.complete(); return; }
                        emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name(event.type()).data(event.data()));
                        position = event.seq();
                    }
                    if (terminalBeforeDrain && batch.size() < 1000) { emitter.complete(); return; }
                    if (System.currentTimeMillis() - lastHeartbeat > 15000) { emitter.send(SseEmitter.event().comment("heartbeat")); lastHeartbeat = System.currentTimeMillis(); }
                    Thread.sleep(250);
                }
            } catch (IOException e) { connected.set(false); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); emitter.complete(); }
            catch (Exception e) { emitter.completeWithError(e); }
        });
        return emitter;
    }
    @PreDestroy void close() { streams.shutdownNow(); }
}
