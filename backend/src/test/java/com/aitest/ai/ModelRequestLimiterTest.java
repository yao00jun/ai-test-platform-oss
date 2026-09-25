package com.aitest.ai;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import static org.assertj.core.api.Assertions.*;

class ModelRequestLimiterTest {
    @Test void requestsBeyondTheCapWaitForTheWindowAndAnnounceTheWaitOnce() {
        var limiter = new ModelRequestLimiter(Duration.ofMillis(600));
        List<Long> announced = new ArrayList<>();
        assertThat(limiter.acquire(2, () -> { }, announced::add)).isZero();
        assertThat(limiter.acquire(2, () -> { }, announced::add)).isZero();
        long waited = limiter.acquire(2, () -> { }, announced::add);
        assertThat(Duration.ofNanos(waited)).isGreaterThan(Duration.ofMillis(300));
        assertThat(announced).hasSize(1);
    }

    @Test void noCapMeansNoWaiting() {
        var limiter = new ModelRequestLimiter(Duration.ofMinutes(1));
        for (int i = 0; i < 50; i++) assertThat(limiter.acquire(0, () -> { throw new AssertionError("no checkpoint needed"); }, pause -> fail("no wait expected"))).isZero();
    }

    @Test void aCancelledJobLeavesTheQueueWithoutWaitingForTheWindow() {
        var limiter = new ModelRequestLimiter(Duration.ofMinutes(1));
        limiter.acquire(1, () -> { }, pause -> { });
        long started = System.nanoTime();
        assertThatThrownBy(() -> limiter.acquire(1, () -> { throw new CancellationException("任务已停止"); }, pause -> { })).isInstanceOf(CancellationException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }
}
