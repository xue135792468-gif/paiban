-- Ticket 10/05: a template has to hold every entry of a semester, so the original
-- 8000-character limit cannot carry a realistic one (200 teacher-slot entries alone are
-- ~12k characters).
--
-- 65535 is the widest VARCHAR a row can hold on both databases this project runs on
-- (H2 in PostgreSQL mode and real PostgreSQL), and `SET DATA TYPE` is accepted by both
-- dialects — unlike `TYPE`, which H2 rejects. The service keeps an explicit refusal
-- above the new limit, so an oversized template still fails loudly instead of being
-- truncated.
ALTER TABLE constraint_template ALTER COLUMN configs_json SET DATA TYPE VARCHAR(65535);
