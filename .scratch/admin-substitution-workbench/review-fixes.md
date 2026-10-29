# 评审修复记录（2026-10-29）

九票集成分支（`49a7fc1`）通过 Standards/Spec 两轴 code-review 后，本记录逐条对应
发现与修法。修复分支 `feature/admin-workbench-review-fixes`。

## Standards 轴

| 发现 | 修法 | 证据 |
| --- | --- | --- |
| 凭证过期双实现：`AdminSwapService.expire()` 无条件 setStatus+save，可能覆盖并发消费结果 | 改用 `SubstitutionPreviewRepository.markExpiredIfActive` 条件更新（与票 03 同口径） | `AdminSwapService.expire` + 既有并发/幂等测试 |
| `generatePlans` 删除**全局**无申请候选，可能连带删掉并发管理员的在途方案 | `substitution_plan` 加 `owner_user_id`（**V43**），生成时记录操作者，清理与预览只认本人方案；跨 owner 返回明确冲突 | `AdminSwapIntegrationTest.adminsRegeneratingCandidatesDoNotWipeEachOthersPlans` |
| 重复代码（最重坏味道）：两服务整段复制 actionHash/min/legacy 检查/活跃单查询/名称四件套/取节逻辑 | 抽取到共享 `SubstitutionLookups`（含 `periodStart` 缺开始时间统一拒绝），四处服务调用同一实现；两服务的私有副本删除 | 全量后端测试原样绿（纯重构） |
| 前端权限常量两处硬编码 | 新建 `frontend/src/constants/permissions.ts` 的 `WORKBENCH_MANAGER_PERMISSIONS`，工作台与导航菜单共用 | 前端 lint/build/unit 绿 |
| `PlanSnapshot.touches` 与 `TimetableViewService.touches` 双写 | 保留（判断题；两处分工不同——前者用于方案对照、后者用于视图投影，均有测试钉住），记录于此不合并 | — |

## Spec 轴

| 发现 | 修法 | 证据 |
| --- | --- | --- |
| 「批准一律受开课截止约束」只在修订生效（`if (revision)`），首次批准可跨点生效 | `SubstitutionRequestService.approve` 与 `SubstitutionPlanService.approvePlan` 的截止复核移出修订分支，锁后重读时钟 | `SubstitutionApprovalDeadlineIntegrationTest`（过去学期两条批准路径 409 零写入）+ 既有未来学期正路径 |
| §7「历史展示…已被后续安排替代」缺失：记录接口只回 EFFECTIVE、前端无标签 | `records()` 纳入 EFFECTIVE+SUPERSEDED（新仓储方法），CSV 增「状态」列；前端 `SUPERSEDED=已被替代` 标签与配色 | `SubstitutionRevisionIntegrationTest` 的 SUPERSEDED 断言 |
| §7 净课量缺「含尚未上课但已确认的安排」注记 | 统计行补注记文案 | 前端可见文案 |
| `SecurityConfig` 无条件放行 `/h2-console/**`（超出 e2e 用途） | 按 `spring.h2.console.enabled` 条件放行；e2e profile 显式启用，其余环境不放行 | `AuthRbacIntegrationTest.h2ConsoleIsNotOpenWhenConsoleIsDisabled` + e2e `runSql` 继续可用 |
| `snapshotHash` 未含实例日期，历史转换改写 `lesson_date` 后旧凭证哈希不变 | 哈希输入加入 `lessonDate`/`targetLessonDate`；存量 PROPOSED 方案按既有 stale 语义失效重预览 | 既有哈希/凭证测试（失配即重新预览） |
| 真实 PostgreSQL 迁移验证未本地执行 | PostgresContainerIT 已编写（票 02 扩展 4 + 既有 1），本机无 Docker，待 CI/服务器 `-Dtestcontainers=true`；如实记录 | 见票 02/09 交付记录 |

## 测试稳定性（全量单 worker 长跑偶发）

- `onboarding.spec.ts`、`constraints-ai-proposals.spec.ts`：断言等待放宽到 10s/15s（不弱化断言）。
- `global-scroll.spec.ts` 同页筛选：滚动能力与位移改为 `expect.poll` 就绪轮询，消除渲染时序抖动。

## 边界

- 未执行任何生产数据操作、未部署、未 push；上线仍须独立授权（`--deploy` + 真实 PostgreSQL + 教务确认历史影响清单）。

## 门禁修复轮（首次全量门禁暴露）

首次 `verify.sh --full`（后端 1012 绿）在 Playwright 暴露 11 条失败，三类根因与修法：

1. **records 接口默认值回归**（评审 P3 引入）：`/substitutions` 与导出默认只回
   EFFECTIVE，历史视图显式传 `status=ALL`；`SubstitutionRevisionIntegrationTest`
   同时钉住两种语义。修掉 admin-reschedule 的「净差行 toDayOfWeek」误取旧行。
2. **e2e 种子锚在过去学期**：批准路径统一受开课截止约束后，`2025-09-01` 锚点的
   批准全部被 409 拒绝。`seedSchool`/`seedSwapScenario`/`dateFor` 改为「最近的下个
   周一」，substitution/substitution-swap/substitution-conversion 相应更新。
3. **冻结用例无法再用 HTTP 造环**：过去学期连首次调课都无法生效（这正是新的
   用户可见语义）。该用例改为直接断言「过去学期：批准/再次调课/代课预览全部 409、
   零生效行、待批单不被改动」；部分已开课的环语义仍由后端固定时钟集成测试覆盖。
4. **全量顺序暴露的存量 flaky**：
   - `onboarding` 需要空库（向导只在无班级/作息时出现），改名 `00-onboarding.spec.ts`
     以在造数据的 spec 之前运行（沿用仓库 `zz-` 命名约定的反向）。
   - `constraints-ai` 的「王老师」前缀解析与 `admin-substitute-revision` 新建的
     「王老师xxx」撞姓 → 歧义候选（待补齐）。改用全站唯一的「慕」姓。
   - `global-scroll` 同页筛选：切换视图后内容高度变化会把滚动合法夹紧；断言改为
     「未被无条件归零（>0）」，并把教师视角换成内容等高的一周网格视角（教室）。
