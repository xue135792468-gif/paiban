# 06 独立故障与交付隔离 QA（准备，待 core checkpoint）

2026-11-13 主控设计。core 继续批准/驳回/直接/换课、目标和重试/退役的业务回归；本 QA 只新增 `NotificationDeliveryIsolationIntegrationTest.java` 与其交付文档，不修改生产源、公共测试基础设施或 core 测试。

必须从可构建、两条纵向测试已绿的 core checkpoint 建隔离 worktree；未拿到提交前不运行半成品快照，也不复制正在变化的工作区。先查资源，最多同时两路 Maven，不能与主控 E2E/全量并跑。

## 三项公开行为

1. **真实 HTTP 的提交失败不外发**：未来学期种 Teacher 申请；临时 H2 CHECK 约束拒绝 `audit_log.action=SUBSTITUTION_REQUEST_APPROVED`，让真实批准在 flush/提交时失败（不是 mock 业务方法）。HTTP 返回既定错误，申请仍 SUBMITTED、生效行/批准历史/本案通知均不落地。finally 必须撤约束、释放外部阻塞并等交付空闲，不能污染后案。配套正常批准正例守住该约束确实作用到审批末端；只看零日志不足以声称网络未发生，应以外部 fake/stub 的可观察输出配套确认。
2. **队列满仍保持已提交结果**：使用专用 Spring executor 这一框架资源，以 latch 占住唯一 worker 并填满实际 queue capacity（不改生产参数，不 shutdown executor）。再经真实 HTTP 批准，仍 200/EFFECTIVE；独立数据库读到固定安全的排队失败记录，课表效果确已提交。不能退回 HTTP 线程同步网络。finally 释放并有界 drain；不测试私有方法或把内部调用次数当业务结果。
3. **入队后停用**：先以框架 latch 占住通知 worker，经 HTTP 批准让通知入队；再通过真实账号接口停用本案申请人及最终老师唯一关联账号，然后释放。确认无本地通知/成功外发记录，外部可观察结果无实际通知；允许诚实的跳过/失败记录，不把“停用不发”误规定为“必须连失败日志都没有”。不能只在入队前停用来冒充交付时重读。

## 测试边界

- 使用既有 ApiIntegrationTest / ScheduleFixture / TestData，唯一账号/教师；不依赖别的类顺序、固定ID或根账号改绑。不会为通知创建影子账号。
- 外部 DingTalkClient 可 fake/stub；只替换第三方边界，核心审批、数据库、事务与专用 executor 保持真实。断 HTTP、最终数据库/日志和外部输出，不断言业务内部调用次数。
- 等待基于可观察条件和明确超时，finally 负责释放所有 latch 并等工作收尾，不用固定 sleep 赌结果。不能停止共享 Spring executor来“清理”。
- 定向红绿、退出码、源提交、失败原始记录都保留；当前未执行。真实企业、手机、PostgreSQL不在这三项离线 QA 证据范围内。
