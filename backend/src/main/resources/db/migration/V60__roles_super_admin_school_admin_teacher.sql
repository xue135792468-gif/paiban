-- 票 01：预置角色重整为 超级管理员 / 教务管理员 / 老师，班主任下线。
--
-- 升级语义（规格「角色与权限」）：
--   * 旧 `admin` 角色持有者 -> 超级管理员，保留原有全部授权；`admin` 编码保留给教务管理员。
--   * 旧 `head_teacher` 持有者 -> 老师；随后删除班主任角色。
--   * 只改角色与授权，不动课表、调代、设置等任何业务数据。
--   * 老库可能没有角色行（全新库由启动种子创建），因此每条语句都是「有则改、无则跳过」。
-- 新权限点「账号审计查看」只给超级管理员。

insert into permission (code, name)
select 'audit:view:account', '账号审计查看'
from (select 1) anchor
where not exists (select 1 from permission where code = 'audit:view:account');

insert into role (code, name)
select 'super_admin', '超级管理员'
from (select 1) anchor
where not exists (select 1 from role where code = 'super_admin');

update role set name = '超级管理员' where code = 'super_admin';
update role set name = '教务管理员' where code = 'admin';
update role set name = '老师' where code = 'teacher';

-- 超级管理员：拥有权限目录里的全部权限点（含本次新增的审计查看）。
insert into role_permission (role_id, permission_id)
select super_role.id, p.id
from role super_role
join permission p on 1 = 1
where super_role.code = 'super_admin'
  and not exists (select 1 from role_permission rp
                  where rp.role_id = super_role.id and rp.permission_id = p.id);

-- 教务管理员：全部减去「用户与角色管理」「集成配置」「账号审计查看」。
delete from role_permission
where role_id = (select id from role where code = 'admin')
  and permission_id in (select id from permission
                        where code in ('user:manage', 'integration:manage', 'audit:view:account'));

-- 老师：新增只读的「查看全部课表」。
insert into role_permission (role_id, permission_id)
select teacher_role.id, p.id
from role teacher_role
join permission p on p.code = 'timetable:view'
where teacher_role.code = 'teacher'
  and not exists (select 1 from role_permission rp
                  where rp.role_id = teacher_role.id and rp.permission_id = p.id);

-- 本班课表 / 本班调代权限点保留在权限目录里，但不分配给任何预置角色。
delete from role_permission
where role_id in (select id from role where code in ('super_admin', 'admin', 'teacher'))
  and permission_id in (select id from permission
                        where code in ('timetable:view:class', 'substitution:view:class'));

-- 旧 admin 持有者 -> 超级管理员（不只是 username = 'admin' 的账号）。
insert into user_role (user_id, role_id)
select holder.user_id, (select id from role where code = 'super_admin')
from user_role holder
where holder.role_id = (select id from role where code = 'admin')
  and not exists (select 1 from user_role existing
                  where existing.user_id = holder.user_id
                    and existing.role_id = (select id from role where code = 'super_admin'));

delete from user_role
where role_id = (select id from role where code = 'admin');

-- 班主任持有者 -> 老师，然后下线班主任角色。
insert into user_role (user_id, role_id)
select holder.user_id, (select id from role where code = 'teacher')
from user_role holder
where holder.role_id = (select id from role where code = 'head_teacher')
  and not exists (select 1 from user_role existing
                  where existing.user_id = holder.user_id
                    and existing.role_id = (select id from role where code = 'teacher'));

delete from user_role
where role_id = (select id from role where code = 'head_teacher');

delete from role_permission
where role_id = (select id from role where code = 'head_teacher');

delete from role where code = 'head_teacher';
