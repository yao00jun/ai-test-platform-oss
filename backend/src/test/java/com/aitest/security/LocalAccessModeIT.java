package com.aitest.security;

import com.aitest.exchange.ExchangeHttpTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"aitest.security.enabled=false", "aitest.schedules.enabled=false",
        "aitest.morning-brief.enabled=false", "aitest.notifications.enabled=false"})
class LocalAccessModeIT extends ExchangeHttpTest {
    @Test void existingLoopbackInstancesCanBootstrapWithoutConfiguredCredentials() throws Exception {
        var response = request("GET", "/api/auth/session", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(object(response)).containsEntry("enabled", false).containsEntry("authenticated", true)
                .containsEntry("username", null).containsEntry("csrfToken", null);
        assertThat(request("GET", "/api/projects", null).statusCode()).isEqualTo(200);
    }
}
