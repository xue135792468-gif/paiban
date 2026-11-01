# 票据 09: 新学期复制结构后按唯一教室后缀认领固定教室

Status: done
Category: bug
Priority: P1
**Spec:** ../spec.md
**来源:** 2026-11-01 新学期验收（semester 2 `Acceptance-2027-S2`）

## What

新学期从学期 1 复制结构后，教室是全局的，名字仍是 `1-八年级八（1）-固定教室`（源学期前缀）。`RoomFillPlanner.fixedRooms` 只按 `新学期名-年级班名-固定教室` 精确匹配，找不到教室，导致：

- 课节没有固定教室，`room` 为 null；
- warm start 的时段虽然灌入，但构造启发式把“还有 null 规划变量”的课节当成未初始化，重新洗牌；
- 自动排课草稿出现 148 处班级/教师/教室冲突，发布 409。

## Fix

`RoomFillPlanner.fixedRooms`：

- 仍先按精确的 `新学期名-年级班名-固定教室` 匹配；
- 精确找不到时，按「年级 + 班名 + `-固定教室`」后缀找候选；
- 恰好一个候选才复用；两个及以上不猜，保持不 pin，交给人处理；
- 原来的“一房两班”去重保留。

## Acceptance

- [x] 新学期复制结构后，能复用旧学期唯一后缀的固定教室。
- [x] 两个学期有同名后缀教室时不 pin，不猜。
- [x] 精确匹配优先，原有固定教室/容量/类型规则不变。
- [x] 新学期完整验收：导入 546 行 → 自动排课 `DRAFT` 起步 `0hard` → `publishable=true` → 发布 546 节。

## Delivery

- 代码：`38e0d75`。测试：`RoomFillPlannerTest` 20 个（含唯一后缀复用、多候选不 pin）。
- 线上验收：semester 2 / draft 13（导入，546 行，0 班级/教师冲突）→ job 13 / draft 15：`warmStartSource=DRAFT`、`0hard`、`valid=true`、`publishable=true`；发布 `entryCount=546`；班级视图 39 节。
- 首次失败留下的 draft 14（148 冲突）仍在，待清理；当前学期已切回 1。
