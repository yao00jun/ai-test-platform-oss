package com.aitest.asset;

import java.util.List;

public record AssetPage(List<Asset> items, long total) { }
