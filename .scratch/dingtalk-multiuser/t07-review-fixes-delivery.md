# 07 复查整改交付（单一 fixer，待主控复核）

2026-11-13；worktree `dingtalk-t07-review-fixes`，分支 `codex/dingtalk-t07-review-fixes`，
起点 `64a8885cefda7782608d1a78a1a6f664cdf2f32a`（开工时已核 HEAD 与干净工作树）。
本轮只做 `.scratch/dingtalk-multiuser/t07-review.md` 主控处置表确认的 4 项：H1、J4、
J7、Spec P3-2。未重新裁决 J1/J2/J3/J5/J6 与 Spec P3-1，未扩大整改，未碰后端业务源、
绑定/权限/迁移、`spec.md`、票面 Status、HANDOFF 与主控 review。

## 处置结果

- **H1（P2，E2E 重复 helper）**：`trackBrowserErrors` 原样上收
  `frontend/e2e/helpers.ts` 并导出（仍是 console error + pageerror 收集，未吞任何
  错误、未削弱断言）；`dingtalk-connection.spec.ts` 与 `dingtalk-login.spec.ts` 删除
  本地同名函数改为 import。登录 spec 因此不再使用 `Page`，类型 import 一并移除；连接
  spec 的 `Page` 仍被 `trackProbeRequests` 使用，保留。其他 spec 无同名定义。
- **J4（P3，空白输入语义）**：`DingtalkConnectionProbe.vue` 新增局部
  `hasText(value)`（`value.trim().length > 0`）只承担「是否填了内容」判断，
  `canReuseSavedSecret` / `requiresNewSecret` / `canTest` 改用它。保存 AppKey 的比较
  仍是原字符串严格相等，发给后端的 AppKey/AppSecret 仍是原值（不 trim）：
  同保存 key + 纯空白 secret + 已有保存密钥 → `useSavedSecret=true`；AppKey 只有
  空白 → 按钮禁用；`saved-key ` 这类带尾空格的 key 与保存 key 不等，绝不借用旧
  secret。没有新增通用校验平台，后端 `hasText` 语义与发送契约未变。
- **J7（P3，SUPER_ADMIN 重复）**：`SUPER_ADMIN` 字面量从三个 spec 的本地定义移入
  `helpers.ts` 导出，`dingtalk-connection` / `account-audit` / `dingtalk-roles` 改为
  import。原账号与测试口令值由脚本从原文件搬运，未打印、未改写、未用占位符替代。
  `apiLogin` 的默认参数改为引用同一常量，参数显式标注 `string` 以避免 readonly
  字面量收窄。其余无关 `const`（`ACCOUNT_PASSWORD` 等）未动；
  `notification-delivery-logs.spec.ts` 自有的同名本地常量不在本票写集内，未改。
- **Spec P3-2（前端请求预算）**：只有探测 POST 带 `{ timeout: PROBE_TIMEOUT_MS }`，
  `PROBE_TIMEOUT_MS = 60_000` 是组件内局部常量；全局 `http` 30 秒与
  `DINGTALK_SYNC_TIMEOUT_MS` 180 秒未改。当前未保存值、固定安全文案、独立 busy、
  迟到响应失效的既有断言全部保留；组件 spec 新增一条在 outbound HTTP seam 上断言
  预算 > 40 秒（不等真实 40 秒、不加 sleep）。

## 红绿证据

环境：全部命令在本 worktree 前台执行后正常结束，未启动常驻进程；临时目录、npm 缓存
与日志都在本 worktree 的 `.runtime/`，未 stage。

| 阶段 | 命令（工作目录） | 结果 | 证据 |
|---|---|---|---|
| 首红（新增用例后、改源码前） | `frontend: npx vitest run --no-file-parallelism src/components/settings/DingtalkConnectionProbe.spec.ts src/views/SettingsView.spec.ts` | `Tests 7 failed / 17 passed`，shell 退出码 1。可核行为的真实红 5 条：三条探测 POST 无 timeout 第三参数（组件两条 + `SettingsView` 一条）、纯空白 AppKey 仍未禁用按钮、请求预算断言拿不到 timeout；另 2 条（空白 secret 复用、带尾空格 key）同样暴露真实行为红，但失败输出里 Expected 已是乱码，按 fixture 污染记录，不据此单独宣称行为红 | `.runtime/logs/vitest-red.log`（保留）。该次未落独立 `.exit` 文件，退出码来自当时终端输出，未补造 |
| 编码修复后的中间态 | 同一条 vitest 命令 | `Tests 2 failed / 22 passed`，两条失败都是中文期望被写成乱码（Received 已是正确文案），非行为失败 | 原日志曾写入 `.runtime/logs/vitest-green.log`，**已被随后的同名运行覆盖**；原件不在了，仅有当时终端输出，未事后补造 |
| 最终绿 | 同一条 vitest 命令 | `Test Files 2 passed`、`Tests 24 passed`，退出码 0；运行前后 10 个受控文件 SHA-256 完全一致 | `.runtime/logs/vitest-final-run1.log` / `vitest-final-run1.exit`=`0`；`final-source-before.tsv` / `final-source-after.tsv` |
| ESLint | `frontend: npx eslint --no-warn-ignored --max-warnings 0` 八个改动文件 | 通过 | `.runtime/logs/eslint-run1.log` / `eslint-run1.exit`=`0` |
| vue-tsc | `frontend: npx vue-tsc --noEmit -p tsconfig.app.json` | 通过 | `.runtime/logs/vue-tsc-run1.log` / `vue-tsc-run1.exit`=`0` |
| E2E 静态类型 | `frontend: npx tsc --noEmit --target es2022 --module esnext --moduleResolution bundler --skipLibCheck --strict --types node e2e/helpers.ts e2e/dingtalk-connection.spec.ts e2e/dingtalk-login.spec.ts e2e/account-audit.spec.ts e2e/dingtalk-roles.spec.ts` | 通过；另以 `--listFiles` 确认解析到本 worktree 的 `e2e/*` 与 `@playwright/test` 类型，而非空转 | `.runtime/logs/e2e-tsc-run1.log` / `e2e-tsc-run1.exit`=`0`；`e2e-tsc-listfiles.log` |

`.runtime/logs/hash-red-before.txt`、`hash-eslint-before.txt` / `hash-eslint-after.txt`
是早期运行的哈希清单；`.runtime/logs/vitest-gr*` 的覆盖情况见上表，没有第二份原件。
`scripts/check-e2e-helpers.sh` 未手工重复执行，交给 pre-commit 钩子（见本提交钩子结果）。

## 改动文件

- `frontend/src/components/settings/DingtalkConnectionProbe.vue`
- `frontend/src/components/settings/DingtalkConnectionProbe.spec.ts`
- `frontend/src/views/SettingsView.spec.ts`
- `frontend/e2e/helpers.ts`
- `frontend/e2e/dingtalk-connection.spec.ts`
- `frontend/e2e/dingtalk-login.spec.ts`
- `frontend/e2e/account-audit.spec.ts`
- `frontend/e2e/dingtalk-roles.spec.ts`
- 本交付记录

`SettingsView.vue` 未改：探测请求形状由 `DingtalkConnectionProbe` 组件 spec 钉住，
视图侧只透传未保存值。未改 backend、真实客户端、绑定/权限/迁移、`spec.md`、票面
Status、HANDOFF、主控 review、README/手册与其他组件；没有删除任何文件或目录。

## 未验证范围

- E2E 实跑：本轮未启动 E2E 后端、前端或浏览器；直接受影响的四组 spec 由主控整合后
  串行执行。
- 两轴增量复核、真 PostgreSQL、真实钉钉租户与手机、full verify、部署与 push。
- 因此本记录不宣称 07 放行，也不代表 08 可以开工。