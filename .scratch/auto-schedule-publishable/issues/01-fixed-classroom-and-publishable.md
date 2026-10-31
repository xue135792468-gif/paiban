# 票据 01: 固定教室与自动排课达到直接发布标准

Status: done
**Spec:** ../spec.md
**执行:** 已实现并验收（2026-10-31，`main`）

## What

1. 自动排课把每个班钉在自己的固定教室，课表（草稿与已发布）与导出不再显示教室名；AI 导入预览仍保留教室列。
2. 求解器把「未排课」的代价提高到高于一处硬冲突，并用 `forEachIncludingUnassigned` 让固定教室/教室类型约束覆盖还有 null 规划变量的课节。
3. 初始解改用 `CHEAPEST_INSERTION`，并引入 warm start：有已发布基线用基线，新学期用最早一份完整草稿，按 `(班级,课程,教师)` 灌入时段，教室由固定教室规则决定。

## Why

用户要求「自动排课排出来的必须能直接发布」，且不需要区分教室类型、课表里不展示教室。原实现有三处缺口：

- 房间是自由变量，同容量同类型的教室可互换，班级课表出现别班教室名。
- 「没排上」和「一处冲突」都只算 1 hard，求解器觉得留空与冲突等价，停在空洞上。
- Timefold 的 `forEach` 跳过仍含 null 规划变量的课节，导致「有时间但没教室」的课节不触发任何约束。

详见 [ADR-0031](../../docs/adr/0031-fixed-classroom-for-auto-scheduling.md)、[ADR-0032](../../docs/adr/0032-unassigned-outranks-conflicts.md)、[ADR-0033](../../docs/adr/0033-warm-start-from-baseline-or-import-draft.md)。

## 验收记录

**门禁**

- 后端测试 1023 个通过（0 失败，含审查修复后新增的固定教室空格复用用例）。
- `scheduling` e2e 5/5 通过；前端 lint、`DraftGrid` 单测、前端全量单测 442 通过。

**线上草稿 9「最终验证」**

- 546/546 节课全部有时间 + 教室。
- 班级 / 教师 / 教室冲突均为 0。
- 14 个班每班只用 1 间本班教室。
- 校验结果 `valid=true`、`hardConflicts=0`。
- 唯一发布拦截为 `SEMESTER_ALREADY_PUBLISHED`（本学期已有发布基线，属流程限制，不是草稿质量问题）。

**备注：** 既没有已发布基线、也没有完整导入草稿的新学期仍走评分修复 + `CHEAPEST_INSERTION` 的从零搜索，不保证一次到 0。

## 相关提交

- `c7e2928` fix(scheduling): pin each class to its fixed classroom and hide rooms
- `f1d988e` fix(scheduling): make an unassigned lesson cost more than a conflict
- `fbaa40f` fix(scheduling): build the initial timetable with cheapest insertion
- `e7f3a20` fix(scheduling): seed the solver from the published baseline
- `74d2cab` fix(scheduling): warm-start from the baseline or the oldest complete draft
- `195fc09` test(scheduling): cover the import-draft warm start
