-- Phase 11 ticket 12. The workbook import and the mapping "create class" step wrote
-- admin_class.student_count = 0 whenever the source carried no class size.
--
-- This migration is a DATA REPAIR, not a rewrite of the snapshot semantics:
--   * 0 is not a value any legal write can produce — the manual class form rejects it
--     (@Positive on SaveClassRequest.studentCount), the foundation Excel import rejects
--     <= 0 (FoundationImportService), and both admin_class.student_count (V4) and
--     draft_lesson.student_count (V7) default to 45;
--   * so a stored 0 can only mean "the import supplied no count": a hole where a value
--     should have been, not a real snapshot of a zero-student class;
--   * 45 is what the manual form would have written at the time (the same default the
--     entity, V4 and V7 carry), so filling the hole restores the missing default.
-- Any non-zero value is left untouched: a real import-time or solve-time snapshot is never
-- rewritten, which is what the ticket promises ("已有草稿保留导入当时人数").
--
-- Nothing here changes what future snapshots will say — that is the service fix, and the 45
-- fallback lives in one place (the entity default). No CHECK (student_count > 0) is added:
-- turning "allow 0 to mean unknown" into a database 500 would be a product decision, and the
-- API's 400 is the right place for that rule.
--
-- Preflight — run on the target database and record the ids before applying:
--   SELECT id, semester_id, grade_id, name FROM admin_class WHERE student_count = 0 ORDER BY id;
--   SELECT id, draft_id, admin_class_id FROM draft_lesson WHERE student_count = 0 ORDER BY id;
-- Back up first, e.g.
--   CREATE TABLE admin_class_t12_bak AS SELECT * FROM admin_class;
--   CREATE TABLE draft_lesson_t12_bak AS SELECT * FROM draft_lesson;
--
-- Rollback: after the repair 0 and 45 cannot be told apart, so roll back by the recorded ids:
--   UPDATE admin_class SET student_count = 0 WHERE id IN (<recorded admin_class ids>);
--   UPDATE draft_lesson SET student_count = 0 WHERE id IN (<recorded draft_lesson ids>);
-- Re-running this migration forward is always safe: both statements only touch 0 values.

UPDATE admin_class SET student_count = 45 WHERE student_count = 0;

-- A draft lesson carries a snapshot of its class size taken at import time. The same defect
-- left those snapshots at 0 — a hole, not a measurement (it made the room-capacity rule
-- vacuously true). Only rows carrying no count at all are filled in from their class; a
-- snapshot with a real number is never recalculated.
UPDATE draft_lesson
SET student_count = (SELECT c.student_count FROM admin_class c WHERE c.id = draft_lesson.admin_class_id)
WHERE student_count = 0
  AND EXISTS (SELECT 1 FROM admin_class c WHERE c.id = draft_lesson.admin_class_id);
