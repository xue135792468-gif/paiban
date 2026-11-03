CREATE TABLE draft_cycle_plan_token (
  token VARCHAR(64) PRIMARY KEY,
  draft_id BIGINT NOT NULL,
  plan_json VARCHAR(4000) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP NULL,
  used_version BIGINT NULL
);

CREATE INDEX idx_cycle_token_draft ON draft_cycle_plan_token (draft_id);
