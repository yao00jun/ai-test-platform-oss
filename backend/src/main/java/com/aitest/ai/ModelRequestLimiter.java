package com.aitest.ai;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Process-wide sliding-window cap on model HTTP requests, for gateways that enforce requests per minute. Every attempt
 * (first try, compatibility fallback, rate-limit or connection retry, format repair, model listing) takes a slot before it
 * is sent, so a busy pipeline queues here instead of collecting HTTP 429 from the provider.
 */
final class ModelRequestLimiter {
    private final long windowNanos;
    private final ArrayDeque<Long> started = new ArrayDeque<>();
    ModelRequestLimiter() { this(Duration.ofMinutes(1)); }
    ModelRequestLimiter(Duration window) { windowNanos = window.toNanos(); }

    /**
     * Blocks until a request may start and returns the nanoseconds spent waiting. {@code waiting} hears the expected
     * wait once, before the first pause; the checkpoint runs while waiting so a cancelled job leaves the queue at once.
     */
    long acquire(int perWindow, Runnable checkpoint, LongConsumer waiting) {
        if (perWindow <= 0) return 0;
        long began = System.nanoTime();
        boolean announced = false;
        while (true) {
            long pause;
            synchronized (started) {
                long now = System.nanoTime();
                while (!started.isEmpty() && now - started.peekFirst() >= windowNanos) started.pollFirst();
                if (started.size() < perWindow) { started.addLast(now); return announced ? now - began : 0; }
                pause = started.peekFirst() + windowNanos - now;
            }
            if (!announced) { announced = true; waiting.accept(pause); }
            checkpoint.run();
            try { TimeUnit.NANOSECONDS.sleep(Math.min(pause, TimeUnit.MILLISECONDS.toNanos(100))); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("模型请求已取消"); }
        }
    }
}
