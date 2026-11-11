# 05 后端交付说明：老师自助代课恢复审批

日期：2026-11-12
工作树：`E:/排课系统/.worktrees/dingtalk-t05-backend`
分支：`codex/dingtalk-t05-backend`
起点：`e091d0f20745b4a8ccb95e52d353857999e4d2b0`（03 放行基点）

本文件记录后端实现、验证证据与边界。票面状态与整体放行由主控收口；这里不把
「本票后端定向绿」写成「05 整票已验收」——前端与通知仍由各自票/执行者完成。

## 已合并的前置

1. `b479f27` 合并集成 tip `d860a57`（票 05 契约、调用方盘点、订单编号盘点等准备文档）。
2. `3e28798` 合并集成 tip `e3b990f`（含主控独立写的 `SubstitutionExpiryRaceIntegrationTest`
   与主 QA 浏览器用例；该测试与前端改动由主控/对应执行者所有，本票不改写）。

## 本票提交

| 提交 | 内容 |
|---|---|
| `16851f2` | checkpoint 1：提交停 `SUBMITTED`、批准/驳回生命周期、V63、DTO 扩展、首个 3 例 HTTP 红→绿 |
| `8f457ac` | checkpoint 2：撤回、惰性作废、锁后 `SUBMITTED` 重验、批准前重验（教师有效性/撞课/基线）、本人判定 |
| 最终文档提交 | ADR-0037 与本交付说明（源码未再改动） |

Immutable SHA 以外，未 amend、未 push、未部署。

## 实现边界

状态与接口（与 `t05-wire-contract.md` 一致）：

- 老师自助 `POST /api/substitution-requests` 只保存 `SUBMITTED`：不写 `substitution`、
  不改课表、不发网络通知；同实例「待审批唯一」检查在学期行锁之后。
- `POST /api/substitution-requests/{id}/approve`（可选 `{substituteTeacherId, comment}`）：
  学期锁 → 申请行锁 → 刷新；按当前课表重验基线存在、原教师与节次未变、最终教师
  存在且未软删且 `active`、不撞课、未到开课时刻；通过后才退出旧 `EFFECTIVE` 行并落新生效行。
- `POST .../{id}/reject`：`reason` 必填，入口校验非空且 ≤500 字，落 `approvalComment`。
- `POST .../{id}/withdraw`：无体；先认真实操作账号 `operatorUserId`，旧行才回退到非空的
  `requesterTeacherId`；仅 `SUBMITTED` 可撤。
- 作废：开课后仍 `SUBMITTED` → `EXPIRED`。用显式 `TransactionTemplate` 在可写事务里
  保存状态、历史与审计，事务提交后外层才返回 409；未使用 `noRollbackFor`。
  列表路径顺带清扫，锁后明确重验仍为 `SUBMITTED` 才作废，再按最新状态重查。
- 状态常量：`SUBMITTED/EFFECTIVE/REJECTED/WITHDRAWN/EXPIRED`；`CANCELLED` 保留既有语义
  （生效安排被系统撤销，例如撤回已发布基线），不等同撤回。
- 审计：提交/批准/驳回/撤回/作废各一条同事务成功 `audit_log`；提交路径的失败审计保留，
  但 `alert=false`，不新增持锁外发。05 不恢复旧通知（06 负责）。
- DTO：追加 `expectedSubstituteTeacherId/Name`、`approverUserId`、`approvedAt`、
  `approvalComment`、`operatorUserId`；既有 `substituteTeacherId/Name` 在待批时为期望人、
  生效时为实际人；旧 22 参数构造器保留。
- 管理端工作台 `confirmDirect` 仍直接生效，未套审批；其旧通知路径按 06 收口，本票未动。

## V63 迁移

`backend/src/main/resources/db/migration/V63__substitution_request_approval.sql`

- 加回 `substitution_request.approver_user_id` / `approved_at` / `approval_comment`；
  外键指向 `app_user(id)`；新增 `(semester_id, status)` 索引。
- 只加不改：V1–V62 与历史 ADR 未修改；未恢复 `approval_mode`；未删除任何列/表。
- 占号复核：开工时最高为 V62，V63 未被任何分支/worktree 占用；合并后仍只有本票的 V63。

## 写集

生产：

- `backend/src/main/java/com/paiban/substitution/domain/SubstitutionRequest.java`
- `backend/src/main/java/com/paiban/substitution/domain/SubstitutionRequestRepository.java`
- `backend/src/main/java/com/paiban/substitution/service/SubstitutionApprovalService.java`（新）
- `backend/src/main/java/com/paiban/substitution/service/SubstitutionLookups.java`
- `backend/src/main/java/com/paiban/substitution/service/SubstitutionMapper.java`
- `backend/src/main/java/com/paiban/substitution/service/SubstitutionRequestService.java`
- `backend/src/main/java/com/paiban/substitution/web/SubstitutionController.java`
- `backend/src/main/java/com/paiban/substitution/web/SubstitutionDtos.java`
- `backend/src/main/resources/db/migration/V63__substitution_request_approval.sql`（新）
- `docs/adr/0037-substitution-approval-restored.md`（新）

测试：

- `backend/src/test/java/com/paiban/substitution/SubstitutionApprovalIntegrationTest.java`（新，13 例）
- `backend/src/test/java/com/paiban/substitution/SubstitutionIntegrationTest.java`（旧自助路径改为提交+批准）
- `backend/src/test/java/com/paiban/substitution/AdminSubstitutionIntegrationTest.java`（同上）
- `backend/src/test/java/com/paiban/substitution/AdminSwapIntegrationTest.java`（同上）
- `backend/src/test/java/com/paiban/acceptance/FullChainAcceptanceTest.java`（同上）

未改：前端、身份、通知、其它既有迁移、历史 ADR、主控未提交准备文档、主控的
`SubstitutionExpiryRaceIntegrationTest.java`。票面复选框未改。

## 实际验证（逐条 TDD，真实 HTTP）

全部定向命令均 `source .tools/env.sh`、`-DforkCount=0`、TEMP/TMP/TMPDIR 指向
本工作树 `.runtime/tmp`；每条都重定向日志并读取真实退出码。日志在 `.runtime/`。

| 日志 | 内容 | 实际退出码 |
|---|---|---|
| `red1.log` | 首例红：提交返回 `EFFECTIVE` | 1 |
| `green1.log` | 首例绿：`submitStaysSubmittedWithoutAnyEffect` | 0 |
| `red2.log` | 批准端点尚不存在（404） | 1 |
| `green2.log` | 批准生效 | 0 |
| `green3.log` | 驳回（理由必填/长度/落意见） | 0 |
| `checkpoint-pair.log` | 提交+批准 2 例 | 0 |
| `slice4.log` | 撤回、他人不可读、旧行归属回退 3 例 | 0 |
| `slice5.log` | 批准时已开课 → EXPIRED（先提交再 409）、待批列表清扫 2 例 | 0 |
| `slice6.log` | 改人批准、教师停用、基线变化、撞课 4 例 | 1（2 例失败，测试前置错误，见下） |
| `slice6b.log` | 修正基线路径后重跑 2 例 | 1（撞课用例断言语义错误） |
| `slice6c.log` | 撞课用例改用共享工作台夹具后 | 0 |
| `class-all.log` | `SubstitutionApprovalIntegrationTest` 12 例 | 0 |
| `callers.log` | Approval 13 + Substitution 9 + AdminSubstitution 10 + AdminSwap 12 + FullChain 1 = **45 例** | 0 |
| `final-targeted.log` | 教务管理员审批权限例 + 主控独立 expiry race 例 | 0 |

`slice6`/`slice6b` 的失败是测试前置错误，不是生产缺陷，原始日志保留不删：

1. 对已 `PUBLISHED` 的草稿直接再 publish 本就 409；合法「基线变化」路径是先
   `POST /timetable-drafts/{id}/withdraw`（条目重建/删除、申请 `entry_id` 置空）。
2. 手写 SWAP clash 行把 `lessonDate` 与 `targetLessonDate` 设成同一天，
   `PlanSnapshot.isMovedOutOn` 会把它当「当天被移出」，不构成撞课事实；
   改为复用 `AdminSubstitutionIntegrationTest.buildFixture` + 公开工作台
   preview/confirm，先断言代课老师同一节确实已有另一班的课，再要求批准 409。

## 覆盖与未覆盖

已覆盖（真实 HTTP + 落库副作用回查）：

- 提交停 `SUBMITTED`、零生效行、零课表变化、历史与审计各一次。
- 批准生效（含 DTO 审批信息）、改人批准（期望人保留、实际人记录）、驳回（理由必填/长度/展示）。
- 撤回：仅本人、仅 `SUBMITTED`；他人与管理员撤回 403；撤回后重提；旧行无
  `operatorUserId` 时按非空教师关系认本人；两个空教师关系不互相认领。
- 截止：用可调 `Clock` 推到开课后，批准返回 409 且 `EXPIRED` 已提交；再次批准仍 409；
  待审批列表不再包含该单，「我的调代」保留 `EXPIRED`。
- 批准前重验：教师被停用 409、发布基线被撤回 409、提交后当前安排变忙 409；均零生效行、
  状态仍 `SUBMITTED`。
- 权限：老师不能批准/驳回（403）；教务管理员可批准并记录 `approverUserId`。
- 旧调用方：四条既有套件 45 例全绿（原日期、多周、再次调整、三视图、旧格式覆盖、
  工作台直接生效、快照失效、并发凭证等原断言未删）。

未覆盖 / 交后续：

- 真实 PostgreSQL：本机无 Docker，未跑 `PostgresContainerIT`；以上均为 H2
  `MODE=PostgreSQL` + Flyway + `ddl-auto=validate`。不声称 PG 已验证。
- 未跑全量 `scripts/verify.sh`、Playwright/E2E、真实服务或外网。
- 前端（待审批面板、我的调代状态与撤回按钮、提交表单去 SWAP）由前端执行者完成；
  `frontend/e2e/withdraw.spec.ts` 仍按旧「提交即 EFFECTIVE」编写，需前端票内改为「提交+批准」。
- 钉钉通知（提交后给谁、批准/驳回结果、驳回理由、去重）与工作台旧通知收口属 06。
- 迁移只在小库验证升到 V63；生产升级/回滚未执行。

## 任务卫生

- 本工作树内的临时运行目录：`.runtime/tmp`、`.runtime/npm-cache`；日志见上表。
- 未尝试递归删除或清理任何缓存，未移除工作树；按主控统一收尾。
- 本票启动的都是前台 Maven 进程，结束时均已退出（收尾检查无 `java` 进程）。
