# Standards 手机导航 delta 复核（d7ee6ff…52cbf13，独立只读）

- 源 `E:/排课系统/.worktrees/dingtalk-mobile-nav-review-snapshot`，HEAD `52cbf131382f769a25dc5e7b781ad4f8abcbebce`，tracked clean，未移动 d7/be48；未跑测试/构建/网络，未 stage/commit。delta 16 文件，源码仅 6：`MainLayout.vue`、新 `MainNavigationMenu.vue`、`navigation.ts`、`navigation.spec.ts`、新 `dingtalk-mobile-navigation.spec.ts`、`dingtalk-acceptance.spec.ts`；无后端改动、无迁移。
- **新 Hard：0**（P1/P2/P3 全 0）。新 spec 复用 `trackBrowserErrors/installFakeWindowDd/apiCall/capture`，无新增内联 collector、无自写登录/下拉，未复制公共交互。
- **新 Judgement：0**。`MainNavigationMenu` 是两处真实 caller（aside:11-16、drawer:64-70）的共享实现，不属 Middle Man；两处传同一 `visibleGroups`+`activeMenu`，差异只在 `menuTestId` 与 tour 锚点，符合单一来源。
- **M1 产品修复核对无问题**：`MainLayout.vue:335-341` 在 ≤900px 隐藏 `.aside` 并显示 `.nav-toggle`（base `display:none`，`data-testid=mobile-nav-toggle`，aria-label 打开导航）；drawer `destroy-on-close`、`direction=ltr`、`mobile-layout-menu`、`with-tour-anchors=false`，桌面保留 `layout-menu` 与 `data-tour`（tour 选择器仍唯一）；`@select` 与 `watch(route.fullPath)` 双保险收起属刻意冗余，不致重复渲染，不升级。≤520px 对 `.page-title/.user-name` 做 96px 省略，不引入横向滚动。
- **resolver**：`navigation.ts:111-118` 仅在「有 `substitution:submit` 且非完整 `WORKBENCH_MANAGER_PERMISSIONS`」时把 `/substitution` 指向既有 `/substitution/requests`，未新增路由、未改 defaultLogin/权限；完整管理组合与管理员仍进工作台（`navigation.spec.ts:71` 钉住）。教师保留 `assignment:view:own`，故「数据管理」入口合法，测试只断言其可见，未反向改权限。
- **e2e 断言只增不减**：原 acceptance 由 `page.goto` 改为真实「打开导航→调代课」并断言 URL 与抽屉收起，后续断言原样；新 nav spec 断言默认落 `/workbench/timetable`、aside 隐藏、toggle 可见、无设置/规则/AI 入口、往返与 `errors` 空，清理仅关停自建账号与配置。
- **原轴计数不变**：Hard P1 0 / P2 0 / P3 0（H1 关闭）；Judgement P3 **1**，仅 **J4**（主控不采纳、非延期、**未修复**）。本 delta 未改变任何原项。
- **边界**：browser 12-spec targeted 仍在跑，未 claim green；我不复跑。修复源对象与 1007 源 Git 56ce 一致、以及“除新增 drawer capture/合法数据入口外红 oracle 未削弱”按主控核对记录，未独立重放。full、真实 PG/钉钉租户/手机仍未验证，**不宣称整规格通过**。
