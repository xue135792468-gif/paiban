# 07 Standards 轴最终极小补充（只读，2026-11-13）

1. 审源 `dingtalk-t07-review-snapshot` @ `016859bc35a31f2cf5c93e2280e395cbad2b6d62`（FF 后工作树干净）；`git diff b253112...016859b -- frontend backend` 仅 `frontend/e2e/dingtalk-roles.spec.ts` 2+/2-，后端 0；只读，未跑测试/构建/网络/服务，未删文件、未改源。
2. 正向（:44）：`.page-tab[href="/settings/users"]` 仍断言超级管理员「能看到账号页入口」，与原 `filter({hasText:'账号'})` 同一意图、同一元素。
3. 负向（:72）：`toHaveCount(0)` 原样保留，仍证明教务「看不到账号页入口」；语气与强度未降低。
4. 选择器真实：`frontend/src/layouts/sectionTabs.ts:30` 生成 `{label:'账号', to:'/settings/users'}`，`PageTabs.vue:7-13` 为 `router-link.page-tab`（渲染真实 `href`）；未用 `.first()`/`nth()`/超时掩盖，符合《测试》「按容器/稳定可观察状态断言、整页文本只用于唯一文案」。strict-mode 双匹配起因是新增审计链接，属测试选择器修正，非产品行为变更。
5. 新增 Hard：P1=0，P2=0，P3=0（原 H1 已关闭，现存 0）。
6. 新增 Judgement：P3=0；J8 维持已裁定 P3（主控理由成立：40 秒门槛是外部串行预算，回引实现常量会随错误值变绿）；未关闭 P3 仍为 6（J1/J2/J3/J5/J6+J8）。
7. 新增 Spec 轴发现：0（本轴不重排他轴、不重评原级）。
8. 运行边界：四 spec 11 通过/退出码/`POST_COMMIT_SOURCE_IDENTICAL=1` 与两图手册 SHA 一致均为主控回执，我未复跑、未读 trace；本补充不据此宣称 07 放行。