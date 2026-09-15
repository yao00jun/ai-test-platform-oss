-- Draft adoption/rejection updates messages by job, role and state. Without this
-- index MySQL scans and locks unrelated conversations while updating one draft.
CREATE INDEX ix_message_job_state ON ai_message(job_id, role, status);
