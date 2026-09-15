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
