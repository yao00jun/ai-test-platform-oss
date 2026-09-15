ALTER TABLE bug_issue ADD COLUMN root_cause_analysis MEDIUMTEXT NULL;
CREATE TABLE bug_failure_registry (
  project_id VARCHAR(32) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  bug_id VARCHAR(32) NOT NULL,
  first_seen DATETIME(3) NOT NULL,
  last_seen DATETIME(3) NOT NULL,
  occurrence_count BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(project_id,fingerprint),
  KEY ix_failure_bug(bug_id),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(bug_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE bug_failure_occurrence (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  run_id VARCHAR(32) NOT NULL,
  run_item_id VARCHAR(32) NOT NULL,
  failure_key VARCHAR(80) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  bug_id VARCHAR(32) NOT NULL,
  job_id VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  evidence JSON NOT NULL,
  diagnosis JSON NOT NULL,
  model_stamp VARCHAR(255) NOT NULL,
  prompt_version CHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_failure_occurrence(run_id,run_item_id,failure_key),
  KEY ix_occurrence_bug(project_id,bug_id,created_at),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(run_id) REFERENCES test_run(id),
  FOREIGN KEY(run_item_id) REFERENCES test_run_item(id),
  FOREIGN KEY(bug_id) REFERENCES asset(id),
  FOREIGN KEY(job_id) REFERENCES job_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
