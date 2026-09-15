package com.aitest.execution;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/runs")
public final class RunController {
    private final ExecutionCoordinator execution; private final RunRepository runs;
    public RunController(ExecutionCoordinator execution, RunRepository runs) { this.execution = execution; this.runs = runs; }
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    ExecutionCoordinator.Submission run(@PathVariable String projectId, @RequestBody ExecutionCoordinator.Request request) { return execution.submit(projectId, request); }
    @GetMapping Object list(@PathVariable String projectId, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) { return runs.list(projectId, offset, limit); }
    @GetMapping("/{id}") Object get(@PathVariable String projectId, @PathVariable String id) { return runs.get(projectId, id); }
    @PostMapping("/{id}/manual-result") Object manual(@PathVariable String projectId, @PathVariable String id, @RequestBody ManualInput input) { return runs.manual(projectId, id, input.itemId(), input.baseVersion(), input.status(), input.notes()); }
    public record ManualInput(String itemId, String baseVersion, String status, String notes) { }
}
