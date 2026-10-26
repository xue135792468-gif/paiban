-- Explicit "every target" entries for the four types whose legacy params could be
-- empty while still carrying a rule (ticket 10/03).
--
-- Before ADR-0028 a blank `courseId` meant "every course" for SAME_COURSE_ONCE_PER_DAY
-- and COURSE_SPREAD, and "the sport-class courses" for SPORT_NOT_FIRST_AFTERNOON and
-- PE_LAST_PERIOD_AVOID. Those rules were stored as `params_json = '{}'` (or NULL),
-- which V26 deliberately did not copy because `{}` used to be "no object condition".
--
-- From ticket 10/03 on, an empty entry *list* means "not configured" and every
-- target must be an explicit entry, so those rows would silently stop evaluating.
-- Give each of them exactly one entry with no object condition, which is what the
-- old value meant. Types whose object field is required never had an empty value
-- that meant "all", and single-switch types keep having no entries at all.
INSERT INTO constraint_entry (semester_id, type, params_json, created_at)
SELECT c.semester_id, c.type, '{}', CURRENT_TIMESTAMP
FROM constraint_config c
WHERE c.type IN ('SAME_COURSE_ONCE_PER_DAY', 'SPORT_NOT_FIRST_AFTERNOON',
                 'COURSE_SPREAD', 'PE_LAST_PERIOD_AVOID')
  AND (c.params_json IS NULL OR TRIM(c.params_json) IN ('', '{}'))
  AND NOT EXISTS (
      SELECT 1 FROM constraint_entry e
      WHERE e.semester_id = c.semester_id AND e.type = c.type
  );
