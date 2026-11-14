# 08 后端整链交付

主控施工工作树 dingtalk-t08-chain，基于07最终审源016859b；施工前07已经双轴与主控放行，文档闸门b7cac2e，08协调提交a5d28de。公开测试边界沿用spec Testing Decisions与t08-acceptance-plan.md，没有新增产品控制端点。

## 实现范围

FakeDingTalkClient仅在既有fake=true装配下增加隔离身份：fake:acceptance:caseId ↔ dd-acceptance-caseId；同一纯解析规则校验1–40位ASCII字母、数字、下划线、短横线。合成身份不加入同步目录，不改真实HTTP客户端，默认EMP001/002及未知员工拒绝保持。

新增独立Fake公开接口回归和真实HTTP整链测试。后者独立H2数据库，真实免登返回JWT并回查/me，唯一同名未绑定教师自动配对为无密码Teacher账号；三个教师明确区分申请人、原期望、最终接课人，另建教务账号审批。提交仅SUBMITTED、无生效行或发送；教务改人批准后检查期望保留、最终老师、操作人与审批人、历史主体、成功审计、实际课表。真实Fake保留认证行为，Mockito spy只观察对外工作通知边界，FIFO executor屏障后断恰好申请人与最终老师各一条，不制造期望/最终教师的影子账号。通知核日期、节次、班级、课程和最终人。

## 实际运行记录

- fake-red：源码冻结，退出1；2条中1通过、1错误（旧Fake拒绝新身份）。这是新身份能力的公开边界红例，未声称产品业务回归。
- chain-run1：源码冻结，退出1；Fake两条通过，整链一条失败。失败是主控测试把既有channel标识误写为大写DINGTALK；实际契约为小写dingtalk。这是测试期望写错，不是已复现产品缺陷；未改产品通道标识来迁就测试。此前认证/审批/课表/审计/外发两对象断言已执行。
- chain-run2：退出0，全部26条通过（Fake身份2、整链1、既有DingTalkIntegrationTest 23），0失败/错误/跳过，SOURCE_IDENTICAL=1。原有目录同步及免登回归保持。

每次原始日志、独立退出码和源前后哈希留在专属.runtime/t08-chain，结束归档至logs/t08-chain。没有full verify、真实租户送达、真实PostgreSQL或部署证据；不进行任何已被拒绝的删除，自己启动的测试进程结束时核对关闭。
