# 03: 删除/停用 + 7 天撤销：核心数据

**What to build:** 教师、班级、课程、年级、学期、授课任务都能删除或停用；删除前显示影响；被历史引用的只能停用；所有删除进入 7 天“最近删除”，可撤销。

**Blocked by:** 01 (7 页外壳与旧入口收敛).

**Status:** in-progress

- [x] 教师、班级、课程、年级、学期、授课任务都有删除/停用入口
- [x] 删除前弹窗列出影响（例如“会一起删掉 N 条授课任务”）
- [x] 被已发布课表或调代记录引用的对象只提供“停用”，不提供硬删
- [x] 无引用的对象可直接删；只被草稿/规则引用的可删，并提供“一起删/改为停用”
- [x] 删除后 7 天内在“最近删除”里可恢复
- [x] 停用对象不出现在新建/编辑的下拉里
- [x] 删除与恢复都写操作日志

**Notes:** 先做这 6 类核心数据，规则/草稿/配方/用户/AI 供应商在票 04 复用同一套机制。

## Delivery

已实现于 `feat/product-trim-03`（2026-11-04）。

- **数据**：V48 给 `teacher` 加 `deleted_at`，给 `admin_class` / `course` / `grade` / `semester` / `assignment` 加 `active` + `deleted_at`；新表 `recent_deletion` 以 `batch_id` 归组，默认保留 7 天。
- **服务**：`foundation/service/LifecycleService` 统一 impact / delete / disable / enable / restore / purgeExpired；`RecentDeletionCleanupJob` 每日 03:30 清理过期标记（业务行保留）。删除、级联、恢复、审计同事务。
- **接口**：教师/班级/课程/年级/学期/授课任务均有 `GET .../delete-impact`、`DELETE ...?mode=DELETE|CASCADE`、`POST .../disable`、`POST .../enable`；`GET /api/recent-deletions`、`POST /api/recent-deletions/{batchId}/restore`。权限按现有 `FOUNDATION_MANAGE` / `SEMESTER_MANAGE` / `ASSIGNMENT_MANAGE` 分组。
- **语义**：被已发布课表或调代记录引用 → `canDelete=false`、DELETE 409，只能停用；只被授课任务/班级引用时普通 DELETE 也 409，前端显式提供“一起删除（CASCADE）”；学期不级联，级联全部是软删除。停用的教师仍参与求解（只从下拉隐藏），停用的授课任务不进入求解。
- **前端**：`api/lifecycle.ts`、`DeleteImpactDialog`、`LifecycleCell`，6 个列表页接入；新增“最近删除”页 `/settings/recycle-bin`（设置壳标签入口）；使用列表做下拉的页面按 `active !== false` 过滤。
- **测试**：`LifecycleIntegrationTest` 8 例 + Foundation/Semester/Assignment/PeriodReference 定向共 39 例通过；另跑 10 个相关后端测试类 91 例通过；前端 566 个单测通过（含新增 5 例）；`npm run lint`、`npm run build`、`check-sfc-size.sh`、`check-blank-checks.sh`、`check-module-boundaries.sh`、`check-migrations.sh` 通过；`scripts/e2e.sh e2e/lifecycle.spec.ts` 2 例通过。
- **既有测试调整（票面要求）**：`FoundationIntegrationTest` 改为断言软删除后教师从列表消失、班级行保留；`PeriodReferenceIntegrityTest` 改为学期软删除后草稿仍保留（可恢复）；`productTrim.spec` 把 `/admin/rooms` 登记为重定向（票 02 遗留漂移）并加入 recycle-bin 路由。
- **风险**：唯一键仍覆盖已软删行（工号、课程名、班级/年级/学期名、授课任务的班+课），删除后用相同唯一值新建会 409，需先在最近删除恢复；物理清理与规则引用的“已失效”展示留给后续票。
- **提交**：`5f7fdd4` 后端；`2f51724` 前端。
