package com.aitest.execution;

import com.aitest.asset.AssetType;
import java.util.List;
import java.util.Map;

public record RunDefinition(AssetGraph graph, List<Item> items) {
    public record Item(String id, String assetId, String name, AssetType type, int position, Integer rowIndex, Map<String, Object> variables, String mode) { }
}
