# 06 复查整改交付（待主控复核）

2026-11-13；worktree `dingtalk-t06-review-fixes`，分支
`codex/dingtalk-t06-review-fixes`，起点
`1f145ca2cb57d97cb9524ea0a23019f2178dbc94`。本记录只陈述本轮整改与验证，
不代替主控浏览器、两轴增量复核、真 PostgreSQL/企业租户/手机或部署验收。

## 处置结果

- **S3 / F1 / QC2（同根）**：`NotificationRecipientResolver` 的两条 Teacher
  映射只保留软删过滤，删除 `Teacher.active`；账号仍以 `AppUser.enabled` 为准。
  User 目标仍只解析启用账号；Teacher 目标保留“无账号但可信 UID 可发、仅停用账号
  不发、多账号同 UID 去重”。申请审批仍发给真实 `operatorUserId`，没有改成审批管理员。
- **S1**：`LocalNotificationChannel.send` 增加经 Spring 代理生效的
  `REQUIRES_NEW` 本地短事务，使 `notification` 与 `notification_log` 同成同败。
  DingTalk 网络仍由 `NotificationDeliveryGateway` 的 `NOT_SUPPORTED` 边界隔离。
- **S2**：新增 `NotificationExecutorTestSupport`，用单 worker FIFO sentinel plus
  active/queue 有界归零作为屏障；`DatabaseResetExtension` 复用该屏障。三个
  `after(500).never()` 改为 drain 后 `verifyNoInteractions`。
- **J1**：按主控裁定不采纳，不新增通用上下文工厂，也不重写两套既有变量接口。
- **J2**：删除 `NotificationFailureRecorder` 未使用的 `errorMessage` 参数、
  `NotificationService.swapEffective` 的 `summary` 参数与调用侧拼接，以及
  `SubstitutionAuditService` 的 `alert` / `requesterTeacherId` 闲参。同步全部内部
  调用者；审计保存值、动作、detail 与 `REQUIRES_NEW` 事务未变。
- **QC1**：发送记录列改为“类型”，已知模板代码显示业务中文（审批批准/驳回、
  代课、调课及已知历史模板），未知代码诚实保留原值。说明改为解释“钉钉已受理 /
  系统已保存”的含义，并明确不代表实际收到或已读；安全错误、未知渠道原值、分页、
  权限和 API 契约未变。

## 红绿证据

运行环境：GitPortable bash，先 `source .tools/env.sh`；Maven
`MAVEN_ARGS='-s E:/排课系统/.tools/maven-settings.xml -Dmaven.repo.local=E:/排课系统/.tools/m2repo'`；
每条测试前确认 free memory ≥ 2 GiB；临时目录均在本 worktree `.runtime/t06-review-fixes`。

| 阶段 | 命令（工作目录） | 结果 | 证据 / 退出码 |
|---|---|---|---|
| QC2 RED | `backend: ./mvnw -B -DforkCount=0 -Dtest=NotificationDeliveryIsolationIntegrationTest#clearingQueuedTeacherSchedulingEligibilityDoesNotSuppressEnabledAccountDelivery test` | 真行为失败：释放后外部通知实际 0、预期 2；`Tests run: 1, Failures: 1` | `.runtime/t06-review-fixes/qc2-red.log`；Maven `BUILD FAILURE`。首次包装脚本未落独立 exit 文件，未补造；主控已独立核验该失败 |
| QC2 GREEN | 同上，改最小删除 active 过滤后 | `Tests run: 1, Failures: 0, Errors: 0` | `qc2-green.log` / `qc2-green.exit` = `0` |
| S1 HTTP RED | `backend: ./mvnw -B -DforkCount=0 -Dtest=NotificationDeliveryIsolationIntegrationTest#localDeliveryLogFailureRollsBackPartialRowsWithoutRollingBackApproval test` | 真 HTTP 批准成功且已生效；`notification` 应 0 实际 2，留下部分本地行 | `s1-http-red.log` / `s1-http-red.exit` = `1` |
| S1 HTTP GREEN | 同 RED，加本地 `REQUIRES_NEW` 后 | `Tests run: 1, Failures: 0, Errors: 0`；业务仍 EFFECTIVE、两表回滚、独立失败标记保留 | `s1-http-green.log` / `s1-http-green.exit` = `0` |
| QC1 RED | `frontend: npx vitest run --no-file-parallelism src/components/settings/NotificationDeliveryLogsCard.spec.ts` | `Tests 2 failed / 3 passed`，仍在显示原始模板代码和旧说明 | `qc1-red.log` / `qc1-red.exit` = `1` |
| QC1 GREEN | 同上，改卡片后 | `Test Files 1 passed`，`Tests 5 passed` | `qc1-green.log` / `qc1-green.exit` = `0` |
| 六类最终组合 | `backend: ./mvnw -B -DforkCount=0 -Dtest=NotificationDeliveryIntegrationTest,NotificationBusinessIntegrationTest,NotificationDeliveryIsolationIntegrationTest,NotificationDeliveryLogApiIntegrationTest,DingTalkWorkNotificationHttpTest,TimetablePublishServiceTest test` | **35 条通过，0 失败/错误/跳过，BUILD SUCCESS** | `targeted-35.log` / `targeted-35-maven.exit` = `0` |
| 前端 lint | `frontend: npx eslint --max-warnings 0 src/components/settings/NotificationDeliveryLogsCard.vue src/components/settings/NotificationDeliveryLogsCard.spec.ts` | 通过 | `qc1-eslint.log` / `qc1-eslint.exit` = `0` |
| 前端类型 | `frontend: npx vue-tsc -b --pretty false` | 通过 | `qc1-tsc.log` / `qc1-tsc.exit` = `0` |

六类组合分组计数：transport 8、notification core 9、isolation 5、records API 7、
workbench business 3、publish unit 3；合计 35。原 33 条为上列原组合，新增的
isolation 两条分别是 QC2 资格反例与 S1 HTTP 原子性反例。组合运行前后
`backend/src`、`frontend/src`、`frontend/e2e` 全文件 SHA-256 一致：
`targeted-35-source-identical.txt` = `1`，前后清单为
`targeted-35-source-before.tsv` / `targeted-35-source-after.tsv`。

`.runtime/t06-review-fixes/local-atomic-red.log` 与 `.exit=1` 保留为早期直接
channel seam 的补充红测；它同样以真实 H2 约束制造日志写入失败并观察到孤立
`notification`，最终纳入交付的回归以真实 HTTP 的
`localDeliveryLogFailureRollsBackPartialRowsWithoutRollingBackApproval` 为准。

## 改动文件

- 后端源码：`NotificationRecipientResolver.java`、`LocalNotificationChannel.java`、
  `NotificationFailureRecorder.java`、`NotificationDeliveryWorker.java`、
  `NotificationDeliveryBridge.java`、`NotificationService.java`、
  `SubstitutionEffects.java`、`SubstitutionAuditService.java`、
  `SubstitutionPlanService.java`、`SubstitutionRequestService.java`
- 后端测试：`NotificationDeliveryIsolationIntegrationTest.java`、
  `NotificationDeliveryIntegrationTest.java`、`NotificationBusinessIntegrationTest.java`、
  `DatabaseResetExtension.java`、新增 `NotificationExecutorTestSupport.java`
- 前端：`NotificationDeliveryLogsCard.vue`、`NotificationDeliveryLogsCard.spec.ts`
- 本交付记录

未改 `AppUser` / Auth / 角色 / 绑定 / TeacherView / 迁移 / spec / 票面 Status /
HANDOFF / README。没有删除任何文件或目录。

## 未验证范围

- 主控负责的真实 Playwright 复验与截图（本轮按边界未启动 E2E）、两轴增量复核。
- 真 PostgreSQL、真实钉钉企业租户、手机实际送达/已读、Docker/部署。
- 未 push；未宣称整票放行或 06 全票完成。
