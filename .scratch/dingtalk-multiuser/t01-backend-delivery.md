# 票01：后端 / 迁移 / 种子切片

2026-11-11，工作树 `.worktrees/dingtalk-t01`，分支 `codex/dingtalk-t01`。
开工基点 `2cd0215`（= 集成 tip），提交前并入集成 tip `b798b6d`。

## 变更

- `identity/domain/Permissions.java`：新增 `audit:view:account`（账号审计查看）。
- `identity/config/IdentityDataInitializer.java`：三角色种子（`super_admin` / `admin` / `teacher`），每次启动只重写角色→权限，不改已有账号角色；不再产出班主任。
- `V60__roles_super_admin_school_admin_teacher.sql`：旧 `admin` 持有者 → `super_admin`，旧 `head_teacher` → `teacher`，删除班主任角色，三角色剔除退休的 `timetable:view:class` / `substitution:view:class`。开工前扫描 `main`/`origin/main`/`feature/rule-groups` 与全部 worktree，最高 V59，全仓无 V60+ 占用。SQL SHA256 `56B63420B786D2D57761FDEA443E16684C083D68E312569F6CD467448F3C42A0`。
- `identity/service/UserService.java` + `AppUserRepository` + `RoleRepository`：最后一个启用超管的降级/停用保护。锚点锁 `super_admin` 角色行 → `EntityManager.refresh` 目标账号 → 数据库标量 count（不读一级缓存）；`update()` 校验后在同一 managed 实体上改姓名/教师/角色/启用状态，停用与启用只在私有 `applyDisable/applyEnable` 落值与审计。管理类通知收件人 `adminUsers()` 同时含两种管理员角色。
- `identity/service/UserImportService.java`：账号导入角色名单改为 超级管理员/教务管理员/老师，班主任按非法角色拒绝。
- `notification/service/NotificationService.java`：课表发布通知的管理端判据由 `timetable:view` 改为 `timetable:edit`。
- 测试新增 `RoleReshapeIntegrationTest`、`RolePermissionMatrixIntegrationTest`、`SuperAdminGuardIntegrationTest`；适配 `AuthRbacIntegrationTest`（含复合更新回归）、`IdentityDataInitializerTest`、`UserImportIntegrationTest`、`TimetablePublishIntegrationTest`、`SubstitutionIntegrationTest`、`AdminSubstitutionIntegrationTest`。
- `docs/adr/0035-super-admin-and-school-admin-roles.md`：取代 ADR-0006 的预置角色名单（原文未改）。
- 越权核对清单写在票面 `issues/01-roles-reshape.md` 的实现说明里。

## 红绿证据（真实执行，均在 `source .tools/env.sh` 后 `cd backend`，H2，`-DforkCount=0`）

- `-Dtest=RoleReshapeIntegrationTest`：红 4 项 4 失败（三角色/权限集全部未实现）→ 实现后绿 4/4。
- `-Dtest=SuperAdminGuardIntegrationTest#stalePreloadedSnapshotCannotDisableTheLastEnabledSuperAdmin`：红 1 项 1 失败——`expected: "CONFLICT" but was: "DISABLED"`（实体查询读到一级缓存里的旧 `enabled = true`，把最后一位启用超管停掉）→ 换成数据库标量 count 后绿（本类 4/4）。
- 终版定向批次 10 个类：`AuthRbacIntegrationTest 10`、`IdentityDataInitializerTest 6`、`RolePermissionMatrixIntegrationTest 6`、`RoleReshapeIntegrationTest 4`、`SettingsPermissionIntegrationTest 4`、`SuperAdminGuardIntegrationTest 4`、`UserImportIntegrationTest 7`、`AdminSubstitutionIntegrationTest 10`、`SubstitutionIntegrationTest 9`、`TimetablePublishIntegrationTest 5` = 65 项，0 失败，0 错误，0 跳过，退出码 0。
- 设置字段隔离与前端切片的数字不在此重复：见 `t01-settings-delivery.md`、`t01-ui-delivery.md`。

## 未验证 / 由后续承接

- 未运行真实 PostgreSQL（开发机无 Docker）：V60 只在 H2(PostgreSQL 模式) 上跑通；旧库保全断言由主模型的 `RoleUpgradeDataPreservationTest` 在合并本分支后复核。
- 未运行 E2E / Playwright，也未运行全量 `verify.sh`（按票面约定留给整链与复查）。
- 账号审计的 HTTP 矩阵在票 02 补齐；调代审批的 HTTP 矩阵在票 05 恢复——本票只用权限点钉住，未造 stub 端点。
- 未接触真实钉钉、未读取任何密钥。
- 前端菜单 / 改身份三项 / E2E 不在本切片写集合内。
