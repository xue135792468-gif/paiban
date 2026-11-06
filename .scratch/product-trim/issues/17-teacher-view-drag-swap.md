# 17: 教师视图拖动换课：拖起时切换为该班浅色课表

**What to build:** 草稿编辑器的「按教师」视图下，可以直接拖动一节课换课。拖起时，网格临时变成这节课所属班级的浅色班级课表；同时把该教师在别的班有课的节次标成灰色。放下后的处理和班级视图拖动完全一致，换完或取消都回到原教师课表。

**Blocked by:** 13 (教师视图换课：班级徽标 + 绿/灰格子)，已完成。

**Status:** done

## 用户故事

例如：在王老师的教师课表里，拖起「周二下午 · 七年级 8 班」这节课。网格变成浅色的七年级 8 班课表，这个班每节上什么课、谁来上都能看到；王老师在其他班有课的节次显示为灰色。放到 8 班的某个格子上完成换课，然后回到王老师的课表。

## 验收

- [x] 教师视图拖起一节课，网格立刻变成该课所属班级的浅色班级课表，同班其他老师的课都能看到
- [x] 该教师在其他班有课的节次显示为灰色，并写明「王老师在 ×× 班有课」（悬停/标签/aria 均可读）
- [x] 放到空节：直接挪过去，和班级视图结果一致
- [x] 放到同班另一节课上且无冲突：两节互换，和班级视图结果一致
- [x] 对方老师那节没空或违反规则：弹出联动方案，由用户选择或放弃（和班级视图一致）；放弃则不做任何改动
- [x] 放下（成功、取消、拖出网格、中途松手）后都回到原教师的课表，所选教师不变
- [x] 点击课卡出现绿/灰格子的换课方式（票 13）保留，点和拖都能用
- [x] 锁定的课、只读草稿、多选锁定状态下不能拖，和现在一致
- [x] 只在同一班内换，不做跨班换人
- [x] 班级视图的拖动行为不变

## 范围外

自动排课、按教师范围重排、新增概念、改数据库或后端接口。只改拖动过程中显示的内容，放下后的保存、校验、联动方案全部复用现有功能。

## Notes（现状，2026-11-06 读码核实）

- 教师视图现在已经能拖：`DraftEditorView.vue:81` 的 `draggable` 不区分视图，换课模式开启时会禁用拖动。
- 问题在于教师视图网格只显示所选教师的课，拖动时看不到同班其他课，用户不知道会和哪节互换。
- 票 13 进入点击换课后，网格范围改为「所选教师的课 ∪ 源课节所属班级全部课」（`useTeacherSwap.ts` 的 `gridLessons`），拖动可参考这种临时扩大显示范围的做法。
- 拖起时调用 `adjust.loadMoveTargets`，放下后走 `drop()`，与班级视图共用同一套移动、互换和联动方案。
- 需求来源：产品所有者 2026-11-06 口述，确认三点：标出教师他班占用；冲突时弹出联动方案让用户选；保留点击换课。

## Comments

---

## 实现记录（2026-11-06）

**基线**：`main @ 1ffb493`（票 15/16 已上线，Flyway 55，交接收口已提交）。**纯前端改动，后端/数据库/接口/排课范围零改动，无 V56，不改 `SchedulingView.vue`、不加 `teacherIds`。**

### 实现
| 文件 | 改动 |
|---|---|
| `frontend/src/components/draft/dragClassView.ts`（新） | 两个纯函数：`dragClassLessons()`（拖起时取该课所属班全部课，含其他老师）、`foreignTeacherPeriods()`（该教师在别班有课按 `periodId` 聚合成「某老师在某班有课」） |
| `frontend/src/views/DraftEditorView.vue` | 4 个 computed：仅 `viewMode==='teacher' && dragged!=null` 时为真；`:lessons` 拖动中切到该班课表，`:drag-class-view` / `:foreign-teacher-periods` 传给网格。`onDragEnd` 清 `dragged` → computed 自动还原原教师课表 |
| `frontend/src/components/draft/DraftGrid.vue` | 2 个新 prop；`.grid.drag-class-view` 浅色底；`.cell.foreign-busy` 灰底虚线 + 可见原因文字 + title/aria；`cellTitle` 不覆盖真实 target/blocked 原因 |
| `frontend/e2e/helpers.ts` | `dragCardToCell` 加可选 `{ release:false }`，供「拖起但不松手」中途断言复用（默认行为不变） |

**放下后仍走既有 `loadMoveTargets` + `drop()`**，移动/互换/联动方案与班级视角共用；`draggable` 互斥、锁定/只读/多选拦截、票 13 点击换课全部保持原样。

### 验收数字
- 定向单测：**37 passed**（`dragClassView.spec.ts` 7 / `DraftGrid.spec.ts` 21 / `useTeacherSwap.spec.ts` 9）；`npx vue-tsc --noEmit` exit 0
- `teacher-view-swap.spec.ts` 单条 e2e：**5 passed**（含新增「互换」「取消/中途松手回原视图」「他班占用灰格仍可放」3 条）
- `draft-drag.spec.ts` 单文件：**10 passed**
- 仓库级 8 项检查（SFC/local-dates/e2e-helpers/isBlank/模块边界/迁移/票状态/AI 超时）全绿
- **`scripts/verify.sh --full` 退出码 0**，终行 `==> verify: ok`：后端 **1012**（0 失败 / 0 错误 / 7 跳过，`BUILD SUCCESS`）、前端 **95 文件 / 690 用例**、Playwright **173/173（17.6 分钟）**
- 本机无 Docker，`PostgresContainerIT` 按既有设置跳过，由 CI `-Dtestcontainers=true` 覆盖

### /code-review 两轴复审（已修复）
- **Standards**：硬违规 2（e2e 内联重抄 `dragCardToCell`、保存后 3 条收敛断言未 `toPass`）→ 已改为复用 helpers + `toPass`。修 `foreign-busy` 语义错误（原 `cursor:not-allowed` 会让「合法同班换课落点」看起来被禁用）→ 改为 `cursor:default` 仅作提醒；`cellTitle` 不再覆盖真实原因。
- **Spec**：(a) 5 项覆盖缺口 → 补 3 条 e2e（互换 / 取消回原视图 / 他班占用格仍可放）+ 1 条纯函数分支测试。(b) 2 项误报（PNG 为会话前既有改动、未排课抽屉拖拽切视图合理）。(c) 1 项已修（foreign-busy 观感禁用实则可放）。
- Hark 明列的另案（未知 `teacherIds` 静默忽略、`GET /scheduling-jobs/current` 500）**未顺手改**，留待单独开票。

### 全量门禁中的两个真实回归及其处置
1. `draft-drag.spec.ts:150` 原「教师视角跨班被拒」失败。根因：票 17 后拖起时网格切成源课所属班的课表，原「别班课可见 → 跨班拦截」变成「同班互换」，**新行为符合票 17 定稿**。已重写该段验证新语义（浅色班级课表、同班他人课可见、互换落库、回原教师视图），并**新增一条**守住「拖起只显示源课所属班、别班课不渲染、取消零写入」的跨班边界。
2. `draft-drag.spec.ts:98` 撤销后的第二次拖拽在全量套件里偶发不发写请求、180s 超时；单跑 11.5s 通过 → 判定负载时序 flake（同 spec 已有 `openPlanPanel` 重试先例）。按 `CODING_STANDARDS.md` 的「同类抖动抽 helper」规则新增私有 `dragAfterUndo()`：撤销后先 `toPass` 等状态收敛，再对零副作用拖拽重试 1 次。连跑该用例 3 次全过。

### 边界
- 未提交前的工作区改动仅本任务 9 个文件；未连生产、未部署、未 push（本次提交由主代理统一处理）。
- `DraftEditorView.vue` 现 **600 行**，正好等于 `check-sfc-size.sh` 的 MAX（判定是 `> MAX` 才失败），零余量；后续改动该文件需先拆分。
