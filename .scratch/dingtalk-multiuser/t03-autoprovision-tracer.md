# 03-A3 自动开号公开HTTP tracer

2026-11-11。主线程独立编写首条 `DingTalkAutoProvisionIntegrationTest`。先只验证一条真实业务链：由Fake企业目录得到UID/name，唯一未绑定教师首次进入→配对→无密码老师账号→JWT可用→重复身份仍同号→两条系统审计。测试不会把前端自报姓名作为身份输入。

## 有效红例

源码基点 `f1a893e991d3ea6772cd8a1ce55b2e5a5828d232`。配置保存、教师档案HTTP创建、Fake目录设置成功；`POST /api/auth/dingtalk` 预期200实际401。**1条/1失败/0错误/0跳过，Maven退出1**，27.44秒测试耗时，`logs/t03-autoprovision-tracer-red.log`，SHA256 `45F5B8A3A72A914D1CC227D6B4761896339A64C1320A195B3DAAE08A6C48A3AD`。后续JWT、hash、审计断言尚未到达，不能声称已通过。

命令：GitPortable Bash，`source .tools/env.sh; cd backend; ./mvnw -B -DforkCount=0 -Dtest=DingTalkAutoProvisionIntegrationTest test`，日志直接重定向，TEMP/TMP/TMPDIR为本树feature/.tmp。独立H2+Fake，无真钉钉/真PG，服务正常关闭。cleanup只删除本fixture教师关联的自有账号，再恢复settings/Fake，不动seed admin。

随后已合入HTTP Adapter切片及能力分类契约至 `fc3b325`（AuthProvider本地不开号行为尚未改变）。本红例分支暂不合入集成，等C绑定保护、A2类型片合入后完成A3短事务实现，再取得真实绿例。重复fake authCode仅用于本地身份幂等验收，不代表测试了真实vendor的一次性code机制。

## A3最小路径已转绿，整票仍未完成

最小本地provisioner与结构化原因使用现有ApiException.details携code，不另建异常框架。合入C/A2后已实际运行同一个tracer：**1通过/0失败/0错误/0跳过，Maven退出0，53.199秒**，`logs/t03-autoprovision-tracer-green.log`，SHA256 `0F012F17903AF0960B3DBF1B7291317F589E792812CBA66D74BEA41A82672198`。首登之后的JWT、hash=null、老师身份、同号复用、两条System审计与LINK后的UID断言均已到达并通过。

匹配先锁角色锚点，再把UID/name查询候选按教师ID升序锁定刷新；先UID，后未绑定同名计数，不排除inactive来假称唯一；创建DING账号、自动创建审计、UID配对及前后快照在同一事务。手工create（含批量导入复用路径）也已加同一锚点，防止管理员关联账号在首次绑定判断中途出现；来源清点见集成`logs/t03-account-writers-raw.txt`。该清点里UserService.save无业务调用，初始化repo写入属于启动阶段，不用于本次请求。

在03整票放行前还必须完成：
- 仅有DING超管不能代替PASSWORD总钥匙；在update/disable/sync共同核心保护最后一个enabled PASSWORD超管，配真实红绿和并发旧快照验证。
- 六类原因、重复/歧义UID、禁用账号无半配对、同UID并发/两UID竞争、长/碰撞用户名、管理员确认、角色提升与真实浏览器整合。
