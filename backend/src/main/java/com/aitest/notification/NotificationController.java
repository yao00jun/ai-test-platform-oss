package com.aitest.notification;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/webhooks/{webhookId}/deliveries")
public final class NotificationController {
    private final NotificationService notifications;
    public NotificationController(NotificationService notifications) { this.notifications = notifications; }
    @GetMapping public Map<String, Object> history(@PathVariable String projectId, @PathVariable String webhookId, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit) { return notifications.history(projectId, webhookId, offset, limit); }
    @PostMapping("/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public NotificationService.Submission retry(@PathVariable String projectId, @PathVariable String webhookId, @PathVariable String id, @RequestBody Map<String, Object> input) { return notifications.retry(projectId, webhookId, id, input); }
}
