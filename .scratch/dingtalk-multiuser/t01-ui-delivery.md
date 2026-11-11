# 票01：前端与前端测试切片

2026-11-11，分支 `codex/dingtalk-t01-ui`，工作树 `.worktrees/dingtalk-t01-ui`。

开工基点 `54977a1`（= 当时 `codex/dingtalk-multiuser` tip，merge-base 与 HEAD 相同，无需变基）；提交前已合并集成最新 tip `4fa09b6`（服务端 SettingsController 字段过滤 + 4 条 HTTP 回归），为 fast-forward，未 reset 任何用户文件。

范围：只改 `frontend/**` 与本文件。未触碰 `backend/**`、票面、ADR。后端角色迁移、种子与权限矩阵由 01 后端切片负责；本切片只做前端入口、提交内容与前端测试/E2E。

## 测试 seam

与用户事前确认的边界一致：

1. **纯函数**：身份清单与页签规则（`constants/roles.ts`、`layouts/sectionTabs.ts`）。
2. **页面组件公开行为**：`SettingsView`（钉钉区块可见性、提交内容）、`UserAdminView`（改身份三选一）、`TimetableView`（老师默认自己的课表）、`SettingsLayout` / `WorkbenchLayout`（页签）。
3. **路由守卫矩阵**：真实 `routes` 表 + 内存 history，断言三身份对草稿/规则/设置/账号页的放行与 403。
4. **Playwright**：一条三身份权限矩阵真实页面用例（`e2e/dingtalk-roles.spec.ts`），待角色后端切片整合后统一跑。

## 红 → 绿记录

命令均在本工作树 `frontend/` 下执行，统一 `npx vitest run --no-file-parallelism <文件>`。

| 切片 | 红（首个失败断言原文） | 最小实现 | 绿 |
|---|---|---|---|
| 身份清单 | `roles.spec.ts`：模块不存在，`Test Files 1 failed / Tests no tests` | 新增 `constants/roles.ts`（三身份 + `primaryRoleLabel`） | 3 passed |
| 页签规则 | `sectionTabs.spec.ts`：模块不存在，`no tests` | 新增 `layouts/sectionTabs.ts` | 4 passed |
| 页签接线 | `sectionLayout.spec.ts`：`expected [ '草稿与排课', '课表' ] to deeply equal [ '课表' ]`；设置页 `2 failed \| 1 passed` | 两个 layout 改用纯函数生成页签 | 3 passed |
| 设置页钉钉区块 | `expected true to be false`（教务仍渲染 `#dingtalk-integration`） | 区块 `v-if="canManageIntegration"`，并按权限跳过集成配置/日志读取 | 6 passed |
| 设置页提交内容 | PUT 实参多出 `"integration.dingtalk.event-token": ""` | 无 `integration:manage` 时剔除 `integration.*` 字段 | 同上 6 passed |
| 改身份三选一 | 选项来自 `/roles`；提交 `roleCodes: "admin"`（字符串）而非 `['admin']` | 单选取值 `identity` + `roleCodes: [identity]`，选项来自 `PRESET_ROLES`，去掉 `/roles` 请求 | 4 passed |
| 老师默认个人课表 | `expected [ '/classes', …(6) ] to include '/timetable/teacher/7'` | 非排课/编辑身份且已关联教师时默认 `teacher` 模式并选中本人 | 3 passed |
| 路由守卫矩阵 | 首轮 1 failed：把裸 `/workbench` 当作菜单入口 | 见下方说明，改为断言「菜单解析目标 = `/workbench/timetable`」，裸路径仍钉住 403 | 5 passed |

路由矩阵那条红暴露的是测试自身语义错误而非产品缺陷：菜单点「课表工作台」走的是 `resolveMenuPath` 解析后的 `/workbench/timetable`，而守卫对裸 `/workbench`（调度页）必须继续返回 403。两者都保留为断言，防止日后把 `timetable:view` 当成管理员入口。

汇总（提交前最后状态）：

- 定向：9 个文件 / 43 用例通过。
- 全量前端单测：93 个文件 / 712 用例通过，退出码 0。
- `npm run lint` 0 warning；`npm run build`（vue-tsc + vite）通过。

## 变更路径

新增：

- `frontend/src/constants/roles.ts`（+ `.spec.ts`）：三身份清单、主身份徽标、`isPresetRoleCode`
- `frontend/src/layouts/sectionTabs.ts`（+ `.spec.ts`、`sectionLayout.spec.ts`）：工作台/设置页签的权限规则
- `frontend/src/router/roleAccess.spec.ts`：三身份 × 草稿/规则/设置/账号页守卫矩阵
- `frontend/e2e/dingtalk-roles.spec.ts`：三身份真实页面权限矩阵（待整合跑）

修改：

- `frontend/src/layouts/SettingsLayout.vue`、`WorkbenchLayout.vue`：页签按权限生成（教务无「账号」，老师只剩「课表」）
- `frontend/src/layouts/MainLayout.vue`、`frontend/src/views/HomeView.vue`：身份徽标改用 `primaryRoleLabel`，去掉「班主任 / 教师」旧文案
- `frontend/src/views/SettingsView.vue`（+ `.spec.ts`）：钉钉区块只对 `integration:manage` 可见，不再为无权限账号请求集成配置；保存时剔除 `integration.*` 字段，避免后端「混合更新整单 403」
- `frontend/src/views/UserAdminView.vue`（+ `.spec.ts`）：改身份/新建身份改为三选一单选，角色标签走统一文案，不再请求 `/roles`
- `frontend/src/views/TimetableView.vue`（+ `.spec.ts`）：老师默认落到本人教师课表，行政班/全校为额外只读入口
- `frontend/e2e/t09-acceptance.spec.ts`：退役「班主任只能看到自己授权班级」用例（该身份已下线，无法再建号登录），文件头写明原因

## 越权核对清单（前端侧）

老师新增 `timetable:view` 后，前端所有相关判断逐条核对：

1. `TimetableView.vue`：`canViewAll` 只控制「行政班/教师/全校」只读切换与目标选择；发布、草稿、排课按钮都不在课表页。`showSchedulingGuide` 仍要求 `scheduling:run`。落地页对非 `scheduling:run`/`timetable:edit` 且已关联教师者固定为本人课表。
2. `WorkbenchLayout.vue`：「草稿与排课」页签要求 `scheduling:run` 或 `timetable:edit`；老师只剩「课表」，不会点进 403。
3. `router/index.ts`：`/workbench` 需 `scheduling:run|timetable:edit`，`/workbench/drafts/:id` 需 `timetable:edit`，`/rules` 需 `constraint:manage`，`/settings/general` 需 `settings:manage`，`/settings/users` 需 `user:manage`；`roleAccess.spec.ts` 钉住老师对这四个入口全部 403。
4. `navigation.ts`：菜单可见性、`workbenchLandingPath`/`dataLandingPath` 与路由同一套权限；老师「课表工作台」解析为 `/workbench/timetable`。
5. `SubstitutionWorkbenchView.vue`：`isManager` 要求 `WORKBENCH_MANAGER_PERMISSIONS` 三项全含（含 `substitution:approve`），老师不满足，调代代发入口不出现。
6. `SubstitutionsView.vue`：全量记录视图要求 `substitution:view`/`substitution:view:class`，老师只有 `substitution:submit`。
7. `DraftEditorView.vue` / `SchedulingView.vue`：编辑、发布、求解分别要求 `timetable:edit`/`timetable:publish`/`scheduling:run`。
8. `RuleBuilderView.vue`：编辑要求 `constraint:manage`；老师即使能被路由拦下，组件内也不提供写入能力。
9. `SettingsView.vue`：钉钉区块与「AI 供应商」入口仍要求各自权限；教务能改业务设置，但提交里不含钉钉字段。
10. `HomeView.vue`：「去排课」要求 `scheduling:run`，快捷卡片只给老师放开课表/调代/本人授课任务。

## 待整合后运行的 E2E

角色后端切片（三角色、`integration:manage` 过滤、`user:manage`/`audit:view:account` 守卫）合进 `codex/dingtalk-multiuser` 之后，在集成分支执行：

```
# Git Bash（C:/Users/liu/AppData/Local/Programs/GitPortable/bin/bash.exe），仓库根目录
scripts/e2e.sh e2e/dingtalk-roles.spec.ts
```

等价直接调用：`cd frontend && npx playwright test e2e/dingtalk-roles.spec.ts`（`PAIBAN_E2E_ISOLATED=1` 时用 28080/25173 隔离端口）。

用例覆盖：

- 超级管理员：`#dingtalk-integration` 可见、设置页出现「账号」页签、账号页可打开、改身份下拉恰好三项（超级管理员/教务管理员/老师）。
- 教务管理员：菜单有「设置」但无钉钉区块、无「账号」页签，`保存设置` 返回 200（不被混合更新拒绝），直接打开 `/settings/users` 落到 403。
- 老师：菜单无「排课规则」「设置」；`/workbench/timetable` 只剩一个课表页签、默认选中本人教师课表、保留「全校」只读入口；打开 `/settings/general` 落到 403。

## 未运行 / 边界

- 本切片未启动后端、未跑 Playwright、未跑后端集成测试与 `scripts/verify.sh`（按约定禁止并行启动后端与全量门禁）。
- 未访问生产库、未连真钉钉、未使用任何生产凭证；临时日志 `frontend/../.tmp-unit.log` 在提交前删除。
- 账号审计页不在本票范围（02 实现）；本切片只为 `audit:view:account` 预留了不误判的测试身份数据。
