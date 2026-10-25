# 13: 草稿校验说"通过"，发布却拒绝（教室口径不一致）

**What to build:** `GET /timetable-drafts/{id}/validation` 对 546 节全部无教室的草稿返回 `valid=true, hardConflicts=0`，而 `POST /timetable-drafts/{id}/publish` 返回 400「还有 546 节课未排课或未分配教室」。同一个"能不能发布"的问题有两个答案，用户会先看到绿色通过、再被拒绝。

**证据（2026-10-25 云端实测）**：两份导入草稿 validation 均 `(True, 0)`；对 draft 1 调发布 → 400「还有 546 节课未排课或未分配教室，不能发布」。

**Blocked by:** None（与 07-rooms-before-publish 相关：07 补教室入口，本票统一口径）

**Status:** ready-for-agent

- [ ] 草稿校验包含"发布前置条件"（缺课节/缺教室/未排课），或在结果里单独列出 publishable=false 及原因
- [ ] 发布与校验共用同一套判定
- [ ] 集成测试：无教室草稿 → validation 不再报 valid=true；补教室后 → valid=true 且能发布

**实现说明：** （待填写）
