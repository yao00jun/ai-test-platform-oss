package com.aitest.asset;

import java.util.Set;

/** Asset references exclude file/run identifiers and arbitrary text or request payloads. */
public final class AssetReferences {
    private AssetReferences() { }
    public static final Set<String> FIELDS = Set.of("environmentId", "databaseSourceId", "apiDefinitionId",
            "requirementId", "datasetId", "associatedCaseId", "targetId");
}
