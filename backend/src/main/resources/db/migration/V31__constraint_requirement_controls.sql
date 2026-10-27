-- Per-requirement controls (ticket 14/01, ADR-0028 revision).
--
-- A requirement may span several entry rows: "体育每天第1、2节不排" is one requirement
-- that expands into concrete slots. `group_key` is that requirement's stable identity;
-- rows created before this migration have no group and stay individual requirements.
--
-- The type row keeps the switch and the defaults. Each entry may pause itself, or
-- override the type's hard/soft choice and soft weight; NULL means "inherit the type"
-- so an upgraded configuration evaluates exactly as before.
ALTER TABLE constraint_entry ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE constraint_entry ADD COLUMN hard BOOLEAN;
ALTER TABLE constraint_entry ADD COLUMN weight INT;
ALTER TABLE constraint_entry ADD COLUMN group_key VARCHAR(64);

CREATE INDEX idx_constraint_entry_group ON constraint_entry (semester_id, type, group_key);
