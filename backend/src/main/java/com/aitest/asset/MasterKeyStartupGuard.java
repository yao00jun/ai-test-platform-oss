package com.aitest.asset;

import com.aitest.common.SecretProtector;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Refuses to boot with a newly generated key when persisted ciphertext needs the old key. */
@Component
public final class MasterKeyStartupGuard implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final SecretProtector secrets;

    public MasterKeyStartupGuard(JdbcTemplate jdbc, SecretProtector secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!secrets.generatedThisBoot() || !hasEncryptedData()) return;
        deleteGeneratedKey();
        throw new IllegalStateException("找不到加密密钥文件 " + secrets.keyFile()
                + "，但数据库已有用旧密钥加密的数据；请从备份把 .master-key 放回 "
                + secrets.keyFile() + "，或设置 AI_TEST_MASTER_KEY");
    }

    private boolean hasEncryptedData() {
        List<String> encryptedColumns = new ArrayList<>();
        encryptedColumns.add("ai_model_config.api_key");
        encryptedColumns.add("source_snapshot.result_cipher");
        for (AssetType type : AssetType.values()) {
            for (FieldDefinition field : type.fields()) {
                if (AssetSecrets.encrypted(type, field)) encryptedColumns.add(type.table() + "." + field.column());
            }
        }
        for (String qualifiedColumn : encryptedColumns) {
            int separator = qualifiedColumn.indexOf('.');
            String table = qualifiedColumn.substring(0, separator);
            String column = qualifiedColumn.substring(separator + 1);
            String sql = "SELECT 1 FROM `" + table + "` WHERE CAST(`" + column + "` AS CHAR) LIKE 'enc:v1:%' LIMIT 1";
            Boolean present = jdbc.query(sql, (ResultSetExtractor<Boolean>) rs -> rs.next());
            if (Boolean.TRUE.equals(present)) return true;
        }
        return false;
    }

    private void deleteGeneratedKey() throws IOException {
        if (secrets.keyFile() != null) Files.deleteIfExists(secrets.keyFile());
    }
}
