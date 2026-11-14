# 全 8 票 Spec 轴终审初稿（P 级，非最终放行）

审源 `be48fc0e3b08013c4ffbd7d716d9385169ce6e8d`（只读 WT，rev-parse 全 SHA 已核，未移动、干净）；base `2e5e585f…`；delta `016859b...be48fc0` 22 files +1309/-47。spec/issues01–08、ADR-0036、已确认绑定保护未变。未跑测试/构建/网络，未改源。

## (a) 缺失 0
spec:25、41「所有账号操作留审计」：自动开号+配对各一条、精确 2 条（`DingTalkAcceptanceChainIntegrationTest.java:105-109`）。spec:134 期望/最终分别记录（同 132/140；`dingtalk-acceptance.spec.ts:142-143`）；spec:24、145 仅两类通知、仅最终接课人：外发恰 2 条且收件人=申请人+最终人，提交阶段 0 条、期望/最终教师均无影子账号（160-176）。真实 `POST /api/auth/dingtalk` 取 JWT（96-101；E2E 69-78），申请人/期望/最终/教务四身份分离（134-135）。旧表保全 21 张逐列投影+灵敏度自检（`OldDatabaseUpgradeAcceptanceTest.java:49、145`）。补审原未覆盖：`AccountAuditView.vue:21-43、83-87`、五状态 `types.ts:164-170`、`UserAdminView.vue:55` 免登无重置密码、`NotificationDeliveryGateway.java:25`（NOT_SUPPORTED）与 `DingTalkNotificationChannel.java:54-76`（3 次重试、首成功降级）。

## (b) 范围外 0
`fake:acceptance:` 仅存在于 `@ConditionalOnProperty(fake=true)` 的假客户端、不入同步目录（`FakeDingTalkClient.java:24、99-104、160-166`），不构成产品扩范围。

## (c) 实现错 0
独立计数：P0/P1/P2/P3 均 0，最坏项无。preliminary 的 4 条 P3 已按主控初裁与 corrigendum 全部撤回，不重开；本 delta 与新读 hunks 未发现新缺陷。

## 证明边界（不得据此宣称）
H2/Fake/离线手机视口不证明真实 PostgreSQL、钉钉租户、手机送达或实机；升级 5 项为 H2，真实 PG 仅靠本轮未运行的 `PostgresContainerIT`；`verify --full` 尚未跑。本轴结论限于「本审源未发现产品 confirmed 缺陷」，不是全量通过。
