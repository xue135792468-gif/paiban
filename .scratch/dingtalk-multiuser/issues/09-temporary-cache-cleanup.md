# 09: 清理集成树任务缓存（外部执行限制跟进）

**Status:** ready-for-human

**Blocked by:** 自动审批对删除命令的拒绝（非特性依赖）

票04 Standards复查跟进。位置 `E:/排课系统/.worktrees/dingtalk-integration/.scratch/dingtalk-multiuser/.tmp/`，包含本任务测试/浏览器缓存。已检查目标归属；递归删除和逐项非递归删除均被自动审批拒绝，只返回`blocked by policy`，没有更具体原因。不得以其他shell、脚本或代理绕过该拒绝。

追加：`E:/排课系统/.worktrees/dingtalk-t04-fixes/.scratch/dingtalk-multiuser/.tmp/` 已独立检查为任务目录、无reparse point；native PowerShell删除命令也在执行前被自动审批拒绝，仅返回相同原因，该工作树因缓存保留。`dingtalk-t04-acceptance/.scratch/dingtalk-multiuser/.tmp/` 也仍存在，未再重复尝试同类删除，不声称该路径曾单独得到拒绝。已合并且工作树干净的A/B/C1/安全回归/03-HTTP工作树均已由项目脚本移除；保留的临时工作树不代表还有功能未合入。

- [ ] 获准的本地环境中确认无测试/浏览器进程占用后，删除该任务缓存
- [ ] 核对仅此任务缓存被删除，源码/证据日志/其他会话文件保留

这张票不属于原8张功能票，也不阻止代码集成或本地验收；未清理事实必须保留在最终交付中。
