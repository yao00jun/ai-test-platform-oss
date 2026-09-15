ALTER TABLE web_test_scenario
  ADD COLUMN timeout_ms BIGINT NOT NULL DEFAULT 120000,
  ADD COLUMN continue_on_failure BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN ignore_https_errors BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE web_test_step
  ADD COLUMN target_selector MEDIUMTEXT NULL,
  ADD COLUMN file_ids JSON NULL,
  ADD COLUMN wait_state VARCHAR(32) NOT NULL DEFAULT 'visible';
UPDATE web_test_step SET file_ids=JSON_ARRAY() WHERE file_ids IS NULL;
