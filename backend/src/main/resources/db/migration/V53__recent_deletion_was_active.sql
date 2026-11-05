-- 票 03 复查：恢复时不要再把删除前已停用的对象误启用。
--
-- NULL 表示旧记录（升级前写入）没有快照，恢复时按“启用”处理，与旧行为一致。

ALTER TABLE recent_deletion ADD COLUMN was_active BOOLEAN NULL;
