package com.aitest.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class MySqlIntegrationTest {
    /** Failsafe fork number (1-based). Forks beyond the first use ai_test_platform_test_N and their own storage root. */
    public static final String FORK = System.getProperty("aitest.test.fork", "1");
    public static final String SCHEMA = "ai_test_platform_test" + ("1".equals(FORK) ? "" : "_" + FORK);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("AI_TEST_INTEGRATION_DB_URL");
        if (url != null) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getenv("AI_TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("AI_TEST_DB_PASSWORD"));
        } else {
            Path file = Path.of("../.runtime/mysql/connection.json");
            if (!Files.exists(file)) throw new IllegalStateException("Real MySQL 8.4 is required: run scripts/bootstrap-mysql.ps1 or set AI_TEST_INTEGRATION_DB_URL.");
            Map<?, ?> config = new ObjectMapper().readValue(file.toFile(), Map.class);
            registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:" + config.get("port") + "/" + SCHEMA + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
            registry.add("spring.datasource.username", () -> config.get("username"));
            registry.add("spring.datasource.password", () -> config.get("password"));
        }
        registry.add("aitest.storage-root", () -> "../.runtime/test-storage" + ("1".equals(FORK) ? "" : "-" + FORK));
    }
}
