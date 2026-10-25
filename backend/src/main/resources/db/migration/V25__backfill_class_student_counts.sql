-- Phase 11 ticket 12: the workbook import and the mapping "create class" step wrote
-- admin_class.student_count = 0 whenever the source carried no class size. 0 is not a
-- product value:
--   * the manual class form rejects it (@Positive on SaveClassRequest.studentCount),
--   * the foundation Excel import rejects <= 0 (FoundationImportService),
--   * admin_class.student_count (V4) and draft_lesson.student_count (V7) both default to 45.
-- So a stored 0 can only mean "the import did not supply a count", never "a user typed 0":
-- these rows are the defaulted ones, and 45 is what the manual form would have written.
-- Positive values are left untouched; a school's real class sizes are never rewritten.
--
-- Preflight (run and record the result before applying, on the target database):
--   SELECT id, semester_id, grade_id, name FROM admin_class WHERE student_count = 0 ORDER BY id;
--   SELECT COUNT(*) FROM draft_lesson WHERE student_count = 0;
-- Back up first, e.g.
--   CREATE TABLE admin_class_t12_bak AS SELECT * FROM admin_class;
--   CREATE TABLE draft_lesson_t12_bak AS SELECT * FROM draft_lesson;
--
-- Rollback: 0 and 45 cannot be told apart afterwards, so use the ids recorded above:
--   UPDATE admin_class SET student_count = 0 WHERE id IN (<recorded ids>);
--   UPDATE draft_lesson SET student_count = 0 WHERE draft_id IN (<drafts of those classes>);
-- Re-running this migration forward is always safe: both statements are idempotent.

UPDATE admin_class SET student_count = 45 WHERE student_count = 0;

-- A draft lesson keeps a snapshot of its class size taken at import time; the same bug
-- left those snapshots at 0, which made the room-capacity rule vacuously true for those
-- drafts. Only snapshots that carry no count at all are synced to the class's count.
UPDATE draft_lesson
SET student_count = (SELECT c.student_count FROM admin_class c WHERE c.id = draft_lesson.admin_class_id)
WHERE student_count = 0
  AND EXISTS (SELECT 1 FROM admin_class c WHERE c.id = draft_lesson.admin_class_id);
