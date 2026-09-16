-- Dashboard history reads the newest assets; the list index orders by position.
CREATE INDEX ix_asset_recent ON asset(project_id,asset_type,deleted,updated_at DESC,id DESC);

-- Position allocation is scoped to type and parent, including the root (NULL).
CREATE INDEX ix_asset_scope_position ON asset(project_id,asset_type,parent_id,deleted,position);
