ALTER TABLE project
  ADD COLUMN backend_repo_path VARCHAR(2048) NOT NULL DEFAULT '',
  ADD COLUMN frontend_repo_path VARCHAR(2048) NOT NULL DEFAULT '',
  ADD COLUMN sql_script_path VARCHAR(2048) NOT NULL DEFAULT '';

CREATE TABLE source_snapshot (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  project_version BIGINT NOT NULL,
  job_id VARCHAR(32) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  input_cipher LONGTEXT NOT NULL,
  result_cipher LONGTEXT,
  secrets_cipher MEDIUMTEXT,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  diagnostics JSON NOT NULL,
  manifest_hash CHAR(64),
  file_count INT NOT NULL DEFAULT 0,
  total_bytes BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3),
  KEY ix_source_project(project_id,created_at),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(job_id) REFERENCES job_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE source_snapshot_file (
  snapshot_id VARCHAR(32) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  path VARCHAR(2048) NOT NULL,
  path_hash CHAR(64) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  byte_size BIGINT NOT NULL,
  content_cipher LONGTEXT NOT NULL,
  PRIMARY KEY(snapshot_id,kind,path_hash),
  FOREIGN KEY(snapshot_id) REFERENCES source_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
