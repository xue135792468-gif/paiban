-- 票 03：测试阶段一次性清空旧规则，只保留两条系统底线。
-- 产品所有者 2026-11-07 确认：当前为测试阶段，旧规则可清空。
--
-- 顺序：先删条目再删类型行（两者没有外键，顺序不影响结果），
-- 随后清掉工作区、最近删除与旧迁移映射，最后删掉「规则模板」表。
-- 已发布课表、草稿、调代与 AI 运行记录都不在本文件范围内。

DELETE FROM constraint_entry
WHERE type NOT IN ('TEACHER_CONFLICT', 'CLASS_CONFLICT');

DELETE FROM constraint_config
WHERE type NOT IN ('TEACHER_CONFLICT', 'CLASS_CONFLICT');

-- 两条系统底线不可关闭：恢复被暂停/软删的系统行，缺的按学期补上。
-- 即便数据库没有这两行，ConstraintSettings 也会强制启用它们；这里同步把库补齐，
-- 让规则页保存时能原样带着它们走。
UPDATE constraint_config
SET enabled = TRUE, hard = TRUE, weight = 100, deleted_at = NULL
WHERE type IN ('TEACHER_CONFLICT', 'CLASS_CONFLICT');

INSERT INTO constraint_config (semester_id, type, params_json, weight, hard, enabled)
SELECT s.id, 'TEACHER_CONFLICT', '{}', 100, TRUE, TRUE
FROM semester s
WHERE NOT EXISTS (
    SELECT 1 FROM constraint_config c
    WHERE c.semester_id = s.id AND c.type = 'TEACHER_CONFLICT'
);

INSERT INTO constraint_config (semester_id, type, params_json, weight, hard, enabled)
SELECT s.id, 'CLASS_CONFLICT', '{}', 100, TRUE, TRUE
FROM semester s
WHERE NOT EXISTS (
    SELECT 1 FROM constraint_config c
    WHERE c.semester_id = s.id AND c.type = 'CLASS_CONFLICT'
);

-- 旧规则草稿工作区与「最近删除」里的旧规则/旧模板，清空后恢复没有意义。
DELETE FROM constraint_workspace;

DELETE FROM recent_deletion
WHERE entity_type IN ('CONSTRAINT_RULE', 'CONSTRAINT_TEMPLATE');

-- 旧 26→19 类迁移映射指向已清空的条目，一并清掉；run 历史保留。
DELETE FROM constraint_migration_map;

-- 收敛后不再有「规则模板」这个概念。
DROP TABLE IF EXISTS constraint_template;
