-- Thresholds become entry-backed: one entry per scope (ticket 10/04, ADR-0028).
--
-- Before this migration the five numeric limits stored a single `params_json` map per
-- type and had no object field at all, so every old value is one *global default*.
-- Copying the map verbatim is exactly right: an exception is an entry whose object
-- field is filled, so a legacy row that already carried one becomes that object's
-- exception without any extra handling.
--
-- A row whose map has no number is still a configured type: it used to evaluate as
-- the catalogue default (4 / 8 / 2 / 2 / 3, the very numbers declared in
-- ConstraintType's parameter schema), so the upgrade writes that number instead of
-- silently dropping the limit. Nothing is invented for a type that has no
-- constraint_config row at all — "no row" still means "not configured".
--
-- `NOT EXISTS` keeps the statement idempotent, matching V27.
INSERT INTO constraint_entry (semester_id, type, params_json, created_at)
SELECT c.semester_id,
       c.type,
       CASE
           WHEN c.params_json LIKE '%maxPerDay%' THEN c.params_json
           WHEN c.params_json LIKE '%maxConsecutive%' THEN c.params_json
           WHEN c.type = 'TEACHER_MAX_PER_DAY' THEN '{"maxPerDay":4}'
           WHEN c.type = 'CLASS_MAX_PER_DAY' THEN '{"maxPerDay":8}'
           WHEN c.type = 'COURSE_MAX_PER_DAY' THEN '{"maxPerDay":2}'
           WHEN c.type = 'EVENING_MAX' THEN '{"maxPerDay":2}'
           ELSE '{"maxConsecutive":3}'
       END,
       CURRENT_TIMESTAMP
FROM constraint_config c
WHERE c.type IN ('TEACHER_MAX_PER_DAY', 'CLASS_MAX_PER_DAY', 'COURSE_MAX_PER_DAY',
                 'EVENING_MAX', 'TEACHER_MAX_CONSECUTIVE')
  AND NOT EXISTS (
      SELECT 1 FROM constraint_entry e
      WHERE e.semester_id = c.semester_id AND e.type = c.type
  );
