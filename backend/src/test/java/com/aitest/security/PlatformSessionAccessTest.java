package com.aitest.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSessionAccessTest {
    @Test void anOpenStreamCannotKeepAnIdleSessionAuthorizedPastItsDeadline() throws Exception {
        var session = new MockHttpSession();
        session.setMaxInactiveInterval(1);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("owner", null, List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        var request = new MockHttpServletRequest();
        request.setSession(session);
        var authorized = new PlatformSessionAccess(new PlatformSecurityProperties(true, "owner", "fixture-password"))
                .capture(request);
        assertThat(authorized.getAsBoolean()).isTrue();
        Thread.sleep(1100);
        assertThat(authorized.getAsBoolean()).isFalse();
    }
}
