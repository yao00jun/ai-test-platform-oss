package com.aitest.ai;

import org.springframework.ai.chat.model.ChatResponse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

/** Metadata only. Never retains prompts, model credentials or generated content. */
public final class ModelCallTelemetry {
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile Long promptTokens, completionTokens, totalTokens;
    private volatile String responseModel;
    private volatile boolean reported;

    void attempted() { attempts.incrementAndGet(); }
    void observe(ChatResponse response) {
        if (response.getMetadata() == null) return;
        String model = response.getMetadata().getModel();
        if (model != null && !model.isBlank()) responseModel = model.substring(0, Math.min(200, model.length()));
    }
    void wireUsage(Map<?, ?> usage) {
        // The SDK stream accumulator synthesizes a zero-valued CompletionUsage even when none
        // arrived. Observe the wire fields before normalization, preserving missing and real zero.
        Long prompt = count(usage.get("prompt_tokens")), completion = count(usage.get("completion_tokens")), total = count(usage.get("total_tokens"));
        if (prompt == null && completion == null && total == null) return;
        promptTokens = prompt; completionTokens = completion; totalTokens = total; reported = true;
    }
    private static Long count(Object value) {
        if (!(value instanceof Number number)) return null;
        try { long count = new java.math.BigDecimal(number.toString()).longValueExact(); return count < 0 ? null : count; }
        catch (ArithmeticException | NumberFormatException invalid) { return null; }
    }
    public int httpAttempts() { return attempts.get(); }
    public boolean usageReported() { return reported; }
    public Long promptTokens() { return promptTokens; }
    public Long completionTokens() { return completionTokens; }
    public Long totalTokens() { return totalTokens; }
    public String responseModel() { return responseModel; }
}
