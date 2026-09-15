package com.aitest.engine.sql;

import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.sql.DriverManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SchemaQueryValidationIT extends ExchangeHttpTest {
    @Autowired Environment configuration;
    @Test void checksActualTableColumnsWithoutExecutingWrites() throws Exception {
        String project = project().id();
        String url = configuration.getProperty("spring.datasource.url").replace("/ai_test_platform_test", "/ai_test_business_test");
        String user = configuration.getProperty("spring.datasource.username"), password = configuration.getProperty("spring.datasource.password");
        String table = "schema_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + "(id INT PRIMARY KEY,state VARCHAR(24))");
            try {
                Asset source = assets.create(project, AssetType.DATABASE_SOURCE, null, "Schema source", Map.of("jdbcUrl", url, "username", user, "password", password), "MANUAL");
                String endpoint = "/api/projects/" + project + "/databases/" + source.id() + "/validate-query";
                var valid = request("POST", endpoint, Map.of("sql", "SELECT state FROM " + table + " WHERE id=:id"));
                assertThat(valid.statusCode()).isEqualTo(200); assertThat(object(valid)).containsEntry("valid", true);
                assertThat(request("POST", endpoint, Map.of("sql", "SELECT invented_column FROM " + table)).statusCode()).isEqualTo(422);
                assertThat(request("POST", endpoint, Map.of("sql", "DELETE FROM " + table + " WHERE id=1")).statusCode()).isEqualTo(422);
                assertThat(request("POST", "/api/projects/" + project().id() + "/databases/" + source.id() + "/validate-query", Map.of("sql", "SELECT 1")).statusCode()).isEqualTo(404);
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { result.next(); assertThat(result.getInt(1)).isZero(); }
            } finally { statement.execute("DROP TABLE " + table); }
        }
    }
}
