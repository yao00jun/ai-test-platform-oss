package com.aitest.bug;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FailureFingerprintTest {
    @Test void runSpecificValuesDoNotSplitOneRecurringFailure() {
        String first = FailureEvidenceReader.normalize("订单 20260923000123 在 2026-09-23T20:58:01.123+08:00 创建失败，耗时 350 ms，trace 1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed");
        String second = FailureEvidenceReader.normalize("订单 20260924000456 在 2026-09-24 08:00:05 创建失败，耗时 1200 ms，trace 2c9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bee");
        assertThat(first).isEqualTo(second).contains("订单 {n}").contains("{time}").contains("{duration}").contains("{id}");
        assertThat(FailureEvidenceReader.normalize("HTTP 500 on /orders/12")).as("short numbers such as status codes carry meaning").isEqualTo("HTTP 500 on /orders/12");
    }
}
