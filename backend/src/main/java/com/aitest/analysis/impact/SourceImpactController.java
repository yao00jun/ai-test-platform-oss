package com.aitest.analysis.impact;

import com.aitest.common.Problem;
import com.aitest.job.JobService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/projects/{project}")
public final class SourceImpactController {
    private final SourceImpactService service; private final SourceImpactRepository repository; private final ImpactSelectionService selection; private final JobService jobs;
    public SourceImpactController(SourceImpactService service, SourceImpactRepository repository, ImpactSelectionService selection, JobService jobs) { this.service = service; this.repository = repository; this.selection = selection; this.jobs = jobs; }
    @PostMapping("/source-analyses/{id}/impact") public Map<String, Object> submit(@PathVariable String project, @PathVariable String id, @RequestBody Map<String, Object> input) {
        fields(input, Set.of("baselineSnapshotId", "idempotencyKey"));
        return service.submit(project, id, new SourceImpactService.Request(text(input, "baselineSnapshotId"), text(input, "idempotencyKey")));
    }
    @GetMapping("/source-analyses/{id}/impacts") public Map<String, Object> list(@PathVariable String project, @PathVariable String id, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "25") int limit) { return repository.list(project, id, offset, limit); }
    @GetMapping("/source-impacts/{id}") public Map<String, Object> get(@PathVariable String project, @PathVariable String id) { return repository.get(project, id); }
    @PostMapping("/source-impacts/{id}/cancel") public Map<String, Object> cancel(@PathVariable String project, @PathVariable String id) { var report = repository.get(project, id); jobs.cancel(project, report.get("jobId").toString()); return repository.get(project, id); }
    @PostMapping("/source-impacts/{id}/regression-plan") public Map<String, Object> plan(@PathVariable String project, @PathVariable String id, @RequestBody Map<String, Object> input) {
        fields(input, Set.of("assetIds", "name", "environmentId", "idempotencyKey"));
        if (!(input.get("assetIds") instanceof List<?> ids) || ids.stream().anyMatch(item -> !(item instanceof String))) throw Problem.invalid("assetIds 必须为测试 ID 数组");
        return selection.create(project, id, new ImpactSelectionService.Request(ids.stream().map(Object::toString).toList(), text(input, "name"), text(input, "environmentId"), text(input, "idempotencyKey")));
    }
    private static void fields(Map<String, Object> input, Set<String> allowed) { if (!allowed.containsAll(input.keySet())) throw new Problem(400, "INVALID_IMPACT_INPUT", "包含未声明的影响分析字段"); }
    private static String text(Map<String, Object> input, String key) { Object value = input.get(key); if (value != null && !(value instanceof String)) throw new Problem(400, "INVALID_IMPACT_INPUT", key + " 必须为文本"); return (String) value; }
}
