# 排课系统三期规格：飞书集成与平台切换

Status: ready-for-agent

---

## Problem Statement

一期二期交付后，系统已支持钉钉集成。但部分学校使用飞书而非钉钉，且学校可能从钉钉迁移到飞书。当前系统无法支持飞书，也无法在双平台间平滑切换。

## Solution

三期实现完整的飞书集成，并支持钉钉/飞书一键切换：

1. **飞书适配层**：OrgDataProvider / AuthProvider / NotificationChannel 的飞书实现，与钉钉实现并存
2. **飞书 H5 微应用与免登**：教师从飞书工作台点击进入系统自动免登
3. **飞书组织同步与 Stream 事件**：WebSocket 长连接接收飞书入离职事件
4. **飞书消息通知与卡片**：调代通知、审批卡片，失败降级到系统内通知
5. **平台一键切换**：钉钉 ↔ 飞书平滑切换，本地数据保留，未启用平台不影响当前平台

## User Stories

### 飞书集成

1. 作为**教务管理员**，我希望能配置飞书企业凭证（App ID / App Secret），以便启用飞书集成。
2. 作为**教师**，我希望能在飞书工作台里点击应用图标直接免登进入系统，以便不用记密码。
3. 作为**教务管理员**，我希望能从飞书同步教职工名单，以便入离职自动同步。
4. 作为**系统**，我希望调代批准时能发送飞书消息通知（支持 Markdown 与交互卡片），以便教师一键查看详情。
5. 作为**系统**，我希望在飞书不可达时降级为系统内消息，以便通知功能不中断。

### 平台切换

6. 作为**教务管理员**，我希望能一键从钉钉切换到飞书，以便学校迁移平台时平滑过渡。
7. 作为**教务管理员**，我希望切换平台后本地数据（班级、课程、课表、调代）完全保留，以便不丢失历史数据。
8. 作为**教务管理员**，我希望未启用平台（如切换后的钉钉）不影响当前平台（飞书）的使用，以便系统稳定运行。
9. 作为**教师**，我希望切换平台后第一次用飞书登录时能自助绑定身份（输入工号/手机号），以便快速完成迁移。
10. 作为**教务管理员**，我希望能查看和修改教师的平台绑定关系（dingtalk_user_id / feishu_user_id），以便处理绑定错误。

## Implementation Decisions

### 飞书适配层

- 复用一期定义的 `OrgDataProvider` / `AuthProvider` / `NotificationChannel` 接口
- 新增 `FeishuOrgDataProvider` / `FeishuAuthProvider` / `FeishuNotificationChannel` 实现
- 配置项 `integration.feishu.enabled` / `app-id` / `app-secret`
- 飞书 API 文档：https://open.feishu.cn/document/

### 飞书免登

- 前端检测在飞书环境则调用 `window.tt.requestAccess` 获取 code
- 后端调用飞书 `/open-apis/authen/v1/access_token` 换取 user_info
- 颁发系统 JWT，与钉钉免登共用同一套认证逻辑

### 飞书组织同步

- 启动时全量拉取飞书组织（`/open-apis/contact/v3/departments` + `/open-apis/contact/v3/users`）
- Stream 模式：飞书 WebSocket 长连接接收事件（`contact.user.created_v3` 等）
- 同步策略：飞书为准，保留本地覆盖开关

### 平台切换

- `system_config` 新增 `integration.active_platform`（枚举：none / dingtalk / feishu）
- 切换时：停用原平台配置 → 启用新平台配置 → 触发组织同步 → 通知渠道切换
- 本地数据完全保留：teacher 表的 dingtalk_user_id / feishu_user_id 双字段并存
- 历史通知记录保留，标记来源平台（dingtalk / feishu / system）

### 教师自助绑定

- 首次飞书登录时检查 feishu_user_id 是否已绑定
- 未绑定则进入绑定页面：输入工号 / 手机号 / 姓名+身份证号后四位
- 匹配本地教师表，绑定 feishu_user_id
- 匹配失败提示联系教务管理员
- 教务可在教师管理页面直接修改绑定关系

## Testing Decisions

### 测试接缝

1. **飞书适配层单元测试**：fake 飞书 API 验证 OrgDataProvider / AuthProvider / NotificationChannel 实现
2. **飞书免登集成测试**：模拟 OAuth 流程，验证 JWT 颁发
3. **平台切换集成测试**：钉钉 → 飞书切换，验证数据保留与通知渠道切换
4. **教师自助绑定 E2E 测试**：首次飞书登录 → 绑定 → 进入系统

### 测试数据

- 飞书沙箱企业（手动验证，不进 CI）
- fake 飞书 API 响应（单元测试用）

## Out of Scope

- 学生/家长端
- 微信小程序
- 多租户 SaaS
- 新高考走班
- 考试排考
- 教室预约/借用
- 数据报表与统计

## Further Notes

### 参考

- ADR-0005：外部集成可插拔
- ADR-0019：AI 供应商管理（配置模式参考）
- 一期钉钉集成实现：`integration/dingtalk/` 目录

### 飞书 vs 钉钉 API 差异

| 功能 | 钉钉 | 飞书 |
|------|------|------|
| 免登 | `dd.getAuthCode` | `window.tt.requestAccess` |
| 组织架构 | `/topapi/v2/department/listsub` | `/open-apis/contact/v3/departments` |
| 消息通知 | `/topapi/message/corpconversation/asyncsend_v2` | `/open-apis/im/v1/messages` |
| 事件订阅 | Stream 模式 WebSocket | 长连接（类似 WebSocket） |
| 消息卡片 | ActionCard | Interactive Message Card（功能更强） |
