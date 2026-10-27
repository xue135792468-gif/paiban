-- Tickets 01-03: the whole-workbook pipeline runs on the import executor.
-- plan_json keeps the confirmed structure (so a retry never re-plans), plan_version
-- makes a batch identity stable across re-plans, and run_id separates one dispatch from
-- the late answer of a stopped, abandoned or superseded run.
ALTER TABLE ai_import_log ADD COLUMN plan_json TEXT;
ALTER TABLE ai_import_log ADD COLUMN plan_version INT NOT NULL DEFAULT 0;
ALTER TABLE ai_import_log ADD COLUMN run_id VARCHAR(64);
