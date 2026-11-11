# 票 03-B：前端免登说明页、密码入口、账号登录类型与教师 UID 权限切片

2026-11-11，分支 `codex/dingtalk-t03-ui`，工作树 `E:/排课系统/.worktrees/dingtalk-t03-ui`。

开工基点 `3f73700`，提交前已 fast-forward 合并 `codex/dingtalk-multiuser` 最新提交 `fc3b325`。每个子任务前均检查物理内存空闲（4.7–5.0 GiB）。

## 范围与边界

- 本切片为 **03-B 前端与浏览器用例准备**，严格遵循 `.scratch/dingtalk-multiuser/t03-wire-contract.md` 与 `t03-module-plan.md`。
- 只改动 `frontend/**` 与本交付文档，未修改 `backend/**`、迁移、04 功能、其他票据或根全局配置，未重做登录页视觉。
- 变更路径：
  - `frontend/src/api/http.ts`（+ `http.spec.ts`）：仅针对 `/auth/dingtalk` 的 401 响应跳过全局清 token 与 `/login` 强制跳转，使免登失败的结构化原因码原样传给 `LoginView`，不破坏正常会话过期 401 行为。
  - `frontend/src/composables/useDingTalkLoginNotice.ts`：6 个稳定原因码（`NOT_PROVISIONED`、`AMBIGUOUS_IDENTITY`、`ACCOUNT_DISABLED`、`DINGTALK_DISABLED`、`DINGTALK_CALL_FAILED`、`ADMIN_BINDING_REQUIRED`）的友好说明映射；前 5 项说明可联系教务处核实教师档案，但绑定钉钉 UserID、重新启用账号、配置钉钉集成参数均须由超级管理员操作；`ADMIN_BINDING_REQUIRED` 明确唯一确认路径为超级管理员在「教师管理」中填写钉钉 UserID 确认绑定（不含「或钉钉同步」），绝不拼接或回显原始 vendor 错误消息。
  - `frontend/src/views/LoginView.vue`（337 行，≤ 600 行限制）+ `LoginView.spec.ts`：钉钉 UA 自动尝试免登；配置关闭 / 缺少 `corpId` / SDK 加载失败 / `dd.ready` 有限超时（5s）/ `requestAuthCode` 失败 / 后端返回原因码时均渲染 `[data-testid="dingtalk-login-notice"]` 说明块，不再静默退回密码表单；提供 `[data-testid="switch-password-login"]`（「我是管理员，用密码登录」）写入 `?mode=password` 并展示密码表单，带 `?mode=password` 刷新跳过自动免登不循环；普通浏览器直接渲染密码表单；免登成功复用 `useLoginLanding().landAfterLogin()`。
  - `frontend/src/stores/auth.ts`：`CurrentUser`（导出别名 `UserSummary`）追加可选 `loginType?: 'PASSWORD' | 'DINGTALK'`。
  - `frontend/src/views/UserAdminView.vue`（393 行）+ `UserAdminView.spec.ts`：`row.loginType === 'DINGTALK'` 隐藏「重置密码」按钮（缺失视为 `PASSWORD` 保持向后兼容）；提升钉钉免登老师为管理员时无密码输入、提交载荷不含密码字段。
  - `frontend/src/constants/permissions.ts`、`frontend/src/views/TeachersView.vue`（404 行）+ `TeachersView.spec.ts`：导出 `INTEGRATION_MANAGE_PERMISSION = 'integration:manage'`，钉钉 UserID 输入框（`[data-testid="teacher-dingtalk-userid"]`）仅 `auth.hasPermission(INTEGRATION_MANAGE_PERMISSION)` 可编辑；教务管理员只读可见，且提交新建/编辑教师时省略 `dingtalkUserId` 字段（不会传空串清除已有绑定），普通字段如姓名正常保存。
  - `frontend/src/api/accountAudit.ts`、`frontend/src/utils/accountAuditChange.ts`（+ `accountAuditChange.spec.ts`、`AccountAuditView.spec.ts`）：`AccountSnapshot` 追加可选 `loginType` 与 `dingtalkUserId`，按 typed allowlist 展示登录方式与钉钉 UID；`null`/`undefined` 统一显示为 `—`（不给历史未知 `null` 贴「未绑定」标签），旧快照（`loginType == null && dingtalkUserId == null`）升级到新快照的常规修改不虚报登录方式或 UID 变更，配对事件（`DINGTALK_LINK`）展示 `钉钉UID：— → <uid>`，忽略任意凭据/hash 或未知对象键。
  - `frontend/e2e/helpers.ts`、`frontend/e2e/dingtalk-login.spec.ts`：`uiLogin` 第 4 参数 `options` 追加可选 `loginPath?: string`（默认 `'/login'`，向后兼容所有既有调用）；准备 2 条 Playwright 用例（免登失败说明页 + `?mode=password` 跳过循环并复用 `uiLogin(..., { loginPath: '/login?mode=password' })` 完成密码登录；`已绑定UID老师免登并取得同一账号` 只读复用现有 `dd-001` 绑定教师或仅在缺失时新建自有教师、只清理自有教师 UID、校验 `POST /api/auth/dingtalk` 200 响应 `token`/`user.id`/`user.teacherId` 与 `GET /api/auth/me` 一致，并记录断言 `console` error / `pageerror`），待后端 A/C 整合后由主线程统一运行。
  - `.scratch/dingtalk-multiuser/logs/t03-ui-final.log`：6 个文件 38 条定向 Vitest 原始输出日志。

## 主线程预审 3 项修复记录（相对 `8157380`）

1. **原因码文案职责边界**：`ADMIN_BINDING_REQUIRED` 删除「或钉钉同步」，明确本版唯一确认路径为超级管理员在「教师管理」填写钉钉 UserID；其余原因码明确联系教务处核实教师档案，而绑定 UID、重新启用账号、配置钉钉集成均由超级管理员操作。
2. **审计快照 `null` 未知语义**：`dingtalkUserId` 为 `null`/`undefined` 时统一显示 `—`，不将历史未知 `null` 标为「未绑定」；旧未知快照（`before.loginType == null && before.dingtalkUserId == null`）在常规更新时不虚报 UID 变更，`DINGTALK_LINK` 配对事件显示 `钉钉UID：— → <uid>`。
3. **E2E 不篡改外部 fixture + 真实响应断言 + 复用 `uiLogin`**：第 1 条复用 `uiLogin(page, 'admin', 'admin123', { loginPath: '/login?mode=password' })`；第 2 条改名为 `已绑定UID老师免登并取得同一账号`，只读检查并复用已绑定 `dd-001` 的教师（多个 `dd-001` 或关联账号全停用时直接报前置错误，绝不清空或启用他人 fixture），仅在不存在时创建自有教师并在 `finally` 仅清自有 UID；等待 `POST /api/auth/dingtalk` 200 响应并校验 `user.id`（有启用账号时等于稳定最小 ID，新建时 `loginType === 'DINGTALK'`）、`user.teacherId` 及 `GET /api/auth/me` 一致性，两条均记录并断言 `console` error 与 `pageerror`。

## TDD 红 → 绿记录

测试 seam 均为公开 DOM、用户输入、路由地址、HTTP 拦截器结果与请求载荷，不使用内部 call counts，不改动既有测试断言。

| 垂直切片 | 红（首次失败摘要） | 最小实现 | 绿 |
|---|---|---|---|
| 1. `http.ts` 免登 401 透传与普通 401 保留 | `http.spec.ts`：`expected null to be 'existing-token'`（1 failed \| 1 passed） | 响应拦截器排除 `/auth/dingtalk` 的 401 清会话与强制跳转 | 2 passed |
| 2. `LoginView` 6 原因码说明、SDK/配置失败、`?mode=password` 跳过循环与 `landAfterLogin` | `LoginView.spec.ts`：`[data-testid="dingtalk-login-notice"]` 不存在、落地页为 `/` 而非 `/onboarding`（10 failed \| 1 passed） | 新增 `useDingTalkLoginNotice.ts` 并接入 `LoginView.vue`（含 `dd.ready` 5s 超时与 `?mode=password` 分支） | 11 passed |
| 3. `UserAdminView` 隐藏 `DINGTALK` 重置密码与提升身份无密码 | `UserAdminView.spec.ts`：`expected 'dd_li李老师...重置密码 停用' not to contain '重置密码'`（1 failed \| 7 passed） | `v-if="row.loginType !== 'DINGTALK'"` 控制重置密码按钮 | 8 passed |
| 4. `TeachersView` 按 `integration:manage` 控制 UID 只超管可改、教务省略 UID | `TeachersView.spec.ts`：`[data-testid="teacher-dingtalk-userid"]` 缺失（2 failed \| 2 passed） | `INTEGRATION_MANAGE_PERMISSION` 控制 disabled 与提交 payload 是否携带 `dingtalkUserId` | 4 passed |
| 5. `AccountAudit` 快照 `loginType` / `dingtalkUserId` allowlist 展示与配对差异 | `accountAuditChange.spec.ts` + `AccountAuditView.spec.ts`：缺少登录方式与 `钉钉UID：— → ...`（2 failed \| 11 passed） | `AccountSnapshot` 与 `accountAuditChange.ts` 按 allowlist 格式化 `loginType` 与 `dingtalkUserId`，未知 `null` 统一显示 `—` | 13 passed |

## 已执行验证命令与退出码

均在 `E:/排课系统/.worktrees/dingtalk-t03-ui/frontend`（或工作树根）串行执行：

1. `npx vitest run --no-file-parallelism src/api/http.spec.ts src/views/LoginView.spec.ts src/views/UserAdminView.spec.ts src/views/TeachersView.spec.ts src/utils/accountAuditChange.spec.ts src/views/AccountAuditView.spec.ts > ../.scratch/dingtalk-multiuser/logs/t03-ui-final.log 2>&1` → **6 files / 38 tests passed, exit 0**（原始输出见 `.scratch/dingtalk-multiuser/logs/t03-ui-final.log`）
2. `npm run lint`（`eslint . --max-warnings 0`） → **exit 0**
3. `npx vue-tsc -b` → **exit 0**
4. `npx playwright test --list e2e/dingtalk-login.spec.ts` → **2 tests in 1 file, exit 0**
5. `bash scripts/check-sfc-size.sh && bash scripts/check-e2e-helpers.sh && bash scripts/check-ticket-status.sh` → **exit 0**（`LoginView.vue` 337 行、`UserAdminView.vue` 393 行、`TeachersView.vue` 404 行，均 ≤ 600 行）

## 未验证项（留待主线程整合 A/C/A2/A3 后验证）

- **Playwright 真实浏览器与后端联调（未跑）**：`frontend/e2e/dingtalk-login.spec.ts` 仅通过 `--list` 校验语法与用例注册，未启动全栈服务，待主线程合入 A/C 后端免登与绑定保护后串行运行。
- **最后一个启用 PASSWORD 超级管理员守卫（未验证）**：由后端 A3 切片实现与整合验证，本前端切片不声称已验证。
- **后端 Maven 测试与全量 `scripts/verify.sh`（未跑）**：本切片仅涉及前端代码与测试。
