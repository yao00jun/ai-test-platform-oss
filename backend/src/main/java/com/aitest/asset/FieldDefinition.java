package com.aitest.asset;

import java.util.List;

public record FieldDefinition(String key, String label, String kind, boolean required,
                              List<String> options, Object defaultValue) {
    public static FieldDefinition text(String key, String label) { return new FieldDefinition(key, label, "text", false, List.of(), ""); }
    public static FieldDefinition area(String key, String label) { return new FieldDefinition(key, label, "textarea", false, List.of(), ""); }
    public static FieldDefinition required(String key, String label) { return new FieldDefinition(key, label, "text", true, List.of(), null); }
    public static FieldDefinition json(String key, String label, Object value) { return new FieldDefinition(key, label, "json", false, List.of(), value); }
    public static FieldDefinition bool(String key, String label, boolean value) { return new FieldDefinition(key, label, "boolean", false, List.of(), value); }
    public static FieldDefinition number(String key, String label, int value) { return new FieldDefinition(key, label, "number", false, List.of(), value); }
    public static FieldDefinition select(String key, String label, String value, String... options) { return new FieldDefinition(key, label, "select", false, List.of(options), value); }
    public static FieldDefinition secret(String key, String label) { return new FieldDefinition(key, label, "password", false, List.of(), ""); }
    public String column() { return key.replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT); }
    public boolean structured() { return kind.equals("json"); }
}
