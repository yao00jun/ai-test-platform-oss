CREATE TABLE source_impact (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  source_snapshot_id VARCHAR(32) NOT NULL,
  baseline_snapshot_id VARCHAR(32),
  job_id VARCHAR(32) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  result_cipher LONGTEXT,
  created_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3),
  KEY ix_impact_source(project_id,source_snapshot_id,created_at),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(source_snapshot_id) REFERENCES source_snapshot(id),
  FOREIGN KEY(baseline_snapshot_id) REFERENCES source_snapshot(id),
  FOREIGN KEY(job_id) REFERENCES job_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE source_regression_submission (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  impact_id VARCHAR(32) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  plan_id VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(impact_id) REFERENCES source_impact(id),
  FOREIGN KEY(plan_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE test_plan
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN impact_id VARCHAR(32) NOT NULL DEFAULT '';
