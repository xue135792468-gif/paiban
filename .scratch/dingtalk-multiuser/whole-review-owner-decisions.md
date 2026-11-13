# 整特性预审的主控逐项裁定（最终闸门尚未通过）

固定原始预审范围2e5e585f6ed80d5024af6b734162a70829b067cf→016859bc35a31f2cf5c93e2280e395cbad2b6d62。两个原轴保持独立，raw原文不删不重排；08与统一整改后的新固定候选另行终审。初稿不是已覆盖全部源或全量门禁通过的证明。

## Standards

原始Hard 1（H1 P2），Judgement 3（均P3），最坏Hard H1 P2；完整原文见whole-standards-preliminary-raw.md。

|原项|主控裁定|理由/具体动作|
|---|---|---|
|H1 四spec复制浏览器错误收集|确认，交统一fixer|四处复用helpers.trackBrowserErrors，保留原afterEach断为空与监听范围，不弱化错误检查。|
|J1 日期fill+Tab重复|采纳|提炼fillDateInput(Locator,date)于helpers，05审批、既有substitution与08整链调用，不改日期、超时或状态断言。|
|J2 SettingsView权限串|部分采纳并纠正事实|现有共享常量只有INTEGRATION_MANAGE_PERMISSION，设置页复用它。substitution:view并无原始报告所称既有常量，维持现有惯例，不为单一字段增造完整权限字典。|
|J3 通知退役方法/notify|部分采纳|移除两处零调用的空退役方法；notify保留，NotificationDeliveryIntegrationTest两处确有调用，用于公开模块事务/队列边界验证，是现有真实测试接缝，不是未出现需求的抽象。无需为删除它重写整个业务种子。|

统一fixer Turing，工作树dingtalk-whole-review-fixes，从已验fecb656开始，结束前合入集成最新；只改已确认写集。静态与后端定向后由主控串行跑受影响浏览器，再独立增量核对。没有把未采纳的判断建议标为“已修复”。

## Spec

原报告confirmed P0/P1/P2=0、P3观察总数写为3；正文实际四条，原轴已在whole-spec-preliminary-corrigendum.md自行更正为4并独立核对后全部撤回，未决0。主控未合并发现或改严重度，原文仍保留whole-spec-preliminary-raw.md。原轴正对含08的be48fc0新固定快照终审，本更正不等于最终放行。

|原观察|主控初裁|核对依据|
|---|---|---|
|ACCOUNT_UPDATE附加审计|不认定范围外|spec:25“所有账号操作留审计”、用户故事41“每一次账号操作”；列举九类不抵触资料变更同样审计。属于同一账号审计行为，不引入新业务功能。|
|回调只有共享令牌、无签名/时间戳|不认定本特性新增范围|主控实读git show 2e5e585:IntegrationController，既有event入口已使用X-DingTalk-Token且同样无签名/时间戳，本次未改变该认证块。新增绑定保护是用户已明确要求所有UID写入遵守，不能以此删除保护。|
|管理员代发时审批通知投操作账号|维持06已有裁定|t06-review.md及wire契约已明确申请结果只投真实operatorUserId；代发管理员是实际申请操作者，不按其所代表的原教师扩散通知。没有新的反例或用户改口，不重开既定收件人规则。|
|提交expected只查存在、批准才查active|不认定明确规格缺陷|提交为待批意向，不产生课表实效；批准依据最终教师的当时资格/冲突复核。两个阶段承担不同职责，现规格未要求把最终生效检查全部提前；审批仍不得跳过资格检查。|

这些是主控裁定，须由原Spec轴在最终候选对真实可达行为复核，不能据本表宣称最终放行。真实PG、钉钉租户/手机、最终full verify均未验证；不push、不部署。
