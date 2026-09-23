-- AI-Test-Platform current structure review: Flyway V1-V24, MySQL 8.4.
-- This concatenation is documentation, not an initialization entry point.
-- Start the application against an empty database so Flyway creates its history.
-- Do not run this file alongside Flyway. Applied migration files are immutable.

-- V1__versioned_assets.sql
CREATE TABLE project (
  id VARCHAR(32) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  description MEDIUMTEXT,
  version BIGINT NOT NULL DEFAULT 1,
  position INT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  CONSTRAINT ck_project_version CHECK(version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE asset (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  asset_type VARCHAR(32) NOT NULL,
  parent_id VARCHAR(32),
  name VARCHAR(255) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  position INT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_asset_project(id, project_id),
  KEY ix_asset_list(project_id, asset_type, deleted, position),
  KEY ix_asset_children(parent_id, deleted, position),
  CONSTRAINT fk_asset_project FOREIGN KEY(project_id) REFERENCES project(id),
  CONSTRAINT fk_asset_parent FOREIGN KEY(parent_id, project_id) REFERENCES asset(id, project_id),
  CONSTRAINT ck_asset_version CHECK(version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE asset_revision (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  asset_id VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL,
  operation VARCHAR(24) NOT NULL,
  source VARCHAR(16) NOT NULL,
  snapshot JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_revision(asset_id, version),
  KEY ix_revision_project(project_id, asset_id),
  CONSTRAINT fk_revision_project FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE asset_relation (
  from_id VARCHAR(32) NOT NULL,
  to_id VARCHAR(32) NOT NULL,
  relation_type VARCHAR(64) NOT NULL,
  PRIMARY KEY(from_id,to_id,relation_type),
  KEY ix_relation_to(to_id),
  FOREIGN KEY(from_id) REFERENCES asset(id),
  FOREIGN KEY(to_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id VARCHAR(32) NOT NULL,
  asset_id VARCHAR(32),
  action VARCHAR(64) NOT NULL,
  source VARCHAR(16) NOT NULL,
  detail JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY ix_audit_project(project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- V2__domain_tables.sql
-- Typed domain tables. Structured request bodies, assertions and datasets use JSON; identities and ordering live in asset.
CREATE TABLE project_environment (
  asset_id VARCHAR(32) PRIMARY KEY,
  `base_url` MEDIUMTEXT NULL,
  `web_url` MEDIUMTEXT NULL,
  `headers` JSON NULL,
  `variables` JSON NULL,
  CONSTRAINT fk_project_environment_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_global_auth (
  asset_id VARCHAR(32) PRIMARY KEY,
  `environment_id` MEDIUMTEXT NULL,
  `enabled` BOOLEAN NULL,
  `login_url` MEDIUMTEXT NULL,
  `login_method` VARCHAR(32) NULL,
  `login_payload` JSON NULL,
  `token_json_path` MEDIUMTEXT NULL,
  `header_key` MEDIUMTEXT NULL,
  `header_prefix` MEDIUMTEXT NULL,
  `ttl_seconds` BIGINT NULL,
  `retry_unauthorized` BOOLEAN NULL,
  CONSTRAINT fk_project_global_auth_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_database_source (
  asset_id VARCHAR(32) PRIMARY KEY,
  `environment_id` MEDIUMTEXT NULL,
  `db_type` VARCHAR(32) NULL,
  `jdbc_url` MEDIUMTEXT NULL,
  `username` MEDIUMTEXT NULL,
  `password` MEDIUMTEXT NULL,
  `safe_mode` BOOLEAN NULL,
  `max_pool_size` BIGINT NULL,
  CONSTRAINT fk_project_database_source_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE case_module (
  asset_id VARCHAR(32) PRIMARY KEY,
  `description` MEDIUMTEXT NULL,
  CONSTRAINT fk_case_module_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE requirement_document (
  asset_id VARCHAR(32) PRIMARY KEY,
  `content` MEDIUMTEXT NULL,
  `file_id` MEDIUMTEXT NULL,
  `source_path` MEDIUMTEXT NULL,
  `sections` JSON NULL,
  `analysis` JSON NULL,
  CONSTRAINT fk_requirement_document_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE functional_case (
  asset_id VARCHAR(32) PRIMARY KEY,
  `priority` VARCHAR(32) NULL,
  `case_type` VARCHAR(32) NULL,
  `precondition` MEDIUMTEXT NULL,
  `remark` MEDIUMTEXT NULL,
  `requirement_id` MEDIUMTEXT NULL,
  `tags` JSON NULL,
  CONSTRAINT fk_functional_case_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE functional_case_step (
  asset_id VARCHAR(32) PRIMARY KEY,
  `step` MEDIUMTEXT NULL,
  `expected` MEDIUMTEXT NULL,
  CONSTRAINT fk_functional_case_step_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE api_definition (
  asset_id VARCHAR(32) PRIMARY KEY,
  `method` VARCHAR(32) NULL,
  `path` MEDIUMTEXT NULL,
  `headers` JSON NULL,
  `query_params` JSON NULL,
  `body_type` VARCHAR(32) NULL,
  `body` JSON NULL,
  `schema` JSON NULL,
  `document_version` MEDIUMTEXT NULL,
  CONSTRAINT fk_api_definition_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE api_test_case (
  asset_id VARCHAR(32) PRIMARY KEY,
  `api_definition_id` MEDIUMTEXT NULL,
  `method` VARCHAR(32) NULL,
  `path` MEDIUMTEXT NULL,
  `headers` JSON NULL,
  `query_params` JSON NULL,
  `body_type` VARCHAR(32) NULL,
  `body` JSON NULL,
  `extractors` JSON NULL,
  `assertions` JSON NULL,
  `timeout_ms` BIGINT NULL,
  `use_global_auth` BOOLEAN NULL,
  CONSTRAINT fk_api_test_case_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE api_scenario (
  asset_id VARCHAR(32) PRIMARY KEY,
  `description` MEDIUMTEXT NULL,
  `variables` JSON NULL,
  `continue_on_failure` BOOLEAN NULL,
  `dataset_id` MEDIUMTEXT NULL,
  CONSTRAINT fk_api_scenario_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE scenario_step (
  asset_id VARCHAR(32) PRIMARY KEY,
  `step_type` VARCHAR(32) NULL,
  `target_id` MEDIUMTEXT NULL,
  `variables` JSON NULL,
  `wait_ms` BIGINT NULL,
  CONSTRAINT fk_scenario_step_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sql_validation (
  asset_id VARCHAR(32) PRIMARY KEY,
  `database_source_id` MEDIUMTEXT NULL,
  `sql` MEDIUMTEXT NULL,
  `parameters` JSON NULL,
  `assertions` JSON NULL,
  `exports` JSON NULL,
  `allow_write` BOOLEAN NULL,
  `dry_run` BOOLEAN NULL,
  CONSTRAINT fk_sql_validation_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE data_driven_dataset (
  asset_id VARCHAR(32) PRIMARY KEY,
  `columns` JSON NULL,
  `rows` JSON NULL,
  `file_id` MEDIUMTEXT NULL,
  CONSTRAINT fk_data_driven_dataset_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE web_test_scenario (
  asset_id VARCHAR(32) PRIMARY KEY,
  `description` MEDIUMTEXT NULL,
  `base_url` MEDIUMTEXT NULL,
  `browser` VARCHAR(32) NULL,
  `viewport_width` BIGINT NULL,
  `viewport_height` BIGINT NULL,
  `headless` BOOLEAN NULL,
  CONSTRAINT fk_web_test_scenario_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE web_test_step (
  asset_id VARCHAR(32) PRIMARY KEY,
  `action` VARCHAR(32) NULL,
  `selector` MEDIUMTEXT NULL,
  `value` MEDIUMTEXT NULL,
  `url` MEDIUMTEXT NULL,
  `expected` MEDIUMTEXT NULL,
  `timeout_ms` BIGINT NULL,
  `frame` MEDIUMTEXT NULL,
  `page_alias` MEDIUMTEXT NULL,
  `save_as` MEDIUMTEXT NULL,
  `attribute` MEDIUMTEXT NULL,
  CONSTRAINT fk_web_test_step_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE test_plan (
  asset_id VARCHAR(32) PRIMARY KEY,
  `description` MEDIUMTEXT NULL,
  `environment_id` MEDIUMTEXT NULL,
  `dataset_id` MEDIUMTEXT NULL,
  `cron_expression` MEDIUMTEXT NULL,
  `timezone` MEDIUMTEXT NULL,
  `schedule_enabled` BOOLEAN NULL,
  `overlap_policy` VARCHAR(32) NULL,
  `concurrency` BIGINT NULL,
  `diagnose_failures` BOOLEAN NULL,
  CONSTRAINT fk_test_plan_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE test_plan_item (
  asset_id VARCHAR(32) PRIMARY KEY,
  `target_id` MEDIUMTEXT NULL,
  `execution_mode` VARCHAR(32) NULL,
  `dataset_id` MEDIUMTEXT NULL,
  `variables` JSON NULL,
  CONSTRAINT fk_test_plan_item_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bug_issue (
  asset_id VARCHAR(32) PRIMARY KEY,
  `severity` VARCHAR(32) NULL,
  `status` VARCHAR(32) NULL,
  `reproduce_steps` MEDIUMTEXT NULL,
  `actual_result` MEDIUMTEXT NULL,
  `expected_result` MEDIUMTEXT NULL,
  `suggestion` MEDIUMTEXT NULL,
  `associated_case_id` MEDIUMTEXT NULL,
  `run_id` MEDIUMTEXT NULL,
  `attachments` JSON NULL,
  `fingerprint` MEDIUMTEXT NULL,
  CONSTRAINT fk_bug_issue_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dashboard_layout (
  asset_id VARCHAR(32) PRIMARY KEY,
  `cards` JSON NULL,
  `notes` MEDIUMTEXT NULL,
  CONSTRAINT fk_dashboard_layout_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE quality_brief (
  asset_id VARCHAR(32) PRIMARY KEY,
  `content` MEDIUMTEXT NULL,
  `run_id` MEDIUMTEXT NULL,
  `metrics` JSON NULL,
  CONSTRAINT fk_quality_brief_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_webhook_notice (
  asset_id VARCHAR(32) PRIMARY KEY,
  `platform` VARCHAR(32) NULL,
  `webhook_url` MEDIUMTEXT NULL,
  `secret` MEDIUMTEXT NULL,
  `enabled` BOOLEAN NULL,
  `fail_only` BOOLEAN NULL,
  CONSTRAINT fk_project_webhook_notice_asset FOREIGN KEY(asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;



-- V3__jobs_and_ai_collaboration.sql
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
  parent_id VARCHAR(160),
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


-- V4__files_and_imports.sql
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


-- V5__change_set_application.sql
ALTER TABLE ai_change_set ADD COLUMN selected_items JSON, ADD COLUMN result JSON;


-- V6__execution_options.sql
ALTER TABLE project_environment
  ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'TEST',
  ADD COLUMN allow_sql_write BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN http_options JSON,
  ADD COLUMN auto_run_generated BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE project_environment SET http_options=JSON_OBJECT() WHERE http_options IS NULL;
ALTER TABLE project_global_auth ADD COLUMN retry_non_idempotent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sql_validation
  ADD COLUMN timeout_seconds BIGINT NOT NULL DEFAULT 30,
  ADD COLUMN max_rows BIGINT NOT NULL DEFAULT 1000,
  ADD COLUMN max_affected_rows BIGINT NOT NULL DEFAULT 1000;


-- V7__runs_and_results.sql
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


-- V8__browser_execution_options.sql
ALTER TABLE web_test_scenario
  ADD COLUMN timeout_ms BIGINT NOT NULL DEFAULT 120000,
  ADD COLUMN continue_on_failure BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN ignore_https_errors BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE web_test_step
  ADD COLUMN target_selector MEDIUMTEXT NULL,
  ADD COLUMN file_ids JSON NULL,
  ADD COLUMN wait_state VARCHAR(32) NOT NULL DEFAULT 'visible';
UPDATE web_test_step SET file_ids=JSON_ARRAY() WHERE file_ids IS NULL;


-- V9__exchange_metadata.sql
ALTER TABLE import_job
  ADD COLUMN format VARCHAR(32) NOT NULL DEFAULT 'json',
  ADD COLUMN metadata JSON,
  ADD COLUMN private_payload LONGTEXT,
  ADD COLUMN applied_at DATETIME(3),
  ADD KEY ix_import_project(project_id,created_at);


-- V10__ui_locator_matching.sql
ALTER TABLE web_test_step ADD COLUMN exact_match BOOLEAN NOT NULL DEFAULT TRUE;


-- V11__bug_failure_history.sql
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


-- V12__durable_pipeline_stages.sql
ALTER TABLE ai_pipeline_record
  ADD COLUMN config_snapshot JSON NULL,
  ADD COLUMN progress INT NOT NULL DEFAULT 0,
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
  ADD COLUMN run_id VARCHAR(32) NULL,
  ADD COLUMN error TEXT NULL;
ALTER TABLE ai_pipeline_step
  ADD COLUMN job_id VARCHAR(32) NULL,
  ADD COLUMN attempt INT NOT NULL DEFAULT 1,
  ADD UNIQUE KEY uq_pipeline_attempt(pipeline_id,stage,attempt);
CREATE TABLE ai_pipeline_batch (
  id VARCHAR(32) PRIMARY KEY,
  pipeline_id VARCHAR(32) NOT NULL,
  stage VARCHAR(8) NOT NULL,
  source_key CHAR(64) NOT NULL,
  input_snapshot JSON NOT NULL,
  output_snapshot JSON NOT NULL,
  asset_ids JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_pipeline_batch(pipeline_id,stage,source_key),
  FOREIGN KEY(pipeline_id) REFERENCES ai_pipeline_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- V13__document_submissions.sql
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


-- V14__api_document_diffs.sql
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


-- V15__durable_plan_schedules.sql
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


-- V16__source_analysis_snapshots.sql
ALTER TABLE project
  ADD COLUMN backend_repo_path VARCHAR(2048) NOT NULL DEFAULT '',
  ADD COLUMN frontend_repo_path VARCHAR(2048) NOT NULL DEFAULT '',
  ADD COLUMN sql_script_path VARCHAR(2048) NOT NULL DEFAULT '';

CREATE TABLE source_snapshot (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  project_version BIGINT NOT NULL,
  job_id VARCHAR(32) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  input_cipher LONGTEXT NOT NULL,
  result_cipher LONGTEXT,
  secrets_cipher MEDIUMTEXT,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  diagnostics JSON NOT NULL,
  manifest_hash CHAR(64),
  file_count INT NOT NULL DEFAULT 0,
  total_bytes BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3),
  KEY ix_source_project(project_id,created_at),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(job_id) REFERENCES job_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE source_snapshot_file (
  snapshot_id VARCHAR(32) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  path VARCHAR(2048) NOT NULL,
  path_hash CHAR(64) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  byte_size BIGINT NOT NULL,
  content_cipher LONGTEXT NOT NULL,
  PRIMARY KEY(snapshot_id,kind,path_hash),
  FOREIGN KEY(snapshot_id) REFERENCES source_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- V17__source_impact_and_run_evidence.sql
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


-- V18__asset_generation_evidence.sql
-- Fixed generation evidence travels with normal typed assets and their existing revisions.
ALTER TABLE requirement_document
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE functional_case
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE functional_case_step
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE api_definition
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE api_test_case
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE api_scenario
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE scenario_step
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE sql_validation
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE web_test_scenario
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE web_test_step
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE test_plan
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE test_plan_item
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());

ALTER TABLE bug_issue
  ADD COLUMN source_snapshot_id VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN generation_evidence JSON NOT NULL DEFAULT (JSON_OBJECT());




-- V19__code_diagnosis_and_human_evaluation.sql
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


-- V20__model_invocation_metrics.sql
CREATE TABLE ai_model_price (
  model_name VARCHAR(200) COLLATE utf8mb4_bin PRIMARY KEY,
  version BIGINT NOT NULL,
  enabled BOOLEAN NOT NULL,
  currency VARCHAR(16) NOT NULL,
  input_per_million DECIMAL(24,10) NOT NULL,
  output_per_million DECIMAL(24,10) NOT NULL,
  updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_model_invocation (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  job_id VARCHAR(32) NOT NULL,
  model_name VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
  model_version VARCHAR(100) NOT NULL,
  response_model VARCHAR(200),
  template_name VARCHAR(100) NOT NULL,
  template_version CHAR(64) NOT NULL,
  purpose VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL,
  error_code VARCHAR(100),
  started_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3),
  duration_ms BIGINT,
  http_attempts INT NOT NULL DEFAULT 0,
  usage_reported BOOLEAN NOT NULL DEFAULT FALSE,
  prompt_tokens BIGINT,
  completion_tokens BIGINT,
  total_tokens BIGINT,
  pricing_version BIGINT,
  currency VARCHAR(16),
  input_per_million DECIMAL(24,10),
  output_per_million DECIMAL(24,10),
  estimated_cost DECIMAL(30,12),
  KEY ix_invocation_project(project_id,started_at),
  KEY ix_invocation_model(project_id,model_name,model_version,started_at),
  KEY ix_invocation_job(job_id),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(job_id) REFERENCES job_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- V21__durable_morning_briefs.sql
CREATE TABLE morning_brief_schedule (
  project_id VARCHAR(32) PRIMARY KEY,
  version BIGINT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  local_time CHAR(5) NOT NULL,
  timezone VARCHAR(64) NOT NULL,
  instruction TEXT NOT NULL,
  max_retries TINYINT NOT NULL,
  next_fire_at DATETIME(3),
  updated_at DATETIME(3) NOT NULL,
  KEY ix_morning_due(enabled,next_fire_at),
  FOREIGN KEY(project_id) REFERENCES project(id),
  CHECK(version > 0),
  CHECK(max_retries BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE morning_brief_occurrence (
  id VARCHAR(32) PRIMARY KEY,
  project_id VARCHAR(32) NOT NULL,
  local_date DATE NOT NULL,
  timezone VARCHAR(64) NOT NULL,
  scheduled_for DATETIME(3) NOT NULL,
  missed_from DATE,
  window_from DATETIME(3) NOT NULL,
  window_to DATETIME(3) NOT NULL,
  instruction TEXT NOT NULL,
  max_retries TINYINT NOT NULL,
  automatic_retries TINYINT NOT NULL DEFAULT 0,
  attempt_count INT NOT NULL DEFAULT 0,
  metrics JSON,
  status VARCHAR(24) NOT NULL,
  asset_id VARCHAR(32),
  conversation_id VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_morning_date(project_id,local_date),
  KEY ix_morning_history(project_id,created_at,id),
  KEY ix_morning_pending(project_id,status),
  FOREIGN KEY(project_id) REFERENCES project(id),
  FOREIGN KEY(asset_id) REFERENCES asset(id),
  FOREIGN KEY(conversation_id) REFERENCES ai_conversation(id),
  CHECK(max_retries BETWEEN 0 AND 3),
  CHECK(window_from < window_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE morning_brief_attempt (
  id VARCHAR(32) PRIMARY KEY,
  occurrence_id VARCHAR(32) NOT NULL,
  attempt_number INT NOT NULL,
  job_id VARCHAR(32) NOT NULL,
  request_key VARCHAR(160),
  origin VARCHAR(16) NOT NULL,
  retryable BOOLEAN NOT NULL DEFAULT FALSE,
  diagnostic_code VARCHAR(64),
  next_retry_at DATETIME(3),
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_morning_attempt(occurrence_id,attempt_number),
  UNIQUE KEY uq_morning_job(job_id),
  UNIQUE KEY uq_morning_retry(occurrence_id,request_key),
  FOREIGN KEY(occurrence_id) REFERENCES morning_brief_occurrence(id),
  FOREIGN KEY(job_id) REFERENCES job_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- V22__durable_webhook_notifications.sql
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

-- V23: scope draft message decisions to their own job rather than scanning conversations.
CREATE INDEX ix_message_job_state ON ai_message(job_id, role, status);

-- V24: recent assets and positions within an asset type / parent scope.
CREATE INDEX ix_asset_recent ON asset(project_id,asset_type,deleted,updated_at DESC,id DESC);
CREATE INDEX ix_asset_scope_position ON asset(project_id,asset_type,parent_id,deleted,position);

-- V27__change_item_local_parent_reference.sql
-- ai_change_item.parent_id widened to VARCHAR(160): "@localKey" references (localKey ≤ 128) did not fit the 32-char ID column.
