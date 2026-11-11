# 09: 清理集成树任务缓存（外部执行限制跟进）

**Status:** ready-for-human

**Blocked by:** 自动审批对删除命令的拒绝（非特性依赖）

票04 Standards复查跟进。位置 `E:/排课系统/.worktrees/dingtalk-integration/.scratch/dingtalk-multiuser/.tmp/`，包含本任务测试/浏览器缓存。已检查目标归属；递归删除和逐项非递归删除均被自动审批拒绝，只返回`blocked by policy`，没有更具体原因。不得以其他shell、脚本或代理绕过该拒绝。

追加：`E:/排课系统/.worktrees/dingtalk-t04-fixes/.scratch/dingtalk-multiuser/.tmp/` 已独立检查为任务目录、无reparse point；native PowerShell删除命令也在执行前被自动审批拒绝，仅返回相同原因，该工作树因缓存保留。`dingtalk-t04-acceptance/.scratch/dingtalk-multiuser/.tmp/` 也仍存在，未再重复尝试同类删除，不声称该路径曾单独得到拒绝。已合并且工作树干净的A/B/C1/安全回归/03-HTTP工作树均已由项目脚本移除；保留的临时工作树不代表还有功能未合入。

- [ ] 获准的本地环境中确认无测试/浏览器进程占用后，删除该任务缓存
- [ ] 核对仅此任务缓存被删除，源码/证据日志/其他会话文件保留

这张票不属于原8张功能票，也不阻止代码集成或本地验收；未清理事实必须保留在最终交付中。

2026-11-11 追加：03 并发 worker 对 `E:/排课系统/.worktrees/dingtalk-t03-concurrency/.scratch/dingtalk-multiuser/tmp/` 的递归删除也被自动审批拒绝。主线程只读复核仍有 hsperfdata、Tomcat 与 Mockito 测试缓存，未清空；不重复尝试删除，不通过移除整个 worktree 绕过。该 worktree 应随缓存保留，源码交付已由 `a733fee7a085aebd8b0b5dbe7ac6bf1884928143` 提供，不代表并发功能未合入。其交付文档开头“已清空”与实际残留不符，以本条和只读现场为准。

03 数字诊断整改目录 `E:/排课系统/.worktrees/dingtalk-t03-review-fixes/feature/.tmp/` 也须保留。主线程询问收尾事实后，worker 明确报告空目录非递归删除及随后递归删除两种命令均被自动审批拒绝，原因仅为 `blocked by policy`；文件未删除。主线程已独立只读列到 hsperfdata、两个 Tomcat 目录及两个 Mockito jar，不再尝试任何删除或整树移除。源码已通过 `75d0f42e896216e80e65fa88044c2a08f87052dc` 合入，日志已归档 `logs/t03-review-fixes/`。

03 UI收尾追加：`E:/排课系统/.worktrees/dingtalk-t03-ui/.scratch/dingtalk-multiuser/.runtime/` 与该feature下 `evidence/`（截图副本）的一次联合清理命令在执行前被自动审批拒绝，原因仅为 `blocked by policy`。未执行删除，也不能声称命令内的哈希归档复核已运行；此前独立只读检查确认runtime无reparse point、UI HEAD为集成祖先，原始日志/截图此前已分别归档。保留这两个目录及整个UI工作树，不换命令/代理重试或借整树移除绕过。原feature证据归档继续保留，功能源码已全部合入。
