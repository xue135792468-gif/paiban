# 05 前端交付（审批恢复）

日期：2026-11-12
分支：`codex/dingtalk-t05-ui`
工作树：`E:/排课系统/.worktrees/dingtalk-t05-ui`
基点：`e091d0f20745b4a8ccb95e52d353857999e4d2b0`
已先 fast-forward 合并集成 tip `10b3e8b4045390265d63dee74e3389473ec51016`（仅文档）。
本票提交：`644b7d3`（feat(dingtalk-t05): restore substitution approval flow in UI）。

## 写集

- `frontend/src/components/substitution/SubstitutionRequestForm.vue` + spec
- `frontend/src/components/substitution/SubstitutionMineTable.vue` + spec
- `frontend/src/components/substitution/SubstitutionApprovalPanel.vue` + spec（新）
- `frontend/src/components/substitution/types.ts` + spec
- `frontend/src/views/SubstitutionsView.vue` + spec（新）
- `frontend/src/views/HomeView.vue` + spec（新）
- `frontend/e2e/substitution-approval.spec.ts`（新，未运行）
- `frontend/e2e/withdraw.spec.ts`（兼容更新，未运行）

未改后端、migration、ADR、shared navigation/permissions、其他文档；未改 James 后端树或
integration 树。

## 行为

- 教师自助表单只保留代课：无调课/换到选项；原因框提示「想调课请写明原因，由教务处理」；
  代课教师必填；提交后提示「申请已提交，等待教务审批」，不再出现「已直接生效」。
- 「我的申请」显示待审批/已生效/已驳回/已撤回/已作废，保留旧 CANCELLED「已取消」；
  改人批准时同时显示「期望：X / 实际：Y」；审批意见列展示 `approvalComment`（驳回即理由）；
  仅 SUBMITTED 显示撤回，撤回成功提示并刷新，失败显示服务端原因且不刷新、不伪成功。
- 新增「待审批」页签仅 `substitution:approve` 可见；按显式 `status=SUBMITTED` 取数，不改共享
  查询默认值。面板再按状态过滤，非待批行不出现管理动作；无权限 prop 时不渲染也不调用审批。
- 批准弹窗默认期望代课人，可改人并填可选意见，`POST .../approve` 发送
  `{substituteTeacherId, comment}`；驳回 `POST .../reject` 强制非空 `{reason}`；两处文本均
  `maxlength=500`（`approvalComment`/历史 comment 同限）。
- 后端 DTO 只按 wire contract 使用追加字段：`expectedSubstituteTeacherId/Name`、
  `approverUserId`、`approvedAt`、`approvalComment`、`operatorUserId`；未虚构新字段。
- 首页调代卡片分流：老师「提交代课申请，等待教务审批后生效」；有审批权限的管理员
  「处理待审批的代课申请；调课去工作台直接调整」。
- 无学期/列表加载失败时，调代页显示后端可读原因（如「当前没有进行中的学期…」）并保持空表，
  不再产生未处理的 Promise 拒绝。未改通用框架。

## 真实 TDD 记录（单文件、串行）

`TEMP` 均指向 `E:/排课系统/.worktrees/dingtalk-t05-ui/.runtime/tmp`。

- 表单首红：2026-11-11 22:51:24，exit 1；断言收到的类型按钮为 `["代课","调课"]`，
  期望 `[]`。首绿：22:53:44 exit 0，1 passed。
- 表单「未选代课教师仍 POST」红：22:56:13 exit 1（1 call）；补必填校验后绿。
- 类型五态/旧 CANCELLED 红：exit 1（SUBMITTED 标签/颜色不符）；实现后 5 passed。
- MineTable 三条红：exit 1（3/3 失败：无撤回、无期望/实际、无驳回理由）；实现后 3 passed。
  其中一次失败是 Element Plus 消息跨用例残留在 body，已在测试 beforeEach 清理。
- ApprovalPanel 首跑 exit 1 为 jsdom `ElSelect` 递归更新（setup 错误，未当成行为红）；
  stub ElSelect 后出现真实行为红（非待批行也渲染批准按钮），修复后 4 passed。
- SubstitutionsView 两切片先绿；无学期切片首跑 exit 1（Unhandled Rejection 400），
  最小 catch + 警告提示后 3 passed。
- HomeView 文案红：exit 1（2/2 失败，仍显示「直接生效」）；实现后 2 passed。
- 最终逐文件复跑（每条独立 vitest，`--no-file-parallelism --maxWorkers=1`）：
  Form 3 passed、MineTable 3、ApprovalPanel 4、types 5、SubstitutionsView 3、HomeView 2，
  共 6 文件 20 tests，全部 exit 0。

## 静态检查

- `npx eslint`（本票 frontend 文件 + 两个 e2e spec）exit 0。
- `npx vue-tsc -b --pretty false` exit 0。
- `scripts/check-e2e-helpers.sh`（GitPortable bash）exit 0：未重复定义 helpers 导出。
- commit 前钩子：本地日期、e2e helper、scheduling 模块边界通过。
- 未跑全量 verify、未跑 Playwright/E2E、未启动后端/前端服务；按要求由主控合并后实际运行。

## E2E（已写，未运行）

- 新 `substitution-approval.spec.ts`：教师提交→待审批→撤回→重新提交；教务在待审批改人批准
  （第三位无课教师）→老师看到已生效、期望/实际、审批意见；另一条覆盖驳回空理由被挡、填理由后
  老师看到已驳回和理由。
- `withdraw.spec.ts`：教师提交断言 `SUBMITTED`，管理员真实 `approve` 后断言 `EFFECTIVE`；
  原日期/多周/再次调整/历史断言保留未删。

## 已知待主控处理

- `frontend/e2e/substitution.spec.ts` 仍断言「提交即 EFFECTIVE / 调代已直接生效」；不在本票
  写集（caller map 只点名 withdraw.spec.ts），合并后续会失败，需主控指派兼容改造。
- `frontend/src/layouts/SubstitutionLayout.vue` 副标题仍写「直接生效并保留记录」；属于共享布局，
  未改，建议主控决定归属。

## 运行时与进程

- 临时路径：`E:/排课系统/.worktrees/dingtalk-t05-ui/.runtime/tmp`（未删除）。
- 未启动常驻进程；vitest/eslint/vue-tsc 均在前台结束后自行退出。
- 未清理任何 worktree/缓存；收尾交主控统一处理。
