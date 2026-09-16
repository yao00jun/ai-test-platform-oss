package com.aitest.job;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class JobSignalsTest {
    @Test void aSignalBetweenReadAndWaitIsNotLostAndAllCurrentSubscribersWake() throws Exception {
        var signals = new JobSignals();
        try (var first = signals.subscribe("a"); var second = signals.subscribe("a"); var other = signals.subscribe("b")) {
            long before = first.version();
            signals.afterCommit("a");
            try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
                reader.submit(() -> { first.awaitChange(before, Duration.ofSeconds(10)); return true; }).get(1, TimeUnit.SECONDS);
                reader.submit(() -> { second.awaitChange(before, Duration.ofSeconds(10)); return true; }).get(1, TimeUnit.SECONDS);
            }
            assertThat(first.version()).isGreaterThan(before);
            assertThat(second.version()).isEqualTo(first.version());
            assertThat(other.version()).isZero();
        }
    }

    @Test void closingOneSubscriberKeepsTheOthersAliveAndFinalCloseReleasesTheJob() {
        var signals = new JobSignals();
        var first = signals.subscribe("a"); var second = signals.subscribe("a");
        first.close(); first.close(); signals.afterCommit("a");
        assertThat(second.version()).isPositive();
        second.close(); signals.afterCommit("a");
        try (var next = signals.subscribe("a")) {
            assertThat(next.version()).as("A released subscription cannot retain the old job notification state").isZero();
            signals.afterCommit("a"); assertThat(next.version()).isPositive();
        }
    }
}
