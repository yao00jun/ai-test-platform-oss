package com.aitest.asset;

/** Synchronous domain effects within the asset transaction, after validation and revision. */
public interface AssetObserver {
    void changed(Asset previous, Asset current);
    default void deleted(Asset asset) { }
}
