-- Ticket 01: a large timetable parses in the background, one batch (class row) at a time.
-- Every batch is persisted as soon as it finishes, so an interrupted parse keeps what it had.
ALTER TABLE ai_import_log ADD COLUMN total_batches INT NOT NULL DEFAULT 0;
ALTER TABLE ai_import_log ADD COLUMN completed_batches INT NOT NULL DEFAULT 0;
ALTER TABLE ai_import_log ADD COLUMN current_batch VARCHAR(180);
ALTER TABLE ai_import_log ADD COLUMN batches_json TEXT;
