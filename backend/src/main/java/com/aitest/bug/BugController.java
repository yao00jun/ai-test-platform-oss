package com.aitest.bug;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}")
public final class BugController {
    private final BugDiagnosisService diagnoses;
    private final BugOccurrenceService occurrences;
    private final com.aitest.analysis.rca.SourceEvidenceReader sources;
    private final RcaEvaluationService evaluations;
    public BugController(BugDiagnosisService diagnoses, BugOccurrenceService occurrences, com.aitest.analysis.rca.SourceEvidenceReader sources, RcaEvaluationService evaluations) { this.diagnoses = diagnoses; this.occurrences = occurrences; this.sources = sources; this.evaluations = evaluations; }
    public record Request(String idempotencyKey) { }
    @PostMapping("/runs/{runId}/diagnose") public Map<String, Object> diagnose(@PathVariable String projectId, @PathVariable String runId, @RequestBody Request request) { return diagnoses.submit(projectId, runId, request.idempotencyKey()); }
    @GetMapping("/runs/{runId}/diagnoses") public Map<String, Object> forRun(@PathVariable String projectId, @PathVariable String runId) { return occurrences.forRun(projectId, runId); }
    @GetMapping("/bugs/{bugId}/occurrences") public Map<String, Object> forBug(@PathVariable String projectId, @PathVariable String bugId) { return occurrences.forBug(projectId, bugId); }
    @GetMapping("/bugs/{bugId}/code-evidence") public Map<String, Object> evidence(@PathVariable String projectId, @PathVariable String bugId) { return sources.forBug(projectId, bugId); }
    @GetMapping("/bugs/{bugId}/rca-evaluations") public Map<String, Object> evaluations(@PathVariable String projectId, @PathVariable String bugId, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "25") int limit) { return evaluations.list(projectId, bugId, offset, limit); }
    @PostMapping("/bugs/{bugId}/rca-evaluations") public Map<String, Object> evaluate(@PathVariable String projectId, @PathVariable String bugId, @RequestBody RcaEvaluationService.Input input) { return evaluations.record(projectId, bugId, input); }
}
