-- Ticket 07: explicit wire protocol per provider (openai / anthropic / gemini).
ALTER TABLE ai_provider ADD COLUMN protocol VARCHAR(20) NOT NULL DEFAULT 'openai';

-- Existing Gemini rows were recognised by URL before the explicit column existed.
UPDATE ai_provider
SET protocol = 'gemini'
WHERE LOWER(base_url) LIKE '%generativelanguage%'
   OR LOWER(base_url) LIKE '%gemini%';
