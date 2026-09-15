package com.aitest.asset;

import java.time.Instant;

public record Revision(String id, String assetId, String version, String operation,
                       String source, Instant createdAt, Asset snapshot) { }
