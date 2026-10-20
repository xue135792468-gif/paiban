## Summary

一期 MVP 全量实现：15 张票据、7 个后端模块、20 个前端页面。本 PR 同时收口了两轮独立复查（MiMo 四视角 + code-review 双轴）的发现。

```text
backend/src/main/java/com/paiban/
├── identity/       # JWT、RBAC 三角色、账号 Excel 导入
├── foundation/     # 学期/作息、年级/行政班/教师/教室/课程、Excel 导入
├── scheduling/     # 授课任务、24 条约束、Timefold 求解、草稿、约束 SPI
├── timetable/      # 基线发布、四视角合并视图、Excel/PDF 导出
├── substitution/   # 调代申请/审批/直接生效、叠加与追溯
├── notification/   # 模板、系统内通知、Composite 降级
├── integration/    # 钉钉免登/组织同步/工作通知（本地 + 假实现）
└── onboarding/     # 向导进度与“还差什么”
```

```diff
 发布课表
-  deleteBySemesterId + 允许学期内再次发布
+  该学期已发布则 409：基线只读，变更走调代
+
 授权
-  user.hasRole("admin")
+  users.hasPermission(user, SUBSTITUTION_VIEW)
```

## Evidence

- **Before:** code-review 双轴共 18 条发现（Standards 9 / Spec 9）；118 个后端测试、4 个 E2E。
- **After:**
  - `cd backend && ./mvnw test` → **119 tests, 0 failures, 1 skipped**（Testcontainers，CI 以 `-Dtestcontainers=true` 执行）
  - `cd frontend && npm run test:e2e` → **5 passed**，新增拖拽场景：
    - 拖到同班已占用格 → 红色高亮 + “冲突原因”
    - 拖到空位 → “已移动课节”
  - `npm run lint && npm run build` → 通过
  - 新增 `EngineConstraintTest` 插件发现用例（18/18）

## Merge Danger

**Door:** two-way

特性分支合入，无数据库迁移变更；回滚只需 revert 本分支。生产部署前需设置 `PAIBAN_JWT_SECRET` 与（可选）`PAIBAN_ADMIN_PASSWORD`。

**Blast Radius:** moderate

触及认证、发布门禁、通知范围与课表渲染；已有 119 个后端测试与 5 个 E2E 覆盖主链路。
