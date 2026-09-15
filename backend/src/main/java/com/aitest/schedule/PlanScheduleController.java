package com.aitest.schedule;

import com.aitest.asset.Asset;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/plans/{planId}/schedule")
public class PlanScheduleController {
    private final PlanScheduleService schedules;
    public PlanScheduleController(PlanScheduleService schedules) { this.schedules = schedules; }
    @GetMapping public Map<String, Object> get(@PathVariable String projectId, @PathVariable String planId, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "25") int limit) { return schedules.state(projectId, planId, offset, limit); }
    @PutMapping public Asset configure(@PathVariable String projectId, @PathVariable String planId, @RequestBody Map<String, Object> input) { return schedules.configure(projectId, planId, input); }
    @PostMapping("/preview") public Map<String, Object> preview(@PathVariable String projectId, @PathVariable String planId, @RequestBody Map<String, Object> input) { return schedules.preview(projectId, planId, input); }
}
