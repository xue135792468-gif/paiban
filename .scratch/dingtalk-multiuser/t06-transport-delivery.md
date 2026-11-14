# 06 传输层交付：工作通知 HTTP 协议与安全失败

2026-11-13，施工 worktree：`E:/排课系统/.worktrees/dingtalk-t06-transport`。

## 写集

- `backend/src/main/java/com/paiban/integration/dingtalk/HttpDingTalkClient.java`
- `backend/src/test/java/com/paiban/integration/dingtalk/DingTalkWorkNotificationHttpTest.java`
- `.scratch/dingtalk-multiuser/t06-transport-delivery.md`

只修改 `sendWorkNotification` 的请求/响应处理。未改 token、身份、目录、探针类，也未改通知业务调用方、异步交付、日志查询或设置页。

## 实现契约

- 使用官方 `asyncsend_v2` 卡片形状：`msg.msgtype=action_card`，卡片字段为 `action_card.title`、`action_card.markdown`、`action_card.single_title`、`action_card.single_url`。
- 读取响应 JSON。仅当 `errcode` 是数字且数值为 `0` 时返回 `true`；HTTP 200 本身不表示受理。
- `task_id` 不要求存在或为正数；`{"errcode":0,"task_id":0}` 仍表示平台响应可受理。
- `errcode` 非零、缺失、类型不是数字，响应体为空、非 JSON 或解析失败，以及 HTTP 传输失败，统一返回 `false`。
- `RuntimeException` 的 catch 只写固定安全诊断 `DingTalk work notification delivery failed`，不记录异常 message、cause、堆栈、vendor 响应、收件 UID、query token 或凭据。

## 纵向 TDD 证据

测试使用 `MockRestServiceServer` 作为本地 HTTP stub。请求经过实际 `RestClient` 消息转换后再断言 JSON，不访问真实钉钉。

| 循环 | 红色证据 | 绿色证据 |
|---|---|---|
| 官方卡片请求形状 | `.runtime/logs/t06-http-red.log`，退出码 `1`；stub 实际捕获 `actionCard` 并报缺少 `action_card` | `.runtime/logs/t06-http-green-body.log`，退出码 `0` |
| HTTP 200 非零 errcode | `.runtime/logs/t06-http-red-http200-nonzero.log`，退出码 `1`；当前错误返回 `true` | `.runtime/logs/t06-http-green-nonzero.log`，退出码 `0` |
| 坏响应与固定安全诊断 | `.runtime/logs/t06-http-red-safe-diagnostics.log`，退出码 `1`；日志含 vendor 正文和收件 UID | `.runtime/logs/t06-http-green-safe-diagnostics.log`，退出码 `0` |
| 最终完整类级回归 | 不适用 | `.runtime/logs/t06-http-final.log`，`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`，退出码 `0` |

最终日志 SHA-256：

```text
AA627587F6166122F533A4E97DECB512ACDA3168819967B2403BF74EAD9A95BD
```

最终测试覆盖：

- 实际序列化出的官方 action card JSON 与相对链接转绝对 URL。
- numeric `errcode=0` 返回 true，且不强制正数 `task_id`。
- HTTP 200 + 非零 errcode 返回 false。
- HTTP 200 + 空体、坏 JSON、缺 errcode、字符串 errcode 均返回 false。
- HTTP 500 返回 false；捕获日志只有固定安全文本，不含 AppSecret、access token、vendor 正文或收件 UID。

## 执行环境与卫生

- 每次运行前使用 CIM 检查物理内存；低于 2 GiB 不启动。最终跑后复查可用物理内存约 `6.28 GiB`，E 盘剩余约 `6.85 GiB`。
- 使用 GitPortable Bash `C:/Users/liu/AppData/Local/Programs/GitPortable/bin/bash.exe`，执行 `source .tools/env.sh`。
- Maven 使用 `MAVEN_ARGS='-s E:/排课系统/.tools/maven-settings.xml -Dmaven.repo.local=E:/排课系统/.tools/m2repo -DforkCount=0'`。
- `TEMP`、`TMP`、`TMPDIR`、`npm_config_cache` 均指向本 worktree `.runtime`。
- Maven 定向测试已结束；没有常驻测试进程。`.runtime/logs` 证据按要求保留，未做递归清理。

## 未验证范围

- 未运行全量 `verify.sh`、E2E、部署、性能或 PostgreSQL 容器测试。
- 未访问真实钉钉网络，未使用企业凭据，未验证真租户授权、手机通知送达或已读。
- 本切片不证明事务提交后异步交付、三次重试、收件收窄、发送记录 UI 或数据库日志链路；这些属于 06 core/UI 写集和整合验收。
- 主控合并与独立双轴评审尚未进行。
