# 票05 复查整改交付：E2E 夹具调用与无学期文案（2026-11-12）

分支 `codex/dingtalk-t05-review-fixes`，起点为集成提交
`dd4ebe7d31eb8d9a9d7777b989e7d5c25b3d5aa7`。本次只处理已确认的两条复查项，
不改前四票，也不宣称票 05 已放行。

## 已确认问题与整改

- 主控对 candidate `6611b3c4b0e2ea91478c9cd227fe31ad102b545e` 实跑完整 6 条 Playwright，
  得到 4 passed / 2 failed、exit 1。两个失败的根因相同：`submitCover` 把 Playwright `Page` 传给只接受 `Locator` 的
  `pickOption`，在 `scope.page()` 处抛 `TypeError`，尚未进入审批。
- E2E 现将“期望代课”表单项的 `.el-form-item` Locator 传给既有 `pickOption` helper。
  `data-testid="expected-substitute"` 位于该表单项内，选择器和原有 helper 均未改变。
- 增加两处真实状态截图，复用 `frontend/e2e/screenshots.ts` 的 `capture`：
  `t05-review-fixes-approval-changed-teacher.png` 在改人批准对话框中选好教师后截取；
  `t05-review-fixes-teacher-effective-expected-actual.png` 在教师看到“已生效”并核对
  期望人与实际人后截取。截图只作人工证据，不承担断言。
- 无学期状态文案改为：“请由教务管理员在「学期管理」中创建并设置当前学期，之后这里会
  显示代课申请与审批记录。”现有标题、`data-testid`、样式和权限逻辑保持不变。

## 第二轮：审批结果按申请行定位

- 主控对源提交 `6c5e43e9c457ad3cdf0cf29df75b5e6944d3cd77` 实跑 run2，得到
  5 passed / 1 failed、真实 exit 1，`SOURCE_IDENTICAL=1`；首例已成功走到 Teacher 最终页。
  失败是整表 `.el-table.getByText('期望：…')` 同时命中已撤回行和重新提交行，strict mode
  报 2 个元素；第二例驳回已通过。
- 按 `CODING_STANDARDS.md` 的“E2E 断言按行或按容器作用域”，用第二次提交独有的 reason
  `家中有事，第二次提交` 定位 `.el-table__row`，先断言 exact count 1，再在该 row 内分别
  断言“已生效”、期望人、实际人和审批意见。撤回与重新提交阶段及原业务断言均保留。
- 行范围取自实际 `SubstitutionMineTable.vue` DOM：数据行使用 `.el-table__row`，reason
  与状态、代课教师、审批意见位于同一行。run2 的 error-context 也确认撤回行 reason 为
  `家中有事，先撤回再提交`，不会与第二次提交行混淆。
- 教师在最终页的正确行断言全部完成后才调用本票固定文件名截图，截图不承担断言。

## 两轴确认项 C/D/E

- C：`CANCELLED` 的展示从“已取消”改为“系统撤销”，并同步既有 types 与 MineTable
  断言。全仓检索本票 UI/E2E 的旧“已取消”文案后，除两个测试期望外只有
  `withdraw.spec.ts` 的说明注释和 API 状态 `CANCELLED` 断言，没有依赖旧用户文案；
  因而未扩该 E2E 写集，业务断言保持。
- D：`changedTeacher` 改为比较 `expectedSubstituteTeacherId` 与 `substituteTeacherId`；
  同名但 ID 不同时仍并列显示期望/实际，并在该场景附“档案编号：ID”。不同名的既有
  期望/实际文案继续由原组件断言覆盖。没有扩后端字段或选人器。
- E：把 approval spec 与既有 substitution spec 中相同的首项下拉交互集中到
  `frontend/e2e/helpers.ts` 的唯一新导出 `pickFirstVisibleOption`。只替换上述两处，
  保留点击后等待 dropdown 隐藏的行为；删除已不用的局部 helper 与 `Locator` import，
  未修改 `pickOption` 签名。该项由主控真实 E2E 验证，不另写镜像测试。

## A/B 与锁内权限快照

- A：`SubstitutionMapper` 只有请求自身为 `EFFECTIVE` 且 `finalTeacherId` 为空时，才按
  entry/date 回退查旧兼容生效行；`SUBMITTED/REJECTED/WITHDRAWN/EXPIRED` 等未批准状态
  统一返回该请求的 expected 人。真正批准或系统撤销已有 `finalTeacherId` 时继续返回 final，
  历史格式与其它 mapper 契约未改。
- B：`reject` 与 `withdraw` 复用现有 `Outcome` 和 `overdue`：锁后 refresh、待批检查与
  角色/归属判断完成后再判断真实截止；已过课时则同事务保存 `EXPIRED`、一次历史和一次
  expiry audit，事务提交后外层返回 409。withdraw 先核 owner 再判断过期，非 owner 不会
  借过期路径修改别人的申请；重复调用因已非 `SUBMITTED` 而不再追加历史或审计。截止时间
  不可确定时沿用现有 `overdue=false` 策略。
- Standards P3：`approve`/`reject` 都改为先完成学期与申请锁、refresh request，再由
  新 `requireCurrentUser` 取当前账号并显式 `entityManager.refresh`，之后才判断审批权限；
  `withdraw` 同样锁后 refresh 当前账号再判 owner。保留学期→申请锁序，核 enabled，未改
  JWT、UserService 全局行为或 JPA clear 策略。
- 既有 `SubstitutionApprovalIntegrationTest` 保留原 13 例，新增 1 条重复生效后的
  SUBMITTED DTO 回归和 2 条课后 reject/withdraw HTTP 回归，共 16 例；主控独立 AuthSnapshot
  test 由不可变 QA commit 合入，未改写。

## 主控视觉 QA

主控实际执行 `view_image` 查看首轮成功的无学期截图，确认教师身份下原文案“请先在
「学期管理」中创建并设置当前学期”会引导教师进入其无权操作的管理页面。本次按主控确认
改为明确由教务管理员完成创建和设置。后续两轴固定 `661` 源码判断仍由主控执行。

主控还实际执行 `view_image` 查看新增的改人批准 dialog 截图，确认 Academic 身份、所选
第三位教师和版面均正确。

## 红 → 绿证据

| 阶段 | 证据 | 结果 |
|---|---|---|
| 真实红（主控） | `E:/排课系统/.worktrees/dingtalk-integration/.scratch/dingtalk-multiuser/logs/t05-browser-run1/playwright.log` 与 `E:/排课系统/.worktrees/dingtalk-integration/.scratch/dingtalk-multiuser/.runtime/t05-browser-run1/test-results/` 下两份 approval error-context | 完整 6 条 Playwright：4 passed / 2 failed，exit 1；源码一致标记 `SOURCE_IDENTICAL=1`，服务均退出 |
| 第二轮红（主控） | `E:/排课系统/.worktrees/dingtalk-integration/.scratch/dingtalk-multiuser/logs/t05-browser-run2/playwright.log` 与同 integration 工作树 `.scratch/dingtalk-multiuser/.runtime/t05-browser-run2/test-results/` 下首个 approval case 的 error-context | 完整 6 条 Playwright：5 passed / 1 failed，真实 exit 1；首例整表文本 strict mode 命中 2 行，驳回例已通过；端口 28405/25405 与 Java 进程均归零 |
| C 真实红 → 绿 | `.runtime/logs/t05-c-status-red.log`、`t05-c-status-green.log` | red：exit 1，`已取消` != `系统撤销`；green：exit 0，types spec 5/5 |
| D 真实红 → 绿 | `.runtime/logs/t05-d-same-name-red.log`、`t05-d-same-name-green.log` | red：exit 1，同名异 ID 仅渲染单个姓名；green：exit 0，MineTable spec 4/4 |
| C/D/E 静态校验 | `.runtime/logs/t05-cde-eslint.log`、`t05-cde-vue-tsc.log` | 定向 ESLint exit 0；`vue-tsc -b` exit 0。E2E 另由上述 ESLint 覆盖，不能据 vue-tsc 声称已类型验证 E2E |
| A/B 公开 HTTP red | `.runtime/logs/t05-ab-integration-red.log` | exit 1，16 tests / 3 failures / 0 errors；A 实际返回旧生效人而非 expected；课后 reject、withdraw 旧实现均错误返回 200 |
| 锁内权限快照 red | 主控权威日志 `E:/排课系统/.worktrees/dingtalk-integration/.scratch/dingtalk-multiuser/logs/t05-authz-snapshot/`；本树复核 `.runtime/logs/t05-authz-snapshot-red.log` | 均为 1 failure / 0 errors、exit 1；失败为 revoked permission 未拒绝。按主控口径不重复把本地复跑充当唯一行为 red |
| 最终四类 green | `.runtime/logs/t05-final-four-classes-green.log` | `SubstitutionApprovalIntegrationTest` + `SubstitutionApprovalConcurrencyTest` + `SubstitutionExpiryRaceIntegrationTest` + `SubstitutionApprovalAuthorizationSnapshotTest`：23 tests / 0 failures / 0 errors，exit 0 |
| 第一轮本次验证 | 对 Vue 与 E2E 两个文件一起运行定向 ESLint；原始日志为 `E:/排课系统/.worktrees/dingtalk-t05-review-fixes/.runtime/logs/t05-targeted-eslint.log` | exit 0 |

两轮都没有伪造单测或镜像测试。第二轮修复后，主控下一轮只需复跑受影响的
`substitution-approval.spec.ts` 两例；本工作树按授权不自行启动服务、网络或 Playwright，
也未提前声称首例已绿。

## 验证边界

当前最终验证以本文件“红 → 绿证据”的 C/D/E、A/B、锁快照和四类 green 行为准；
下面先保留前两轮整改当时的执行边界。

### 历史阶段记录

- 仅对 `frontend/src/views/SubstitutionsView.vue` 与
  `frontend/e2e/substitution-approval.spec.ts` 一起执行定向 ESLint。
- 第二轮仅定向 ESLint `frontend/e2e/substitution-approval.spec.ts`，保留真实退出码；
  未运行 Vitest、后端测试、Playwright、全量 `verify.sh`、服务或网络。
- `vue-tsc -b` 不覆盖 E2E，不能作为两处失败调用已通过类型检查的证据。
- 临时 JSON、ESLint 日志与缓存均置于本工作树 `.runtime/`；shell、Git hook 和 npm cache
  均显式指向该目录。
- 不处理 reviewer 报告中的其他判断项；收到主控后续确认的整改清单前不扩写集。

### 当前最终范围

- 前端定向 Vitest：`types.spec.ts` 5/5、`SubstitutionMineTable.spec.ts` 4/4，均 exit 0。
- 前端静态校验：本票修改的 Vue/TS、组件测试、两个 E2E 与共享 helper 跑定向 ESLint；
  Vue/TS 项目执行 `vue-tsc -b`，二者均 exit 0。
- 后端定向 Maven：ApprovalIntegrationTest、ApprovalConcurrencyTest、ExpiryRace、
  AuthorizationSnapshot 四类合并运行 23 tests / 0 failures / 0 errors，exit 0。
- 主控下一轮只跑受影响 4 条 E2E：approval 2 条、substitution 1 条、withdraw 1 条，
  用于验证 row 作用域、共享首项 dropdown helper、系统撤销和既有审批链；login 2 条源未变，
  已由 run2 覆盖。
- 未运行 Playwright、全量 `verify.sh`、服务或外网；不宣称 05 放行。
