/** Matches the public asset-reference fields in backend AssetReferences; request bodies and text remain literal. */
export const assetReferenceFields = new Set(['environmentId', 'databaseSourceId', 'apiDefinitionId', 'requirementId', 'datasetId', 'associatedCaseId', 'targetId'])

export function localAssetReferences(parentId: string | null, data: Record<string, unknown>): string[] {
  return [parentId, ...Object.entries(data).filter(([key]) => assetReferenceFields.has(key)).map(([, value]) => value)]
    .filter((value): value is string => typeof value === 'string' && value.startsWith('@'))
    .map(value => value.slice(1))
}
