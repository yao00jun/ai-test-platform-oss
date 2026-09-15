CREATE TABLE test_run (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  asset_id VARCHAR(32) NOT NULL,
  job_id VARCHAR(32) NOT NULL,
  name VARCHAR(255) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  snapshot JSON NOT NULL,
  private_snapshot MEDIUMTEXT NOT NULL,
  summary JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  started_at DATETIME(3),
  completed_at DATETIME(3),
  UNIQUE KEY uq_run_job(job_id),
  KEY ix_run_project(project_id,created_at),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(job_id) REFERENCES job_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE test_run_item (
  id VARCHAR(32) PRIMARY KEY,
  run_id VARCHAR(32) NOT NULL,
  asset_id VARCHAR(32) NOT NULL,
  name VARCHAR(255) NOT NULL,
  asset_type VARCHAR(32) NOT NULL,
  position INT NOT NULL,
  row_index INT,
  variables JSON NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  duration_ms BIGINT NOT NULL DEFAULT 0,
  manual_version BIGINT NOT NULL DEFAULT 1,
  notes TEXT,
  error TEXT,
  started_at DATETIME(3),
  completed_at DATETIME(3),
  KEY ix_run_item(run_id,position,row_index),
  FOREIGN KEY(run_id) REFERENCES test_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE test_step_result (
  id VARCHAR(32) PRIMARY KEY,
  run_item_id VARCHAR(32) NOT NULL,
  asset_id VARCHAR(32) NOT NULL,
  name VARCHAR(255) NOT NULL,
  engine VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL,
  position INT NOT NULL,
  result JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY ix_step_result(run_item_id,position),
  FOREIGN KEY(run_item_id) REFERENCES test_run_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE manual_result_event (
  id VARCHAR(32) PRIMARY KEY,
  run_item_id VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  notes TEXT,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_manual_version(run_item_id,version),
  FOREIGN KEY(run_item_id) REFERENCES test_run_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
