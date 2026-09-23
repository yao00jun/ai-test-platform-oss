package com.aitest.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobFailureSummaryTest {
    @Test void theInnermostCauseIsNamedAndShortened() {
        var failure = new RuntimeException("wrapper", new IllegalStateException("outer", new java.sql.SQLException("Data too long for column 'parent_id' at row 1")));
        assertThat(JobService.rootCauseSummary(failure)).isEqualTo("：SQLException: Data too long for column 'parent_id' at row 1");
        assertThat(JobService.rootCauseSummary(new RuntimeException("x".repeat(300)))).hasSize(1 + 240 + 1).endsWith("…");
        assertThat(JobService.rootCauseSummary(new RuntimeException())).isEmpty();
    }
}
