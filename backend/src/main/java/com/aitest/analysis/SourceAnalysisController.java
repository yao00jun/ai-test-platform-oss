package com.aitest.analysis;

import com.aitest.job.JobService;
import com.aitest.common.Problem;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/projects/{project}/source-analyses")
public class SourceAnalysisController {
    private final SourceAnalysisService service;
    private final SourceSnapshotRepository snapshots;
    private final JobService jobs;
    public SourceAnalysisController(SourceAnalysisService service, SourceSnapshotRepository snapshots, JobService jobs) { this.service = service; this.snapshots = snapshots; this.jobs = jobs; }
    @PostMapping public Map<String, Object> submit(@PathVariable String project, @RequestBody Map<String, Object> input) {
        Set<String> fields = Set.of("backendRepoPath", "frontendRepoPath", "sqlScriptPath", "ddlText", "backendRef", "frontendRef", "baselineRef", "idempotencyKey");
        if (input.keySet().stream().anyMatch(key -> !fields.contains(key)) || input.values().stream().anyMatch(value -> value != null && !(value instanceof String))) {
            throw new Problem(400, "INVALID_SOURCE_INPUT", "源码分析只接受声明的文本字段");
        }
        return service.submit(project, new SourceAnalysisService.Input((String) input.get("backendRepoPath"), (String) input.get("frontendRepoPath"),
                (String) input.get("sqlScriptPath"), (String) input.get("ddlText"), (String) input.get("backendRef"), (String) input.get("frontendRef"), (String) input.get("baselineRef"), (String) input.get("idempotencyKey")));
    }
    @GetMapping public Map<String, Object> list(@PathVariable String project, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "25") int limit) { return snapshots.list(project, offset, limit); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable String project, @PathVariable String id) { return snapshots.get(project, id); }
    @GetMapping("/{id}/files") public Map<String, Object> files(@PathVariable String project, @PathVariable String id, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) { return snapshots.files(project, id, offset, limit); }
    @GetMapping("/{id}/file") public Map<String, Object> file(@PathVariable String project, @PathVariable String id, @RequestParam String kind, @RequestParam String path, @RequestParam(defaultValue = "1") int from, @RequestParam(defaultValue = "100") int to) { return snapshots.excerpt(project, id, kind, path, from, to); }
    @PostMapping("/{id}/cancel") public Map<String, Object> cancel(@PathVariable String project, @PathVariable String id) { var snapshot = snapshots.get(project, id); jobs.cancel(project, snapshot.get("jobId").toString()); return snapshots.get(project, id); }
}
