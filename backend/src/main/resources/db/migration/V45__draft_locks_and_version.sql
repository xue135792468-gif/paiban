ALTER TABLE timetable_draft ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE draft_lesson ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX uk_draft_lesson_identity ON draft_lesson (draft_id, assignment_id, lesson_index);
CREATE INDEX idx_draft_lesson_locked ON draft_lesson (draft_id, locked);
