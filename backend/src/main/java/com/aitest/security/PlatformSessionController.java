package com.aitest.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformSessionController {
    private final PlatformSecurityProperties properties;

    public PlatformSessionController(PlatformSecurityProperties properties) { this.properties = properties; }

    @GetMapping("/api/auth/session")
    SessionState session(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        response.setHeader("Cache-Control", "no-store");
        boolean authenticated = !properties.enabled() || PlatformSecurityConfig.authenticated(authentication);
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new SessionState(properties.enabled(), authenticated,
                properties.enabled() && authenticated ? authentication.getName() : null,
                csrf == null ? null : csrf.getToken(), "X-CSRF-TOKEN");
    }

    record SessionState(boolean enabled, boolean authenticated, String username, String csrfToken, String csrfHeader) { }
}
