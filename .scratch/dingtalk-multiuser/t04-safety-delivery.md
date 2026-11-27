# 票 04 停用链路回归网（有界子任务 04-C2）

分支 `codex/dingtalk-t04-regression`，基于 04 候选 `639016c`（含 integration 全部 tip）。
本子任务**只补测试与会话记录**：不改生产代码、不改现有测试、不启下一票、不部署、不跑门禁。

## 交付物

| 路径 | 性质 |
|---|---|
| `backend/src/test/java/com/paiban/integration/DingTalkSyncSafetyIntegrationTest.java` | 新增：6 组 HTTP 回归 |
| `.scratch/dingtalk-multiuser/t04-safety-delivery.md` | 本文 |

## 定位

这些是**当前候选已实现**的行为，不是新功能；因此没有伪造 red，只把既有语义钉在公开 HTTP 面上，
防止后续改动（尤其守卫、事务边界、权限注解）悄悄放松。

断言只走公开接口（sync/preview、sync/confirm、旧 /sync、/api/users、/api/teachers、
/api/account-audits、/api/auth/dingtalk、/api/timetable/class），并允许两处 SQL 只读回查
（`timetable_entry` 整行、`teacher.deleted_at`）——这两处是「内容没被改」无法从现有 HTTP 视图
完全证明的地方。不调用私有方法、不数内部调用次数。

与同目录 `DingTalkSyncDeactivationIntegrationTest`（选中后停用、旧选择拒绝、不完整扫描）互补，
不重复其断言。

## 覆盖矩阵

| # | 场景 | 用例 | 关键断言 |
|---|---|---|---|
| 1 | 未提交停用选择绝不停账号 | `confirmWithoutSelectionsKeepsBoundAccountsEnabled`（参数化：字段缺失 / 显式 null / 空数组） | confirm 200、`accountDisabled=0`、账号仍 enabled、教师绑定未变、`ACCOUNT_SYNC_DISABLE` 审计总数为 0 |
| 1 | 旧 `/sync` 一键接口不自动停 | `legacySyncEndpointNeverStopsMissingAccounts` | `/api/integrations/dingtalk/sync` 200、`accountDisabled=0`、账号与审计原样 |
| 1 | 扫描不完整但未选停用 | `incompleteScanWithoutSelectionStopsNoAccount` | 部门 `user/list` 失败 + 空选择 → 200、`success=true`、`accountDisabled=0`、账号仍 enabled |
| 2 | 预览后 UID 重新可见 | `anOldSelectionIsRefusedWhenTheUidReappearsAfterPreview` | 预演先给出 1 个候选（accountId 对齐）；UID 回到目录后带旧选择 confirm → 409 且 message 含「重新预演」；账号仍 enabled、教师姓名与 `dingtalkUserId` 不变、同步审计为空 |
| 3 | 批量停用原子性 | `batchStopRollsBackWhenTheOperatorIsInTheSelection` | 额外 super_admin（关联 missing 教师，seed `admin` 不动）作操作者，选择顺序「普通账号 → 操作者」→ 409 且 message 含「当前登录的账号」；两者仍 enabled、绑定未变、`ACCOUNT_SYNC_DISABLE=0`；操作者会话仍有效（`/api/auth/me` 200） |
| 4 | 已发布课表与教师档案 | `stoppingTheTeacherAccountKeepsPublishedTimetableAndTeacherProfile` | 未来学期 `ScheduleFixture.publishedIn`（12 条）→ 绑定教师停用账号 → 先断言 `timetable_entry` 12 行非空与基线视图非空，停用后**整行**（`select *` 全列）与 `publishedEntries` 前后全等；教师 name/employeeNo/dingtalkUserId 不变、`deleted_at` 仍为 null |
| 5 | 重新启用复用同账号 | `reEnablingTheStoppedAccountRestoresTheSameDingTalkLogin` | 停用后 `POST /api/users/{id}/enable` → fake 钉钉免登 200，`user.id` 与 `user.teacherId` 均为原账号/原教师，未另开新号 |
| 6 | 权限矩阵 | `nonIntegrationRolesCannotPreviewOrConfirm`（参数化：`admin` 教务管理员 / `teacher` 老师） | preview 与 confirm 均 403；被选账号仍 enabled、`ACCOUNT_SYNC_DISABLE=0`；调用者账号未被牵连 |

## 测试隔离

- 独立 H2：`jdbc:h2:mem:paiban_sync_safety`（`app_user` 不在 `DatabaseResetExtension` 清空名单里，
  共用库会看到别的测试建的账号）。
- `@BeforeEach`：`resetIntegrationSettings()` + `fakeDingTalk.resetToDefault()` + 重配集成凭据，
  不依赖上一个用例残留。
- `@AfterEach`：只删除本类登记过的账号（`createdAccountIds`），再重置设置与 fake 目录；
  seed 的 `admin` 与其它测试的数据不动。

## 未运行与限制

- **未运行任何 Maven / Vitest / E2E**：按主线程测试串行分工，由主线程统一跑定向与全量门禁。
  因此本文件里的用例**尚未得到执行证据**，仅通过逐处核对
  `IntegrationController`、`DingTalkOrgDataProvider.confirm`、`DingTalkDeactivationPlanner`、
  `UserService.disableForSync`、`AccountMutationGuard`、`V4/V8/V60` 迁移与同目录既有用例得出的
  预期行为与状态码。
- 用例 4 会真实驱动一次求解（`maxSeconds=5`）并发布课表，是本类最慢的一例。
- 用例 3 依赖 `SecurityContext` 在同一请求线程内可见（`UserService.currentOrNull()`）；
  这是现有实现的既有前提，若将来确认流程改为异步，该用例会先红，属于应有的告警。
- 用例 6 使用预置角色编码 `admin`（教务管理员）/ `teacher`（老师）；若角色编码再次调整，
  只需改 `DeniedRole` 一处的 code，断言结构不变。
- 停用期间「钉钉免登被拒」的具体状态码与结构化原因属于票 03 的设计面，本类**故意不断言**，
  只断言重新启用后回到同一账号。
