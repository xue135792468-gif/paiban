ALTER TABLE scheduling_job ADD COLUMN source_draft_id BIGINT;
ALTER TABLE scheduling_job ADD COLUMN source_draft_version BIGINT;
ALTER TABLE scheduling_job ADD COLUMN source_snapshot_json TEXT;
ALTER TABLE scheduling_job ADD COLUMN inherited_lock_count INT;
ALTER TABLE scheduling_job ADD COLUMN result_publishable BOOLEAN;
ALTER TABLE scheduling_job ADD COLUMN result_blockers TEXT;
