# 自动排课结果达到直接发布标准

Status: done

验收（2026-10-31）：见 [issues/01-fixed-classroom-and-publishable.md](issues/01-fixed-classroom-and-publishable.md)

## Problem Statement

自动排课产出的草稿经常留下「有时间但没教室」或「未排课」的课节，用户在发布前还要手工补教室、逐节调整，无法直接发布。另有两个体验问题：求解器把教室当自由变量，同年级可互换的教室导致一个班的课被分散到全校；教室名按「学期-年级班名-固定教室」命名，班级课表的第二行显示别班名字，看起来像串班。

## 用户要求

1. 课表（草稿与已发布）不显示教室名。
2. 每个班固定使用本班的一间教室。
3. 自动排课排出来的草稿必须达到直接发布标准，不需要人工再补。

## 验收标准

- 草稿内全部课节同时具备「时间（星期 + 节次）」和「教室」。
- 整草稿校验 0 硬冲突（班级 / 教师 / 教室冲突均为 0）。
- 每个行政班的普通课只落在本班固定教室（每班只用 1 间本班教室）。
- 校验结果 `valid=true`；除流程性限制（学期已有发布基线）外没有发布拦截项。

## 相关决策

- [ADR-0031](../../docs/adr/0031-fixed-classroom-for-auto-scheduling.md)：固定教室 + 课表不展示教室。
- [ADR-0032](../../docs/adr/0032-unassigned-outranks-conflicts.md)：未排课代价必须高于一处硬冲突。
- [ADR-0033](../../docs/adr/0033-warm-start-from-baseline-or-import-draft.md)：从已发布基线或导入草稿热身起步。
