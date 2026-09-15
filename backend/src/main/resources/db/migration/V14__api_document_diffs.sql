CREATE TABLE api_document_diff (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  import_id VARCHAR(32) NOT NULL,
  private_payload MEDIUMTEXT NOT NULL,
  accepted_items JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY ix_api_diff_project(project_id,created_at),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(import_id) REFERENCES import_job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE api_diff_application (
  project_id VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(160) COLLATE utf8mb4_bin NOT NULL,
  diff_id VARCHAR(32) NOT NULL,
  selected_items JSON NOT NULL,
  result JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY(project_id,idempotency_key),
  FOREIGN KEY(diff_id) REFERENCES api_document_diff(id),
  FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_change_guard (
  change_set_id VARCHAR(32) NOT NULL,
  asset_id VARCHAR(32) NOT NULL,
  base_version BIGINT NOT NULL,
  PRIMARY KEY(change_set_id,asset_id),
  FOREIGN KEY(change_set_id) REFERENCES ai_change_set(id),
  FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
