package com.aitest.analysis;

public record SourceDiagnostic(String severity, String code, String kind, String path, int line, String message) {
    public static SourceDiagnostic warning(String code, String kind, String path, String message) {
        return new SourceDiagnostic("WARNING", code, kind, path, 0, message);
    }
}
