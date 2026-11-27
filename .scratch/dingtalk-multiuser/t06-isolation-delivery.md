# 06 独立故障与交付隔离 QA 交付

2026-11-13。隔离 worktree 分支 `codex/dingtalk-t06-isolation`；稳定 core 基点为
`02565816723287b46c82e7415410b36452a037aa`。最终在合入已提交的
`codex/dingtalk-multiuser@9ec96a1` 后，以整合提交
`0bbf732`（`Merge branch 'codex/dingtalk-multiuser' into codex/dingtalk-t06-isolation`）
复跑。core worktree 中正在修改的生产源、业务测试、公共测试基础设施和 ADR 均未合入；
最终以主控整合后的 core 候选复验为准。

写集仅为本计划允许的两条路径：

- `backend/src/test/java/com/paiban/notification/NotificationDeliveryIsolationIntegrationTest.java`
- `.scratch/dingtalk-multiuser/t06-isolation-delivery.md`

## 公开行为证据

### 1. 真实 HTTP 提交失败时不外发

测试为未来学期使用 `ScheduleFixture` / `TestData` 建立唯一教师、申请人账号、最终接课教师及
真实 HTTP 调代申请。临时在 H2 `audit_log` 上安装只拒绝
`SUBSTITUTION_REQUEST_APPROVED` 的 CHECK，再调用真实批准 HTTP：

- HTTP 返回 `500`，响应为既定 `服务器内部错误`；
- H2 原始日志明确记录 `audit_log` 插入被
  `ck_qa_reject_substitution_approval_audit` 拒绝；
- 独立 JDBC 查询仍读到申请 `SUBMITTED`，该申请的生效行、`EFFECTIVE` 历史记录和批准审计均为
  0；本地通知与通知记录也为 0；
- 第三方 `DingTalkClient` 边界记录的外部通知 payload 为 0，证明回滚后没有真实外发；
- `finally` 撤除 CHECK。随后对同一申请执行正常批准，HTTP 返回 `200/EFFECTIVE`，独立查询读到
  生效行、历史、批准审计和两条成功发送记录，外部 payload 为申请人与最终接课教师的两个
  实际 UserId。该正例证明临时故障确实作用在审批末端，而没有绕过审批路径。

### 2. 专用 executor 队列满仍提交审批

测试直接取得生产 `notificationExecutor`，以 latch 占住唯一 worker，再按
`getQueueCapacity()` 填满实际配置的队列；没有修改生产容量、没有回落到 HTTP 线程同步网络，
也没有 shutdown 共享 executor。随后真实 HTTP 批准：

- HTTP 返回 `200/EFFECTIVE`，独立查询读到生效行；
- 两个通知任务的入队均被真实框架队列拒绝，`notification_log` 中留下两条独立的
  `success=false`、`error_message=DELIVERY_QUEUE_REJECTED` 记录；
- 两条记录分别对应审批模板/`user:<id>` 与接课模板/`teacher:<id>`；
- 第三方边界没有收到任何外部 payload；
- `finally` 释放全部 latch，并以 executor active/queue 状态的有界等待确认收尾。

### 3. 入队后通过真实账号 HTTP 停用

测试先占住唯一通知 worker，再经真实 HTTP 批准，等待专用 executor 队列中出现两个通知任务。
随后通过真实 `POST /api/users/{id}/disable` 分别停用本案申请人账号与最终接课教师的唯一关联
账号，确认两者数据库中均为 disabled，之后才释放 worker：

- 批准已先返回 `200/EFFECTIVE`，证明动作发生在入队后而不是入队前；
- worker 释放时重新解析收件身份，第三方边界没有收到实际通知；
- 没有本地 `notification` 记录，也没有成功通知记录；
- 允许并保留诚实的跳过/失败记录，不把“停用不发”错误收窄为“必须一条失败记录都没有”。

## 验证记录

最终命令：

```bash
source .tools/env.sh
export MAVEN_ARGS='-s E:/排课系统/.tools/maven-settings.xml -Dmaven.repo.local=E:/排课系统/.tools/m2repo'
cd backend
./mvnw -B -DforkCount=0 -Dtest=NotificationDeliveryIsolationIntegrationTest test
```

最终结果：`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`；Maven/命令真实退出码 `0`。

日志（worktree 内准确相对路径）：

- 最终整合分支：`.runtime/logs/isolation-final-20261113-105921.log`，退出码文件
  `.runtime/logs/isolation-final-20261113-105921.exit`，内容为 `0`。
- 合并 UI 前全类：`.runtime/logs/isolation-class-20261113-105603.log`，退出码文件
  `.runtime/logs/isolation-class-20261113-105603.exit`，内容为 `0`。
- 首条定向重跑：`.runtime/logs/first-http-rollback-rerun-20261113-105109.log`，退出码文件
  `.runtime/logs/first-http-rollback-rerun-20261113-105109.exit`，内容为 `0`；结果为
  `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。
- 首轮调用路径错误：`.runtime/logs/first-http-rollback-20261113-105000.log`，退出码文件
  `.runtime/logs/first-http-rollback-20261113-105000.exit`，内容为 `1`。该轮从仓库根启动
  wrapper，Maven 报 root POM 不存在，测试未进入执行；日志按原样保留，后续从
  `backend/` 正确重跑。

首条在新测试完成后首次有效执行即绿，没有伪造 TDD 红，也没有发现需要修改生产代码的缺陷。

## 未验证范围

- 本证据为 H2 集成测试；未运行真实 PostgreSQL、Docker、E2E 或全量门禁。
- 第三方 DingTalk 仅使用可控 fake 边界记录 payload；没有真实企业、真实手机送达或钉钉回执证据。
- 前端日志页、UI E2E 与其他票据功能不在本 QA 写集和本轮定向验证范围。
- 当前测试基于稳定 core `0256581` 与最终已提交 UI 集成；未合入 core worktree 中未提交的
  生产修复。主控整合最终 core 候选后仍需按同一测试复验。
- 进程崩溃后的持久重放、真实网络容量和长期队列压力不在本票范围。
