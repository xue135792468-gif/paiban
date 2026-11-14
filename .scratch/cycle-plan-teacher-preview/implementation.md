# 联动方案教师课表预览：正式实现

Status: implemented

当前为本地实现完成、待用户前端体验及发布门禁；不是已合并或部署。首轮 0e625e9 的四项确认问题已集中整改，最终检查与边界见 delivery.md，原始复查及处置见 review.md。

用户已体验并接受原型，要求先按原型推进正式实现。原型参考：分支 codex/prototype-cycle-teacher-preview，提交 75961c6，frontend/src/components/draft/prototype-teacher-preview/index.html。可从相邻工作树只读查看，不复制静态原型进入生产。

## 确认行为

1. 联动方案中的教师姓名可点击，查看当前草稿中该教师跨所有行政班的完整周课表。
2. 电脑上方案和预览并排；手机全屏预览，返回保留方案及滚动位置。
3. 默认应用后预览，明确尚未生效，可切换当前安排。
4. 从同一份当前草稿同时投影整个方案的所有移动，突出全部受影响课节并说明原位置及目标位置；不改变原草稿。
5. 预览中直接切换本方案涉及的教师，以教师 ID 识别；切换方案不得累积上一方案投影。
6. 预览只读；沿用原应用确认、真实接口和取消流程，不用原型模拟按钮代替真实应用。

## 范围与分工

Qwen（group/qwen3.8，不显式覆盖推理强度）负责正式施工及必要的代码检查，主控负责方案、调度、代码评审处置与提交收口。用户负责前端观感和交互验收，主控不运行浏览器验收，本轮不评价模型能力。

从 origin/main c27af84 创建 codex/cycle-teacher-preview。优先只改 CyclePlanPanel 及相邻专用预览组件/纯投影辅助函数、相关现有测试、必要的 DraftEditorView 传参和帮助文字。沿用现有 Vue/Element Plus、DraftGrid 和业务类型。无需新增后端、接口、依赖、权限、迁移或修改求解器；若现有数据确实不足，先报告具体事实，由主控判断。

## 检查边界

在现有组件公开 props/交互事件及纯投影输出上补必要回归，关注整组互换不丢课、同教师多节移动、同名不同 ID、不修改原输入、方案切换隔离以及原 apply 事件兼容。避免私有实现断言和镜像实现的测试。组件交互变化应更新旧选择器，不能删除原业务断言。

施工期只跑定向前端检查；源码收口后一次前端 lint、typecheck/build、unit。单测串行或限制 worker，先语法和定向再全套。此范围未改后端，不为形式重跑后端长套件；主控不做前端浏览器验收。不自动合并、推送、部署。交付应明确没有实机或浏览器验收。

只 stage 本任务路径；不得触碰主仓库的未提交 AGENTS.md 和其他历史现场。临时文件放本工作树的忽略目录并在结束时清理，退出自启动临时进程。

## 首轮施工记录（当时未 commit，后由主控保存为 0e625e9）

改动文件（工作树 codex/cycle-teacher-preview，起点 c27af84，未提交）：

- 新增 `frontend/src/components/draft/cyclePlanPreview.ts`：整组 moves 的纯投影、按教师 id 取课表、节次文案。
- 新增 `frontend/src/components/draft/CyclePlanTeacherPreview.vue`：教师完整周课表预览（应用后 / 当前安排、教师 chips、移动明细）。
- 新增 `frontend/src/components/draft/cyclePlanPreview.spec.ts`（9 例）。
- 改 `frontend/src/components/draft/CyclePlanPanel.vue`：教师姓名链接、并排/全屏两态、预览接线、apply 事件不变。
- 改 `frontend/src/components/draft/CyclePlanPanel.spec.ts`：旧选择器迁到 `cycle-plan-apply`，新增 7 例覆盖预览行为。
- 改 `frontend/src/components/draft/DraftGrid.vue` + spec：新增只读 `lessonNotes`、`vacatedPeriods`（仅空格显示）。
- 改 `frontend/src/views/DraftEditorView.vue`：向面板传 `courses`（同行追加，SFC 仍 600 行）。
- 改 `frontend/src/help/docs/manual-adjust.md`：新增 `#cycle-preview` 段落。
- 改 `frontend/e2e/draft-drag.spec.ts`：两处按钮选择器迁到 `cycle-plan-apply`（未实跑，见下）。

后端事实核对：`DraftCyclePlanDtos.PlanMove` 只有 lessonId / fromPeriodId / toPeriodId / teacherId / courseId / classId，没有换教师字段，所以预览的投影就是「节次移动」，与 apply 语义一致，无需新增接口。

已跑命令与结果：`npx vue-tsc --noEmit -p tsconfig.app.json` 0；定向 vitest（CyclePlanPanel / DraftGrid / cyclePlanPreview）44 passed 0；`npm run lint` 0；`npm run build` 0；`VITEST_MAX_WORKERS=4 npx vitest run` 821 passed 0；`scripts/check-sfc-size.sh` 0；`check-ticket-status / check-e2e-helpers / check-module-boundaries / check-migrations / check-local-dates` 均 0。

未验证与限制：按用户分工无浏览器与 Playwright 实跑，前端观感由用户验收；本机没有 Docker 不等于普通 H2 后端无法启动，原施工说明在此更正。未跑后端套件（本范围未改后端）；该记录形成时未 commit / push / merge / deploy；`frontend/dist/` 为构建产物（git 忽略）保留；本轮未启动常驻进程，调试用临时 spec 已删除。
