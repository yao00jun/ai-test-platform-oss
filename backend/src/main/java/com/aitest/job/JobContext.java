package com.aitest.job;

import java.util.Map;
import java.util.function.Supplier;

public record JobContext(String id, String projectId, JobService jobs) {
    public void checkpoint() { jobs.checkpoint(projectId, id); }
    public void progress(int percent, String message) { jobs.progress(projectId, id, percent, message); }
    /** Replaces the visible status line (what the job is waiting for) without moving the progress bar. */
    public void status(String message) { jobs.status(projectId, id, message); }
    public void event(String type, Map<String, Object> payload) { checkpoint(); jobs.event(id, projectId, type, payload); }
    public <T> T atomic(Supplier<T> work) { return jobs.atomic(projectId, id, work, false); }
    public Map<String, Object> completeAtomically(Supplier<Map<String, Object>> work) { return jobs.atomic(projectId, id, work, true); }
}
