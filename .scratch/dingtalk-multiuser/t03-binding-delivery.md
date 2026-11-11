# 03-C 交付：钉钉绑定的写保护

2026-11-11，分支 `codex/dingtalk-t03-binding`（基于 `3f73700`，已并入 integration tip `fc3b325`）。
**范围只有 C**：绑定保护。不做免登自动开号、登录类型、迁移、前端，也不碰 04。

## 一句话结论

「谁可以把钉钉 UID 首次写到教师档案上」现在只有一条可信路径：**超级管理员在现有教师档案里显式填写 UID**。
通讯录同步（key 命中 / 显式同名合并）与钉钉事件都不得给「关联管理能力账号且尚未绑定」的档案首次绑定，
也不会把已有的非空 UID 改绑成别人。

## 保护口径：按能力，不按角色名

ADR-0006 要求预置角色只用于初始化，权限判断不写角色字符串；而 `UserService.resolveRoles` /
`listRoles` 仍接受库里遗留的自定义角色，V60 也只删了 `head_teacher`。只认 `super_admin` / `admin`
会把「编码不是 admin、却持有 `foundation:manage`」的老角色漏掉，所以：

- `DingTalkBindingPolicy.MANAGEMENT_PERMISSIONS` 是 `Permissions` 常量的集合：
  `user:manage`、`integration:manage`、`foundation:manage`、`school:manage`、`semester:manage`、
  `assignment:manage`、`constraint:manage`、`scheduling:run`、`scheduling:view`、`timetable:edit`、
  `timetable:publish`、`substitution:approve`、`substitution:view`、`settings:manage`、
  `onboarding:manage`、`audit:view:account`。
- **刻意不含老师侧权限**：`timetable:view`、`timetable:view:own`、`assignment:view:own`、
  `substitution:submit`、`substitution:view:own`（预置「老师」角色只有这五个，普通老师照旧自动配对）；
  `export:timetable` 也不当作管理能力。这条边界由公开 HTTP 的「普通老师仍能自动配对」用例钉住。
- 判定只看事实：没有 UID + 关联账号（**启用与停用都算**）持有任一管理权限点。不看教师姓名，
  不用 `Teacher.active` 代替 `account.enabled`。停用管理账号同样受保护，堵「先弱绑定、日后启用再冒用」。
- 端点授权仍然只用 `Permissions` 常量（`@PreAuthorize` 未改一个字符）。

## 写集（6 个文件）

| 文件 | 改动 |
|---|---|
| `identity/service/DingTalkBindingPolicy.java`（新） | `requiresExplicitFirstBinding(Teacher)`；`validateManualBinding(Teacher, String)`（未提交 / 规范化后不变 → 放行；否则要 `integration:manage`）。新增代码用 `StringUtils.hasText`，不新增 `isBlank`。 |
| `identity/domain/AppUserRepository.java` | 只加一条标量查询 `existsByTeacherIdAndRolesPermissionsCodeIn(Long, Collection<String>)`；不把账号实体载入持久化上下文。 |
| `foundation/service/FoundationService.java` | `createTeacher` / `updateTeacher` 先 `accountMutationGuard.lock()`，再 `findById` + `entityManager.refresh`，**校验 UID 变化后才写字段**（`refresh` 在 apply 之前，不会覆盖本次刚写的字段）。 |
| `integration/org/DingTalkSyncPlanner.java` | 第一遍 key 命中、第二遍显式同名 MERGE，遇到受保护未绑定档案**整行 SKIP**，原因固定 `PROTECTED_BINDING_REASON`（「…须超级管理员在教师档案确认…」），`fills` 为空——预演不宣称可补齐；`DingTalkMergeRules` 保持纯字段规则、无 DB 依赖。 |
| `integration/org/DingTalkOrgDataProvider.java` | `applyEvent`：先角色锚点 `accountMutationGuard.lock()` 再匹配；受保护未绑定档案整行返回（连资料也不由事件改）；**只有当前为空白时才写 UID**，非空 UID 永不被事件改绑，其它 metadata 语义保留。`apply`：规划阶段已整行跳过，写入阶段用同一个 policy 再拦一次，命中计入 `skipped`，不留「可偷绑」分支。 |
| 测试 | `integration/BindingProtectionIntegrationTest`（10，公开 HTTP，主验收入口）、`integration/org/DingTalkSyncPlannerTest`（18，构造器加 policy 依赖 + 4 个纯规则保护用例）。 |

## 验收：公开 HTTP 为主，单测为补充

`BindingProtectionIntegrationTest`（独立 H2 `paiban_binding_protection`、fake 钉钉重置、只清自己建的账号与自定义角色）：

1. 教务管理员改 UID → 403，且**同请求里的姓名也没落库**（无半写）；未带 UID / 规范化后相同的普通编辑 → 200。
2. 教务管理员新建带 UID 的教师 → 403；不带 UID → 200；超级管理员显式写 UID → 200。
3. 预演 key 命中受保护未绑定档案 → `SKIP` + 原因，`fills` 空；确认后 UID 仍为 null（**启用 / 停用账号各跑一遍**）。
4. 显式同名 MERGE 受保护档案 → 确认 `updated=0`、`跳过 1`、UID 仍 null。
5. **遗留自定义角色**（编码 `legacy_dean_*`，只持 `foundation:manage`）→ 仍被识别为受保护并跳过。
6. 事件命中受保护未绑定档案 → UID 仍 null 且姓名不被改；事件带不同 UID 命中已绑定档案 → UID 保持原值、手机照常更新。
7. 普通老师（`teacher` 角色）key 命中 → 确认后正常绑上 UID。

施工与敏感性验证（原日志在 `.scratch/dingtalk-multiuser/logs/`，已归档集成`logs/t03-binding/`）：

主线程复核说明：下面的HTTP“红”是实现后临时移除保护的**变异验证**，证明公开HTTP断言能抓到错误；不是施工前的首个TDD红例，不应混称。原worker以BUILD/surefire汇总判断，报告exit0；主线程核实raw计数与BUILD SUCCESS，不把它冒称为自己捕获的子进程退出码。整合后由主线程直接捕获真实Maven退出码再验收。

| 阶段 | 命令 | 结果 |
|---|---|---|
| 红 | 临时 stash 掉 `FoundationService`，`-Dtest=BindingProtectionIntegrationTest#academicAdminCannotChangeTheBindingAndNothingIsHalfWritten` | `t03c-http-red.log`：`Expected status code <403> but was <200>`，Tests run 1, Failures 1 |
| 绿（HTTP） | `-Dtest=BindingProtectionIntegrationTest test` | `t03c-http-green2.log`：Tests run 10, Failures 0 → BUILD SUCCESS |
| 绿（终版定向） | `-Dtest=BindingProtectionIntegrationTest,FoundationIntegrationTest,DingTalkSyncPlannerTest` | `t03c-final.log`：见下节 |
| 闸门 | `bash scripts/check-blank-checks.sh` | `isBlank ok (no new hits; baseline files did not grow)` |

首轮 `t03c-http-green.log` 的 6 个失败全部是我夹具把工号传成了 UID（教师没有 `employeeNo`，于是落到
`SUSPECTED`/新教师），修正夹具后即绿；不是实现问题，已在上面表格中只列修正后的证据。

未跑全量 `scripts/verify.sh --full` 与 e2e（按分片约定留给整合后统一跑）。

## 质量裁决调整：删掉我新建的 policy 单测

首版另建了 `identity/DingTalkBindingPolicyTest`（9 条）。裁决认为它是**镜像内部实现**：把
`hasPermission` / `existsBy…` mock 成 true 再断言 true 或抛错，以及直接断言管理权限集合本身，
和公开 HTTP 已经验证的保护重复。已删除该文件（未删任何旧测试或用户文件），
证据只作历史保留：`logs/t03c-policy-red.log`、`logs/t03c-policy-green.log` 与
`logs/t03c-regression.log`（含那 9 条）**不计入最终门禁**。

保留的测试都在外部行为上：
- 公开 HTTP 10 条覆盖能力口径的全部变体——自定义管理角色、停用管理账号、普通老师、教务 403、
  403 无半写、事件不改绑、显式同名合并不偷绑。
- `DingTalkSyncPlannerTest` 保留纯规则边界（分类结果、`fills` 为空、原因文案、已绑定档案仍可补其它字段）。

新规则再变动，应当从这些公共行为上发现，而不是靠单测断言内部集合。

## UID 写入来源审计（`setDingtalkUserId` 全仓）

`grep setDingtalkUserId backend/src/main` 只有三处：`FoundationService.apply`（人工，已加锁 + 权限校验）、
`DingTalkMergeRules.apply`（同步，仅 planner 判过的 MERGE 项会走到，planner 已排除受保护档案，provider 再拦一次）、
`DingTalkOrgDataProvider.applyEvent`（事件，已改为只填空白 UID）。

- `FoundationImportService` 创建教师传 `dingtalkUserId = null`：**没有**独立 UID 列，不宣称它受额外保护。
- `ai/imports/ImportMappingService.createTeacher` 直接 `new Teacher()` 只设姓名，不写 UID。
- 因此无人值守弱匹配无法先绑定、再绕过免登保护。

## 边界与移交

- 未改：`UserService`、`AuthService`、`IntegrationController`、`DingTalkClient`/`Http`/`Fake` Adapter、
  登录 DTO、迁移、04 文档、前端（B 另人）。
- 未新增审批队列：本版唯一可信的首次绑定就是超级管理员在教师档案里显式填 UID（`TeachersView` 的
  输入与只读限制由 B 负责）。
- 给 A：直接复用 `DingTalkBindingPolicy.requiresExplicitFirstBinding`（A2 的受保护档案唯一同名 →
  `ADMIN_BINDING_REQUIRED`），不要复制管理员/权限判定。A 的「先计数再检查保护」顺序不受本票影响：
  policy 只在候选确定后调用。
