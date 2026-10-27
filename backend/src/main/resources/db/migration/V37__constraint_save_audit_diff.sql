-- The audit's own proof of the effective change (ticket 14/10 review): the
-- configuration before the save and the computed structural diff, next to the final
-- configuration and the user's proposal decisions.
ALTER TABLE constraint_save_audit ADD COLUMN previous_config_json TEXT;
ALTER TABLE constraint_save_audit ADD COLUMN diff_json TEXT;
