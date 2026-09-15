package com.aitest.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.util.function.BooleanSupplier;

/** Long-lived responses must recheck the original session after their initial filter-chain authorization. */
@Component
public class PlatformSessionAccess {
    private final PlatformSecurityProperties properties;

    public PlatformSessionAccess(PlatformSecurityProperties properties) { this.properties = properties; }

    public BooleanSupplier capture(HttpServletRequest request) {
        if (!properties.enabled()) return () -> true;
        HttpSession session = request.getSession(false);
        return () -> active(session);
    }

    static boolean active(HttpSession session) {
        if (session == null) return false;
        try {
            int idleSeconds = session.getMaxInactiveInterval();
            if (idleSeconds > 0 && System.currentTimeMillis() - session.getLastAccessedTime() >= idleSeconds * 1000L) return false;
            Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            return value instanceof SecurityContext context && PlatformSecurityConfig.authenticated(context.getAuthentication());
        } catch (IllegalStateException invalidated) { return false; }
    }
}
