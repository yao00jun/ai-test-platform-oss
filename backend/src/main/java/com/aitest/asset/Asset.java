package com.aitest.asset;

import java.time.Instant;
import java.util.Map;

public record Asset(String id, String projectId, AssetType type, String parentId, String name,
                    String version, int position, String source, boolean confirmed,
                    Instant createdAt, Instant updatedAt, Map<String, Object> data) { }
