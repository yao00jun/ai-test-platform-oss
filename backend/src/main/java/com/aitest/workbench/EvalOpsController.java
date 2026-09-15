package com.aitest.workbench;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/evalops")
public class EvalOpsController {
    private final EvalOpsService evalops;
    public EvalOpsController(EvalOpsService evalops) { this.evalops = evalops; }
    @GetMapping Object get(@PathVariable String projectId, @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return evalops.summary(projectId, EvalOpsService.window(from, to));
    }
    @GetMapping("/invocations") Object invocations(@PathVariable String projectId, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String modelName, @RequestParam(required = false) String modelVersion,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "25") int limit) {
        return evalops.list(projectId, EvalOpsService.window(from, to), modelName, modelVersion, offset, limit);
    }
}
