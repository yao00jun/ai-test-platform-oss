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

