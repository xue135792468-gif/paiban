# 票据 04: 统一求解得分与草稿校验的口径

Status: needs-triage
**Spec:** ../spec.md
**来源:** 2026-10-31 code-review Standards 新发现

## What

同一份草稿存在两套数字：

- 求解器得分：未排课 10 hard、「班级固定教室」1 hard（`PaibanConstraintProvider`）。
- 草稿校验：`DraftValidator`/`ConstraintChecker` 只数班级/教师/教室等冲突；未排课和固定教室违规走单独的 publish blocker，不进入 `hardConflicts`。
- `SchedulingService.moveLesson` 在手工移动后把 `scoreHard` 覆写成 `-hardConflicts`，于是同一份草稿的「得分」和「校验硬冲突数」不是同一把尺子。

用户看到「得分 -10 hard」但校验说「0 硬冲突」，会以为哪里算错了。

## Why

展示口径不一致会掩盖真实违规，也让回归测试无法用一个分数判断草稿质量。固定教室规则目前只约束求解器，手工把课拖到别班教室不会被校验拦住。

## Acceptance

- [ ] 明确一个「草稿硬违规」的统一口径：求解得分、校验 `hardConflicts`、publish blocker 三者可解释地对应。
- [ ] 决定「班级固定教室」是否进入 `ConstraintChecker`（若进入，手工移动也要被校验拦住；若不进入，UI 明确标注它是求解偏好）。
- [ ] 移除或解释 `moveLesson` 里 `scoreHard = -hardConflicts` 的覆写，避免两套刻度。
- [ ] 更新 `DraftStats`/校验面板文案（如「求解得分」vs「硬冲突」）。
- [ ] 测试：未排课、固定教室、班级冲突三种情况的分数与 blocker 数量一一对应。
