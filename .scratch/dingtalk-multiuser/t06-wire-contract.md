# 06 发送记录只读接口契约

2026-11-13 主控锁定；与 t06-admin-log-plan.md、t06-delivery-boundary.md 配套。

- `GET /api/notification-delivery-logs?page=0&size=20`，现有 `Permissions.SUBSTITUTION_VIEW`，Teacher 403。
- page 非负，size 1..100；非法参数 400。按 createdAt、id 倒序。
- 返回 `{items, total, page, size}`。items 字段固定：`id, channel, platform, recipient, recipientLabel, templateCode, content, success, createdAt, errorMessage`。
- id 为数字，recipient 为原收件标识字符串；channel/platform/templateCode/content/createdAt 为字符串，success 布尔。recipientLabel 为服务器安全解析的显示名，未知时回退 recipient；errorMessage 成功为 null，失败为固定安全说明，绝不回传数据库旧错误原文。
- channel/platform 沿用现有日志实际枚举；UI 对钉钉成功显示“钉钉已受理”，本地成功显示“系统已保存”，其它成功诚实显示“已受理”；未知渠道名称原值以文本显示，不猜手机送达或已读。
- 管理员设置页按需展开加载，每页20条；不依赖学期、不调用 /users；老师不显示卡片也不发请求。分页、加载、失败与空态齐全。
- 查询仅只读，不附带发送、重试、标记、删除；不新增权限、迁移、导航或个人收件箱。

后端若实际字段类型/现有枚举有必要调整，先通知主控与 UI worker，不各自猜改。
