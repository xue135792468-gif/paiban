-- 票 02：账号操作审计落在现有 audit_log 上，不新造平行审计表。
--
-- 目标：
--   * category = 'ACCOUNT' 把账号事件从通用基础日志里分流出来：旧接口
--     GET /api/foundation/audit-logs 只读非账号事件，账号审计页只读 ACCOUNT。
--   * target_user_id 是稳定的目标账号 ID（账号从不物理删除，ID 可长期追随）。
--   * before_json / after_json 只存 allowlist 快照（账号标识、姓名、身份、启用、
--     关联教师），不存密码、哈希、token 或原请求。
-- 旧日志全部保留；既有账号事件按原 entity_type / action 归入 ACCOUNT。

alter table audit_log add column category varchar(20);
alter table audit_log add column target_user_id bigint;
alter table audit_log add column before_json text;
alter table audit_log add column after_json text;

-- 账号审计页按「分类 + 时间倒序 + id」读，索引跟查询同序。
create index idx_audit_category_created_id on audit_log (category, created_at, id);

-- 旧事件归类：UserService 的启停用 APP_USER，批量导入账号的摘要用 users / IMPORT。
update audit_log
set category = 'ACCOUNT'
where entity_type = 'APP_USER'
   or (entity_type = 'users' and action = 'IMPORT');
