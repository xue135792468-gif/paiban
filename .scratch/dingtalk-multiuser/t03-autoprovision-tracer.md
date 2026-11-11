# 03-A3 自动开号公开HTTP tracer

2026-11-11。主线程独立编写首条 `DingTalkAutoProvisionIntegrationTest`。先只验证一条真实业务链：由Fake企业目录得到UID/name，唯一未绑定教师首次进入→配对→无密码老师账号→JWT可用→重复身份仍同号→两条系统审计。测试不会把前端自报姓名作为身份输入。

## 有效红例

源码基点 `f1a893e991d3ea6772cd8a1ce55b2e5a5828d232`。配置保存、教师档案HTTP创建、Fake目录设置成功；`POST /api/auth/dingtalk` 预期200实际401。**1条/1失败/0错误/0跳过，Maven退出1**，27.44秒测试耗时，`logs/t03-autoprovision-tracer-red.log`，SHA256 `45F5B8A3A72A914D1CC227D6B4761896339A64C1320A195B3DAAE08A6C48A3AD`。后续JWT、hash、审计断言尚未到达，不能声称已通过。

命令：GitPortable Bash，`source .tools/env.sh; cd backend; ./mvnw -B -DforkCount=0 -Dtest=DingTalkAutoProvisionIntegrationTest test`，日志直接重定向，TEMP/TMP/TMPDIR为本树feature/.tmp。独立H2+Fake，无真钉钉/真PG，服务正常关闭。cleanup只删除本fixture教师关联的自有账号，再恢复settings/Fake，不动seed admin。

随后已合入HTTP Adapter切片及能力分类契约至 `fc3b325`（AuthProvider本地不开号行为尚未改变）。本红例分支暂不合入集成，等C绑定保护、A2类型片合入后完成A3短事务实现，再取得真实绿例。重复fake authCode仅用于本地身份幂等验收，不代表测试了真实vendor的一次性code机制。
