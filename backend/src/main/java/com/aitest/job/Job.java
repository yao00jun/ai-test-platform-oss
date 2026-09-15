package com.aitest.job;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record Job(String id, String projectId, String kind, String status, int progress,
                  String message, Map<String, Object> result, String error, Instant createdAt, Instant updatedAt) {
    public boolean terminal() { return Set.of("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED").contains(status); }
}
