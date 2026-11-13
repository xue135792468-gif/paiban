# 06 主控整合验收（复查中，尚未放行）

2026-11-13，集成分支 codex/dingtalk-multiuser；固定源码与双轴审源为 `90bd973e824d5fec9ba9978c9076f851c00e1a1b`，票基点 `23b789fd5e90008aa2cc2f5b23d6f8cb07416caa`。

## 实际通过

- 主控六类 Maven 组合 **33 条、零失败/错误/跳过，退出0**：传输8、通知核心9、独立隔离3、记录API7、工作台业务3、发布单元3。运行前后所有 backend/src、frontend/src、frontend/e2e 路径与内容哈希相同，`SOURCE_IDENTICAL=1`。
- 主控真实 Playwright **6条通过、退出0**：发送记录3、申请审批2、既有代课1。同样 `SOURCE_IDENTICAL=1`，隔离端口28406/25406；没有使用 GET 成功响应 route 来伪造记录。
- 实际 view_image 检查教务发送记录截图：教务身份、唯一接课教师行、系统已保存状态及真实日期/课节/班课程正文可见；三个调代策略保留，旧通知范围/名单入口撤下。结果行已滚入主内容区，不把截不到的行冒作视觉证据。
- 首次 doctor 退出2：Docker未安装、5432无数据库；内存/磁盘/端口充足。后续使用 H2/Fake，不把这一结果解释为真实 PostgreSQL 验收通过。

## 证据

- `logs/t06-main-targeted/`：doctor.json/exit、maven.log/exit、source路径/哈希前后及一致性结果。runner为本特性 `.runtime/t06-main-targeted/run.sh`，无测试管道覆盖退出码。
- `logs/t06-browser-run1/`：Playwright原始日志/退出码/前后源哈希；trace及测试产物在本特性 `.runtime/t06-browser-run1/test-results/`。
- `evidence/t06-browser-run1/dingtalk-t06-academic-notification-logs.png`：真实记录页截图；同目录还有审批回归截图。
- 切片日志已分别核SHA归档至 `logs/t06-core/`、`logs/t06-transport/`、`logs/t06-ui/`、`logs/t06-log-api/`、`logs/t06-isolation/`。QA首次错误工作目录未执行到测试，已在其交付记录区分，不能计作行为红测。
- 先前 worker定向与41条既有回归只见各 delivery，不与主控33重复相加。

## 主控截图 QC（待统一整改）

**QC1，产品文案/可读性**：记录页目前显示“只显示固定安全说明/不展示第三方原始错误或响应”等实现说明，且“模板”列呈现截断的 `SUBSTITUTION_...` 编码。它们不能帮助教务判断这是什么通知、是否还需确认送达。改为业务“类型”（审批批准/驳回、代课、调课等已知类型中文名，未知类型诚实保留标识），说明用“钉钉已受理/系统已保存”的实际含义。保留固定安全错误机制、未知渠道原值、权限、分页和接口不变；不新增功能或导航。

此项为主控实际截图发现，不计入两轴原始报告，也不擅改它们的优先级/数量。等待原始两轴报告后交单一 fixer 统一处理；受影响组件及浏览器需复验。

**QC2，通知收件资格（静态确认，待行为红测）**：`NotificationRecipientResolver.java:71,97` 在 Teacher 目标和 User 目标的 UID 解析中都新增 `Teacher.isActive()` 过滤。既有 `DingTalkBindingPolicy.java:31`、03 已放行 `t03-module-plan.md:18` 明确 active 仅为排课资格，不代表 AppUser.enabled；06 交付边界也要求两者区分。启用账号/可信 UID 的审批结果或已经生效的接课通知，不应因后来关闭排课资格而漏发。保留软删与真实账号 enabled 过滤，不能为了此修正重新启用账号或改身份。

统一 fixer 先补公开行为红测：latch 占住通知 worker，经真实 HTTP 批准入队；随后只关闭本案申请人与最终老师的排课资格，明确断账号仍 enabled、UID 未丢，再释放。应仍有准确两类钉钉 payload/成功记录；源候选若因 active 过滤漏发必须真正红，不能用缺 UID 等 fixture 错误冒充。修正后同一测试转绿，同时保留原“入队后停用账号不发”的相反回归。该项目前不是已执行的红测，也不能覆盖写成33条已有绿证据。

QC2 与任一轴后续若报告同根问题，只统一一次修复；各轴原始数量与优先级仍独立保留。

当前没有单票放行；07/08仍仅准备。完整八票 full verify、真实企业/手机/PostgreSQL与部署均未在本记录通过。被拒绝的清理按09保留，不重试。
