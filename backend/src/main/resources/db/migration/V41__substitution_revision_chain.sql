-- 票 04（课前更换代课人与恢复原安排）：修订链的前驱引用。

-- 修订 = 新的申请 + 新的生效安排（ADR-0030）：批准时只替代本次涉及实例的
-- 当前安排（基准生效行转入 SUPERSEDED 状态、行保留），驳回/过期/校验失败时
-- 旧安排继续生效。revises_request_id 记录「本申请取代哪一笔已生效申请」，
-- 历史里的 A→B 与 B→C 由此可追溯；为空表示首次代课/调课。
ALTER TABLE substitution_request ADD COLUMN revises_request_id BIGINT;
ALTER TABLE substitution_request ADD COLUMN revision_kind VARCHAR(30);
ALTER TABLE substitution_request
    ADD CONSTRAINT fk_request_revises
    FOREIGN KEY (revises_request_id) REFERENCES substitution_request (id);

-- 预览凭证绑定修订基准：创建预览时记录当时生效的基准申请与修订类型，
-- 确认时复核「快照哈希 + 基准申请」——基准被其他管理员抢先替代时明确冲突。
ALTER TABLE substitution_preview ADD COLUMN revises_request_id BIGINT;
ALTER TABLE substitution_preview ADD COLUMN revision_kind VARCHAR(30);
ALTER TABLE substitution_preview
    ADD CONSTRAINT fk_preview_revises
    FOREIGN KEY (revises_request_id) REFERENCES substitution_request (id);
