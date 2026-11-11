# 票03 复查整改：学校没有当前学期时的课表空态（2026-11-11）

分支 `codex/dingtalk-t03-review-fixes`，起点为已合并的集成 `399b74b`。只做真实 E2E 发现的这一条产品缺陷；
不扩范围，不动后端。

## 真实 E2E 证据（主线程发现，非本 worker 复跑）

最终 2 条 Playwright → 1 pass 1 fail（exit 1），日志
`E:/排课系统/.worktrees/dingtalk-integration/.scratch/dingtalk-multiuser/logs/t03-browser-final.log`。失败链路：
老师免登后落到 `/workbench/timetable`，页面随后 `GET /api/classes` 返回 400（学校没有当前学期），并抛出
`pageerror: AxiosError` 400。trace 在
`integration/frontend/test-results/dingtalk-login-已绑定UID老师免登并取得同一账号-chromium/trace.zip`。
「有 Teacher 但没有 currentSemester」是允许的引导期状态，因此不能靠加 seed 学期或放宽 console 断言绕过。

## 根因

`TimetableView.vue` 的 `onMounted` 用 `Promise.all` 同时请求 `/classes` 与 `/semesters/current`：当前学期为 204 时
`/classes` 仍要 400，`Promise.all` 直接整体 reject，页面既没有空态也把 400 抛成未处理异常；`load()`（切视图/切日期）
同样没有「没有当前学期」的保护，后续照样打依赖学期的接口。

## 改动（写集 4 个文件）

- `frontend/src/views/TimetableView.vue`
  - 新增 `currentSemesterId`（`number | null`）作为唯一状态；没有造状态机或通用 loader。
  - `onMounted`：`/teachers/options`、`/courses` 与 `/semesters/current` 并行（前两者不依赖学期）；只有拿到有效当前学期
    才并行请求 `/classes`、`/semesters/{id}/periods`、`/timetable/publish-info`。无当前学期时 `classes` 保持空、不发依赖请求。
  - `load()`：`currentSemesterId == null` 时清空 `entries` 并直接返回，切全校/切日期都不会再请求依赖学期的接口。
  - `emptyDescription`：无当前学期 →「学校尚未设置当前学期，请联系教务处。」；有学期未发布、和「所选日期无课节」两种文案保持不变。
    管理员既有的「去课表工作台」引导与其它网络错误的 `ElMessage.error` 处理未改，网络错误不会被吞成空态。
  - 老师视角与姓名不变：无学期时仍 `mode = teacher`、`targetId = 本人 teacherId`，教师下拉仍显示本人姓名。
- `frontend/src/views/TimetableView.spec.ts`：保留原有 3 条（Teacher 默认本人课表 / 教务管理员默认行政班 / 撤回未发布空态），
  新增 1 条公开 DOM 无学期用例（teacherId 7 + `/teachers/options` 返回本人）：`/semesters/current` 204，其余依赖学期的接口
  一律 400，断言不请求 `/classes`、不请求任何 `/timetable/*`，页面出现「学校尚未设置当前学期，请联系教务处」且保留「张老师」，
  再切「全校」仍不新增依赖请求。
- `frontend/e2e/dingtalk-login.spec.ts`：只加固第一条（失败说明页）用例——`page.goto('/login')` 前先注册
  `POST /api/auth/dingtalk` 响应等待（与第二条相同的 `Promise.all` 模式），先断真实 `response.status() === 401` 与
  `response.json().code === 'DINGTALK_CALL_FAILED'`，再断页面 `data-reason-code`，避免只看到 fallback 值也假绿。
  用例数不变、不加 seed、不放宽 console、第二条未改。
- 本文件。

没有改后端、公共 API 默认语义、迁移、其它 UI、登录 helper 或既有 E2E 断言。

## 红 → 绿（真实退出码）

| 阶段 | 命令（frontend 目录，低内存串行） | 结果 |
|---|---|---|
| 红（旧实现） | `npx vitest run --no-file-parallelism --maxWorkers=1 src/views/TimetableView.spec.ts` | **exit 1**，`Tests 1 failed / 3 passed (4)`；新用例在 `expect(urlsAfterMount).not.toContain('/classes')` 处失败，并伴随一条 400 的 Unhandled Rejection。属功能红，不是编译失败 |
| 绿（最小实现） | 同一条命令 | **exit 0**，`Test Files 1 passed`、`Tests 4 passed (4)`，无 unhandled error（3.53s） |
| Lint | `npm run lint`（`eslint . --max-warnings 0`） | **exit 0** |
| 类型检查 | `npx vue-tsc -b` | **exit 0**（无输出、无诊断） |

日志与退出码落在本验证目录
`.scratch/dingtalk-multiuser/.runtime/t03-empty-semester/`：`t03-empty-semester-red.log`（保留失败原文）、
`t03-empty-semester-green.log`、`lint.log`、`tsc.log`（空）、`exits.txt`（四个实际退出码）。
该目录及 `TEMP`/`TMP`/`TMPDIR`/`npm_config_cache` 均为本次专用路径，未被清理，由主线程收尾处理；被拒绝删除的
`feature/.tmp` 未做任何删除或清理。

## 边界

- 未启动任何服务、未复跑 Playwright、未跑全量 verify；真实 E2E 复跑与截图由主线程负责（本轮只做单 spec 的 Vitest + lint + tsc）。
- 只有前端单测与类型检查证据；无学期场景的真实浏览器验证以主线程 E2E 为准。
- 未处理 399 报告里其它 3 个 P3 抽象建议。