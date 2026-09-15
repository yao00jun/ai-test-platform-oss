ALTER TABLE bug_issue ADD COLUMN code_diagnosis JSON NOT NULL DEFAULT (JSON_OBJECT());

CREATE TABLE bug_rca_evaluation (
  id VARCHAR(32) NOT NULL PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  bug_id VARCHAR(32) NOT NULL,
  asset_version BIGINT NOT NULL,
  diagnosis_hash CHAR(64) NOT NULL,
  verdict VARCHAR(16) NOT NULL,
  regression VARCHAR(24) NOT NULL,
  note VARCHAR(4000) NOT NULL,
  actor VARCHAR(64) NOT NULL,
  source VARCHAR(16) NOT NULL,
  idempotency_key VARCHAR(120) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_rca_evaluation_bug FOREIGN KEY (bug_id) REFERENCES asset(id),
  UNIQUE KEY uq_rca_evaluation_request (project_id, bug_id, idempotency_key),
  KEY ix_rca_evaluation_history (project_id, bug_id, created_at, id),
  KEY ix_rca_evaluation_digest (bug_id, diagnosis_hash, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
