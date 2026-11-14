# 票 05 恢复审批只读盘点（主线程复核）

2026-11-11。Gemini 原始盘点基点 fe759dd；主线程验证 fe759dd 到 51dfe3a 的 substitution 后端、相关组件和 SubstitutionsView 无变化（git diff --quiet 退出 0）。本报告是准备资料，不是施工或验收记录。未运行测试，05 尚未开始。

- SubstitutionController.java:51 起暴露提交与查询；当前无批准、驳回、撤回接口。
- SubstitutionRequestService.create：先查同实例 SUBMITTED，后在约131行锁学期；重复检查须移至锁后。提交尾部从 SUBMITTED 直接转 EFFECTIVE、退出旧生效行、创建代课行并通知。尚未写 operatorUserId。
- AdminSubstitutionService.confirmDirect 是工作台直接生效入口，05 保持此行为。
- SubstitutionRequest 当前只有 SUBMITTED / EFFECTIVE / CANCELLED；新申请需增加 REJECTED / WITHDRAWN / EXPIRED，历史 CANCELLED 保留。
- V55__trim_legacy_columns_and_tables.sql:42-45 删除了 approval_mode、approver_id、approved_at、approval_comment。新迁移加回审批人、时间、意见，不恢复模式切换、不改 V55。当前集成最高 V62；V63 仅为候选，开工前仍须盘点全部分支/worktree占号。
- SubstitutionsView 当前只有“我的调代”“调代记录”，无待审批页签。SubstitutionRequestForm 仍展示 SWAP 选项；后端已拒绝自助 SWAP，前端须收敛为仅代课。
- SubstitutionMineTable 当前无撤回按钮与驳回理由展示。更新老师五种状态，历史取消仍能展示。
- 复用 ApiIntegrationTest、TestData、ScheduleFixture.publishedEntries。原始盘点误列的 ScheduleFixture.publishedTimetable 实际不存在，不使用。
- 截止测试参考 AdminSubstitutionDeadlineIntegrationTest 等现有 @MockitoBean Clock + AtomicReference<Instant>；不用真实过去日期跑成功生效链。
- 状态历史与成功审计不同：SubstitutionEffects.recordHistory 只写历史，现有自助提交没有成功 AuditLog。05 须同事务补足真实操作审计；不要拿专门 REQUIRES_NEW 的 SubstitutionAuditService.failure 当成功入口。

事务、权限与过期提交边界以 t05-transaction-plan.md 为准备方案；最终公开协议在03放行后定稿。主线程已复查当前锁前预查、未写operator与直接生效尾段，以及 V55 删列事实。历史需求不因本报告变为完成。