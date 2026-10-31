# 票据 02: 合并 RoomFillPlanner 的固定教室匹配逻辑

Status: needs-triage
**Spec:** ../spec.md
**来源:** 2026-10-31 code-review Standards 判断项

## What

`RoomFillPlanner.fixedRooms` 与 `RoomFillPlanner.plan` 各自实现了一遍「同名 + NORMAL + 容量足够」的判定：前者按班级人数比较容量，后者按 `requiredCapacity`（班级人数与课节人数快照取大）；名字规范化也已统一为 trim，但判定逻辑仍是两份。把这段判定收成一个共享函数，让固定教室解析和补教室计划读同一份规则。

## Why

两处规则分叉会在边界数据上产生不同答案：同一间教室可能被 `plan` 接受、被 `fixedRooms` 拒绝，或反之。求解器和补教室入口对「这间房能不能给这个班」应当只有一个答案。

## Acceptance

- [ ] 抽出一个纯函数（例如 `RoomFillPlanner.matchFixedRoom`），同时供 `fixedRooms` 和 `plan` 使用，容量基准显式作为参数。
- [ ] `RoomFillPlannerTest` 覆盖：同名带空格、容量不足、非 NORMAL、两个班抢同一间房。
- [ ] `fixedRooms` 与 `plan` 对同一组输入给出同一间房/同一拒绝理由。
- [ ] 不改求解器评分与课表展示行为。
