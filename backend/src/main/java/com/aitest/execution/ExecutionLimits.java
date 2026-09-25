package com.aitest.execution;

/** Single source of truth for numeric limits shared by asset validation and runtime executors. */
public final class ExecutionLimits {
    public static final int HTTP_TIMEOUT_MIN_MS = 50, HTTP_TIMEOUT_MAX_MS = 300_000;
    public static final int UI_SCENARIO_TIMEOUT_MIN_MS = 1_000, UI_SCENARIO_TIMEOUT_MAX_MS = 3_600_000;
    public static final int UI_STEP_TIMEOUT_MIN_MS = 100, UI_STEP_TIMEOUT_MAX_MS = 120_000;
    public static final int WAIT_MIN_MS = 0, WAIT_MAX_MS = 60_000;
    public static final int SQL_TIMEOUT_MIN_SECONDS = 1, SQL_TIMEOUT_MAX_SECONDS = 300;
    public static final int SQL_ROWS_MIN = 1, SQL_ROWS_MAX = 10_000;
    public static final int AUTH_TTL_MIN_SECONDS = 1, AUTH_TTL_MAX_SECONDS = 86_400;
    private ExecutionLimits() { }
}
