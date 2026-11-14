# 发布 CI 增量：通知测试等待完成边界

## 触发与范围

PR [#31](https://github.com/xue135792468-gif/paiban/pull/31) 首轮 CI 的 [backend job](https://github.com/xue135792468-gif/paiban/actions/runs/43548680054/job/132305830912) 在 `onlyDisabledLocalAccountsSuppressDingTalkAndLocalCoverDelivery` 失败：接课通知预期 2 条失败记录，实际读到 1 条。总计 1265 项，1 失败、0 错误、6 跳过；PostgresContainerIT 1 项实际通过。同源 Java 测试在 #30 的后端全套通过，两边浏览器各 155 项通过。这不是整组发布 CI 已通过。

只修改 `NotificationDeliveryIntegrationTest` 与本说明。不改通知业务、表结构、权限、断言期望、重试次数或外部配置。

## 根因与反馈循环

旧 `awaitDeliveryLogs(2)` 只等全表达到 2 条，并不表示两项异步发送均结束。停用接课账号的最终记录是：

```text
申请人的审批结果：远程通道成功
接课通知：远程通道失败
接课通知：本地降级失败
```

读到前两条时，接课模板只有 1 条失败记录，恰好对应 CI 症状。测试在本地降级通道使用 spy 调用真实实现前引入至多 2 秒的可控门闩等待，以放大这个可观察中间态；`@AfterEach` 同时释放门闩。未伪造持久化结果。

红例命令（先保留旧等待器）：

```bash
./mvnw -B -DforkCount=0 \
  '-Dtest=NotificationDeliveryIntegrationTest#onlyDisabledLocalAccountsSuppressDingTalkAndLocalCoverDelivery' test
```

实际退出 1，1 项失败/0 错误，错误同为 `Expected size: 2 but was: 1`。该例单独运行，不依赖上一个测试。红例测试源编辑 checkpoint SHA256：`f3ea170a47ed5ce4368866d2956159f05585778d79b6c04d60c369a6bbd06ddf`。

修复只将测试等待改为已有的 `NotificationExecutorTestSupport.awaitIdle` 单线程 FIFO 屏障，再保留原最少记录数检查；之后所有精确模板数量、失败状态、停用账号零远程发送和零本地通知断言仍执行。没有用更长超时或重跑掩盖失败。

## 本地绿例（非新的完整门禁）

```bash
./mvnw -B -DforkCount=0 -Dtest=NotificationDeliveryIntegrationTest test
```

实际退出 0：9 项通过，0 失败/错误/跳过。Maven 1:56 分钟；单 JVM，堆上限 512MB、Metaspace 上限 256MB，在低内存下串行执行，未启动本地全量或浏览器套件。绿例测试源编辑 checkpoint SHA256：`5abfdf0939a69cc6135aa1751ee43a4645eb9eaaafd5ae3931000259b5e4a4a8`。

这些 checkpoint 是测试源版本记录，不冒称完整运行的 before/after 冻结证明。原始 red/green 日志和 doctor 位于本批 `.runtime/release-notification-fix/`，仅本地保留，不入 Git。最终发布结论以两张 PR 最新 HEAD 的完整 CI 为准；原轮失败记录保持可查。
