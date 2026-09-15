package com.aitest.ai;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PromptRenderContractTest {
    @Test
    void onlyExplicitPromptSlotsAreRenderedAndRuntimeTokensRemainUntouched() {
        assertThat(PromptCatalog.renderText("需求 {userRequirement}\n{\"Authorization\":\"Bearer ${token}\"}", Map.of("userRequirement", "退款")))
                .isEqualTo("需求 退款\n{\"Authorization\":\"Bearer ${token}\"}");
    }
}
