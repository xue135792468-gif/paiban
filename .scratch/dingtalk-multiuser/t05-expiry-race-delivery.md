# 05 独立过期清扫竞态回归

2026-11-12，主线程质量写集，基于核心固定提交8f457acb0bbf186fe84f150f486a057fe8d8cd1e。只新增SubstitutionExpiryRaceIntegrationTest.java与本文，没有交付生产代码改动，也不是整票05已放行。

## 守住的行为与时序

预先确认的服务层并发边界与HTTP读回边界：公开API建未来学期课表、提交待批；A在外层事务批准但延迟提交；B在已过截止时查询待审批，并借外部Clock暂停在读到旧候选之后。先让A提交，再恢复B，使B锁后读到已生效状态。最终待批列表为空，但原申请必须仍EFFECTIVE、课表确实由最终代课人授课，仅一条生效行、提交/批准两条历史，无作废审计。

只替换外部时间，不mock业务类/仓库、不spy私有方法、不验证内部调用次数；不依赖sleep决定先后。CountDownLatch均有上限，finally释放屏障并关闭/等待自建executor。Root管理员代发是既有合法入口，申请账号与原教师不冒充同一人。

## 实际执行

- 语法/类型准备：`./mvnw -B -DforkCount=0 -DskipTests test-compile`，退出0。
- **受控破坏敏感性验证**：只在本独立工作树临时移除过期扫描锁后SUBMITTED条件，保留截止检查；`./mvnw -B -DforkCount=0 -Dtest=SubstitutionExpiryRaceIntegrationTest test`，1条、1失败、0错误，退出1。失败在最终真实HTTP JSON：期望EFFECTIVE，实际EXPIRED；不是超时/编译/夹具红。
- `finally`恢复生产文件原始字节，SHA256前后相同，git diff确认生产源码与8f457ac一致。
- **恢复后同一命令**：1条通过、0失败/错误，退出0，BUILD SUCCESS。没有为通过修改测试断言。

这是针对已发现真实缺陷的可重复破坏验证，不伪称此前未运行过的历史版本已经TDD红。原始日志与实际退出码归档在集成树`.scratch/dingtalk-multiuser/logs/t05-expiry-race/`；原工作树`.runtime/t05-expiry-main/`及源文件备份按既有清理限制保留，未尝试删除。测试后本工作树Java进程0。

## 边界

另补既有dingtalk-login Playwright正例：真实当前学期接口返回204时，进入`/substitution/requests`，断无学期说明、无提交按钮并继续收集控制台错误；不拦截接口、不预造学期。此改动eslint定向退出0，浏览器执行等待前端修复整合，当前不记为通过。已有学期的全量执行不强迫空库分支，空库分支由主线程隔离启动专跑留证。

真实H2/JPA、公开服务操作与HTTP结果读回；未验证真实PostgreSQL、企业钉钉、手机、全量verify。其余五个并发边界由独立并发writer负责，本例不重复计数。最终若核心生产相关路径再次改变，应比较实际差异再决定是否重跑；整合及两轴复查仍待完成。
