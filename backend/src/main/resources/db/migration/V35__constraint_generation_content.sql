-- The assistant's content, separate from the provider's full HTTP body (ticket 14/10).
--
-- Re-normalizing a proposal after the user picks a homonym needs the JSON answer the
-- parser read, while the audit still keeps the complete provider body untouched.
ALTER TABLE constraint_generation_run ADD COLUMN model_content TEXT;
