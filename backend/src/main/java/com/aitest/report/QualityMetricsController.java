package com.aitest.report;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/quality-metrics")
public class QualityMetricsController {
    private final QualityMetricsService metrics;
    public QualityMetricsController(QualityMetricsService metrics) { this.metrics = metrics; }
    @GetMapping public Map<String, Object> get(@PathVariable String projectId, @RequestParam(required = false) String runId) { return metrics.capture(projectId, runId); }
}
