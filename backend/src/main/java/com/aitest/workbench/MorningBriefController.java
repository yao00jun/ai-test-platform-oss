package com.aitest.workbench;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/morning-brief")
public class MorningBriefController {
    private final MorningBriefService briefs;
    public MorningBriefController(MorningBriefService briefs) { this.briefs = briefs; }
    @GetMapping("/schedule") public MorningBriefService.Schedule get(@PathVariable String projectId) { return briefs.settings(projectId); }
    @PutMapping("/schedule") public MorningBriefService.Schedule configure(@PathVariable String projectId, @RequestBody Map<String, Object> input) { return briefs.configure(projectId, input); }
    @GetMapping("/history") public Map<String, Object> history(@PathVariable String projectId, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit) { return briefs.history(projectId, offset, limit); }
    @PostMapping("/occurrences/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public MorningBriefService.Submission retry(@PathVariable String projectId, @PathVariable String id, @RequestBody Map<String, Object> input) { return briefs.retry(projectId, id, input); }
}
