# 01: 角色重整（超级管理员 / 教务管理员 / 老师）

**What to build:** 预置角色改为超级管理员、教务管理员、老师三种。超级管理员全权；教务管理员 = 全部减去「用户与角色管理」「集成配置」「账号审计查看」；老师在现有权限上加「查看全部课表」只读。班主任下线。升级后原 `admin` 账号是超级管理员。账号页「改身份」只给三个选项，可把老师提升为管理员。见 spec「角色与权限」。

**Blocked by:** —

**Status:** done

- [x] 新增权限点「账号审计查看」，只给超级管理员
- [x] 迁移：原 `admin` 账号 → 超级管理员；持有班主任角色的账号 → 老师；删除班主任角色（版本号按 AGENTS.md 续号，当前 rule-groups 占 V56–V58、计划 V59）
- [x] 种子数据（每次启动幂等）只产出三个角色，不再写回班主任
- [x] 教务管理员访问账号页、集成配置、审计查看接口返回 403；前端菜单不显示
- [x] 老师可只读打开全校课表（班级 / 教师视角）；不显示草稿、规则、设置入口
- [x] **越权核对清单**：列出所有把「查看全部课表」与其他权限组合判断管理能力的位置（含调代代发组合、通知 `onTimetablePublished` 过滤等），逐条说明老师不因此获得管理能力，写入实现说明
- [x] 账号页改身份只列三项；不能降级或停用最后一个启用的超级管理员
- [x] 权限矩阵集成测试：三身份 × 账号管理 / 集成配置 / 审计 / 全校课表 / 审批 / 草稿编辑
- [x] 迁移数据保留测试（参照 `TrimMigrationDataPreservationTest`）
- [x] 新 ADR：取代 ADR-0006 的三个预置角色

## 实现说明（后端 / 迁移 / 种子）

### 角色与权限

- 新权限点 `audit:view:account`（账号审计查看），只由超级管理员持有；审计读取接口在票 02 落地，
  01 只断言权限点（`GET /api/auth/me`）。
- 种子（`IdentityDataInitializer`）每次启动只重写「角色 → 权限」对应关系：
  `super_admin`（全部权限减去两个退休的按本班查看）、`admin`（再减账号管理 / 集成配置 / 审计查看）、
  `teacher`（个人课表 + 本人授课任务 + 提交调代 + 本人调代记录 + 只读 `timetable:view`）。
  已有账号的角色**不再被种子改动**，否则重启会把被降级的教务管理员升回超管。
- 首次启动的 `admin` 账号现在拿 `super_admin` 角色。

### 迁移 V60（`V60__roles_super_admin_school_admin_teacher.sql`）

- 版本号：开工前扫描 `main` / `origin/main` / `feature/rule-groups` / 全部 worktree，最高为 **V59**，
  且全仓 `git log --all --diff-filter=A` 无 V60+ 文件 → 本票取 **V60**。旧迁移一律未改。
- 语义：`super_admin` 拿全部权限点；`admin` 去掉三项并改名为「教务管理员」；`teacher` 补 `timetable:view`
  并剔除两个按本班查看权限点；旧 `admin` 持有者 → `super_admin`（保留旧授权，而不是只处理 username=admin）；
  旧 `head_teacher` 持有者 → `teacher`；最后删除 `head_teacher` 角色与其授权行。
- 每条语句「有则改、无则跳过」（子查询取不到角色 id 时整条语句不生效），所以全新库（角色由启动种子创建）
  与老库升级都安全；不触碰任何业务表。
- 旧库升级的保全断言由主模型在 `RoleUpgradeDataPreservationTest` 复核（V59 造数 → V60 迁移），
  本票只读引用，不重复实现。

### 最后一个启用超管的并发保护（`UserService`）

- 降级与停用共用同一把锚点：`RoleRepository.findByCodeForUpdate("super_admin")` 先锁稳定的角色行
  （超管集合为空时 app_user 上没有行可锁），再 `EntityManager.refresh` 刷新目标账号，
  最后用数据库标量 `count(... where enabled and id <> :target)` 判定，避免读到持久化上下文里的旧 `enabled`。
- `update()` 先锁、再读、再校验、最后在**同一个** managed 实体上改姓名 / 教师 / 角色 / 启用状态，
  中途不再 refresh，复合更新不会丢字段；停用与启用只在私有 `applyDisable/applyEnable` 落值与审计。

### 「查看全部课表」越权核对清单

老师新增 `timetable:view` 后，全仓所有出现点逐条核对（`grep TIMETABLE_VIEW backend/src/main/java`）：

| 位置 | 判断方式 | 老师是否获得管理能力 |
|---|---|---|
| `TimetableController.classView` / `teacherView` | `TIMETABLE_VIEW`（canAll）∨ `TIMETABLE_VIEW_CLASS`（本班）∨ `TIMETABLE_VIEW_OWN`（本人） | 否：只放开只读视图，读的是已发布基线 + 调代叠加，不返回草稿 |
| `TimetableController.schoolView` | 仅 `TIMETABLE_VIEW` | 否：规格要求的「只读查看全校课表」本身就是这个权限 |
| 草稿 / 发布 / 导出（`/api/timetable-drafts/**`、`publish`、`withdraw`、`export/*`） | `TIMETABLE_EDIT` / `TIMETABLE_PUBLISH` / `EXPORT_TIMETABLE` | 否：老师一个都没有，测试断言 403 |
| `AdminSubstitutionService.MANAGER_PERMISSIONS`（工作台代发、代人提交） | `TIMETABLE_VIEW` + `SUBSTITUTION_SUBMIT` + `SUBSTITUTION_APPROVE` 三项全部具备 | 否：老师缺 `SUBSTITUTION_APPROVE`；`RolePermissionMatrixIntegrationTest` 与 `AdminSubstitutionIntegrationTest.plainTeacherCannotUseTheManagerWorkbench` 双重断言 403 |
| `SubstitutionPlanService.plansFor`（读方案） | 端点 `SUBSTITUTION_SUBMIT` + 服务内「`SUBSTITUTION_APPROVE` 或本人是申请人」 | 否：老师只能读到自己提交的方案 |
| `SubstitutionPlanService.swapTargets`（为他人课节选目标节次） | `AdminSubstitutionService.MANAGER_PERMISSIONS` 三项全部具备：`timetable:view` + `substitution:submit` + `substitution:approve` | 否：老师缺 `substitution:approve` |
| 置换方案确认（`AdminSwapService.requireManager`，经工作台控制器） | 同上 `MANAGER_PERMISSIONS` 三项 | 否：同上 |
| `SubstitutionRequestService.create/list/detail/history` | `SUBSTITUTION_VIEW`（全局）或本人 / 本班教师关系 | 否：老师只能看到本人记录（`/api/substitutions/my`），`/api/substitutions` 返回 403 |
| `NotificationService.onTimetablePublished` | 曾用 `teacherId != null \|\| TIMETABLE_VIEW` | **已修**：改为 `teacherId != null \|\| TIMETABLE_EDIT`，否则任何只读账号都会被当成管理端收件人 |
| `NotificationService` 管理类收件人 `adminUsers()` | 角色持有者 | **已修**：同时包含 `admin` 与 `super_admin`，否则升级后运维通知失去收件人 |
| `ImportPermissions`（AI / 手动导入） | `TIMETABLE_EDIT` / `SEMESTER_MANAGE` | 否：老师没有 |
| `ConstraintAuditService` / `ConstraintProposalService` / `AiProviderController` / `AiTaskConfigController` | `SETTINGS_MANAGE` | 否：老师没有 |
| `SettingsController` GET / PUT / logo 返回 | `SETTINGS_MANAGE` + 字段级 `INTEGRATION_MANAGE` | 否：教务可进设置页但钉钉字段被服务端剔除（该切片由主模型独立实现并覆盖） |

### 测试与证据

- 新增：`RoleReshapeIntegrationTest`（三角色与权限集）、`RolePermissionMatrixIntegrationTest`（三身份 × 账号 / 集成 / 审计权限点 / 全校课表 / 草稿 / 工作台组合 / 设置）、
  `SuperAdminGuardIntegrationTest`（降级、停用、旧快照确定性交错、并发不变量）。
- 适配：`AuthRbacIntegrationTest`、`IdentityDataInitializerTest`、`UserImportIntegrationTest`、
  `TimetablePublishIntegrationTest`、`SubstitutionIntegrationTest`、`AdminSubstitutionIntegrationTest`
  中依赖班主任身份或用 `timetable:view` 当管理判据的旧断言。
- 定向命令（H2，`-DforkCount=0`）：见下方交付回执；**真实 PostgreSQL 与真实钉钉未运行**。
- 审计 HTTP 矩阵在票 02 补齐、审批 HTTP 矩阵在票 05 恢复，本票只用权限点钉住（不为它们造 stub 端点）。
- 前端菜单 / 改身份三项 / E2E 由前端切片 `t01-ui-delivery` 承接，设置字段隔离由 `t01-settings-delivery` 承接。

## Comments

2026-11-13：源码完成、双轴及整改复核关闭，最后冻结完整verify通过，状态done。仅完成本地实现与验收，未推送/部署；原始失败、直接退出/源码收据及外部验收边界见[最终验收](../final-acceptance.md)。
