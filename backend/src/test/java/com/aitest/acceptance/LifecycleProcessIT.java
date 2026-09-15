package com.aitest.acceptance;

import com.aitest.support.IsolatedApplication;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class LifecycleProcessIT {
    @Test void instanceTokenStopsOnlyItsOwnApplicationGracefullyAndSavedAssetsSurviveRestart() throws Exception {
        try (var app = new IsolatedApplication()) {
            app.start(); String project = app.project();
            var asset = app.asset(project, "FUNCTIONAL_CASE", null, "正常停止后保留", Map.of("remark", "人工内容"));
            assertThat(app.shutdown(false).statusCode()).isEqualTo(404);
            assertThat(app.call("GET", "/actuator/health", null, 200)).containsEntry("status", "UP");
            assertThat(app.shutdown(true).statusCode()).isEqualTo(202);
            assertThat(app.awaitExit(Duration.ofSeconds(60))).isZero();
            assertThat(app.descendants()).isEmpty();
            app.start(); assertThat(app.asset(project, asset.get("id").toString())).isEqualTo(asset);
            assertThat(app.shutdown(true).statusCode()).isEqualTo(202);
            assertThat(app.awaitExit(Duration.ofSeconds(60))).isZero();
            app.start(Map.of("aitest.lifecycle.shutdown-token", ""));
            assertThat(app.shutdown(true).statusCode()).isEqualTo(404);
            assertThat(app.call("GET", "/actuator/health", null, 200)).containsEntry("status", "UP");
        }
    }
}
