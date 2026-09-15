package com.aitest.security;

import com.aitest.common.JsonCodec;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** One configured workspace account. Project/reference boundaries remain in the domain services. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(PlatformSecurityProperties.class)
public class PlatformSecurityConfig {
    private final JsonCodec json;

    public PlatformSecurityConfig(JsonCodec json) { this.json = json; }

    @Bean UserDetailsService workspaceAccount(PlatformSecurityProperties properties, @Value("${server.address:}") String bindAddress) {
        properties.validate(bindAddress);
        var users = new InMemoryUserDetailsManager();
        if (properties.enabled()) users.createUser(User.withUsername(properties.username())
                .password("{bcrypt}" + new BCryptPasswordEncoder(12).encode(properties.password()))
                .roles("WORKSPACE_OWNER").build());
        return users;
    }

    @Bean SecurityFilterChain platformFilterChain(HttpSecurity http, PlatformSecurityProperties properties) throws Exception {
        http.httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        if (!properties.enabled()) {
            http.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                    .logout(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(access -> access.anyRequest().permitAll());
            return http.build();
        }
        http.sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        .ignoringRequestMatchers("/internal/lifecycle/stop"))
                .authorizeHttpRequests(access -> access
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/projects", "/cases", "/api-tests",
                                "/scenarios", "/ui-tests", "/plans", "/bugs", "/assets/**", "/favicon.ico", "/favicon.svg",
                                "/api/auth/session", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/internal/lifecycle/stop").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> unauthorized(response))
                        .accessDeniedHandler((request, response, failure) -> {
                            if (failure instanceof CsrfException) csrfFailure(request, response);
                            else error(response, 403, "ACCESS_DENIED", "当前会话无权执行此操作");
                        }))
                .formLogin(login -> login.loginPage("/").loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> {
                            response.setContentType("application/json");
                            response.getWriter().write("{\"authenticated\":true}");
                        })
                        .failureHandler((request, response, failure) -> error(response, 401,
                                "INVALID_CREDENTIALS", "用户名或密码不正确")))
                .logout(logout -> logout.logoutUrl("/api/auth/logout").deleteCookies("AI_TEST_SESSION")
                        .invalidateHttpSession(true).clearAuthentication(true)
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        return http.build();
    }

    static boolean authenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private void csrfFailure(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"/api/auth/login".equals(request.getServletPath())
                && !authenticated(SecurityContextHolder.getContext().getAuthentication())) unauthorized(response);
        else error(response, 403, "CSRF_INVALID", "会话校验已失效，请重新登录后重试；本次操作未提交");
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        error(response, 401, "AUTHENTICATION_REQUIRED", "登录会话已失效，请重新登录；本次操作未提交");
    }

    private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(json.write(Map.of("code", code, "message", message, "requestId", UUID.randomUUID().toString())));
    }
}
