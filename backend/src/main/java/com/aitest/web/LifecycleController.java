package com.aitest.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** The launcher supplies a per-instance token; this endpoint is disabled without one. */
@RestController
public final class LifecycleController {
    private final ConfigurableApplicationContext context;
    private final byte[] token;
    private final AtomicBoolean stopping = new AtomicBoolean();

    public LifecycleController(ConfigurableApplicationContext context,
                               @Value("${aitest.lifecycle.shutdown-token:${AI_TEST_SHUTDOWN_TOKEN:}}") String token) {
        this.context = context;
        this.token = token.getBytes(StandardCharsets.UTF_8);
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
            Thread.ofPlatform().name("instance-shutdown").start(context::close);
    }
}
