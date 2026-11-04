-- 票 04：把票 03 的删除/7 天撤销扩展到规则、规则模板、草稿、用户（只停用）、
-- AI 供应商/模型和钉钉配置。删除仍是软删除；恢复靠 recent_deletion 的快照。

-- 规则与模板：deleted_at 与 enabled 正交——删除后保留行，让历史引用仍能解析，
-- 但评估快照、列表和保存都必须跳过它。
ALTER TABLE constraint_config ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE constraint_entry ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE constraint_template ADD COLUMN deleted_at TIMESTAMP NULL;

ALTER TABLE timetable_draft ADD COLUMN deleted_at TIMESTAMP NULL;

ALTER TABLE ai_provider ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE ai_model ADD COLUMN deleted_at TIMESTAMP NULL;

-- 供应商/模型删除时任务绑定只停用、不删除，恢复后由用户决定是否重新启用。
ALTER TABLE ai_task_config ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 规则卡/供应商/钉钉这类需要“恢复原样”的实体，把恢复所需的最小信息存进快照。
-- 用 VARCHAR(65535) 与 constraint_template.configs_json 一致：模板快照也存这里。
ALTER TABLE recent_deletion ADD COLUMN snapshot_json VARCHAR(65535) NULL;

CREATE INDEX idx_constraint_config_deleted ON constraint_config (deleted_at);
CREATE INDEX idx_constraint_entry_deleted ON constraint_entry (deleted_at);
CREATE INDEX idx_timetable_draft_deleted ON timetable_draft (deleted_at);
CREATE INDEX idx_ai_provider_deleted ON ai_provider (deleted_at);
CREATE INDEX idx_ai_model_deleted ON ai_model (deleted_at);
