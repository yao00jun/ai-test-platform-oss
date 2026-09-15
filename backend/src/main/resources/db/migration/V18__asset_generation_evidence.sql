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


