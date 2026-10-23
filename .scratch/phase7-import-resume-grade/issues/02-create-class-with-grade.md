# 02: 按年级名称创建班级

**What to build:** `CreateItem` 增加 `gradeName` 与 `studentCount`；`ImportMappingService` 创建班级时接受 `gradeId` 或 `gradeName`（优先 `gradeId`），`gradeName` 在当前学期不存在时创建年级（`sortOrder` = 当前最大 + 1），并写入 `studentCount`。完成后，批量创建可以一次建好缺失的年级和班级，不用先手动建年级。

**Blocked by:** 01

**Status:** done

- [x] `CreateItem` 增加 `gradeName`、`studentCount`
- [x] `validateCreate`：班级至少给 `gradeId` 或 `gradeName`，都空 → 400
- [x] `createClass`：按 `gradeName` 查找或创建年级，再创建班级
- [x] 写入 `studentCount`（缺省 0）
- [x] 集成测试：新建年级 + 班级；已有年级匹配；跨年级批量；人数写入
