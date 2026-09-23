-- A new asset's parent may be another proposal in the same change set, referenced as "@localKey" (localKey up to 128 chars).
-- The column was sized for real 32-character IDs only, so a long model-chosen key failed with a constraint error.
ALTER TABLE ai_change_item MODIFY parent_id VARCHAR(160);
