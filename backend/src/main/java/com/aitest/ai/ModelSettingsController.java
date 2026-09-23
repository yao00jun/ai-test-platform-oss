package com.aitest.ai;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/model")
public class ModelSettingsController {
    private final ModelSettingsService settings;
    private final CompanyModelGateway gateway;
    private final ModelPricingService pricing;
    public ModelSettingsController(ModelSettingsService settings, CompanyModelGateway gateway, ModelPricingService pricing) { this.settings = settings; this.gateway = gateway; this.pricing = pricing; }
    @GetMapping Map<String, Object> get() { return settings.view(); }
    @PutMapping Map<String, Object> put(@RequestBody ModelSettingsService.Input input) { return settings.save(input); }
    @PostMapping("/models") Map<String, Object> models(@RequestBody ModelSettingsService.ModelListRequest input) { return Map.of("models", settings.listModels(input, gateway)); }
    @GetMapping("/pricing") Object pricing(@RequestParam String modelName) { return pricing.get(modelName); }
    @PutMapping("/pricing") Object pricing(@RequestBody ModelPricingService.Input input) { return pricing.save(input); }
    @PostMapping("/test") Object test() {
        String response = gateway.complete(settings.current(), "Reply with OK only.", "Connection check", token -> { }, () -> { });
        return Map.of("ok", true, "message", "连接成功：" + response.substring(0, Math.min(80, response.length())));
    }
}
