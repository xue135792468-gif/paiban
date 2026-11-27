# 08 手机导航入口补齐交付（第二阶段，单一 fixer）

2026-11-13；worktree `dingtalk-whole-review-fixes`，分支 `codex/dingtalk-whole-review-fixes`，
起点为第一阶段交付 `d7ee6ff`（本批不改动第一阶段提交与 R6 证据）。

## 背景与红例

主控 `whole-mobile-navigation-qc.md` 记录：老师免登默认落 `/workbench/timetable`，
`MainLayout` 在 ≤900px 隐藏 aside、header 只有帮助/退出，原 08 用 `page.goto` 绕过入口。
主控新红例 `dingtalk-mobile-navigation.spec.ts`（集成 56ce4f7 起）真实失败于
`getByRole('button', { name: '打开导航' })` 不存在；认证/角色/默认 URL/隐藏 aside 均先通过。

## 本批改动（5 个源文件 + 本文件）

1. `frontend/src/layouts/MainNavigationMenu.vue`（新增）：桌面 aside 与手机 drawer 共用的
   同一份菜单模板与 `.menu/.group-title` 样式；两个真实 caller 用 `menuTestId` /
   `withTourAnchors` 区分：桌面保留 `data-testid="layout-menu"` 与 `data-tour`，抽屉用
   `mobile-layout-menu` 且不挂 tour 锚点，`@select` 向上抛。
2. `frontend/src/layouts/MainLayout.vue`：接入共享菜单；header 左侧加窄屏
   `aria-label="打开导航"` 按钮（桌面 `display:none`，≤900px 显示）；新增 `title="导航"`、
   `direction="ltr"`、`destroy-on-close` 的 `el-drawer`（`mobile-layout-menu`），
   `@select` 与 `route.fullPath` 变更都收起；≤520px 收敛 header 并省略姓名/标题防溢出。
   桌面 aside 仍用 `visibleGroups` + `activeMenu`，无手机专用权限分支。
3. `frontend/src/layouts/navigation.ts`：`resolveMenuPath('/substitution')` 走既有集中
   resolver——可 `substitution:submit` 且不具备完整 `WORKBENCH_MANAGER_PERMISSIONS` 的
   老师落 `/substitution/requests`；完整管理组合仍 `/substitution`；approve-only 且不能
   submit 的遗留入口保持原样。不改路由表、不改权限定义、不改默认落地。
4. `frontend/src/layouts/navigation.spec.ts`：老师 `/substitution` 目标断言改为
   `/substitution/requests`；补一条「完整管理组合仍进 `/substitution`」的 literal 回归。
5. `frontend/e2e/dingtalk-acceptance.spec.ts`：手机登录默认落地后，改真实点击
   「打开导航」→ 抽屉「调代课」进入 `/substitution/requests` 并断言抽屉收起，不再 `goto`。

未改：权限/管理员常量、路由表、默认落地页、后端 Java、主控新建的
`dingtalk-mobile-navigation.spec.ts`（由主控拥有，本批不修改）。

## 源冻结 + 静态/单测（真实 exit）

源冻结证据：运行前/后对 5 个源文件取 sha256，两次**完全相等**（测试只读、不改源）。

| 文件 | sha256（before == after） |
|---|---|
| MainLayout.vue | e199e93df651777c871fa447f29620197939f79b8ab7526d1645c152a0358a8a |
| MainNavigationMenu.vue | bc9b37a4c89550b6670dce897b6bc084315fccae4bf2e15cf4f3c7d25b2977e3 |
| navigation.ts | 846680e62fbaaa763d90dc6f3b8a08f027a5bb798e479f7e1f215eb593a4cad0 |
| navigation.spec.ts | 89f8556c985e06ae9b0450a4a644ac2c3858f8040f19d075e7fb0d16b95e90ab |
| dingtalk-acceptance.spec.ts | 89f819584bb7d8fa84e529850cb1723930cdb151350a4d173196b19ac07ae95f |

| 轮次 | 命令（frontend） | 结果 |
|---|---|---|
| R7b | `npx eslint --max-warnings 0 <5 文件>` | exit 0（无 error/无 warning） |
| R9 | `npx vue-tsc -b` | exit 0 |
| R8b | `npx vitest run --no-file-parallelism src/layouts/navigation.spec.ts src/router/roleAccess.spec.ts` | exit 0；2 文件 14 用例通过 |

日志在 `.runtime/logs/`（R7b-eslint2.log、R9-vuetsc2.log、R8b-vitest-nav.log、
phase2-freeze-before/after.sha256），git 忽略、不 stage。

## 未验事项 / 交接

- 未启动浏览器、未跑 Playwright；主控将串行跑新红例转绿及扩张用例（tour/global-scroll +
  本次 21 案例）。未跑 `verify.sh --full`。
- 本轮无 Java 改动，不重复 Maven（第一轮 R6 的 12 项后端证据保留不动）。
- 收口前 merge 集成分支最新 `de8dbd0`（新 test 合法数据入口修正 + drawer capture +
  QC/顾问/第一批 delta），带入主控新 spec；不修改其业务断言、不伪造入口/JWT、不 skip。
