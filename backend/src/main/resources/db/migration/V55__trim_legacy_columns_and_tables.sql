-- 票 15：契约收口，物理删除旧代码之后清理遗留的表与列。
--
-- 只加不改：V1–V54 保持原样，本文件是 V55。
-- 删表前请按 docs/runbooks/deploy.md 先做数据库备份并验证 gzip。
-- 顺序：先删依赖的索引/约束，再删列，最后删表。
-- 与 ddl-auto=validate 一致：所有 DROP 的字段都已从对应实体删除。
--
-- 明确保留（不要 DROP）：
--   notification / notification_log（Q7：功能下线、数据先留着；钉钉仍写 notification_log）
--   ai_call_log / ai_import_log（主链运行时日志与导入会话状态机）
--   recent_deletion / import_recipe / timetable_publish（7 天撤销、导入配方、发布撤回）
--   substitution_request.operator_user_id 等直接生效字段
--   substitution_preview.deadline_at（Q5 保留：开课截止是直接生效的时间护栏）
--   substitution_plan.approved_at / approved_by（实体仍保留）
--   substitution_plan_move 的其余业务列、draft_lesson.locked（锁本身保留）
--   constraint_config / constraint_entry 里已停用的 8 类旧规则行（Q6：留行可查）
--   notification_log.platform（Q7：表留着，列不强制清）

-- 1) 教室整套：先删引用列的索引与约束
DROP INDEX IF EXISTS idx_entry_room;
DROP TABLE IF EXISTS room;

ALTER TABLE course DROP COLUMN IF EXISTS required_room_type;
ALTER TABLE course DROP COLUMN IF EXISTS require_outdoor;
ALTER TABLE draft_lesson DROP COLUMN IF EXISTS room_id;
ALTER TABLE timetable_entry DROP COLUMN IF EXISTS room_id;
ALTER TABLE substitution DROP COLUMN IF EXISTS new_room_id;
ALTER TABLE substitution_plan_move DROP COLUMN IF EXISTS room_id;

-- 2) 作息：只保留节次与时间
ALTER TABLE period DROP COLUMN IF EXISTS section;

-- 3) 飞书整套：先删唯一索引再删列
DROP INDEX IF EXISTS uk_teacher_feishu_user_id;
ALTER TABLE teacher DROP COLUMN IF EXISTS feishu_user_id;
ALTER TABLE teacher DROP COLUMN IF EXISTS feishu_open_id;

-- 4) 审批链 / 修订链 / 历史转换：先删自引用外键，再删列，最后删转换表
ALTER TABLE substitution_request DROP CONSTRAINT IF EXISTS fk_request_revises;
ALTER TABLE substitution_preview DROP CONSTRAINT IF EXISTS fk_preview_revises;

ALTER TABLE substitution_request DROP COLUMN IF EXISTS approval_mode;
ALTER TABLE substitution_request DROP COLUMN IF EXISTS approver_id;
ALTER TABLE substitution_request DROP COLUMN IF EXISTS approved_at;
ALTER TABLE substitution_request DROP COLUMN IF EXISTS approval_comment;
ALTER TABLE substitution_request DROP COLUMN IF EXISTS revises_request_id;
ALTER TABLE substitution_request DROP COLUMN IF EXISTS revision_kind;

ALTER TABLE substitution_preview DROP COLUMN IF EXISTS revises_request_id;
ALTER TABLE substitution_preview DROP COLUMN IF EXISTS revision_kind;

DROP INDEX IF EXISTS idx_sub_conv_item_batch;
DROP INDEX IF EXISTS idx_sub_conv_semester;
DROP TABLE IF EXISTS substitution_conversion_item;
DROP TABLE IF EXISTS substitution_conversion;
