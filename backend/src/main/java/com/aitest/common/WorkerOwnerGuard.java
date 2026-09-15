package com.aitest.common;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** Internal disposable workers must not outlive their owning application JVM. */
public final class WorkerOwnerGuard implements AutoCloseable {
    private static final String PID = "AI_TEST_WORKER_OWNER_PID", STARTED = "AI_TEST_WORKER_OWNER_STARTED";
    private final Thread watcher;

    public static void attach(ProcessBuilder builder) {
        ProcessHandle owner = ProcessHandle.current();
        builder.environment().put(PID, Long.toString(owner.pid()));
        builder.environment().put(STARTED, owner.info().startInstant().orElseThrow().toString());
    }

    public static WorkerOwnerGuard watch() {
        long pid = Long.parseLong(System.getenv(PID));
        Instant started = Instant.parse(System.getenv(STARTED));
        ProcessHandle owner = ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("Worker owner already exited"));
        if (!alive(owner, started)) throw new IllegalStateException("Worker owner identity changed");
        return new WorkerOwnerGuard(owner, started);
    }

    private WorkerOwnerGuard(ProcessHandle owner, Instant started) {
        watcher = Thread.ofPlatform().name("worker-owner-watch").daemon().start(() -> {
            try {
                while (alive(owner, started)) Thread.sleep(250);
                // Capture the full tree before killing intermediates; Windows may otherwise
                // detach their still-live children from ProcessHandle.descendants().
                Set<ProcessHandle> children = new LinkedHashSet<>();
                for (int pass = 0; pass < 3; pass++) {
                    children.addAll(ProcessHandle.current().descendants().toList());
                    children.stream().toList().reversed().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
                    Thread.sleep(100);
                }
                // Playwright may be stuck in native IO. Do not depend on its main thread or
                // shutdown hooks to release this now-ownerless disposable JVM.
                Runtime.getRuntime().halt(130);
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        });
    }
    private static boolean alive(ProcessHandle owner, Instant started) {
        return owner.isAlive() && owner.info().startInstant().filter(started::equals).isPresent();
    }
    @Override public void close() { watcher.interrupt(); }
}
