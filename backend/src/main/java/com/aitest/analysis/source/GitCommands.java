package com.aitest.analysis.source;

import com.aitest.common.Problem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Git is invoked directly with individual arguments. Repository scripts, filters and hooks are never run. */
public final class GitCommands {
    private GitCommands() { }
    public static byte[] run(Path directory, List<String> arguments, byte[] input, int outputLimit, Duration timeout, Runnable checkpoint) {
        return run(directory, arguments, input, outputLimit, timeout, checkpoint, Set.of(0));
    }
    /** Git diff uses exit 1 for a successful comparison containing changes. */
    public static byte[] run(Path directory, List<String> arguments, byte[] input, int outputLimit, Duration timeout, Runnable checkpoint, Set<Integer> successCodes) {
        checkpoint.run();
        List<String> command = new ArrayList<>(List.of("git", "--no-pager", "--no-optional-locks",
                "-c", "core.hooksPath=", "-c", "core.fsmonitor=false", "-c", "credential.helper=",
                "-c", "init.templateDir=", "-c", "protocol.allow=never", "-c", "protocol.http.allow=always", "-c", "protocol.https.allow=always",
                "-c", "http.followRedirects=false", "-c", "http.sslVerify=true", "-c", "gc.auto=0", "-c", "maintenance.auto=false"));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        // Keep the user's global/system CA bundle, proxy and credential policy. Only
        // disable interactive prompts and repository-side hooks for this read-only job.
        builder.environment().putAll(Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_ASKPASS", "", "SSH_ASKPASS", "", "GIT_LFS_SKIP_SMUDGE", "1", "GIT_OPTIONAL_LOCKS", "0"));
        Process process = null;
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            process = builder.start();
            Process child = process;
            Future<byte[]> output = io.submit(() -> read(child.getInputStream(), outputLimit));
            Future<byte[]> errors = io.submit(() -> read(child.getErrorStream(), 128 * 1024));
            Future<?> write = io.submit(() -> { try (OutputStream stream = child.getOutputStream()) { stream.write(input); } return null; });
            try {
                long deadline = System.nanoTime() + timeout.toNanos();
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    checkpoint.run();
                    if (System.nanoTime() > deadline) throw new Problem(422, "GIT_TIMEOUT", "Git 读取超时，请缩小仓库或在本地检出后分析");
                    if (output.isDone()) output.get();
                    if (errors.isDone()) errors.get();
                }
                checkpoint.run();
                write.get(5, TimeUnit.SECONDS); String stderr = new String(errors.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
                if (!successCodes.contains(process.exitValue())) throw new Problem(422, "GIT_READ_FAILED", "Git " + arguments.getFirst() + " 读取失败，请检查仓库、版本及读取权限；私有仓库可先在本地检出" + tail(stderr));
                return output.get(5, TimeUnit.SECONDS);
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close();
            }
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Problem problem) throw problem;
            throw new Problem(422, "GIT_READ_FAILED", "无法完整读取 Git 对象");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new CancellationException("Git 读取已取消");
        } catch (IOException | TimeoutException failure) {
            throw new Problem(422, "GIT_READ_FAILED", "无法运行 Git 或读取其输出，请检查 Git 安装与仓库访问");
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
    public static String text(Path directory, List<String> arguments, Duration timeout, Runnable checkpoint) {
        return new String(run(directory, arguments, new byte[0], 16 * 1024 * 1024, timeout, checkpoint), StandardCharsets.UTF_8).strip();
    }
    private static byte[] read(InputStream stream, int maximum) throws IOException {
        try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024]; int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (output.size() + count > maximum) throw new Problem(422, "GIT_OUTPUT_LIMIT", "Git 输出超过分析上限，请缩小仓库范围");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    private static String tail(String stderr) {
        String safe = stderr == null ? "" : stderr.replaceAll("(?i)(https?://)([^/@\\s]+):([^/@\\s]+)@", "$1***:***@").replaceAll("(?i)(token|password|passwd|secret|authorization)(\\s*[:=]\\s*)[^\\s]+", "$1$2***").replaceAll("\\s+", " ").strip();
        if (safe.isBlank()) return "";
        return "；Git 输出：" + (safe.length() > 1200 ? safe.substring(Math.max(0, safe.length() - 1200)) : safe);
    }
}
