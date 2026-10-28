-- 票 05（管理员同班调课方案与跨日对照）：明确目标日期 + 管理端置换环方案 + SWAP 预览凭证。

-- SWAP 申请带明确目标实际日期：管理端调课必须落在管理员指定的日期上，
-- 绝不静默替换成同星期的另一周。旧教师自助路径该列为空，继续按星期推算，语义不变。
ALTER TABLE substitution_request ADD COLUMN target_date DATE;

-- 管理端调课的候选方案在预览阶段没有申请（取消预览、切换方案、无解时零申请），
-- 提交审批/亲自确认时才关联申请；request_id 从「创建即必有」放宽为「批准时必有」。
ALTER TABLE substitution_plan ALTER COLUMN request_id DROP NOT NULL;

-- SWAP 预览凭证没有代课教师，改为绑定选定方案（plan_id）；凭证生成与消费
-- 都不写申请、不改课表、不发通知（沿用票 03 的凭证合同与幂等语义）。
ALTER TABLE substitution_preview ALTER COLUMN substitute_teacher_id DROP NOT NULL;
ALTER TABLE substitution_preview ADD COLUMN plan_id BIGINT;
