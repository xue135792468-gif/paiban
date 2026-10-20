# 02: 本地认证与 RBAC 三角色

**What to build:** 实现基于 RBAC 的认证授权体系：用户/角色/权限三表 + 关联表，预置教务管理员（admin）、教师（teacher）、班主任（head_teacher）三个角色及其权限点；登录接口颁发 JWT；Spring Security + `@PreAuthorize` 完成接口鉴权；前端登录页与路由守卫。完成后，不同角色登录看到不同菜单，无权限访问接口返回 403。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 数据库迁移脚本包含 user/role/permission/user_role/role_permission 五张表
- [ ] 预置三个角色及其权限点（权限点命名遵循 `<resource>:<action>` 规范）
- [ ] POST `/api/auth/login` 返回 JWT，过期时间可配置
- [ ] Spring Security 配置完成，所有 `/api/**`（除 `/api/auth/login` 与 `/api/health`）需认证
- [ ] 至少一个接口用 `@PreAuthorize("hasAuthority('xxx')")` 验证权限生效
- [ ] 前端登录页 + 路由守卫：未登录跳转登录页，无权限路由显示 403
- [ ] 管理员可在 UI 上创建用户并分配角色（仅 admin 可见"用户管理"菜单）
- [ ] REST Assured 集成测试：三种角色分别登录，验证各自能访问/不能访问的接口
- [ ] 权限模型设计遵循 ADR-0006（RBAC 三表结构，预置角色，不写死 if-else）
