ALTER TABLE project_environment
  ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'TEST',
  ADD COLUMN allow_sql_write BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN http_options JSON,
  ADD COLUMN auto_run_generated BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE project_environment SET http_options=JSON_OBJECT() WHERE http_options IS NULL;
ALTER TABLE project_global_auth ADD COLUMN retry_non_idempotent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sql_validation
  ADD COLUMN timeout_seconds BIGINT NOT NULL DEFAULT 30,
  ADD COLUMN max_rows BIGINT NOT NULL DEFAULT 1000,
  ADD COLUMN max_affected_rows BIGINT NOT NULL DEFAULT 1000;
