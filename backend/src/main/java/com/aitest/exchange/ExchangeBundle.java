package com.aitest.exchange;

import java.util.List;
import java.util.Map;

public record ExchangeBundle(String formatVersion, Map<String, Object> metadata, List<ExchangeNode> nodes,
                             Map<String, ExternalReference> externalReferences, List<ExchangeIssue> warnings) {
    public static final String VERSION = "aitest.exchange/v1";
    public ExchangeBundle {
        metadata = metadata == null ? Map.of() : metadata;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        externalReferences = externalReferences == null ? Map.of() : externalReferences;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
    public static ExchangeBundle of(List<ExchangeNode> nodes) { return new ExchangeBundle(VERSION, Map.of(), nodes, Map.of(), List.of()); }
    public record ExternalReference(String type, String name) { }
}
