package com.aitest.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real isolated Git repositories. No network, installed hooks or user commit identity are needed. */
public final class GitFixture {
    private GitFixture() { }
    public static String run(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=", "-c", "core.fsmonitor=false", "-c", "commit.gpgsign=false", "-c", "user.name=Source fixture", "-c", "user.email=source-fixture@example.invalid"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readNBytes(1_000_000);
        if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) { process.destroyForcibly(); throw new IllegalStateException("Git fixture failed: " + new String(output, StandardCharsets.UTF_8)); }
        return new String(output, StandardCharsets.UTF_8).strip();
    }
    public static String commit(Path directory, String message) throws Exception { run(directory, "add", "."); run(directory, "commit", "-m", message); return run(directory, "rev-parse", "HEAD"); }
}
