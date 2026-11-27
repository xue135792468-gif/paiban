# 06 前端交付（通知收敛与只读发送记录）

日期：2026-11-13
分支：`codex/dingtalk-t06-ui`
工作树：`E:/排课系统/.worktrees/dingtalk-t06-ui`

## 起点与合并

- 施工起点：`30fed896bcb7653c1d2ec2bbcc12c7984a0e70e4`。
- 开工后先 fast-forward 合并仅文档提交 `0979597`。
- 完成前再次 fast-forward 合并 `codex/dingtalk-multiuser` 最新 tip
  `246f0959e977e484aa6728eb11cd926b33cd35d2`（含 06 wire contract、
  quality plan、transport 8 绿、协调与隔离 QA 文档）；未 reset 任何文件。

## 写集

- `frontend/src/components/settings/SubstitutionPolicyCard.vue` + spec
- `frontend/src/components/settings/NotificationDeliveryLogsCard.vue` + spec（新）
- `frontend/src/views/SettingsView.vue` + spec
- `frontend/src/api/notificationDeliveryLogs.ts` + spec（新；类型与只读 API）
- `frontend/e2e/notification-delivery-logs.spec.ts`（新，仅写未运行）
- `.scratch/dingtalk-multiuser/t06-ui-delivery.md`（本文件）

未改后端、路由、权限目录、迁移、导航、旧通知中心、发送/重试按钮或真钉钉配置。
旧 `SubstitutionRecipientsCard.vue` 组件与 `notification.scope` 配置值保留，只撤下设置页入口。

## 行为

- 调代策略卡只显示搜索窗口、最多变动几节课、方案排序三项；
  `notification.scope` 不再渲染。
- 设置页不再挂载额外收件人卡；已有其它 `substitution:view` 权限的超级/教务管理员
  可看到只读“通知发送记录”，老师不渲染卡片。
- 日志卡使用 `el-collapse` 按需展开；首次展开才请求
  `GET /notification-delivery-logs?page=0&size=20`，组件本身不依赖学期，也不请求 `/users`。
- 表格按服务端 `recipientLabel` 显示收件人；分页界面为 1 基，接口传零基 page。
- 成功状态按实际 `channel` 判定：`dingtalk` = “钉钉已受理”，`local` = “系统已保存”，
  其它成功 = “已受理”并原值显示未知渠道名；`platform=system` 不用于识别 local。
- 失败行固定显示“失败”，组件忽略或可能泄漏的旧 `errorMessage` 原文，只显示固定安全说明。
- 加载、空态、失败与重新展开重试均有公开 DOM 行为测试；内容列溢出使用 tooltip。

## 纵向 TDD 记录

所有 RED/GREEN 均为单文件 Vitest，使用 `--no-file-parallelism`；每轮启动前 CIM
确认可用物理内存高于 2 GiB。实际证据保留在
`E:/排课系统/.worktrees/dingtalk-t06-ui/.runtime/`。

| 切片 | 首跑证据 | 绿色证据 |
|---|---|---|
| 隐藏 `notification.scope`，保留三项 | `.runtime/policy-red.log`，exit `1`；收到第四个 form item “通知范围” | `.runtime/policy-green.log`，exit `0`；5 tests passed |
| 管理员可见、老师隐藏、展开前零请求 | `.runtime/settings-wire-red.log`，exit `1`；找不到“通知发送记录” | `.runtime/settings-wire-green.log`，exit `0`；8 tests passed |
| 只读 API 路径与参数 | `.runtime/logs-api-red.log`，exit `1`；API 模块不存在 | `.runtime/logs-api-green.log`，exit `0` |
| 折叠懒加载与钉钉受理 | `.runtime/logs-card-red-1.log`，exit `1`；折叠控件不存在 | `.runtime/logs-card-green-1.log`，exit `0` |
| 三渠道成功、失败安全显示 | `.runtime/logs-card-status.log`，exit `0`；该行为已由前一最小实现覆盖 | 2 tests passed |
| 分页/加载/空态/重试 | `.runtime/logs-card-states-red.log`，exit `0`；前一实现已覆盖状态边界 | 4 tests passed |
| 未知渠道原名 | `.runtime/logs-card-unknown-channel-red.log`，exit `1`；仅显示“已受理”未显示 `webhook` | `.runtime/logs-card-unknown-channel-green.log`，exit `0` |

合并最新集成 tip 后最终定向回归：

- `.runtime/t06-ui-final-vitest.log`：`VITEST_EXIT=0`，4 files / 19 tests passed。
- `.runtime/t06-ui-final-eslint.log`：`ESLINT_EXIT=0`，资源释放前可用内存约 6.32 GiB。
- `.runtime/t06-ui-final-tsc.log`：`TSC_EXIT=0`，资源释放前可用内存约 5.90 GiB。

## E2E（已写，未运行）

`frontend/e2e/notification-delivery-logs.spec.ts` 使用真实后端和 Fake 钉钉配置：

1. `seedWorkbenchScenario` 发布真实课表；为最终接课教师建真实账号；
   经公开 workbench preview/confirm 产生 local 通知记录。
2. 通过真实 `GET /notification-delivery-logs` 轮询确认本案日志已落库。
3. 用 role code `admin` 建立独立教务账号，登录设置页，断言其余三项策略保留、
   两个旧通知控件消失、展开卡片发出真实分页 GET，并以唯一教师名定位本案日志行。
4. 老师直接打开设置页必须落到 403，且页面不得发出日志请求。
5. 超级管理员保持 `admin` 密码钥匙可进入设置页。

不 route 伪造 GET 成功 payload；截图前对目标日志行执行
`scrollIntoViewIfNeeded()`。全部用例在 `beforeEach`/`afterEach` 收集并断言
console error 与 pageerror 为空。每个 API context 在 `finally` 释放；本 spec 不修改全局配置。

## 未验证范围

- 按 worker 约束，未启动服务、后端、前端或 E2E，未运行 Playwright、Maven、
  全量 `verify.sh`、性能/PostgreSQL 容器测试、部署或 push。
- 未访问真实钉钉网络，未使用企业凭据，不证明真租户授权、手机送达或已读。
- 06 core/log-api 的整合结果、真实浏览器执行、截图复核与独立双轴评审由主控/merger 完成。

## 运行时卫生

- `TEMP`/`TMP`/`TMPDIR`/`npm_config_cache` 均指向本 worktree `.runtime`。
- 本轮未启动常驻进程；Vitest/eslint/vue-tsc 均在当前命令内退出。
- `.runtime` 证据保留，未做递归清理；未清理 worktree、node_modules junction 或缓存。
