# 三期实现契约（跨票据共享）

本文件是 01–05 票据实现时的统一接口约定。**不要偏离**；若必须偏离，先在票据 PR 描述里说明原因。
票据与规格见同目录 `spec.md` 与 `issues/`。工作树统一放在 `E:/paiban-wt/tXX`。

## 0. 通用

- 后端包：`com.paiban.integration.feishu`（客户端与领域记录）、`auth` / `org` / `notification` 沿用一期包。
- 所有外部调用失败返回结构化结果或 `false`，不向业务层抛异常（ADR-0005 / CODING_STANDARDS）。
- 日志、异常、响应、审计里不得出现 app secret、tenant token、user access token。
- 集成测试沿用 `ApiIntegrationTest` + `@ActiveProfiles("test")`；test profile 使用 fake 客户端，绝不访问外网。
- 前端统一走 `@/api/http`；错误提示沿用现有 `e?.response?.data?.message` 模式。
- 每个票据完成后跑：`cd backend && ./mvnw -q -DskipTests compile` 与相关测试；前端改动跑 `npm run lint && npm run build`。

## 1. 数据库（仅 01 创建，后续票据不再加迁移）

新建 `backend/src/main/resources/db/migration/V18__feishu_integration.sql`：

```sql
ALTER TABLE teacher ADD COLUMN feishu_user_id VARCHAR(100);
ALTER TABLE teacher ADD COLUMN feishu_open_id VARCHAR(100);
ALTER TABLE notification_log ADD COLUMN platform VARCHAR(20);
CREATE UNIQUE INDEX uk_teacher_feishu_user_id ON teacher (feishu_user_id);
CREATE INDEX idx_notification_log_platform ON notification_log (platform);
```

H2 与 PostgreSQL 都必须可用。`ddl-auto=validate` 下实体字段必须与列一致。

另加 `V19__backfill_notification_platform.sql`，回填历史通知来源：

```sql
UPDATE notification_log SET platform = 'dingtalk' WHERE platform IS NULL AND channel = 'dingtalk';
UPDATE notification_log SET platform = 'system' WHERE platform IS NULL AND channel IN ('local','system');
UPDATE notification_log SET platform = channel WHERE platform IS NULL AND channel NOT IN ('dingtalk','local','system');
```

## 2. SettingsService 键（01 增加）

```java
FEISHU_ENABLED            = "integration.feishu.enabled"
FEISHU_APP_ID             = "integration.feishu.app-id"
FEISHU_APP_SECRET         = "integration.feishu.app-secret"
FEISHU_VERIFICATION_TOKEN = "integration.feishu.verification-token"
FEISHU_ENCRYPT_KEY        = "integration.feishu.encrypt-key"
FEISHU_SYNC_STRATEGY      = "integration.feishu.sync-strategy"   // feishu | local
ACTIVE_PLATFORM           = "integration.active-platform"        // none | dingtalk | feishu（05 增加）
```

- `@Value` 默认值前缀 `paiban.integration.feishu.*`，`application.yml` 增加对应 `paiban.integration.feishu` 段（enabled=false, fake=false）。
- `application-test.yml`/`application-e2e.yml` 的 `paiban.integration.feishu.fake=true`，e2e 还设 `enabled=true`。
- `SettingsService.all()` 输出除 secret 外的键（secret 只输出布尔由 DTO 负责；`all()` 不含 secret，沿用钉钉现状）。
- `SettingsController.EDITABLE` 增加飞书键（不含 secret？沿用钉钉：含 `FEISHU_APP_SECRET` 以便设置页保存）。
- `activePlatform()`：读取 `ACTIVE_PLATFORM`；为空时派生：dingtalk enabled → `dingtalk`，否则 feishu enabled → `feishu`，否则 `none`。
  钉钉优先是为了兼容已有部署（两平台同时开启且未显式选择时，不改变现有钉钉行为）。
- `ACTIVE_PLATFORM` **不进入** `SettingsController.EDITABLE`：唯一写入口是 `PUT /api/integrations/platform`，避免出现「选了平台但没启用」的不一致态。

## 3. 飞书客户端端口（01）

`com.paiban.integration.feishu.FeishuClient`：

```java
public interface FeishuClient {
    FeishuUser exchangeAuthCode(String code);
    List<FeishuDepartment> listDepartments();
    List<FeishuUser> listUsers();
    boolean sendMessage(FeishuMessage message);
}
```

记录：

```java
public record FeishuUser(String userId, String openId, String name, String mobile,
                         String email, String employeeNo) {}
public record FeishuDepartment(String departmentId, String name, String parentId) {}
public record FeishuMessage(String openId, String userId, String title, String content,
                            String link, boolean card, String templateCode) {}
```

- `HttpFeishuClient`：`@ConditionalOnProperty(name = "paiban.integration.feishu.fake", havingValue = "false", matchIfMissing = true)`。
  - tenant_access_token：`POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal`，内存缓存，提前 5 分钟刷新。
  - app_access_token：`POST .../auth/v3/app_access_token/internal`（免登换码用）。
  - 免登：`POST .../authen/v1/access_token`，`Authorization: Bearer <app_access_token>`，body `{grant_type:"authorization_code", code}`；响应 `data` 含 `access_token/open_id/user_id/name/mobile/email/employee_no`。
  - 部门：`GET .../contact/v3/departments?parent_department_id=0&fetch_child=true&page_size=50&page_token=...`。
  - 用户：`GET .../contact/v3/users/find_by_department?department_id=0&page_size=50&user_id_type=open_id&page_token=...`。
  - 消息：`POST .../im/v1/messages?receive_id_type=open_id|user_id`，body `{receive_id, msg_type, content}`；`content` 是 JSON 字符串。
  - 重试：5xx/429/IO 最多 3 次，指数退避（`200ms * 2^(attempt-1)`，上限约 2s）；日志只记方法名、状态码、飞书业务错误码与 msg。
  - 业务错误：HTTP 200 但响应 `code != 0` 也算失败，必须记录 code 并返回失败。
  - `listDepartments()` / `listUsers()` 失败时抛 `IllegalStateException`（不得返回空列表），避免同步把「拉取失败」误判为「全员离职」而停用所有教师。
- `FakeFeishuClient`：`@ConditionalOnProperty(name = "paiban.integration.feishu.fake", havingValue = "true")`。
  - 固定用户：`fs-001` / open `ou-001` / 张三 / EMP001；`fs-002` / `ou-002` / 李四 / EMP002。
  - `exchangeAuthCode("fake:EMP001")` 返回张三；未知 code 抛 `IllegalArgumentException`。
  - `sendMessage` 记录到 `ConcurrentHashMap` 或列表，供测试断言；返回 true。

## 4. 三个适配器（01）

- `FeishuAuthProvider implements AuthProvider`，`code() == "feishu"`：
  - `!settings.feishuEnabled()` → empty。
  - 换码得 `FeishuUser`；按 `feishuUserId` 查 `TeacherRepository.findByFeishuUserId`；再按 `feishuOpenId` 兜底。
  - 找到教师 → 取其启用中的本地账号用户名；未找到 → empty（不自动建号）。
- `FeishuOrgDataProvider implements OrgDataProvider`，`code() == "feishu"`：
  - `sync()` 与钉钉同构：先 `listDepartments()`（仅用于日志/校验），再 `listUsers()`。
  - 匹配顺序：`feishuUserId` → `feishuOpenId` → `employeeNo`。
  - 新建教师写 `feishuUserId/feishuOpenId/name/employeeNo/phone/email`。
  - 策略 `feishu` 覆盖姓名/电话/邮箱；`local` 只补空字段。
  - `feishu` 策略下远端不存在的已绑定教师 → `active=false`（不删除）。
  - 审计 `FEISHU_SYNC`，detail 含新增/更新/停用数量。
  - `applyEvent(String type, FeishuUser user)`：`user_deleted_v3`/`user.deleted_v3` → 停用；其余 upsert。
- `FeishuNotificationChannel implements NotificationChannel`，`code() == "feishu"`，`@Order(5)`：
  - 仅当 `settings.feishuEnabled()` 且 `settings.activePlatform()` 为 `feishu`（若 05 尚未合并，先只判断 enabled）时发送。
  - 收件人：按 `AppUser.teacherId` → `Teacher.feishuOpenId/feishuUserId`；没有绑定则跳过。
  - `templateCode` 以 `SUBSTITUTION_` 开头时 `card=true`，其余 `card=false`。
  - 发送失败重试 3 次；写 `notification_log`，`channel="feishu"`、`platform="feishu"`。
  - 任一收件人失败/无人可发 → 返回 false，让 composite 降级。

## 5. 通知记录 platform（04）

- `NotificationLog` 增加 `String platform`（`@Column(length=20)`）。
- `LocalNotificationChannel` 写 `platform="system"`；`DingTalkNotificationChannel` 写 `platform="dingtalk"`；`FeishuNotificationChannel` 写 `platform="feishu"`。
- 现有 `channel` 字段保留（值同 platform，历史兼容）。
- 卡片：`msg_type="interactive"`，header 标题取 `message.title()`，`template="blue"`；正文 markdown 元素；有 link 时加 `action` 按钮「查看详情」，URL 用 `settings.publicBaseUrl() + link`（link 已是绝对地址则原样）。
- 无 link 的模板用 `msg_type="post"` 或 `text`，但保留 Markdown 语义；实现可统一 interactive。

## 6. HTTP 端点

### 01（无 controller 要求；可只加测试）

### 02

- `GET /api/auth/feishu/config`（公开）→ `{enabled, appId}`。
- `POST /api/auth/feishu` body `{code}` → `FeishuLoginResponse`：
  ```java
  record FeishuLoginResponse(String status, String token, Long expiresInMinutes,
                             UserSummary user, String bindToken) {}
  ```
  - `status="ok"` 时带 token/expiresInMinutes/user；`status="binding_required"` 时带 bindToken，其余 null。
  - 集成未启用 → 400；换码失败/未绑定且 05 未实现 → 401。
- 前端：`window.tt` 或 UA 含 `Lark`/`Feishu` 判定飞书环境；调用 `tt.requestAccess({ appID, success, fail })` 取 `code`；成功后 `POST /auth/feishu`；`status="ok"` 存会话跳转，`binding_required` 跳 `/bind?token=...`（05 实现页面，02 可先提示联系管理员）。
- 失败静默降级到账号密码表单，不阻塞页面。

### 03

- `GET /api/integrations/feishu/config`（integration:manage）→ `{enabled, appId, hasSecret, syncStrategy, verificationTokenConfigured}`。
- `PUT /api/integrations/feishu/config` → 同上；`appSecret` 留空表示不修改。
- `POST /api/integrations/feishu/sync` → `OrgSyncResult`。
- `GET /api/integrations/feishu/logs` → `List<AuditLogDto>` 过滤 `FEISHU_SYNC`。
- `POST /api/integrations/feishu/event`（公开）：
  - URL 校验：body 含 `challenge` 时原样返回 `{"challenge": "..."}`。
  - 普通事件校验 token：`X-Feishu-Token` 头，或 v2 事件体里的 `header.token` / 顶层 `token`（飞书 v2 事件把 token 放在 body 里），常量时间比较；失败 403。
  - 事件体兼容 `{header:{event_type}, event:{...}}` 与一期扁平 `{type,userId,openId,name,...}`。
  - 处理 `contact.user.created_v3` / `updated_v3` / `deleted_v3`，转 `orgProvider.applyEvent`。
  - 加密事件（body 只有 `encrypt`）返回 400 并提示需在飞书后台关闭加密或配置转发；README 记录。
  - 管理 UI：SettingsView 增加「飞书集成」卡片（启用开关、App ID、App Secret、验证 Token、同步策略、保存、立即同步、同步日志），布局对齐钉钉卡片。
- 启动全量同步：`FeishuStartupSync` 监听 `ApplicationReadyEvent`，仅当 `paiban.integration.feishu.sync-on-startup=true`（默认）、飞书已启用且当前平台为飞书时触发；异常只记日志，不影响启动。test/e2e profile 设为 false。

### 05

- `GET /api/integrations/platform`（integration:manage）→
  `{activePlatform, dingtalkEnabled, feishuEnabled, dingtalkConfigured, feishuConfigured}`。
- `PUT /api/integrations/platform` body `{platform}` → 同上。
  - 校验 `none|dingtalk|feishu`；目标平台未配置 → 400。
  - 切换：写 `ACTIVE_PLATFORM`；`dingtalk` 时 `DINGTALK_ENABLED=true`、`FEISHU_ENABLED=false`，反之亦然；`none` 两者 false。
  - 触发目标平台 `OrgDataProvider.sync()`（网络调用放在事务外）；审计 `PLATFORM_SWITCH`，detail 记录 from→to 与同步结果。
- `POST /api/auth/feishu/bind` body `{bindToken, employeeNo?, phone?, name}` → `LoginResponse`。
  - `bindToken` 为签名 JWT（短时效 10 分钟，claim `purpose=feishu-bind`，含 `openId`/`userId`/`name`），由 02 的 `/api/auth/feishu` 在未绑定时签发；使用 `JwtService` 或独立密钥均可，但不得复用登录 token 的 claims。
  - 校验：`name` 必填；`employeeNo` 或 `phone` 至少一个；教师需同时匹配姓名与工号/手机号。
  - 已绑定其他飞书账号 → 409；教师无启用中的本地账号 → 409「该教师尚未开通系统账号，请联系教务管理员」；匹配失败 → 404「未找到您的信息，请联系教务管理员」。
  - 成功写 `teacher.feishuUserId/feishuOpenId`，审计 `FEISHU_BIND`，返回该系统账号 JWT。
- 教师 DTO 增加 `dingtalkUserId`、`feishuUserId`、`feishuOpenId`、`bindingStatus`（`dingtalk|feishu|both|none`）。
- `SaveTeacherRequest` 增加 `dingtalkUserId`、`feishuUserId`、`feishuOpenId`（可空，管理员手动修正）。
- 前端：
  - `/bind` 公开路由 + `FeishuBindView.vue`：读取 `token` 查询参数，表单（姓名 + 工号/手机号），成功存会话跳首页，失败显示后端消息。
  - SettingsView「IM 平台」卡片：单选 不启用/钉钉/飞书、当前状态、配置入口、切换按钮；切换前 `ElMessageBox.confirm` 文案「将停用钉钉，启用飞书。教职工数据保留，需重新绑定飞书账号。」
  - TeachersView：新增「平台绑定」列（标签）与编辑弹窗中的钉钉/飞书 ID 输入。
- E2E：完整切换（钉钉 → 飞书 → 教师绑定 → 功能验证）。

## 7. 测试要求

- 01：`FeishuAdapterIntegrationTest`（fake 客户端）：配置读写、同步导入、免登需本地账号、消息发送/降级、事件停用。
- 02：后端免登集成测试；Playwright 注入 `window.tt` mock 验证免登。
- 03：全量同步、事件 upsert/停用、策略覆盖、日志、加密事件拒绝。
- 04：卡片发送成功、失败降级到系统内通知、`notification_log.platform` 标记。
- 05：切换逻辑（数据保留、渠道切换）、绑定成功/失败/冲突、教师 DTO 绑定状态、E2E 全流程。

## 8. 已知约束与接受的偏差

- 官方 Stream SDK 未加入依赖；增量事件走 `POST /api/integrations/feishu/event`，与一期钉钉做法一致，README「已知限制」说明生产可用官方 SDK 转发。
- 教师表无身份证字段，自助绑定用「姓名 + 工号/手机号」验证，不引入身份证数据。
- 自助绑定只覆盖飞书；切回钉钉后，钉钉绑定由教务在教师管理页手动维护（教师已有 dingtalk_user_id 时可直接免登）。
- 仓库没有前端单测运行器；飞书环境检测与免登由 Playwright 覆盖。
- 真实飞书沙箱企业的验收是手动步骤，不进 CI；README「手动验收」列出检查项。
