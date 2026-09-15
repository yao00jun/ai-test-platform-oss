package com.aitest.engine.web;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public final class PageEvidenceController {
    private final PageEvidenceService service;
    public PageEvidenceController(PageEvidenceService service) { this.service = service; }
    public record Request(String idempotencyKey) { }
    @PostMapping("/api/projects/{projectId}/environments/{id}/page-evidence")
    public Map<String, Object> capture(@PathVariable String projectId, @PathVariable String id, @RequestBody Request input) { return service.submit(projectId, id, input.idempotencyKey()); }
}
