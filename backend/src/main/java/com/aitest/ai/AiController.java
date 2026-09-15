package com.aitest.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiRefinementService refinement;
    private final AiConversationService conversations;
    private final AiChangeSetService changes;
    private final AiGenerationService generation;
    private final GlobalFeedbackService feedback;
    public AiController(AiRefinementService refinement, AiConversationService conversations, AiChangeSetService changes, AiGenerationService generation, GlobalFeedbackService feedback) {
        this.refinement = refinement; this.conversations = conversations; this.changes = changes; this.generation = generation; this.feedback = feedback;
    }
    @PostMapping("/generate") @ResponseStatus(HttpStatus.ACCEPTED)
    AiRefinementService.Submission generate(@RequestBody AiGenerationService.Request request) { return generation.submit(request); }
    @PostMapping("/feedback") @ResponseStatus(HttpStatus.ACCEPTED)
    AiRefinementService.Submission feedback(@RequestBody GlobalFeedbackService.Request request) { return feedback.submit(request); }
    @PostMapping("/refine-item") @ResponseStatus(HttpStatus.ACCEPTED)
    AiRefinementService.Submission refine(@RequestBody AiRefinementService.RefineRequest request) { return refinement.submit(request); }
    @GetMapping("/conversations/{id}") Object conversation(@PathVariable String id, @RequestParam String projectId) {
        Map<String, Object> result = conversations.get(projectId, id); result.put("messages", conversations.messages(projectId, id)); return result;
    }
    @GetMapping("/change-sets/{id}") Object changeSet(@PathVariable String id, @RequestParam String projectId) { return changes.get(projectId, id); }
    @PostMapping("/change-sets/{id}/apply") Object apply(@PathVariable String id, @RequestBody AiChangeSetService.ApplyInput input) { return changes.apply(input.projectId(), id, input.itemIds()); }
    @PostMapping("/change-sets/{id}/reject") @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@PathVariable String id, @RequestBody Map<String, String> input) { changes.reject(input.get("projectId"), id); }
}
