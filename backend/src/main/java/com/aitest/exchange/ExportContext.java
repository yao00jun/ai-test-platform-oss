package com.aitest.exchange;

import com.aitest.asset.Asset;
import com.aitest.asset.AssetType;
import java.util.List;
import java.util.Map;

/** assets retain IDs for graph-aware code generation; every data value is already redacted. */
public record ExportContext(String projectId, AssetType type, List<String> selectedIds, List<Asset> assets,
                            ExchangeBundle bundle, Map<String, Object> options) { }
