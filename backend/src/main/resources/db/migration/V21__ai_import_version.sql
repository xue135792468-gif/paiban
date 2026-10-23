-- Phase 8 review: a background parse and a stop/abandon request can hold the same row at
-- once. A version column makes the second writer lose instead of overwriting the newer
-- state (an ABANDONED session must never be written back to PARSING).
ALTER TABLE ai_import_log ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
