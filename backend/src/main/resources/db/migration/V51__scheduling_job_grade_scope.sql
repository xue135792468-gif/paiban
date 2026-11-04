-- 票 12：按年级排课的任务范围与失败诊断。
-- grade_ids_json 为 NULL 表示全部年级；diagnostics_json 只在硬冲突时写入。
ALTER TABLE scheduling_job ADD COLUMN grade_ids_json TEXT NULL;
ALTER TABLE scheduling_job ADD COLUMN diagnostics_json TEXT NULL;
