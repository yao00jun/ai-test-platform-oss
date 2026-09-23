package com.aitest.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/** The launcher supplies a per-instance token; this endpoint is disabled without one. */
@RestController
public final class LifecycleController {
    private final ConfigurableApplicationContext context;
    private final byte[] token;
    private final int forceExitSeconds;
    private final IntConsumer exit;
    private final AtomicBoolean stopping = new AtomicBoolean();

    @Autowired
    public LifecycleController(ConfigurableApplicationContext context,
                               @Value("${aitest.lifecycle.shutdown-token:${AI_TEST_SHUTDOWN_TOKEN:}}") String token,
                               @Value("${aitest.lifecycle.force-exit-seconds:75}") int forceExitSeconds) {
        this(context, token, forceExitSeconds, System::exit);
    }

    LifecycleController(ConfigurableApplicationContext context, String token) { this(context, token, 75, System::exit); }

    LifecycleController(ConfigurableApplicationContext context, String token, int forceExitSeconds, IntConsumer exit) {
        this.context = context;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.forceExitSeconds = forceExitSeconds;
        this.exit = exit;
    }

    @PostMapping("/internal/lifecycle/stop")
    void stop(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String supplied = request.getHeader("X-AITest-Shutdown-Token");
        // Use the actual peer, never a caller-supplied forwarded address.
        boolean local = InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        if (token.length < 32 || !local || supplied == null
                || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.setContentLength(0);
        response.flushBuffer();
        if (stopping.compareAndSet(false, true))
            Thread.ofPlatform().name("instance-shutdown").start(this::shutdown);
    }

    /**
     * A stop request must end the process. Closing the context normally does that on its own, but when the JAR was
     * replaced under a running instance, classes needed to finish the close are gone and {@code close()} dies halfway
     * with Tomcat still serving; a blocked bean teardown would likewise leave the process behind. Both cases end here.
     */
    private void shutdown() {
        Thread watchdog = Thread.ofPlatform().name("instance-shutdown-watchdog").daemon(true).unstarted(() -> {
            try { Thread.sleep(java.time.Duration.ofSeconds(Math.max(5, forceExitSeconds))); }
            catch (InterruptedException interrupted) { return; }
            System.err.println("Graceful shutdown still running after " + forceExitSeconds + "s; halting the process.");
            Runtime.getRuntime().halt(2);
        });
        watchdog.start();
        int status = 0;
        try { context.close(); }
        catch (Throwable failure) {
            status = 1;
            System.err.println("Graceful shutdown did not complete (" + failure + "); exiting the process anyway.");
        } finally { watchdog.interrupt(); }
        exit.accept(status);
    }
}
