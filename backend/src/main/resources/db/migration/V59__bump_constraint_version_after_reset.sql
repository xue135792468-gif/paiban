-- 票 03 的 V56 清空规则后，推进每个学期的配置版本，让部署时仍打开的旧规则页
-- 带着旧 baseVersion 保存时得到 409，而不是把旧规则写回来（Hark 复审 P2-6）。
--
-- 生产实际存在“有规则、但 constraint_config_state 没有行”的学期（V32 之后从未
-- 通过页面保存过）：这类学期读出来是版本 0，只 UPDATE 不到它们。因此两步：
--   1) 已有行统一 +1；
--   2) 有规则行但没有版本行的学期补一行 version=2。
-- 两步之后，旧页面手里的 version=0 一定落后于当前版本；新页面读到 2。
UPDATE constraint_config_state
SET version = version + 1,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO constraint_config_state (semester_id, version, updated_at)
SELECT DISTINCT c.semester_id, 2, CURRENT_TIMESTAMP
FROM constraint_config c
WHERE NOT EXISTS (
    SELECT 1
    FROM constraint_config_state s
    WHERE s.semester_id = c.semester_id
);
