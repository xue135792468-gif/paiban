-- 票 03 的 V56 清空规则后，推进每个学期的配置版本，让部署时仍打开的旧规则页
-- 带着旧 baseVersion 保存时得到 409，而不是把旧规则写回来（Hark 复审 P2-6）。
--
-- 只动已有行的版本号：没有行表示该学期从未保存过规则，读出来就是 0，
-- 也不存在“旧页面带着更旧规则”的情况。H2 与 PostgreSQL 都支持这条语句。
UPDATE constraint_config_state
SET version = version + 1,
    updated_at = CURRENT_TIMESTAMP;
