# 票据 03: 把 warm start 选择从 TimetableProblemBuilder 抽出去

Status: needs-triage
**Spec:** ../spec.md
**来源:** 2026-10-31 code-review Standards 判断项（Divergent Change）

## What

`TimetableProblemBuilder` 现在同时负责：建求解问题、解析固定教室、选择 warm start（`timetable_entry` 或最早完整草稿）、校验草稿覆盖度。构造函数已有 8 个依赖。把 warm start 的「来源选择 + 覆盖校验 + 时段灌入」抽成一个独立的 service（例如 `WarmStartSeedService`），builder 只接收「每个 assignment 的时段队列」。

## Why

建题和选初始解是两件会独立变化的事：新增一种初始解来源、改变草稿完整性判断，都不应该再改 builder 的构造签名。当前一次 review 修复就让 builder 多背了两个仓储依赖，后续还会继续膨胀。

## Acceptance

- [ ] 新增 `WarmStartSeedService`（scheduling.service），builder 只依赖它和建题所需的仓储。
- [ ] warm start 来源优先级仍是：已发布基线 > 最早完整草稿 > 无。
- [ ] `TimetableProblemBuilderTest` 拆成两组：builder 建题/固定教室；warm start 选择与覆盖校验。
- [ ] 修复草稿遍历的 N+1（一次取草稿列表，按需批量取行或只取候选草稿）。
- [ ] 不改变 ADR-0033 的外部行为。
