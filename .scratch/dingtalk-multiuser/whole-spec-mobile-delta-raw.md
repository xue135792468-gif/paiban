# 手机导航 delta：Spec 轴（d7ee6ff…52cbf13）

审源 `52cbf131382f769a25dc5e7b781ad4f8abcbebce`（只读 WT，rev-parse 全 SHA 已核、干净）；diff `d7ee6ff...52cbf13` 6 个源文件（5 UI + 新 nav spec，另 1 acceptance spec 改真实点击）。未跑测试/构建/网络，未改源。

- 结论：新增规格缺失 0、越界 0；行为变更 1（P3）；独立计数 P0 0 / P1 0 / P2 0 / P3 1，最坏项 P3-1。
- M1 覆盖逐项对上：默认仍落本人课表（`dingtalk-mobile-navigation.spec.ts:46`，`useLoginLanding` 未改）；≤900 aside 隐藏且 toggle 显示（`MainLayout.vue:336-340`）；drawer 复用同一 `visibleGroups/activeMenu`（`MainLayout.vue:55-66`）；桌面保留 `layout-menu`+`data-tour`、手机 `mobile-layout-menu` 不带 tour（`MainNavigationMenu.vue:15、28-31`）；`destroy-on-close` + select 与 `route.fullPath` watch 双关闭（`MainLayout.vue:58、200-205`）。
- 入口解析：`resolveMenuPath` 仅 `can(substitution:submit)` 且非完整 `WORKBENCH_MANAGER_PERMISSIONS` 时去 `/substitution/requests`（`navigation.ts:111-118`）；完整组合仍 `/substitution`，单测已钉（`navigation.spec.ts:70`）。路由表、权限点、后端与布局层手机专用权限均未新增；老师「数据管理」仍由合法 `assignment:view:own` 可见（`navigation.ts:65-74`、spec 62 行）。
- P3-1：注释称「遗漏权限的老入口保持原样」，但缺少 `timetable:view` 的自定义 submit+approve 角色也会改落 requests；其「待审批」页签仍在（`SubstitutionsView.vue:49、89`），不越权，属行为微变，交付说明需注明，预置三角色不受影响。
- 边界：真实 red→green 与 12 spec 浏览器回执尚未完成，本文件不宣布 browser 转绿或 full 通过；原轴 0 不改写、不抹 M1 由主控 QC 发现的来源。
