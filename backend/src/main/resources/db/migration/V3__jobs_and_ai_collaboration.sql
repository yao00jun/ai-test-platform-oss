CREATE TABLE job_task (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  kind VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(160) NOT NULL,
  input JSON NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  progress INT NOT NULL DEFAULT 0,
  message VARCHAR(2000),
  result JSON,
  error TEXT,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  owner VARCHAR(64),
  lease_until DATETIME(3),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_job_idempotency(project_id,kind,idempotency_key),
  KEY ix_job_claim(status,created_at),
  FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE job_event (
  seq BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_id VARCHAR(32) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY ix_job_event(job_id,seq),
  FOREIGN KEY(job_id) REFERENCES job_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_model_config (
  id VARCHAR(32) PRIMARY KEY,
  base_url VARCHAR(2000) NOT NULL,
  api_key TEXT NOT NULL,
  model_name VARCHAR(200) NOT NULL,
  temperature DECIMAL(4,2) NOT NULL DEFAULT 0.3,
  timeout_seconds INT NOT NULL DEFAULT 120,
  version BIGINT NOT NULL DEFAULT 1,
  updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_conversation (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  scope VARCHAR(24) NOT NULL,
  target_id VARCHAR(32),
  target_type VARCHAR(32),
  created_at DATETIME(3) NOT NULL,
  FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_message (
  id VARCHAR(32) PRIMARY KEY,
  conversation_id VARCHAR(64) NOT NULL,
  job_id VARCHAR(32),
  role VARCHAR(16) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  base_version BIGINT,
  applied_version BIGINT,
  context_snapshot JSON,
  candidate JSON,
  validation JSON,
  model_version VARCHAR(200),
  template_version VARCHAR(128),
  created_at DATETIME(3) NOT NULL,
  KEY ix_message_conversation(conversation_id,created_at),
  FOREIGN KEY(conversation_id) REFERENCES ai_conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_change_set (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  conversation_id VARCHAR(64) NOT NULL,
  job_id VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME(3) NOT NULL,
  applied_at DATETIME(3),
  FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_change_item (
  id VARCHAR(32) PRIMARY KEY,
  change_set_id VARCHAR(32) NOT NULL,
  operation VARCHAR(16) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id VARCHAR(32),
  parent_id VARCHAR(32),
  local_key VARCHAR(128),
  base_version BIGINT,
  before_snapshot JSON,
  after_snapshot JSON NOT NULL,
  validation JSON NOT NULL,
  position INT NOT NULL,
  FOREIGN KEY(change_set_id) REFERENCES ai_change_set(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_pipeline_record (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  job_id VARCHAR(32) NOT NULL,
  conversation_id VARCHAR(64) NOT NULL,
  requirement_snapshot MEDIUMTEXT,
  api_snapshot MEDIUMTEXT,
  status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  current_stage VARCHAR(64),
  asset_ids JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_pipeline_step (
  id VARCHAR(32) PRIMARY KEY,
  pipeline_id VARCHAR(32) NOT NULL,
  stage VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  input_snapshot JSON NOT NULL,
  output_snapshot JSON,
  error TEXT,
  started_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3),
  FOREIGN KEY(pipeline_id) REFERENCES ai_pipeline_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
