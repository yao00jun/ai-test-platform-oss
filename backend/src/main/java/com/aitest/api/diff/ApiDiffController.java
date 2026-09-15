package com.aitest.api.diff;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/api-diffs")
public class ApiDiffController {
    private final ApiDiffService diffs;
    private final ApiHealingService healing;
    public ApiDiffController(ApiDiffService diffs, ApiHealingService healing) { this.diffs = diffs; this.healing = healing; }
    @PostMapping("/preview") Object preview(@PathVariable String projectId, @RequestBody ApiDiffService.PreviewInput input) { return diffs.preview(projectId, input); }
    @GetMapping Object list(@PathVariable String projectId, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) { return diffs.list(projectId, offset, limit); }
    @GetMapping("/{id}") Object get(@PathVariable String projectId, @PathVariable String id) { return diffs.get(projectId, id); }
    @PostMapping("/{id}/apply") Object apply(@PathVariable String projectId, @PathVariable String id, @RequestBody ApiDiffService.ApplyInput input) { return diffs.apply(projectId, id, input); }
    @PostMapping("/{id}/heal") Object heal(@PathVariable String projectId, @PathVariable String id, @RequestBody ApiHealingService.Input input) { return healing.submit(projectId, id, input); }
}
