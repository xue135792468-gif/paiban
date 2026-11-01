# 票据 08: warm start 来源枚举与最后一轮复审小项

Status: needs-triage
Category: enhancement
Priority: P4
**Spec:** ../spec.md
**来源:** 2026-11-01 最后一轮 code-review 判断项

## What

最后一轮复审在 04/06/07/03 的实现上留下的非阻塞小项：

1. `WarmStartSeedService`、`JobDto`、前端 `JobItem` 里的 `BASELINE` / `DRAFT` / `NONE` 是三处散落字符串，容易拼错；可以抽成一个后端枚举，DTO 用枚举名，前端用同一字符串值。
2. `firstTime` 的唯一性判断依赖 `HashSet` 对 null 键的语义；当前 `draft_lesson.admin_class_id/teacher_id` 非空使其安全，但没有测试固定这一行为。
3. `WarmStartSeedServiceTest` 的“同班同节”用例同时触发教师同节，不能单独证明班级检查；补一个“同班不同教师同节”的隔离用例。
4. `TimetableProblemBuilder.build()` 现在只被性能测试使用，和 `buildWithWarmStart` 语义重复；决定保留薄包装还是合并。
5. 票据 04 的“未排课/班级冲突/缺教室三场景显示口径”没有新增专门测试；现有 `DraftPublishReadiness` / blocker 测试已覆盖数量映射，可在票面注明，或补一条显式测试。

## Why

这些不改变当前行为，也不影响发布；它们是最后一轮复审的可读性/测试隔离/文档一致性问题。集中成一张 P4 票，避免散落在各票的 unchecked box 里。

## Acceptance

- [ ] `WarmStartSource` 枚举（或等价类型）在后端 service/DTO 与前端类型之间只有一个字符串事实源。
- [ ] 补 `firstTime` 的 null 键测试，明确当前非空约束下的行为。
- [ ] 补“同班不同教师同节”用例，单独证明班级唯一性。
- [ ] 决定 `build()` 薄包装去留，并在票面记录理由。
- [ ] 票据 04 的三场景测试要么引用现有测试，要么补一条显式用例；不留含糊的勾选。
