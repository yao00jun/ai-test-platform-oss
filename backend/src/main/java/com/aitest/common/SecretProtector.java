package com.aitest.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public final class SecretProtector {
    public static final String MASK = "••••••••";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretProtector(@Value("${aitest.storage-root}") String storageRoot) throws IOException {
        String configured = System.getenv("AI_TEST_MASTER_KEY");
        byte[] material;
        if (configured != null && !configured.isBlank()) material = Base64.getDecoder().decode(configured);
        else {
            Path root = Path.of(storageRoot).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Path file = root.resolve(".master-key");
            if (!Files.exists(file)) {
                byte[] generated = new byte[32]; random.nextBytes(generated);
                try { Files.write(file, generated, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
                catch (FileAlreadyExistsException ignored) { /* Another startup created the same key first. */ }
            }
            material = Files.readAllBytes(file);
        }
        if (material.length != 32) throw new IllegalStateException("AI_TEST_MASTER_KEY must contain 32 bytes in Base64");
        key = new SecretKeySpec(material, "AES");
        Arrays.fill(material, (byte) 0);
    }
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] content = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + content.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(content, 0, combined, iv.length, content.length);
            return "enc:v1:" + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Cannot encrypt secret", e); }
    }
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) return "";
        if (!ciphertext.startsWith("enc:v1:")) throw new IllegalStateException("Stored secret is not encrypted");
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext.substring(7));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, combined, 0, 12));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IllegalStateException("Cannot decrypt secret with current master key", e); }
    }
}
