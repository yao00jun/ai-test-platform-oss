package com.aitest.execution;
public record AssertionResult(String type, String path, String operator, Object expected, Object actual, boolean passed, String message) { }
