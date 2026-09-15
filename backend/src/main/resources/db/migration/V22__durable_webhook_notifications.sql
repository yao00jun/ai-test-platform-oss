ALTER TABLE project_webhook_notice
  ADD COLUMN max_retries INT NOT NULL DEFAULT 3,
  ADD COLUMN timeout_seconds INT NOT NULL DEFAULT 10,
  ADD CONSTRAINT ck_webhook_retries CHECK(max_retries BETWEEN 0 AND 5),
  ADD CONSTRAINT ck_webhook_timeout CHECK(timeout_seconds BETWEEN 1 AND 60);

CREATE TABLE run_completion_event (
  seq BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  run_id VARCHAR(32) NOT NULL,
  failed BOOLEAN NOT NULL,
  report JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_run_completion(run_id),
  KEY ix_completion_project(project_id,seq),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(run_id) REFERENCES test_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_subscription (
  webhook_id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL,
  config_version BIGINT NOT NULL,
  event_cursor BIGINT NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY ix_subscription_project(project_id,enabled),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(webhook_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- No historical completion events are synthesized during upgrade.
INSERT INTO notification_subscription(webhook_id,project_id,enabled,config_version,event_cursor,updated_at)
  SELECT a.id,a.project_id,COALESCE(w.enabled,FALSE),a.version,0,CURRENT_TIMESTAMP(3)
  FROM asset a JOIN project_webhook_notice w ON w.asset_id=a.id
  JOIN project p ON p.id=a.project_id
  WHERE a.deleted=FALSE AND p.deleted=FALSE;

CREATE TABLE notification_delivery (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  webhook_id VARCHAR(32) NOT NULL,
  event_seq BIGINT NOT NULL,
  run_id VARCHAR(32) NOT NULL,
  report JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  automatic_retries INT NOT NULL DEFAULT 0,
  max_retries INT NOT NULL,
  next_retry_at DATETIME(3),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_notification_event(webhook_id,event_seq),
  KEY ix_notification_pending(project_id,status,next_retry_at),
  KEY ix_notification_history(webhook_id,event_seq),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(webhook_id) REFERENCES asset(id),
  FOREIGN KEY(event_seq) REFERENCES run_completion_event(seq),
  FOREIGN KEY(run_id) REFERENCES test_run(id),
  CHECK(max_retries BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_attempt (
  id VARCHAR(32) PRIMARY KEY,
  delivery_id VARCHAR(32) NOT NULL,
  attempt_number INT NOT NULL,
  job_id VARCHAR(32) NOT NULL,
  request_key VARCHAR(160) COLLATE utf8mb4_bin,
  request_fingerprint CHAR(64),
  origin VARCHAR(16) NOT NULL,
  config_version BIGINT NOT NULL,
  private_config MEDIUMTEXT NOT NULL,
  outcome VARCHAR(32) NOT NULL,
  retryable BOOLEAN NOT NULL DEFAULT FALSE,
  diagnostic_code VARCHAR(64),
  http_status INT,
  created_at DATETIME(3) NOT NULL,
  started_at DATETIME(3),
  completed_at DATETIME(3),
  UNIQUE KEY uq_notification_attempt(delivery_id,attempt_number),
  UNIQUE KEY uq_notification_job(job_id),
  UNIQUE KEY uq_notification_request(delivery_id,request_key),
  FOREIGN KEY(delivery_id) REFERENCES notification_delivery(id),
  FOREIGN KEY(job_id) REFERENCES job_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
