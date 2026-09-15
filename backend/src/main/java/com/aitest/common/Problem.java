package com.aitest.common;

public final class Problem extends RuntimeException {
    private final int status;
    private final String code;
    private final Object details;

    public Problem(int status, String code, String message) { this(status, code, message, null); }
    public Problem(int status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }
    public int status() { return status; }
    public String code() { return code; }
    public Object details() { return details; }
    public static Problem invalid(String message) { return new Problem(422, "VALIDATION_FAILED", message); }
    public static Problem missing() { return new Problem(404, "NOT_FOUND", "记录不存在或不属于当前项目"); }
    public static Problem conflict(String message) { return new Problem(409, "VERSION_CONFLICT", message); }
}
