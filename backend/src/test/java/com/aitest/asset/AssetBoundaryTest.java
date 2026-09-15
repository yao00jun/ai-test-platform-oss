package com.aitest.asset;

import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.common.SecretProtector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AssetBoundaryTest {
    @TempDir Path directory;
    @Test void authenticationHeaderSeparatorsDoNotBypassPublicMasking() throws Exception {
        AssetSecrets secrets = new AssetSecrets(new SecretProtector(directory.toString()), new JsonCodec());
        assertThat(secrets.redactValue("headers", Map.of("X-API-Key", "sentinel-secret", "Content-Type", "application/json")))
                .isEqualTo(Map.of("X-API-Key", SecretProtector.MASK, "Content-Type", "application/json"));
    }
    @Test void jdbcCredentialsMustUseEncryptedFieldsAndCannotHideInUrl() {
        AssetValidator validator = new AssetValidator(new JsonCodec());
        for (String url : new String[]{"jdbc:mysql://localhost/db?password=sentinel", "jdbc:mysql://user:sentinel@localhost/db", "jdbc:postgresql://localhost/db?%70assword=sentinel", "jdbc:mysql://address=(host=localhost)(user=review)(password=sentinel)/db"}) {
            assertThatThrownBy(() -> validator.validate(AssetType.DATABASE_SOURCE, "DB", Map.of("jdbcUrl", url, "username", "user", "dbType", url.contains("postgresql") ? "POSTGRESQL" : "MYSQL"))).isInstanceOf(Problem.class);
        }
    }
}
