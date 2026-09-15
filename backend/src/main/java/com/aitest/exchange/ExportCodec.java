package com.aitest.exchange;

import com.aitest.asset.AssetType;
import java.util.Set;

/** Add a Spring component for optional rendering/codegen. Advertise only real implementations. */
public interface ExportCodec {
    Set<AssetType> assetTypes();
    Set<String> formats();
    ExportFile export(ExportContext context, String format);
}
