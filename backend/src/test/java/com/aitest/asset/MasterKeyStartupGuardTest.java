package com.aitest.asset;

import com.aitest.common.SecretProtector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MasterKeyStartupGuardTest {
    @TempDir Path storageRoot;

    @Test
    void refusesExistingCiphertextAndDeletesAKeyGeneratedForThisBoot() throws Exception {
        SecretProtector secrets = new SecretProtector(storageRoot.toString());
        Path keyFile = storageRoot.resolve(".master-key");
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate("FROM `project_webhook_notice` WHERE CAST(`secret`");
        MasterKeyStartupGuard guard = new MasterKeyStartupGuard(jdbc, secrets);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments(new String[0])));

        assertTrue(failure.getMessage().contains("找不到加密密钥文件"));
        assertTrue(failure.getMessage().contains(keyFile.toString()));
        assertTrue(failure.getMessage().contains("AI_TEST_MASTER_KEY"));
        assertFalse(Files.exists(keyFile));
        assertTrue(jdbc.queries.stream().anyMatch(sql -> sql.contains("project_environment") && sql.contains("headers")));
        assertTrue(jdbc.queries.stream().anyMatch(sql -> sql.contains("project_global_auth") && sql.contains("login_payload")));
        assertTrue(jdbc.queries.stream().anyMatch(sql -> sql.contains("project_webhook_notice") && sql.contains("secret")));
    }

    @Test
    void preservesNewKeyWhenDatabaseContainsNoCiphertext() throws Exception {
        SecretProtector secrets = new SecretProtector(storageRoot.toString());
        Path keyFile = storageRoot.resolve(".master-key");
        MasterKeyStartupGuard guard = new MasterKeyStartupGuard(new FakeJdbcTemplate(null), secrets);

        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments(new String[0])));

        assertTrue(Files.exists(keyFile));
    }

    @Test
    void doesNotInspectOrDeleteAnExistingKey() throws Exception {
        SecretProtector firstBoot = new SecretProtector(storageRoot.toString());
        new MasterKeyStartupGuard(new FakeJdbcTemplate(null), firstBoot)
                .run(new DefaultApplicationArguments(new String[0]));
        Path keyFile = storageRoot.resolve(".master-key");
        byte[] original = Files.readAllBytes(keyFile);
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate("FROM `ai_model_config` WHERE CAST(`api_key`");

        assertDoesNotThrow(() -> new MasterKeyStartupGuard(jdbc,
                new SecretProtector(storageRoot.toString())).run(new DefaultApplicationArguments(new String[0])));

        assertTrue(jdbc.queries.isEmpty());
        assertArrayEquals(original, Files.readAllBytes(keyFile));
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private final String matchingQuery;
        private final List<String> queries = new ArrayList<>();

        private FakeJdbcTemplate(String matchingQuery) {
            this.matchingQuery = matchingQuery;
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> extractor) {
            queries.add(sql);
            return extractor == null ? null : (T) Boolean.valueOf(matchingQuery != null && sql.contains(matchingQuery));
        }
    }
}
