package com.aitest.ai;

import com.aitest.common.Problem;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ModelSettingsIT extends MySqlIntegrationTest {
    @Autowired ModelSettingsService settings;

    @AfterEach void restoreDefaults() {
        // Other test classes in this fork share the row; never leave a request cap behind.
        settings.save(new ModelSettingsService.Input("http://127.0.0.1:9/v1", "fixture-key", "fixture", 0.1, 30, false, 0));
    }

    @Test void fieldsAClientDoesNotSendKeepTheirSavedValues() {
        settings.save(new ModelSettingsService.Input("http://127.0.0.1:9/v1", "fixture-key", "fixture", 0.2, 900, false, 5));
        // What the settings screen sent before it had these fields: address, name and key only.
        var view = settings.save(new ModelSettingsService.Input("http://127.0.0.1:9/v1", null, "fixture-2", null, null, null, null));
        assertThat(view).containsEntry("timeoutSeconds", 900).containsEntry("requestsPerMinute", 5).containsEntry("modelName", "fixture-2");
        assertThat(((Number) view.get("temperature")).doubleValue()).isEqualTo(0.2);
        assertThat(settings.current().requestsPerMinute()).isEqualTo(5);
    }

    @Test void rangeErrorsNameTheFieldThatIsWrong() {
        assertThatThrownBy(() -> settings.save(new ModelSettingsService.Input("http://127.0.0.1:9/v1", "fixture-key", "fixture", 0.2, 4, false, 0)))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.getMessage()).contains("单次调用超时").contains("5–3600"));
        assertThatThrownBy(() -> settings.save(new ModelSettingsService.Input("http://127.0.0.1:9/v1", "fixture-key", "fixture", 0.2, 600, false, -1)))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.getMessage()).contains("每分钟最多请求数"));
        assertThatThrownBy(() -> settings.save(new ModelSettingsService.Input("http://127.0.0.1:9/v1", "fixture-key", "fixture", 2.5, 600, false, 0)))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.getMessage()).contains("温度"));
    }
}
