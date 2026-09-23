package com.aitest.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleControllerTest {
    @Test void missingOrWeakTokenNeverEnablesShutdown() throws Exception {
        for (String token : new String[]{"", "short-token"}) {
            try (var context = new GenericApplicationContext()) {
                context.refresh();
                var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
                request.addHeader("X-AITest-Shutdown-Token", token);
                var response = new MockHttpServletResponse();
                new LifecycleController(context, token).stop(request, response);
                assertThat(response.getStatus()).isEqualTo(404);
                assertThat(context.isActive()).isTrue();
            }
        }
    }

    @Test void aLocalStopClosesTheContextAndThenEndsTheProcess() throws Exception {
        var context = new GenericApplicationContext(); context.refresh();
        var exits = new java.util.concurrent.LinkedBlockingQueue<Integer>();
        String token = "b".repeat(64);
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1"); request.addHeader("X-AITest-Shutdown-Token", token);
        var response = new MockHttpServletResponse();
        new LifecycleController(context, token, 75, exits::add).stop(request, response);
        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(exits.poll(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(0);
        assertThat(context.isActive()).isFalse();
    }

    @Test void aCloseThatDiesHalfwayStillEndsTheProcessWithAFailureStatus() throws Exception {
        // A replaced JAR makes class loading fail inside doClose(); the process used to linger with Tomcat still serving.
        var context = new GenericApplicationContext() { @Override protected void doClose() { throw new NoClassDefFoundError("ch/qos/logback/classic/spi/ThrowableProxy"); } };
        context.refresh();
        var exits = new java.util.concurrent.LinkedBlockingQueue<Integer>();
        String token = "c".repeat(64);
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1"); request.addHeader("X-AITest-Shutdown-Token", token);
        new LifecycleController(context, token, 75, exits::add).stop(request, new MockHttpServletResponse());
        assertThat(exits.poll(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test void validTokenFromAnotherHostCannotUseAForwardedLoopbackAddress() throws Exception {
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            String token = "a".repeat(64);
            var request = new MockHttpServletRequest(); request.setRemoteAddr("192.0.2.10");
            request.addHeader("X-AITest-Shutdown-Token", token); request.addHeader("X-Forwarded-For", "127.0.0.1");
            var response = new MockHttpServletResponse();
            new LifecycleController(context, token).stop(request, response);
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(context.isActive()).isTrue();
        }
    }
}
