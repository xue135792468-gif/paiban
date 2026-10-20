# 权限模型：一期用 RBAC 三表结构，预置 3 角色，二期开放自定义角色

数据库采用标准 RBAC：`User - Role - Permission`（User 与 Role 多对多，Role 与 Permission 多对多）。一期预置 3 个角色：

- **教务管理员**：全权
- **教师**：查看个人课表、提交调代申请
- **班主任**：教师权限 + 查看本班课表 + 接收本班调代通知

一期 UI 只提供"给用户分配预置角色"，不提供"自定义角色"界面。二期开放自定义角色时仅需新增前端页面与少量管理接口，数据库与权限校验逻辑零改动。

## 为什么

- **写死角色 = 二期重做**：如果用 `if user.role == "admin"` 硬编码，二期开放自定义角色时需要全代码库重构权限判断点。
- **RBAC 是行业事实标准**：Spring Security 原生支持，权限点用 `@PreAuthorize("hasAuthority('timetable:publish')")` 声明，清晰且易测。

## Consequences

- 权限点（Permission）的字典要在一期就设计得尽量正交（资源 × 动作），例如 `timetable:read`、`timetable:publish`、`substitution:create`、`substitution:approve`
- 预置角色只是初始化数据，不写入代码逻辑
