package com.aitest.asset;

/** Domain engines may reject invalid changes; policies must be side-effect free. */
public interface AssetPolicy {
    void validate(Asset previous, Asset candidate);
    default void validateInGraph(Asset previous, Asset candidate, java.util.Map<String, Asset> proposedGraph) { validate(previous, candidate); }
}
