# 四期实现契约（跨票据共享）

本文件是 01–05 票据实现时的统一接口约定。**不要偏离**；若必须偏离，在票据提交说明里写明原因。
规格与票据见同目录 `spec.md` 与 `issues/`。工作树统一放在 `E:/paiban-wt/tXX`。

## 0. 通用

- 四期全部是前端改动（`frontend/`），后端不动。
- 集成分支：`feature/phase4-onboarding`（基线为三期 `feature/phase3-feishu` 的最新提交）。
- 每个票据一个工作树、一个分支 `feat/p4-tXX`；实现完成后把集成分支 tip 合入自己的分支再报告。
- 工作树创建方式：

  ```bash
  git worktree add E:/paiban-wt/t01 -b feat/p4-t01 feature/phase4-onboarding
  cmd //c "mklink /J E:\\paiban-wt\\t01\\frontend\\node_modules E:\\排课系统\\frontend\\node_modules"
  ```

- 工作树里没有 `.tools`。跑构建/测试前先 `source /e/排课系统/.tools/env.sh`。
- 依赖已由集成分支预装：`driver.js`、`markdown-it`、`vitest`、`@vue/test-utils`、`jsdom`。
  **不要再改 `package.json` / `package-lock.json`**。
- 每个票据完成前必须跑：`cd frontend && npm run lint && npm run build && npm run test:unit`。
- E2E 约定：全仓只有一个 Playwright 实例（固定端口 8080/5173），多个工作树不能同时跑。
  运行前用目录锁串行化，跑完释放：

  ```bash
  # 获取锁（最多等 30 分钟）
  until mkdir /e/paiban-wt/.e2e-lock 2>/dev/null; do sleep 30; done
  trap 'rmdir /e/paiban-wt/.e2e-lock' EXIT
  source /e/排课系统/.tools/env.sh
  cd frontend && npx playwright test e2e/<your-spec>.ts
  ```

  只跑本票据新增的 spec，不要跑全量。合并后由集成分支统一跑全量。
- 提交信息用 `feat(onboarding): ...` / `feat(help): ...` / `test(e2e): ...`，正文第一行带票据号。

## 1. 帮助中心路由与链接（04 拥有，02/05 消费）

`src/help/links.ts` 已由集成分支提供，**不要改 slug 列表**：

```ts
export const HELP_SLUGS = [
  'foundation', 'scheduling', 'substitution', 'timetable', 'settings',
  'flow-term-start', 'flow-substitution', 'flow-term-end',
] as const
export type HelpSlug = (typeof HELP_SLUGS)[number]
export function helpLink(slug: HelpSlug, anchor?: string): string
```

- 路由是**顶层路由**（不在 `MainLayout` 内）：`/help` 与 `/help/:slug`，`name: 'help'` / `'help-doc'`。
  顶层是为了打印样式能干净地隐藏导航；页面自己提供「返回系统」入口。
- 文档文件：`src/help/docs/<slug>.md`，必须覆盖全部 8 个 slug。
- 每篇文档必须用 `<a id="..."></a>` 显式声明以下锚点（供 02/05 链接）：
  - `foundation`: `grades` `classes` `teachers` `rooms` `courses` `import`
  - `scheduling`: `constraints` `assignments` `run` `draft` `publish`
  - `substitution`: `apply` `approve` `notify`
  - `timetable`: `views` `export` `substitution-mark`
  - `settings`: `school-type` `ai` `platform`
  - `flow-term-start`: `school` `periods` `grades` `teachers` `rooms` `courses` `assignments` `constraints` `finish`
  - `flow-substitution`: `apply` `approve`
  - `flow-term-end`: `stats`
- 文档内跳转使用真实路由（如 `/admin/classes`），由 `HelpView` 拦截站内链接改走 `router.push`。
- `src/help/docs.ts` 导出文档目录供侧边栏与搜索使用，至少包含 `slug`、`title`、`group`、`body`（原始 markdown）。
- `MainLayout` 的「系统」分组末尾增加 `{ label: '帮助中心', path: '/help', icon: QuestionFilled }`，不需要权限。

## 2. 空状态组件（02 拥有）

`src/components/EmptyStateGuide.vue`：

```ts
props: {
  title: string
  description?: string
  buttonText?: string
  buttonTo?: string          // 站内路由；存在时按钮 router.push
  helpSlug?: HelpSlug        // 存在时渲染「查看帮助文档」
  helpAnchor?: string
  helpText?: string          // 默认「查看帮助文档」
}
emits: { action: [] }        // 未给 buttonTo 时，按钮点击触发
```

- 根节点 `data-testid="empty-state-guide"`，内容居中：图标 + 标题 + 描述 + 主按钮 + 帮助链接。
- 帮助链接用 `<router-link :to="helpLink(helpSlug, helpAnchor)">`。
- 覆盖页面与触发条件：无学期（引导去 `/admin/semesters`）与「有学期但列表为空」两种。
  涉及：年级、行政班、教师、教室、课程、授课任务、约束配置、自动排课、调代（待审批/记录）、课表查看。
- 表格式页面在空数据时用 `EmptyStateGuide` 替代表格主体；不要同时显示表格自带的「暂无数据」。

## 3. Tooltip（03 拥有）

- `src/help/tooltips.ts` 导出 `TOOLTIPS: Record<TooltipKey, string>` 与 `TooltipKey`。
- `src/components/InfoTooltip.vue`：props `{ field: TooltipKey }`，内部用 `el-tooltip`，
  悬停显示 `TOOLTIPS[field]`，触发器是问号图标（`QuestionFilled`），可加 `aria-label`。
- 必含 key 与落点：
  - `constraint.weight` — `ConstraintsView` 权重标签
  - `constraint.hard` — `ConstraintsView` 硬/软约束单选组
  - `constraint.param` — `ConstraintsView` 参数标签
  - `course.consecutive` — `CoursesView` 连堂设置
  - `assignment.weeklyLessons` — `AssignmentsView` 每周节数
  - `substitution.approvalMode` — `SettingsView` 审批模式
  - `substitution.effectiveDate` — `SubstitutionsView` 生效日期
  - `timetable.view` — `TimetableView` 视角切换（基线/最新）
  - `timetable.substitutionMark` — `TimetableView` 标题说明里的「代」标记
  - `settings.schoolType` — `SettingsView` 学校类型
  - `settings.aiProvider` — `SettingsView` AI 供应商卡片
  - `settings.platformSwitch` — `ImPlatformCard` 启用平台
- 单元测试 `src/components/InfoTooltip.spec.ts`：挂载组件，断言传给 `el-tooltip` 的 content 等于配置值；
  并断言所有 `TooltipKey` 的文案非空。

## 4. 新手教程（01 拥有）

- `src/onboarding/tour.ts` 导出：
  - `TOUR_SEEN_KEY = 'paiban.tour.seen'`、`TOUR_ADVANCED_KEY = 'paiban.tour.advanced'`
  - `hasSeenTour(stage)`, `markTourSeen(stage)`, `resetTour()`
  - `startTour(stage: 'basic' | 'advanced')`，内部创建 driver.js 实例；`onDestroyed` 标记已看。
- 基础教程 6 步：欢迎（无元素）→ 基础数据 → 排课 → 调代 → 课表 → 完成（无元素）。
  元素选择器用 `MainLayout` 菜单项上的 `:data-tour="item.path"`（01 只加这一行绑定）。
  元素不存在时跳过该步，不要让 driver.js 抛错。
- 高级教程：约束权重 / 提交排课 / 课表导出，触发条件为「基础已看 + 首次进入 `/admin/scheduling`」。
- 触发位置：`MainLayout` 内，条件为已登录、非 `/onboarding`、`route.path === '/'`、
  有 `onboarding:manage` 权限、未看过；延迟约 600ms 等布局渲染。
- 设置页重开：在 AI 供应商卡片之后、`v-if="aiEnabled"` 的 AI 特性卡片之前，插入
  `<el-card id="tour-reopen">`，按钮「重新查看新手教程」→ `resetTour(); startTour('basic')`。
- E2E 登录助手统一到 `e2e/helpers.ts`：

  ```ts
  export async function uiLogin(page, username, password, options?: { tour?: boolean })
  ```

  默认通过 `page.addInitScript` 写入 `localStorage['paiban.tour.seen'] = '1'`（并写 advanced），
  避免教程遮挡既有用例；教程用例传 `{ tour: true }`。把现有 6 个 spec 里的本地 `uiLogin` 换成这个导入。

## 5. 初始化向导优化（05 拥有，01–04 合并后开工）

- 顶部进度：`第 {{ currentStep + 1 }}/{{ steps.length }} 步：{{ current.title }}`。
  注意：现有向导是 8 步（票据里的 6 是示意），按实际步数动态显示。
- 每步「查看帮助」链接：`helpLink('flow-term-start', <对应锚点>)`，新窗口或同窗均可，用 router-link。
- 完成后不立刻跳转：显示庆祝卡片（「初始化完成！」+ 动画/图标 + 「开始使用」按钮），点击后 `router.push('/')`。
- 跳过：`ElMessageBox.confirm('确定跳过初始化向导吗？您可以随时在设置中重新进入', ...)`，确认后才持久化跳过。
- 设置页重新进入：在页面最后一个卡片之后追加 `<el-card id="wizard-reenter">`，按钮跳 `/onboarding`。
- 同步更新 `e2e/onboarding.spec.ts`（跳过要处理确认框），以及任何会点「跳过向导」的用例（含 01 的 tour spec）。

## 6. 冲突热点（合并时注意）

| 文件 | 涉及票据 | 约定 |
|---|---|---|
| `SettingsView.vue` | 01、03、05 | 01 插 `#tour-reopen`（AI 供应商后）；05 追加 `#wizard-reenter`（末尾）；03 只包裹既有标签 |
| `MainLayout.vue` | 01、04 | 01 只改 `el-menu-item` 加 `:data-tour`；04 只在「系统」分组追加帮助入口 |
| `ConstraintsView.vue` 等视图 | 02、03 | 02 改空状态块，03 改字段标签，尽量不动同一行 |
| `e2e/helpers.ts` | 01 | 01 统一 `uiLogin`；后续票据只导入使用 |
| `package.json` | 无 | 已预置，禁止改动 |
