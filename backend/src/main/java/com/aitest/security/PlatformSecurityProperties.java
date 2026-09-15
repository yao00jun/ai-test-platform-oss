package com.aitest.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

@ConfigurationProperties("aitest.security")
public record PlatformSecurityProperties(boolean enabled, String username, String password) {
    void validate(String bindAddress) {
        if (!enabled) {
            if (!loopback(bindAddress)) throw new IllegalStateException("Enable platform authentication before binding outside loopback");
            return;
        }
        if (username == null || !username.matches("[\\p{L}\\p{N}_.@-]{1,64}"))
            throw new IllegalStateException("Configure a platform username of 1 to 64 letters, digits or _.@- characters");
        if (password == null || password.isBlank() || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("Configure a platform password of at least 12 characters and at most 72 UTF-8 bytes");
    }

    private static boolean loopback(String address) {
        if (address == null || !(address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
                || address.contains(":") && address.matches("[0-9a-fA-F:.]+"))) return false;
        try { return InetAddress.getByName(address).isLoopbackAddress(); }
        catch (UnknownHostException invalid) { return false; }
    }

    @Override public String toString() { return "PlatformSecurityProperties[enabled=" + enabled + ", credentials=REDACTED]"; }
}
