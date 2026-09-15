package com.aitest.execution;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public final class ExecutionValidationController {
    private final ScenarioDependencyValidator validator;
    public ExecutionValidationController(ScenarioDependencyValidator validator) { this.validator = validator; }
    public record Request(String environmentId, String datasetId) { }
    @PostMapping("/api/projects/{projectId}/assets/{id}/validate-execution")
    public Map<String, Object> validate(@PathVariable String projectId, @PathVariable String id, @RequestBody Request input) {
        return validator.validate(projectId, id, input.environmentId(), input.datasetId());
    }
}
