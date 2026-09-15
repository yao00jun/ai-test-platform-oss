ALTER TABLE import_job
  ADD COLUMN format VARCHAR(32) NOT NULL DEFAULT 'json',
  ADD COLUMN metadata JSON,
  ADD COLUMN private_payload LONGTEXT,
  ADD COLUMN applied_at DATETIME(3),
  ADD KEY ix_import_project(project_id,created_at);
