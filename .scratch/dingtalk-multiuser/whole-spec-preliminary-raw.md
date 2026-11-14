# 整特性 Spec 轴只读预审（P 级初稿，非最终放行）

- 审源：`dingtalk-t07-review-snapshot` HEAD `016859bc35a31f2cf5c93e2280e395cbad2b6d62`（已核对，未移动、工作树干净）
- 起点：`2e5e585f6ed80d5024af6b734162a70829b067cf`；commits/paths 见 `logs/whole-review-preflight/`
- 输入：`spec.md`、`issues/01–08`、ADR-0036、t03 binding 保护（用户已确认）
- 方式：只读源码与 git；未构建、未跑测试、未联网、未改源、未删文件
- 结论：本轴 **confirmed 产品缺陷 0（P0/P1/P2）**；P3 观察 3 条；**08 未审，不据未覆盖宣称全通过**

## (a) 缺失部分
01–07 未见 spec 条目缺项：免登四步、唯一未绑定同名配对、管理员首绑需手工 UID、disabled/遗留自定义角色按权限点保护（`DingTalkBindingPolicy.java:77-100`、`AppUserRepository.java:32-33`，不滤 enabled）、`Teacher.active` 不参与账号判定、`DingTalkMergeRules.java:39/63` 不覆盖非空 UID、不建影子账号（`DingTalkAccountProvisioner.java:64-90` 仅 linked 为空才建号、停用即拒）。未落地者仅 08 整链与实机验收，按指示不计缺陷。

## (b) 范围外
`ACCOUNT_UPDATE`（改资料）为 spec 九类之外的附加审计动作（`AccountAuditAction.java:17`），P3 合理扩大。事件回调仅共享令牌校验、无签名/时间戳，且未匹配时新建档案（`IntegrationController.java:210-230`、`DingTalkOrgDataProvider.java:320-345`），超出「事件令牌」最小口径，P3。

## (c) 实现错
confirmed 0。P3-1：管理员经 `POST /substitution-requests` 代人提交时 `operatorUserId`=管理员（`SubstitutionRequestService.java:161-165`），审批结果仅投给该账号（`NotificationService.approvalTarget`），被代表老师不可见亦无通知；spec 36 只覆盖老师自助，故待终审确认可达性。P3-2：提交仅 `existsById`（`SubstitutionLookups.requireTeacher`），批准才校验 `active`（`SubstitutionApprovalService.java:367-372`），两处口径不一致。

## 覆盖边界（未覆盖即未证明）
已读：identity/integration/notification/substitution 主链 Service+Controller、V60–V63、TeachersView/SettingsView/LoginView/探针/导航权限。未读未证：前端 AccountAuditView、SubstitutionMineTable 五状态渲染、通知网关重试与渠道降级内部、Timefold 求解、全部测试与 e2e 代码；未执行任何门禁或实机。探针已确认用未保存参数、仅 AppKey 相同才复用密钥、无写库/通知（`DingTalkConnectionProbeService.java:31-48`）。最终放行待 08 delta 与新独立 snapshot 全起点→最终候选复核。
