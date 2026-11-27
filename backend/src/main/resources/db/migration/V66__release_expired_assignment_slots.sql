-- Keep historical assignment ids available to drafts after the undo window closes.
ALTER TABLE assignment ADD COLUMN recycle_slot INTEGER DEFAULT 1;
ALTER TABLE assignment ADD CONSTRAINT ck_assignment_recycle_slot
    CHECK ((recycle_slot IS NOT NULL AND recycle_slot = 1) OR (recycle_slot IS NULL AND deleted_at IS NOT NULL));
ALTER TABLE assignment DROP CONSTRAINT uk_assignment_class_course;
ALTER TABLE assignment ADD CONSTRAINT uk_assignment_class_course
    UNIQUE (semester_id, teaching_class_id, course_id, recycle_slot);

UPDATE assignment SET recycle_slot = NULL
WHERE deleted_at IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM recent_deletion r
    WHERE r.entity_type = 'ASSIGNMENT' AND r.entity_id = assignment.id
        AND r.restored_at IS NULL
);
