CREATE TABLE document_submission (
  project_id VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(160) COLLATE utf8mb4_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  requirement_id VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY(project_id,idempotency_key),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(requirement_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
