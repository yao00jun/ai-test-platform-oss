-- Optional cap for gateways that enforce requests per minute (0 = no cap).
ALTER TABLE ai_model_config ADD COLUMN requests_per_minute INT NOT NULL DEFAULT 0;
ALTER TABLE ai_model_config ALTER COLUMN timeout_seconds SET DEFAULT 600;
-- The settings screen could not change the timeout and every save wrote 120 back, so 120 is the old default rather
-- than a choice. Long generations on slower or reasoning models need more; the screen now lets users pick.
UPDATE ai_model_config SET timeout_seconds = 600 WHERE timeout_seconds = 120;
