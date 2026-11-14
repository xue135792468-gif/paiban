# 整特性 Standards 复查统一整改交付（单一 fixer，待主控复核）

2026-11-13；worktree `dingtalk-whole-review-fixes`，分支 `codex/dingtalk-whole-review-fixes`，
起点 `fecb656`（含 08 browser/backend；开工时已核 HEAD 是 fecb656 且工作树干净，未移动）。
本轮按 `whole-review-owner-decisions.md` 主控逐项确认的 Standards 项施工：H1（P2）、
J1（P3，采纳）、J2（P3，部分采纳并纠正事实）、J3（P3，部分采纳）。未改 Spec 轴结论、
票面 Status、`HANDOFF.md`、`spec.md` 与其他审查 raw；未采纳 J4（主控已明确拒绝：现有
`apiCall` 已共用，fixture 参数不同）。未改权限模型、UID 写入、通知收件对象与教师
active 逻辑。

## 处置结果

- **H1（P2，E2E 浏览器错误收集未上收）**：四个 spec 的 `beforeEach` 内联
  `console` + `pageerror` 收集改为调用 `helpers.ts` 既有 `trackBrowserErrors(page)`
  （仍是 console error + pageerror 两类，未吞错误、未 skip）。监听范围与各处
  `expect(browserErrors).toEqual([])` 断言原样保留：`substitution-approval`、
  `notification-delivery-logs` 保留 `afterEach` 断言；`dingtalk-sync`、
  `account-audit` 保留用例体内联断言。四 spec 仅新增 import。
- **J1（P3，日期 fill+Tab 重复）**：`helpers.ts` 新增导出
  `fillDateInput(input: Locator, date: string)`，只做 `fill` + `press('Tab')`
  （先 rg 全仓确认无同名定义）。`substitution-approval.spec.ts` 删除私有
  `pickDate` wrapper，调用点直接传 `name=请假日期` 的 locator；
  `substitution.spec.ts` 与 `dingtalk-acceptance.spec.ts` 的同一日期动作改用该
  helper。日期值、日期范围/学期锚点与全部断言不变；未为该函数另造测试。
- **J2（P3，SettingsView 权限串，部分采纳并纠正事实）**：`canManageIntegration`
  改用 `frontend/src/constants/permissions.ts` 既有 `INTEGRATION_MANAGE_PERMISSION`
  （`integration:manage`）并新增该 import；`canViewDeliveryLogs` 的
  `'substitution:view'` 无既有常量，保持当前仓库惯例、不增造，未新增全权限字典。
- **J3（P3，NotificationService 空退役方法，部分采纳）**：删除
  `substitutionPlanFailed`、`substitutionVerifyAlert` 两个零调用空方法
  （rg main+test 确认仅在声明处出现）；`notify(...)` 保留不动——
  `NotificationDeliveryIntegrationTest` 有两处公开调用，是现有真实模块事务/队列
  边界接缝，未重写 fixture、未新增测试端点。仅删死方法，无业务行为变化。

## 红绿证据（真实 exit）

环境：全部命令经 Git Bash 前台执行后正常结束，未启动常驻进程；临时目录、npm 缓存
与日志都在本 worktree 的 `.runtime/`（已 git 忽略，未 stage）。R5 为入口失败、R6 为
可靠配方复跑，两次原始日志都保留，不删改。

| 轮次 | 命令（工作目录） | 结果 |
|---|---|---|
| R1 | `npx eslint <8 个目标文件>`（frontend） | exit 0 |
| R2 | `bash scripts/check-e2e-helpers.sh`（worktree 根） | exit 0（no spec redefines an exported helper） |
| R3 | `npx vue-tsc -b`（frontend） | exit 0 |
| R4 | `npx tsc --noEmit --strict ...` 六个受影响 spec（frontend） | exit 0 |
| R5 | 首次 `./mvnw -Dtest=...`（`MSYS_NO_PATHCONV=1` 误设致入口 ClassNotFoundException） | exit 1，测试未启动，非业务 red |
| R6 | `.runtime/run-maven.sh`：source `.tools/env.sh` + Windows 绝对 `MAVEN_ARGS` + `cygpath -w` TEMP + `./mvnw -B -DforkCount=0 -Dtest=NotificationDeliveryIntegrationTest,NotificationBusinessIntegrationTest test` | exit 0；Tests run: 12, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS |

原始日志：`.runtime/logs/R1-eslint.log`、`R2-e2e-helpers.log`、`R3-vue-tsc.log`、
`R4-e2e-tsc.log`、`R5-maven.log`、`R6-maven.log` 与 `R6.exit`。

## 写集 hash（sha256，before → after）

| 文件 | before | after |
|---|---|---|
| frontend/e2e/helpers.ts | 01891acac50923fee06fd5342d06e2935f772cc376bef707ab29f615502b9008 | ad3385c3b1986eb80b5136b98ae5aafa53f85aefe393203c81dceab6940806c2 |
| frontend/e2e/dingtalk-sync.spec.ts | a35f60c0a31fafe4c7955a67f302c593f727c078864bd3d1777a0b46a9bcff7b | 202330a9572e50db7297d796ca2882598b2dc8729a3defb2bc8b5b904099d957 |
| frontend/e2e/substitution-approval.spec.ts | c23f2e3b13e2dc47a9058432cf466b51a01f82547e6410a143e3922acb607352 | e7f5a13e3a54e1e184989693502fb58c282cddf269155285ef415b468feb9ff6 |
| frontend/e2e/account-audit.spec.ts | 44f58576b0ba2c1d8f33d3ddddb7536156cf75d1b5cd62f8304aa6021a70006a | d6754afc3ce11aa6649fb10e3219f3725df88f77cea2a536a5739f8c0369cd09 |
| frontend/e2e/notification-delivery-logs.spec.ts | 19f5580876633b448e348dfbe22fe5abd05d0c0e2e8a24acf0b53a64695f0b77 | fc8032b2025e14d8bd6ff26f1dfe242fbf7510c0a1daa989cb99d0aa3a4d694b |
| frontend/e2e/substitution.spec.ts | bfbde1f80a0befbc41eaab146c5bdd4ee711fc0efc1d9c01f6ff0b25c03819d0 | f8898140008512969f73c972625491d51ab8c15de185f1504250398f19da07f3 |
| frontend/e2e/dingtalk-acceptance.spec.ts | de76c4ae854e4a3449e2bb5705c10fad5513fd3fcbed731c2074e0d03c6040a6 | 1e0fba7fe8e8c33dc3e8c960124e8f345cbc0d13ed1bcae5d3cb35dfd20f964e |
| frontend/src/views/SettingsView.vue | c63223688a189f8b3ca918e382d6460de9b642f167bf335760de7e2f1a15b22b | e9edd8bd170997a0ddcb31a413bbbce80e6e676b9b73b84207aa4d492e62f496 |
| backend/src/main/java/com/paiban/notification/service/NotificationService.java | 4148f1702137f793d7a3ad7fd02de95007fd6f3eb35fb207cc9418887d9d82c1 | 3ce83a4080361f37ab5744dcc12ab95de0605aa4ad79721c1348eb3e686baf02 |

## 变更文件清单（仅以下 10 个）

1. `frontend/e2e/helpers.ts`
2. `frontend/e2e/dingtalk-sync.spec.ts`
3. `frontend/e2e/substitution-approval.spec.ts`
4. `frontend/e2e/account-audit.spec.ts`
5. `frontend/e2e/notification-delivery-logs.spec.ts`
6. `frontend/e2e/substitution.spec.ts`
7. `frontend/e2e/dingtalk-acceptance.spec.ts`
8. `frontend/src/views/SettingsView.vue`
9. `backend/src/main/java/com/paiban/notification/service/NotificationService.java`
10. `.scratch/dingtalk-multiuser/whole-review-fixes-delivery.md`（本文件）

未改：`dingtalk-connection.spec.ts`（03SDK）、其他 spec、票面、`HANDOFF.md`、规格、
其他审查 raw。未做全仓 format；默认行为、错误断言与任意超时均未变。

## 合并与未验事项

- 收口前正常 merge `codex/dingtalk-multiuser` 最新：合并时集成分支已从 `7dc5657`
  推进到 `55e20e7`（`7dc5657` + 保留审查报告的 docs + 独立 604 行升级验收测试与
  截图），`fecb656` 为两分支共同祖先、无冲突；不 reset/amend 已交付提交，保留
  hooks。内容提交 `6871b6c`，合并提交 `1da9f37`。
- **未验证**：受影响浏览器 e2e 未实跑（主控将串行跑 9 spec / 20 test）；未跑
  `verify.sh --full`、Playwright、Vitest；未验证 PostgreSQL 真实库与真实钉钉
  租户/手机送达。以上属主控集成阶段职责，本交付不宣称整特性放行。
