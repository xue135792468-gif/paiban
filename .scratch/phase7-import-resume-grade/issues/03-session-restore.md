# 03: 导入会话恢复

**What to build:** 解析成功后把 `session` 和 `target` 写进 URL；页面挂载时按 `?session=` 调 `GET /api/ai/import/sessions/{id}` 恢复预览；`useImportSession` 增加 `restore`。空页面用 localStorage 的「上次导入」提供继续入口。切页、F5 刷新后预览都还在。开始新上传或清除时移除 URL 里的 `session`。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] 解析成功后 `router.replace` 写入 `session`、`target`
- [x] 挂载时按 URL 恢复；失败给出可读错误
- [x] `localStorage` 记录 `paiban.aiImport.lastSession`
- [x] 空页面的「上次导入」卡片 + 继续按钮
- [x] 按状态显示：PREVIEW/EDITED/COMMITTED/FAILED
- [x] 新上传/清除时移除 URL 与会话状态
- [x] 单测：URL 恢复、localStorage 上次会话、状态映射、清除
