ALTER TABLE test_plan ADD COLUMN misfire_policy VARCHAR(16) NOT NULL DEFAULT 'SKIP';

CREATE TABLE plan_schedule_cursor (
  plan_id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  configuration_hash CHAR(64) NOT NULL,
  configuration JSON NOT NULL,
  enabled BOOLEAN NOT NULL,
  next_fire_at DATETIME(3),
  last_fire_at DATETIME(3),
  updated_at DATETIME(3) NOT NULL,
  KEY ix_schedule_due(enabled,next_fire_at),
  FOREIGN KEY(plan_id) REFERENCES asset(id),
  FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE plan_schedule_occurrence (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  plan_id VARCHAR(32) NOT NULL,
  scheduled_for DATETIME(3) NOT NULL,
  misfire_through DATETIME(3),
  configuration_hash CHAR(64) NOT NULL,
  configuration JSON NOT NULL,
  plan_version BIGINT NOT NULL,
  dispatched_plan_version BIGINT,
  status VARCHAR(32) NOT NULL,
  reason VARCHAR(512),
  run_id VARCHAR(32),
  job_id VARCHAR(32),
  created_at DATETIME(3) NOT NULL,
  dispatched_at DATETIME(3),
  UNIQUE KEY uq_schedule_occurrence(plan_id,scheduled_for),
  KEY ix_schedule_queue(plan_id,status,scheduled_for),
  KEY ix_schedule_history(project_id,plan_id,created_at),
  FOREIGN KEY(plan_id) REFERENCES asset(id),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(run_id) REFERENCES test_run(id),
  FOREIGN KEY(job_id) REFERENCES job_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX ix_run_active_asset ON test_run(project_id,asset_id,status);
