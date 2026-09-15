package com.aitest.exchange;

import com.aitest.asset.AssetType;
import java.util.LinkedHashMap;
import java.util.Map;

/** references contains field -> local/external key; ordinary data is never interpreted as a key. */
public record ExchangeNode(String key, AssetType type, String parentKey, String name, int position,
                           Map<String, Object> data, Map<String, String> references) {
    public ExchangeNode {
        data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        references = references == null ? new LinkedHashMap<>() : new LinkedHashMap<>(references);
    }
    public ExchangeNode withData(Map<String, Object> value) { return new ExchangeNode(key, type, parentKey, name, position, value, references); }
}
