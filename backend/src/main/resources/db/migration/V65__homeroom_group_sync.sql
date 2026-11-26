CREATE TABLE school_data_write_lock (
    id INTEGER PRIMARY KEY CHECK (id = 1)
);
INSERT INTO school_data_write_lock (id) VALUES (1);

ALTER TABLE rule_group ALTER COLUMN name TYPE VARCHAR(103);
