-- Phase 11: the workbook import asks the model to browse the file with read-only tools.
-- Every round (tool name, arguments, result summary) is kept on the session so a wrong
-- structural judgement can be replayed after the fact. Nullable: sessions that never ran
-- a tool loop (text, image, the legacy single-target spreadsheet path) leave it empty.
ALTER TABLE ai_import_log ADD COLUMN tool_trace_json TEXT;
