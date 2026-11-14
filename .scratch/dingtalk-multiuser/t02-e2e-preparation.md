# 票 02 账号审计：E2E 准备（独立 QA 侧）
**范围**：只写 `frontend/e2e/account-audit.spec.ts` 与本记录，不改后端、组件、共享 helper；造数走公开 `/users`（`createAccount`/update/disable）与 `/teachers`，不 mock 页面接口。
## 用例（3 条，未跑）
1. 超级管理员：造唯一目标账号 → 改身份 → 停用，再建更新的干扰账号；进页先断言干扰行可见，按目标账号筛选后目标 3 行（停用/修改/手工创建，时间倒序）且干扰行消失；变更列断言「老师 → 教务管理员」「启用 → 停用」；再按动作「停用账号」筛到 1 行。
2. 教务管理员：设置页无「账号审计」页签；直开路由 403；接口 403。
3. 老师：同上（设置页本身 403）。
## 静态检查（仅发现，不等于浏览器通过）
- `npx eslint e2e/account-audit.spec.ts --max-warnings 0` → exit 0
- `npx playwright test --list e2e/account-audit.spec.ts` → 3 tests / 1 file，exit 0
- `bash scripts/check-e2e-helpers.sh` → 无重复 helper 定义，exit 0
- 未启动后端/前端/浏览器，未跑 Maven；功能未合入，真实执行与验收由主线程负责。
## 依赖契约（主线程与施工约定，未自行改动）
- 路由 `/settings/account-audit`、权限 `audit:view:account`、设置页签「账号审计」。
- 筛选项可访问名「动作类型」「目标账号」（严格 combobox name，不做兜底）；选项文案取自 `/account-audits/actions`。
- 表格列含时间/操作者/目标账号/动作/变更前→后。
## 主线程运行方式
`bash scripts/verify.sh --full` 或 `scripts/e2e.sh account-audit.spec.ts`；截图目录由 `PAIBAN_E2E_SCREENSHOTS` 指定。种子账号随机后缀，不碰旧数据。
