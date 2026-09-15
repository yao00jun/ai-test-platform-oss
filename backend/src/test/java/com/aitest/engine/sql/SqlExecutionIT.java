package com.aitest.engine.sql;

import com.aitest.asset.*;
import com.aitest.execution.*;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.sql.DriverManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SqlExecutionIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired SqlStepExecutor sql;
    @Autowired DynamicDataSourceManager pools;
    @Autowired Environment configuration;
    @Test void realBusinessDatabaseRollsBackDryRunBindsInjectionTextAndLimitsWrites() throws Exception {
        String project = assets.createProject("SQL execution " + UUID.randomUUID(), Map.of()).id();
        String url = configuration.getProperty("spring.datasource.url").replace("/ai_test_platform_test", "/ai_test_business_test");
        String user = configuration.getProperty("spring.datasource.username"), password = configuration.getProperty("spring.datasource.password");
        String table = "fixture_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + "(id INT PRIMARY KEY,state VARCHAR(100))");
            statement.executeUpdate("INSERT INTO " + table + " VALUES(1,'NEW'),(2,'NEW')");
            Asset source = assets.create(project, AssetType.DATABASE_SOURCE, null, "Business", Map.of("jdbcUrl", url, "username", user, "password", password, "safeMode", false), "MANUAL");
            source = assets.getInternal(project, source.id());
            var context = new ExecutionContext(Map.of("id", 1), () -> { });
            var update = Map.<String,Object>of("sql", "UPDATE " + table + " SET state='PAID' WHERE id=:id", "allowWrite", true, "dryRun", true);
            assertThat(sql.execute(source, update, context, true).status()).isEqualTo("PASSED");
            var read = sql.execute(source, Map.of("sql", "SELECT state FROM " + table + " WHERE id=:id", "exports", Map.of("state", "state")), context, false);
            assertThat(read.exports()).containsEntry("state", "NEW");
            Map<String, Object> commit = new LinkedHashMap<>(update); commit.put("dryRun", false);
            assertThat(sql.execute(source, commit, context, true).actual()).containsEntry("committed", true);
            assertThat(sql.execute(source, Map.of("sql", "SELECT state FROM " + table + " WHERE id=:id", "exports", Map.of("state", "state")), context, false).exports()).containsEntry("state", "PAID");
            var injection = sql.execute(source, Map.of("sql", "SELECT id FROM " + table + " WHERE state='${state}'"), new ExecutionContext(Map.of("state", "PAID' OR 1=1 --"), () -> { }), false);
            assertThat(injection.actual()).containsEntry("rowCount", 0);
            var overLimit = sql.execute(source, Map.of("sql", "UPDATE " + table + " SET state='BROKEN' WHERE id>0", "allowWrite", true, "dryRun", false, "maxAffectedRows", 1), context, true);
            assertThat(overLimit.status()).isEqualTo("ERROR");
            assertThat(sql.execute(source, Map.of("sql", "SELECT state FROM " + table + " WHERE id=:id", "exports", Map.of("state", "state")), context, false).exports()).containsEntry("state", "PAID");
            assertThat(pools.statistics()).containsEntry("leasedConnections", 0);
            statement.execute("DROP TABLE " + table);
        }
    }
}
