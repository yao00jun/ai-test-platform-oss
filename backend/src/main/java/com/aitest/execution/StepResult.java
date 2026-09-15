package com.aitest.execution;

import java.util.List;
import java.util.Map;

public record StepResult(String status, long durationMs, Map<String, Object> request, Map<String, Object> actual,
                         List<AssertionResult> assertions, Map<String, Object> exports, List<String> artifactIds, String error) {
    public boolean successful() { return status.equals("PASSED"); }
    public static StepResult error(String message, long durationMs, Map<String, Object> request) {
        return new StepResult("ERROR", durationMs, request, Map.of(), List.of(), Map.of(), List.of(), message);
    }
}
