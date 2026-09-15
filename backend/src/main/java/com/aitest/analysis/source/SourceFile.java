package com.aitest.analysis.source;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public record SourceFile(String kind, String path, String sha256, long bytes, String content) {
    public static SourceFile text(String kind, String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new SourceFile(kind, path, hash(bytes), bytes.length, content);
    }
    public static String hash(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String hash(String content) { return hash(content.getBytes(StandardCharsets.UTF_8)); }
}
