package com.aitest.security;

import com.aitest.common.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSecurityConfigurationTest {
    private final WebApplicationContextRunner application = new WebApplicationContextRunner()
            .withUserConfiguration(PlatformSecurityConfig.class).withBean(JsonCodec.class);

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "192.0.2.10", ""})
    void anonymousModeCannotBindToAnExternalOrImplicitWildcardAddress(String address) {
        application.withPropertyValues("aitest.security.enabled=false", "server.address=" + address)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1"})
    void legacyLoopbackModeDoesNotRequireAnyCredentials(String address) {
        application.withPropertyValues("aitest.security.enabled=false", "server.address=" + address)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @MethodSource("invalidAccounts")
    void enabledAuthenticationRejectsUnusableOrWeakConfigurationWithoutLoggingThePassword(String username, String password) {
        application.withPropertyValues("aitest.security.enabled=true", "server.address=0.0.0.0",
                        "aitest.security.username=" + username, "aitest.security.password=" + password)
                .run(context -> {
                    assertThat(context).hasFailed();
                    var trace = new java.io.StringWriter();
                    context.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
                    if (!password.isBlank()) assertThat(trace.toString()).doesNotContain(password);
                });
    }

    static Stream<Arguments> invalidAccounts() {
        return Stream.of(Arguments.of("owner", "short"), Arguments.of("owner", "密".repeat(25)),
                Arguments.of(" ", "a-real-test-password!"), Arguments.of("owner", ""));
    }

    @Test void configuredOwnerCanEnableRemoteAccess() {
        application.withPropertyValues("aitest.security.enabled=true", "server.address=0.0.0.0",
                        "aitest.security.username=owner", "aitest.security.password=a-real-test-password!")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
