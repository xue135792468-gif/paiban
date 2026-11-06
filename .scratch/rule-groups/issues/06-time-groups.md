# 06: 时间分组 + 可编辑的上午/下午

**What to build:** 时间组归属学期，成员是该学期作息里的课节。每个学期自动带「上午」「下午」两个可编辑的时间组（初始按作息时间折算），取代现在不可改的上午/下午快捷项。规则「什么时候」可选时间组，如「技能课 不排在 上午」。作息列表显示课节所属时间组后缀。

**Blocked by:** 04

**Status:** ready-for-agent

- [x] 每个学期有预建的「上午」「下午」时间组，可改成员、可改名
- [x] 可新建其他时间组；同一学期内组名唯一
- [ ] 规则「什么时候」可选时间组，下拉内可新建
- [x] 快照展开时间组；改成员即生效（集成测试）
- [ ] 作息列表显示时间组后缀，可按组筛选
- [x] 作息节次被删除时，时间组成员同步移除
- [ ] 与票 05 合并后，不看帮助一分钟内能拼出「技能课 不排在 上午」

**Notes:** 「上午/下午」原先由前端按时间折算，改为数据后句子渲染优先用时间组名。

## 实现说明（06a 后端部分完成）

- **V58 `prebuilt_time_groups.sql`**：`rule_group` 增加 `auto_filled BOOLEAN NOT NULL DEFAULT FALSE`；为每个存量学期补「上午」「下午」两个 `TIME` 组（`live_key = TIME:<semesterId>:<name>`，`auto_filled = TRUE`），再按 `period.start_time`（`< 12:00` 上午，其余下午）填成员。三条 INSERT 都是幂等的（同名活组 / 同坐标成员存在则跳过）。只用了 `CONCAT`、`TIME '12:00:00'` 与 `INSERT ... SELECT`，H2 2.3 PostgreSQL 模式与 PostgreSQL 都支持；H2 已在集成测试启动时实跑通过。未改 V1–V57。
- **实体 / DTO**：`RuleGroup.autoFilled`；`RuleGroupDto` 末尾追加 `autoFilled`（前端可忽略，06b 用来显示「预建」标记）。
- **事件**：新增 `PeriodsChangedEvent(semesterId)`。`SemesterService.replacePeriods` 在 `saveAll` + `flush` 后发布；`appendPeriods` 同样；`generateDefaultPeriods` 走 `replacePeriods` 不重复发布。监听同步执行在同一事务内，同步失败会回滚整次作息保存。
- **自动组生命周期**（`RuleGroupService`）：
  - `SemesterCreatedEvent` → 若该学期已有活的时间组则跳过，否则建「上午」「下午」空组（`autoFilled = true`，学期刚建时通常无作息）；
  - `PeriodsChangedEvent` → `syncTimeGroups`：`autoFilled && 名字仍是「上午」/「下午」` 的组删光旧成员后按新作息重算（空作息也清空）；其他组（用户建/用户编辑过）只删除坐标已不存在的成员；
  - `create` 与 `update` 都写 `autoFilled = false`，`restore` 保留原值；用户给预建组改名后不再被作息覆盖（有意行为）。
  - 重算用 `deleteByGroupId` + `flush` 再 `saveAll`，避开 `(group_id, member_key)` 唯一索引冲突。
- **快照展开**：现有 `RuleGroupExpander` 已满足契约（时间组坐标必须存在于该学期作息；`timeGroupId` × `courseGroupId`/`teacherGroupId` 笛卡尔积；组不存在/空/跨学期展开 0 行），本次无需改逻辑，只补测试确认。
- **测试**：新增 `TimeGroupIntegrationTest`（8 条），既有 `RuleGroupIntegrationTest` 调整 1 处断言（学期现在自带两个预建时间组，不能再断言列表长度为 1）。覆盖：预建空组、按作息填充、`TEACHER_UNAVAILABLE × timeGroupId` 展开 4 行、`COURSE_UNAVAILABLE` 两门课 × 4 节 = 8 行（「技能课 不排在 上午」）、改成员立即生效且 `autoFilled = false`、删课节同步（自动组重算 + 用户组只剪失效坐标）、跨学期时间组 400、同学期第二个「上午」409。
- **剩余（06b 前端）**：规则「什么时候」下拉接 `timeGroupId` 与下拉内新建；作息列表时间组后缀与按组筛选；句子渲染优先用时间组名；E2E 主路径。
- **验证**：`RuleGroupIntegrationTest` 13 + `TimeGroupIntegrationTest` 8 + `RuleBuilderIntegrationTest` 3 + `ConstraintIntegrationTest` 5 + `ConstraintEntriesIntegrationTest` 10 = 39 条通过；`scripts/check-migrations.sh` 通过（57 个版本唯一）。
