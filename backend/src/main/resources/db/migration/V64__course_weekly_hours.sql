ALTER TABLE course ADD COLUMN weekly_hours INTEGER;

ALTER TABLE course ADD CONSTRAINT ck_course_weekly_hours
    CHECK (weekly_hours BETWEEN 1 AND 20);
