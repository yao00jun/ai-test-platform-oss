CREATE TABLE artifact_file (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  storage_path VARCHAR(1000) NOT NULL,
  media_type VARCHAR(128) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  byte_size BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY ix_file_project(project_id,created_at),
  FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE requirement_chunk (
  id VARCHAR(32) PRIMARY KEY,
  requirement_id VARCHAR(32) NOT NULL,
  chunk_index INT NOT NULL,
  title VARCHAR(255) NOT NULL,
  start_offset INT NOT NULL,
  content MEDIUMTEXT NOT NULL,
  UNIQUE KEY uq_chunk(requirement_id,chunk_index),
  FOREIGN KEY(requirement_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE import_job (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  asset_type VARCHAR(32) NOT NULL,
  parent_id VARCHAR(32),
  file_id VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL,
  parsed_rows JSON NOT NULL,
  errors JSON NOT NULL,
  warnings JSON NOT NULL,
  result JSON,
  created_at DATETIME(3) NOT NULL,
  FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
