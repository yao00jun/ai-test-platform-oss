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
