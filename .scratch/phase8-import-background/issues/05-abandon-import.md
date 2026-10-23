# 05: 放弃导入（ABANDONED）

**What to build:** 解析中或 `PARTIAL` 状态下可以「放弃导入」，会话标记为 `ABANDONED`：不生成草稿，不写入任何业务数据，会话记录保留用于审计。放弃后的会话不再作为可继续的导入出现在恢复入口。

**Blocked by:** 02, 03

**Status:** done

- [x] 解析中与 `PARTIAL` 下都可以放弃，状态变为 `ABANDONED`
- [x] 放弃不创建草稿、授课任务、班级等业务数据（用查询断言）
- [x] 会话记录保留，可在导入历史中查看状态与原因
- [x] 「上次导入」与 URL 恢复入口不再把 `ABANDONED` 当作可继续会话
- [x] 测试覆盖两种放弃入口与「无业务数据写入」

**实现说明：** `POST /sessions/{id}/abandon` 置 `ABANDONED` 并触发停止标记，后台线程不再发起新调用；`ai_explanation` 固定为「用户放弃导入」（不接受自定义原因，避免出现前端从不发送的死参数）。导入历史摘要显示「文件名 · 已放弃」，`success=false`。`ABANDONED` 会话对 `commit/rows/mapping/continue/reparse/stop/abandon` 全部 409，`GET session` 仍可读（审计用）。前端恢复入口与「上次导入」都不再展示它。
