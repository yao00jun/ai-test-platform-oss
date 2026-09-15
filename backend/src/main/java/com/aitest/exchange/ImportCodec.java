package com.aitest.exchange;

import com.aitest.asset.AssetType;
import java.util.Set;

/** Optional code importers participate in the same bounded preview and atomic apply workflow. */
public interface ImportCodec {
    Set<AssetType> assetTypes();
    Set<String> formats();
    ParsedExchange parse(String source, String format, byte[] bytes, AssetType type);
}
