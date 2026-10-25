-- Draft lessons must point at a real period row.
--
-- Until now draft_lesson.period_id had no foreign key, and saving the period table
-- deleted and recreated every row, so existing drafts were silently orphaned (see
-- ticket 11). With this key, a dangling reference can no longer be written — even
-- through a forced single-lesson move — and the period save service refuses to delete
-- a slot that a draft still uses.
--
-- Preflight (run before deploying; any row returned must be repaired by hand first,
-- this migration deliberately does not guess which period a lesson belonged to):
--
--   SELECT dl.id, dl.draft_id, dl.period_id, td.semester_id
--     FROM draft_lesson dl
--     JOIN timetable_draft td ON td.id = dl.draft_id
--    WHERE dl.period_id IS NOT NULL
--      AND NOT EXISTS (SELECT 1 FROM period p WHERE p.id = dl.period_id);
--
-- If that query returns rows the ALTER below fails on purpose: the deployment must
-- stop and the data problem be fixed explicitly. Allowing NULL keeps "unscheduled
-- lesson" (period_id IS NULL) legal, which the scheduler and the publish guard rely on.
ALTER TABLE draft_lesson ADD CONSTRAINT fk_lesson_period
    FOREIGN KEY (period_id) REFERENCES period (id);

CREATE INDEX idx_draft_lesson_period ON draft_lesson (period_id);
