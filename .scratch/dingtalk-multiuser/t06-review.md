# 06 双轴复查与主控判定（整改中）

2026-11-13；固定基点23b789fd5e90008aa2cc2f5b23d6f8cb07416caa，固定候选90bd973e824d5fec9ba9978c9076f851c00e1a1b。审查命令：git diff 23b789fd5e90008aa2cc2f5b23d6f8cb07416caa...90bd973e824d5fec9ba9978c9076f851c00e1a1b。完整提交列表见 git log 同两端；两独立只读轴已交付。后续1f145ca仅增加core交付文档，源码无差异。

主控33后端/6浏览器和截图通过，仅是候选证据；下列问题未处置完，06未放行，07/08未施工。原始报告保留于t06-standards-raw.md与t06-spec-raw.md，以下只调整标题层级，不合并或重排发现。

## Standards

只读审 `23b789f...90bd973`；未跑测试/构建/网络/服务。主控 33/33、浏览器 6/6、`EXIT=0`、`SOURCE_IDENTICAL=1` 及截图仅证明 H2/Fake，不证明 PG、真租户或手机。路径根：Java=`backend/src/main/java/com/paiban/`，test=`backend/src/test/java/com/paiban/`；`t06-*` 在 `.scratch/dingtalk-multiuser/`。

### Hard

- **P2 本地渠道跨表写非原子**：`notification/channel/LocalNotificationChannel.java:44-67,92` 先写多行 `notification`，再写 `notification_log`；方法无事务，且 `notification/service/NotificationDeliveryGateway.java:22` 为 `NOT_SUPPORTED`，各 repository 默认事务独立提交。违反 `CODING_STANDARDS.md:14`“写多张表的业务动作须同事务”及 `t06-delivery-boundary.md:31`；中途失败会留部分通知或日志断档。最小修复：本地入口加 `REQUIRES_NEW`，并测任一通知写失败时两表全回滚。
- **P2 异步负例依赖固定观察窗**：`test/notification/NotificationDeliveryIntegrationTest.java:336,354` 和 `test/substitution/NotificationBusinessIntegrationTest.java:170` 用 `after(500).never()`。违反 `t06-delivery-boundary.md:43,52`“不可用固定 sleep 赌时序”。最小修复：按 executor queue/active-count 有界 drain 后立即 `verifyNoInteractions`。
- **P2 把排课资格当作账号停用**：`notification/service/NotificationRecipientResolver.java:71,97` 两条分支都过滤 `Teacher.isActive()`；但 `docs/adr/0036-dingtalk-first-login-identity-and-password-recovery.md:11`、`t03-module-plan.md:18` 和 `t06-delivery-boundary.md:15,29,46` 明确账号看 `AppUser.enabled`，可信无账号 Teacher 可按 UID 发。结果会静默漏掉 enabled 账号、有效 UID 但排课资格关闭的审批/接课通知。源码已确认，反例尚未运行。最小修复：两处仅保留 `deletedAt == null`；User 目标按 enabled，Teacher 目标用 enabled 账号加可信 UID。加入“入队后 active=false、account enabled+UID，预期两通知”真红测。

### Judgement

- **P3 possible Duplicated Code + Data Clumps**：`notification/service/NotificationService.java:131-147` 与 `substitution/service/SubstitutionEffects.java:195-217` 重复 class/course/date/period/substitute 变量拼装。抽成有类型渲染上下文或单一工厂。
- **P3 possible Mysterious Name + Speculative Generality**：`notification/service/NotificationFailureRecorder.java:22-32` 的未用 `errorMessage` 与实际写入 `errorCode` 混淆；`notification/service/NotificationService.java:86-89` 未用 `summary`，`substitution/service/SubstitutionEffects.java:191` 仍计算；`substitution/service/SubstitutionAuditService.java:34-45` 的 `alert`、`requesterTeacherId` 已休眠。删除误名/未用参数与计算并更新调用方。
- 未有明确证据：Feature Envy、Repeated Switches、Shotgun Surgery、Divergent Change、Primitive Obsession、Message Chains、Refused Bequest；文档要求的 gateway/事件边界使 Middle Man 被 repo 覆盖。工具已查项不列。

### Counts

- Hard：P1 **0**、P2 **3**、P3 **0**。
- Judgement：P1 **0**、P2 **0**、P3 **2**。
- 最坏：**P2 active/enabled 混用导致静默漏通知**。



## Spec

`90bd973e...23b789fd`。

### P1
1. **(c) 实现错误。** `spec.md:76-77` 要求审批人及最终接课人收钉钉通知；`t03-module-plan.md:18` 明定“`Teacher.active`是排课资格，不等同于账号enabled”。`NotificationRecipientResolver.java:71,97` 却在两类映射均过滤 `isActive()`。故账号启用、UID已绑定但排课资格关闭时，审批人不走钉钉，接课人可能连本地降级也跳过。

最小修：两处删 `isActive()`，补“入队后停排课资格、账号/UID不变”红测，断言两通知仍发。33测试及6/6浏览器未覆盖；红测未跑，不称复现。

### P2/P3
无。

计数：P1=1，P2=0，P3=0；最坏=P1。真租户/PG/手机未验证。


## 主控逐项判定

|ID|判定与边界|处置/证据要求|
|---|---|---|
|S1 本地写原子性|确认。主控复读LocalNotificationChannel无事务，gateway暂停外层事务；notification与notification_log分开提交，真实违反跨表事务约定。|本地DB入口经实际代理的短REQUIRES_NEW事务，不把Ding网络包入事务。先真实HTTP/DB故障红测证明旧源留部分本地记录，再同测转绿；成功通知/成功日志同回滚，独立安全失败标记允许另事务保留。审批已提交结果保持正确。|
|S2 固定观察窗|确认。after(500).never不能证明异步队列已收尾，负例会受负载时序影响。|有界等待框架executor真正排空，再断外部结果。可用单worker FIFO的sentinel Future完成屏障或已有可靠helper；不以加长sleep/timeout或只删断言过关。|
|S3 / F1 / 主控QC2|同根确认，各轴计数独立保留（Standards P2、Spec P1）。Teacher.active是排课资格，不能冒充AppUser.enabled。|统一一次修复；入队后只停排课资格、保留真实enabled和UID的公开红绿，删除两处active过滤，保留软删/账号启停/无账号可信UID规则。原账号停用不发的相反回归仍须绿。|
|J1 Duplicated Code / Data Clumps|不采纳这一启发式抽取建议。两处分别服务审批结果与既有生效通知，其候选/最终/旧生效行回查、comment/reason语义不同；重复主要是模板字典字段与查名装配，没有独立业务规则被复制。|本轮不新增通用上下文工厂或重写既有变量接口。此为已裁定判断建议，不是延期缺陷；原P3计数不改写为零。未来确有共同规则扩展再评估，不据此要求追加票。|
|J2 未用参数/计算|采纳。退役抄送后summary及alert/requester参数已无业务用途，errorMessage参数实际未写入，保留会误导维护。|最小移除闲置参数/summary计算并同步实际内部调用者；保留审计动作、detail与状态事务，不改权限/审核结果。退役public通知兼容门面如仍有明确兼容意图可保留无操作，不借清理恢复推送。|
|主控QC1 产品文案|确认（来自真实截图，独立于两轴原始数量）。实现说明与截断模板代码不帮助教务判断。|业务中文类型与状态解释，未知类型/渠道诚实回退标识；安全错误机制不变，不增加操作/路由。复验组件与受影响浏览器。|

Spec原报告中的“审批人”是文字误称；规格与整改目标始终是申请人/实际operator，不是批准管理员。这个澄清不改变原P1等级或数量，也不增加新的收件对象。

全部确认问题交codex/dingtalk-t06-review-fixes单一worker；原始证据保留，行为红测必须区分fixture错误，不能用早先33绿掩盖新反例。整改后固定新源运行受影响定向与主控浏览器，再由原两轴独立增量复核，满足后才单票放行。最终八票full verify仍留到全部施工/复查后。

轴内汇总：Standards共5项（hard 3×P2、judgement 2×P3，最坏hard P2）；Spec共1项（P1，最坏P1）。未做跨轴合并或重排。

