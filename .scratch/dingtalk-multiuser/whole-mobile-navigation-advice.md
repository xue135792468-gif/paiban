# 手机视口全局导航缺失（代码检查 + 离线手机视口顾问方案）
- **问题界定（非实机放行，属代码检查 + 离线手机视口 <=900px 阻断）**：`useLoginLanding.ts:21-30` 将本人课表教师默认落到 `/workbench/timetable`（保持不变），但 `MainLayout.vue:242-246` 在 `<=900px` 隐藏 `.aside`，且 `.header`（`MainLayout.vue:20-38`）仅有标题/帮助/退出，导致窄屏下无入口从课表页前往 `/substitution`。
- **Target 文件集（仅 2 个文件，不改路由/权限/默认落地页）**：
  1. 新增 `frontend/src/layouts/MainNavigationMenu.vue`（窄复用组件，供桌面 `aside` 与移动 `drawer` 共用同一份 `visibleGroups`）。
  2. 修改 `frontend/src/layouts/MainLayout.vue`（接入移动端「打开导航」按钮、左侧导航 `el-drawer` 与窄屏姓名省略样式）。
- **为何值得抽 `MainNavigationMenu.vue` 及桌面 DOM/testid 隔离设计**：
  1. 消除 `aside` 与 `drawer` 的 `<el-menu>` 重复模板与 `.menu/.group-title` 重复样式，控制 `MainLayout.vue` 体积。
  2. 组件接收 `groups: ResolvedMenuGroup[]`、`activeMenu: string`、`menuTestId?: string`、`withTourAnchors?: boolean` 并 emit `select`：桌面端传 `menu-test-id="layout-menu" :with-tour-anchors="true"`，移动端 drawer 传 `menu-test-id="mobile-layout-menu" :with-tour-anchors="false"`，保证 `[data-testid="layout-menu"]` 与 `[data-tour]` 在全页唯一，零破坏 `global-scroll.spec.ts`、`dingtalk-roles.spec.ts` 及 `onboarding/tour.ts`。
- **`MainLayout.vue` 最小修补要点**：
  1. **Header 导航按钮**：左侧包一层 `.header-left`（`display: flex; align-items: center; gap: 8px; min-width: 0`），放入 `<el-button class="nav-toggle" circle text aria-label="打开导航" data-testid="mobile-nav-toggle" @click="navOpen = true"><el-icon><Menu /></el-icon></el-button>`；桌面默认 `display: none`，`@media (max-width: 900px)` 设 `display: inline-flex`，匹配 `getByRole("button", { name: "打开导航", exact: true })`。
  2. **左侧导航抽屉**：新增 `<el-drawer v-model="navOpen" title="导航" direction="ltr" size="240px" data-testid="mobile-nav-drawer">`（匹配 `getByRole("dialog", { name: "导航", exact: true })`），内嵌 `MainNavigationMenu`；在 `@select="navOpen = false"` 及 `watch(() => route.fullPath, () => { navOpen.value = false })` 双重收起，确保点击「调代课」（重定向至 `/substitution/requests`）或「课表工作台」后抽屉立即隐藏。
  3. **窄屏姓名省略防溢出**：在 `@media (max-width: 520px)` 下收敛 `.header-right { gap: 6px; min-width: 0 }`，对 `.page-title` 与 `.user-name` 设 `max-width: 96px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;`，防止新增图标按钮后长教师姓名撑破 390px 顶栏。
- **主要风险与防呆检查**：
  1. 切勿在 drawer 内的 `el-menu` 复用 `data-testid="layout-menu"` 或 `data-tour`，否则桌面/角色 E2E 与新手引导查询会因双节点冲突报错。
  2. `el-drawer` 内 `.menu` 需局部作用域样式（在 `MainNavigationMenu.vue` `<style scoped>` 内定义 `border-right: none; flex: 1; overflow-y: auto`），确保抽屉内菜单可独立纵向滚动且无右边框残留。
