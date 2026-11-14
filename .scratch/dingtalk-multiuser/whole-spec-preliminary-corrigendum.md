# 整特性 Spec 轴预审 更正与裁定接收（corrigendum）

- 针对：`whole-spec-preliminary-raw.md`（原稿保留原样、原数字不改，本文件为独立更正）
- 审源不变：`dingtalk-t07-review-snapshot` HEAD `016859bc...`（起点 `2e5e585f...`），只读
- 性质：接收主控初裁 + 自有元数据复核；仍为 P 级初稿，不构成最终放行

## 一、计数自我纠正

原稿写「P3 观察 3 条」系计数错误。按原稿实际枚举，独立观察为 **4 条**：(b)-1 `ACCOUNT_UPDATE`、(b)-2 event 入口认证、(c)-1 管理员代发审批结果收件、(c)-2 提交/批准可用性口径。原稿数字保留不动，以本文件为准；严重级别由本文件自行重述，不要求主控重排。

## 二、逐条裁定

| 原编号 | 观察 | 裁定 | 依据 |
|---|---|---|---|
| (b)-1 | `ACCOUNT_UPDATE` 属九类之外 | **撤回（非范围外）** | spec:25「所有账号操作留审计」、spec:41 为总则，资料变更本在其中；`AccountAuditAction` 增项是实现该总则，非扩范围 |
| (b)-2 | event 入口仅共享令牌、无签名/时间戳 | **撤回（非 scope creep）** | 我自行 `git show 2e5e585:backend/.../IntegrationController.java` 核到 182–198 行原有 `X-DingTalk-Token` + `MessageDigest.isEqual`，本 diff 未改该认证块；`applyEvent` 新增绑定保护有授权，**不得删除** |
| (c)-1 | 管理员代发时审批结果只投管理员 | **撤回（已裁定）** | `t06-review.md:63`：收件对象始终为申请人/实际 `operatorUserId`；管理员代发不抄给被代表老师属既定裁决，无新证据不重开 |
| (c)-2 | 提交只 `existsById`、批准才校验 `active` | **撤回（两阶段职责）** | 提交的 `expectedSubstituteTeacherId` 是意向；批准才对最终 teacher 查最新 active 与冲突。spec 未要求提交即验证可排课 |

## 三、更正后结论

- 未决独立观察：**0**（原 4 条全部撤回）；confirmed 产品缺陷 **0（P0/P1/P2）**；范围外新增 **0**。
- 覆盖边界同原稿：未读未证（AccountAuditView、SubstitutionMineTable 五状态渲染、通知网关重试/降级内部、求解器、全部测试与 e2e），未执行任何门禁或实机；08 未审。
- 本文件不宣布最终通过。待最终独立 snapshot（全起点→最终候选命令 + 08 delta）到位后做最终全规格终审，届时另出终审记录。
