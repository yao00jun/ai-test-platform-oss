package com.aitest.ai.pipeline;

import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/ai/pipelines")
public final class PipelineController {
    private final PipelineService service;
    public PipelineController(PipelineService service) { this.service = service; }
    @PostMapping public Map<String, Object> submit(@RequestBody PipelineService.Request input) { return service.submit(input); }
    @GetMapping public List<Map<String, Object>> list(@RequestParam String projectId) { return service.list(projectId); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable String id, @RequestParam String projectId) { return service.get(projectId, id); }
    @PostMapping("/{id}/resume") public Map<String, Object> resume(@PathVariable String id, @RequestBody PipelineService.Resume input) { return service.resume(id, input); }
    @PostMapping("/{id}/cancel") public Map<String, Object> cancel(@PathVariable String id, @RequestBody Map<String, String> input) { return service.cancel(input.get("projectId"), id); }
}
