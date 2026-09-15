package com.aitest.engine.web;

import com.aitest.asset.Asset;
import com.aitest.execution.StepResult;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Ephemeral IPC key arrives over stdin, never in process arguments or a key file. */
public final class WorkerProtocol {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    public WorkerProtocol(String base64) { key = Base64.getDecoder().decode(base64); if (key.length != 32) throw new IllegalArgumentException("Invalid IPC key"); }
    public static String newKey() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getEncoder().encodeToString(bytes); }
    public String encode(String text) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            byte[] combined = Arrays.copyOf(iv, iv.length + encrypted.length); System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException error) { throw new IllegalStateException("Worker IPC encryption failed", error); }
    }
    public String decode(String text) {
        try {
            byte[] bytes = Base64.getDecoder().decode(text.strip()); if (bytes.length < 29) throw new GeneralSecurityException("Truncated IPC event");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, bytes, 0, 12));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) { throw new IllegalStateException("Worker IPC verification failed", error); }
    }
    public record Request(Asset scenario, List<Asset> steps, Map<String, Object> variables, Map<String, String> headers, String baseUrl, String key) { }
    public record Event(String assetId, StepResult result, Map<String, Object> exports) { }
}
