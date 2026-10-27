-- Shared configuration version (ticket 14/07).
--
-- Every entry point that changes the effective configuration (manual save, template
-- apply, first configuration) advances this one row. A save carries the version it was
-- based on, so a second writer cannot overwrite the first without seeing it. The row is
-- also the optimistic lock: Hibernate increments `version` on every update, and the
-- loser of a true race gets a conflict instead of silently winning.
--
-- Existing semesters start at 0; a semester without a row reads as version 0 as well.
CREATE TABLE constraint_config_state (
    semester_id BIGINT PRIMARY KEY,
    version     BIGINT    NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    CONSTRAINT fk_constraint_state_semester
        FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE CASCADE
);

INSERT INTO constraint_config_state (semester_id, version, updated_at)
SELECT DISTINCT semester_id, 0, CURRENT_TIMESTAMP FROM constraint_config;

INSERT INTO constraint_config_state (semester_id, version, updated_at)
SELECT DISTINCT e.semester_id, 0, CURRENT_TIMESTAMP
FROM constraint_entry e
WHERE NOT EXISTS (
    SELECT 1 FROM constraint_config_state s WHERE s.semester_id = e.semester_id
);
