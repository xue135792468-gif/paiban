# 06 通知发送记录只读 API 交付

2026-11-13。worker：`codex/dingtalk-t06-log-api`；施工 base 为
`7ed1c6c9f88ef39e0136cc1026e68441f6c978c1`，最终测试前已同步 integration
`1dd5bcc8ce8d3456991a499dfde78de6a26a0377`。本文件记录当前实现、真实测试证据和仍未验证的
范围；不代表整票或集成候选已放行。

## 实现边界

仅新增以下生产代码与测试：

- `backend/src/main/java/com/paiban/notification/web/NotificationDeliveryLogController.java`
- `backend/src/main/java/com/paiban/notification/web/NotificationDeliveryLogDtos.java`
- `backend/src/main/java/com/paiban/notification/service/NotificationDeliveryLogQueryService.java`
- `backend/src/test/java/com/paiban/notification/NotificationDeliveryLogApiIntegrationTest.java`
- 本交付文档

未修改 `NotificationLogRepository`、实体、迁移、前端、其它生产源或测试，也未新增权限、路由或
通知写路径。

## Wire 契约

- `GET /api/notification-delivery-logs?page=0&size=20`。
- 权限使用 `Permissions.SUBSTITUTION_VIEW`；Teacher 没有该权限，教务与超级管理员可读。
- `page >= 0`，`size` 为 `1..100`；缺失时分别为 `0/20`。非整数、越界与负数均返回 400。
- 返回固定 `{items,total,page,size}`；item 固定为
  `id,channel,platform,recipient,recipientLabel,templateCode,content,success,createdAt,errorMessage`。
- 数据库分页排序固定为 `created_at DESC, id DESC`。先把 `page * size` 以 `long` 计算并读取
  `total`；offset 已越过总数时直接返回同 page/size 的合法空页，避免超大 JPA offset 导致 500。
  该规则覆盖 `page=2147483647,size=100`，同时保留锁定的“page 非负”契约。
- 旧 nullable `platform/content` 归一为空字符串。成功行的 `errorMessage` 恒为 JSON `null`；
  失败行恒为固定安全文案“发送失败，请联系管理员”，不读取或回传旧错误原文。
- 查询不依赖学期，不调用 `/users`，不发送、重试、标记、删除或写记录。

## 收件人标签

标签严格按实际 channel/platform 语义解析：

- `channel=dingtalk`：recipient 按钉钉 UID 查 `TeacherRepository.findAllByDingtalkUserId`；
  只有唯一教师命中时显示教师姓名。多个教师命中或未知 UID 时原值回退。
- `channel=local` 且 platform 为 `system`/旧 nullable：仅识别旧 local 的 `[id, id]`；
  按 user id 查 `AppUser.displayName`，不按 enabled 过滤。多个可解析名称按原 id 顺序以“、”
  连接；任一 id 无法解析时整个标签原值回退。
- `channel=notification` 且 platform 为 `system`/nullable：仅该 core 内部失败记录语义允许识别
  `user:id` / `teacher:id`，分别显示账号名称或教师姓名。同形成本地账号 id、停用状态不参与过滤。
- 其它 channel、未知格式、悬空 id、歧义 UID 一律回显原 recipient，不猜身份。因此 Ding UID
  即使文本恰好是 `user:123`，仍按 UID 查询 Teacher；未知 channel 即使 platform 写 `dingtalk`，
  也不进入 DingTalk 标签解析。

## TDD 与真实测试

测试使用随机端口、真实 HTTP、真实 H2/Flyway/JPA，并从 HTTP 进入；外部钉钉边界只用
`@MockitoBean DingTalkClient`，未访问真实外网。

已覆盖：

- 固定 wire allowlist、nullable 归一、UID 唯一标签与 `errorMessage:null`。
- `user:/teacher:/local [id]`、停用账号、歧义 UID、未知格式、未知 channel 与同形平台值。
- Teacher 403、Academic 200、Super 200。
- `createdAt DESC, id DESC` 跨页、`page=-1`、`size=0/101`、非数字 400、`size=100` 200。
- 旧危险错误正文和敏感账号信息不泄漏，成功 Error 也强制 null。
- 无学期可查询；读取前后所有 public base table 行数不变、`notification_log` 全部原始行不变，
  `DingTalkClient` 零交互。
- `page=2147483647,size=100` 返回 200 空页与正确 total，不出现 500。

真实日志均在 `.runtime/`，未清理：

| 日志 | 阶段 | 真实退出码/结果 |
|---|---|---|
| `t06-log-api-red.log` | 首条 tracer | Maven 1；HTTP 404，期望 200 |
| `t06-log-api-red-label.log` | 标签切片 | Maven 1；`user:2` 未解析 |
| `t06-log-api-green-label.log` | 标签切片 | Maven 0；1/1 |
| `t06-log-api-red-pagination-auth.log` | 边界切片 | Maven 1；`size=101` 返回 200 |
| `t06-log-api-green-pagination-auth.log` | 4-test 批次 | Maven 0；4/4 |
| `t06-log-api-red-or-green-safety.log` | 安全/只读切片 | Maven 0；1/1 |
| `t06-log-api-red-main-review.log` | 主控边界回归 | Maven 1；7 tests 中 2 failures：同形标记误判、极值页 500 |
| `t06-log-api-green-final-preliminary.log` | 修复后 API 批次 | Maven 0；7/7 |
| `t06-log-api-green-final-merged.log` | merge `1dd5bcc` 后最终 API 批次 | Maven 0；7/7 |

按主控补充又做了两项最小收紧：未知 channel 不再因 platform 值进入 UID 解析，极值页测试改为
在 total=1 时验证空页；新增对应未知 channel/platform 回归。同步稳定 core+UI+transport
integration `1dd5bcc` 后，最终 7-test 定向批次已真实通过。

## 未验证与约束

- 未运行全量 gate、Playwright、Vitest、服务启动、PostgreSQL 容器或真实企业钉钉租户。
- 未验证前端真实 workbench 产生 local 日志后的浏览器展示；该边界由 UI worker/E2E 负责。
- 未 push、未 deploy，未访问真实外网。
- 本轮最终 Maven 已退出；后续阶段不得再启动新的 Maven。
