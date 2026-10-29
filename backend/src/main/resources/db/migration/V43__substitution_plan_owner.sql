-- 评审修复（P2b）：管理端候选方案按操作者隔离。
-- 重新生成候选只清理「本人上一批」试算方案，避免并发管理员的候选被互删
-- （被删方案所绑定的预览凭证随后会报「方案不存在」）。
-- 教师自助路径（有 request_id 的方案）不参与试算清理，该列为空。
ALTER TABLE substitution_plan ADD COLUMN owner_user_id BIGINT REFERENCES app_user(id);

CREATE INDEX idx_sp_owner_status ON substitution_plan(owner_user_id, status);
