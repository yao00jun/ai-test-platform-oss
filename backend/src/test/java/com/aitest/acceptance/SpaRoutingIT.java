package com.aitest.acceptance;

import com.aitest.exchange.ExchangeHttpTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"spring.web.resources.static-locations=classpath:/spa-fixture/", "aitest.schedules.enabled=false", "aitest.morning-brief.enabled=false", "aitest.notifications.enabled=false"})
class SpaRoutingIT extends ExchangeHttpTest {
    @Test void everyPublishedFrontendRouteCanBeReloadedWithoutTurningUnknownApiOrStaticPathsIntoHtml() throws Exception {
        for (String path : List.of("/", "/projects", "/cases", "/api-tests", "/scenarios", "/ui-tests", "/plans", "/bugs")) {
            var response = request("GET", path, null);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("SPA routing fixture");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        }
        for (String path : List.of("/api/not-a-real-route", "/assets/missing.js")) {
            var response = request("GET", path, null);
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).doesNotContain("SPA routing fixture");
        }
    }
}
