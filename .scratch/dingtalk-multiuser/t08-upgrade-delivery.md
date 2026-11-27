# 08 存量升级切片交付：V59 旧库 → 全部当前迁移

**分支**：`codex/dingtalk-t08-upgrade`（唯一工作树 `E:/排课系统/.worktrees/dingtalk-t08-upgrade`）
**基线**：`016859b`（08 施工起点，07 双轴已放行）
**交付前合并**：`codex/dingtalk-multiuser` → `26edaa6`（fast-forward，无冲突）
**范围**：只新增一个独立升级测试与本交付记录，不改产品代码、不改 V1–V63 任何迁移、不改他人测试、不改 HANDOFF 与票面。

## 写集

| 文件 | 性质 |
|---|---|
| `backend/src/test/java/com/paiban/acceptance/OldDatabaseUpgradeAcceptanceTest.java` | 新增（唯一源码改动） |
| `.scratch/dingtalk-multiuser/t08-upgrade-delivery.md` | 新增（本文件） |
| `.runtime/logs/t08-upgrade-run{1,2,3,4-postmerge}.log` | 本地原始日志，`.runtime/` 已被 `.git/info/exclude` 排除，不入库但保留在盘上 |

## 输入源（实际用到的四份）

1. `RoleUpgradeDataPreservationTest#seedOldSchool`（第 96 行起）：V59 建旧库 + 业务种子的写法；
2. `AccountAuditUpgradeDataPreservationTest`：旧审计行「正文/主体不改、只补分类」的口径；
3. `LoginTypeMigrationTest`：V62 的 PASSWORD 默认、哈希逐字保留、类型/哈希组合 CHECK；
4. `db/migration/V59` 起的全部 classpath 迁移（施工时实测升到 **V63**，用例按 classpath 最新版本断言，不写死上限）。

`.scratch/dingtalk-multiuser/t08-upgrade-seed-inventory.md` 是已证伪的 Gem 摘录，**未使用**、未据其设计任何断言。

## Seam 与结构（一条纵向片）

Seam 是公开的迁移 SQL 边界：用 Flyway 对同一个 H2(PostgreSQL 模式) 库先 `target=59` 建旧库，落种子，
抓「每张表的旧列名 + 按 id 的整行旧值」，再**不带 target** 升到 classpath 最新，然后用同一批旧列重查
逐值比较。新列不进投影，其默认值/约束/索引在独立用例里单独断言；验证约束用的插入只在各自用例的
独立数据库里发生，不进入任何保全投影。

五个用例（同一条纵向片内按职责分开，互不共享数据库状态）：

1. `everyOldColumnValueSurvivesTheWholeMigrationChain`
   - 21 张业务表逐表：旧列必须都还在（新列只能追加）→ 种子非空（防「空等于空」）→ 按 id 的每一行每一个旧列值逐字相等。
   - 覆盖：`app_user`（旧哈希、`enabled`、`teacher_id` 绑定）、`teacher`、`semester`/`period`、`grade`/`admin_class`/`teaching_class`/`course`/`assignment`（教师绑定）、`timetable_draft`/`draft_lesson`/`timetable_entry`/`timetable_publish`（草稿-课节-基线）、`scheduling_job`、`substitution_request`+`substitution_history`+`substitution`（旧申请主体与状态历史）、`constraint_config`/`constraint_entry`、`ai_import_log`、`audit_log`。
   - 断言末尾做一次投影灵敏度自检（改一个旧值后同投影必须报出差异），证明对照确实在读库。
2. `v60TurnsOldAuthorityIntoTheThreePresetRolesAndKeepsCustomRoles`
   - 两个旧 `admin` 持有者（含非 `username='admin'` 的第二个）→ 只有 `super_admin`；`head_teacher` → `teacher`；普通教师/停用账号保持 `teacher`；自定义角色 `audit_jw` 及其持有关系原样保留；`head_teacher` 角色连同其 `role_permission`/`user_role` 一起下线。
   - 预置角色授权：`admin` 让出 `user:manage`/`integration:manage`/`audit:view:account`；`teacher` 新增 `timetable:view`；三个预置角色都不再持有 `timetable:view:class`/`substitution:view:class`；**权限目录里的退休行仍保留**（历史行），自定义角色的这两条授权也保留。
3. `v61ClassifiesLegacyAccountEventsAndLeavesSnapshotColumnsEmpty`
   - 旧 `APP_USER` 与 `users/IMPORT` 事件 `category='ACCOUNT'`，其他事件为 `null`；`target_user_id`/`before_json`/`after_json` 对旧行全为 `null`（不补造）；旧动作码原样；`idx_audit_category_created_id` 已建。
4. `v62MovesEveryLegacyAccountToPasswordAndEnforcesTheHashPairing`
   - 全部旧账号 `login_type='PASSWORD'`、`password_hash` 逐字保留；不带 `login_type` 的 raw INSERT 回落默认；`NOT NULL`、`CHECK(login_type in (...))`、`CHECK(哈希与类型配对)` 各自用违规插入反证；`password_hash` 原 `NOT NULL` 已放开（钉钉账号可无密码）。
5. `v63AddsApprovalColumnsWithoutInventingLegacyApprovals`
   - 旧申请的新增审批三列全为 `null`；`idx_substitution_request_semester_status` 已建；`approver_user_id` 指到不存在账号被外键拒绝，指到真实账号可写入。

## 运行记录（真实命令与退出码）

命令（PowerShell，显式设置与 `source .tools/env.sh` 等价的变量）。本机 Git Bash 可用
（`C:/Users/liu/AppData/Local/Programs/GitPortable/bin/bash.exe`），只是默认的 `bash` 解析到未安装的 WSL；
本轮选择 PowerShell 执行，属于执行入口选择，不代表本机没有 bash：

```
$env:JAVA_HOME='E:\排课系统\.tools\jdk-21.0.12.1+1'
$env:MAVEN_ARGS='-s E:/排课系统/.tools/maven-settings.xml -Dmaven.repo.local=E:/排课系统/.tools/m2repo'
cd backend; mvn.cmd -B -DforkCount=0 -Dtest=OldDatabaseUpgradeAcceptanceTest test > ..\.runtime\logs\<log> 2>&1
TMP/TEMP/TMPDIR 全部指向 .worktrees/dingtalk-t08-upgrade/.runtime/tmp
```

| run | 树 | 结果 | exit | 原始日志 |
|---|---|---|---|---|
| 1 | 016859b + 新测试 | Tests run 5, Failures 1 | **1** | `.runtime/logs/t08-upgrade-run1.log`（sha256 `1DFFE028…DDE272`） |
| 2 | 同上（修正断言后） | Tests run 5, Failures 0 | **0** | `.runtime/logs/t08-upgrade-run2.log`（`38FA5954…D9F887`） |
| 3 | 同上（加投影灵敏度自检后） | Tests run 5, Failures 0 | **0** | `.runtime/logs/t08-upgrade-run3.log`（`72D86F36…B9ABA6A`） |
| 4 | 合并 `26edaa6` 后 | Tests run 5, Failures 0（Flyway 实测 59→63） | **0** | `.runtime/logs/t08-upgrade-run4-postmerge.log`（`88162651…32D8C`） |

测试源文件 sha256：`9AA5AA24ADB1DA37C83C478C329D8C36357C475C115EAEE915478732CDABB119`
（run2 之后只改了断言与自检注释；run3/run4 对应本哈希的最终内容。）

## run1 失败语义裁定（不是业务保全红）

run1 唯一失败是**本测试自己的断言写错**：`v60` 用例原先把 `super_admin` 期望成「权限目录全部 8 行」，
漏算了 V60 末尾「三个预置角色一律剔除 `timetable:view:class` / `substitution:view:class`」这条退休动作。
实际结果（6 条，剔除两个本班范围权限点）与 V60 + `IdentityDataInitializer` + `Permissions`（含 01 review
的裁定）一致：**退休权限点的目录历史行保留，但不再授权任何预置角色**。因此判定为
**验收断言语义修正**，不是产品缺陷，也不是保全红；迁移与产品代码零改动，也没有为迁就测试放宽权限。
修正后额外加了两条断言把这条语义钉死：目录里仍有退休权限行、预置角色（含超级管理员）都没有它们。

## 边界（如实）

- 全部用例跑在 **H2 PostgreSQL 兼容模式**上：只证明「迁移 SQL 在 V59 旧数据上可执行」和「旧值不被改写」，**不证明真实 PostgreSQL** 的行为。本机没有 Docker/真实 PG，未跑 `PostgresContainerIT`；CI 现成的 `PostgresContainerIT` 会覆盖真实 PG 的可执行性——那是既有用例将覆盖，不是本轮已跑的证据。
- 未跑 `scripts/verify.sh --full`、未跑浏览器/Playwright、未跑 vitest（按切片边界与主控安排）。
- 迁移版本号只按 classpath 最新断言，未来新增迁移会被本用例自动纳入；用例不把 V63 写成永久上限。
- 种子里的旧哈希是明显的合成字面量（原任务说明中的密码占位符不是可用值），语义只用于证明「逐字保留」。

## 遗留

- 无产品侧遗留。本切片与 08 的整链/浏览器切片互不相干，可独立评审。
