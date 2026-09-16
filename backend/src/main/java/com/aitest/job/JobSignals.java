package com.aitest.job;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Best-effort wakeups only; subscribers always read authoritative events from MySQL. */
@Component
public final class JobSignals {
    private final ConcurrentHashMap<String, Signal> waiting = new ConcurrentHashMap<>();

    public Subscription subscribe(String jobId) {
        Signal signal = waiting.compute(jobId, (key, current) -> {
            Signal value = current == null ? new Signal() : current;
            value.subscribers++; return value;
        });
        return new Subscription(jobId, signal);
    }

    public void afterCommit(String jobId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish(jobId); }
            });
        } else publish(jobId);
    }

    private void publish(String jobId) {
        Signal signal = waiting.get(jobId);
        if (signal == null) return;
        synchronized (signal) { signal.version++; signal.notifyAll(); }
    }

    private static final class Signal {
        volatile long version;
        int subscribers; // Modified only under ConcurrentHashMap.compute for this job.
    }

    public final class Subscription implements AutoCloseable {
        private final String jobId;
        private final Signal signal;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Subscription(String jobId, Signal signal) { this.jobId = jobId; this.signal = signal; }
        public long version() { return signal.version; }

        public void awaitChange(long observedVersion, Duration timeout) throws InterruptedException {
            long remaining = timeout.toNanos(), deadline = System.nanoTime() + remaining;
            synchronized (signal) {
                while (!closed.get() && signal.version == observedVersion && remaining > 0) {
                    TimeUnit.NANOSECONDS.timedWait(signal, remaining);
                    remaining = deadline - System.nanoTime();
                }
            }
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            waiting.computeIfPresent(jobId, (key, current) -> {
                if (current != signal) return current;
                return --current.subscribers == 0 ? null : current;
            });
            synchronized (signal) { signal.notifyAll(); }
        }
    }
}
