# final-gate-fix 合并接收单

- 合入方式：`git merge --ff-only 97cade5427433b407730d343ad476ad2eef16b0c`，无冲突、无 hook 输出；集成分支 HEAD 32c91e9 -> 97cade5（FF，非合并提交）。
- 97cade5 父提交：bcb80a39205ce3ef43e3e5459c8c3cd1ddadbd76（fix）+ 32c91e980991425ad45a9e0ab7f6451d2954d1c3（集成）。
- 交付范围核对：`git diff --name-status 32c91e9 97cade5` 仅 2 项，即 `backend/src/test/java/com/paiban/identity/RoleReshapeIntegrationTest.java`(M) 与 `.scratch/dingtalk-multiuser/final-gate-fix-delivery.md`(A)。
- 合入后源 blob：`0d4923a9ca59f735a500ca83e4c5993c8c29c821`，与交付声明一致。
- 产品源不变性：32c91e9..HEAD 在 `backend/src/main`、`backend/src/main/resources`、`frontend` 上 diff 为空。
- 日志复制（只复制不删）：fix 树 `.scratch/dingtalk-multiuser/logs/final-gate-fix/` 的 4 个普通文件复制到本树同名目录，另写 `SHA256SUMS`。
- SHA256 比对 4/4 一致：red.log 12b14a03…e056827d、red-exit.log 4355a46b…460dd865、green.log 4042a398…652ee4ec、green-exit.log 9a271f2a…ab86aa。

## 测试事实（转录自上述日志，本轮未重跑）

- 修复前 red.log：`Tests run: 1, Failures: 1, Errors: 0`，BUILD FAILURE；red-exit.log = `1`。
- 修复后 green.log：`Tests run: 6, Failures: 0, Errors: 0`（RoleReshape 4 + AiImport 2），BUILD SUCCESS；green-exit.log = `0`。

## 效力边界

- 仅确认按轮次记录的日志 hash 与交付一致；**未**对 before/after 源码做独立冻结或复算，故无法排除日志与源码时点错配。
- 本单只代表测试修复的整合与日志一致性核对，**不等于实际 full gate 通过**；两轴并行只读补核仍在进行，放行判断以主控结论为准。
- 本单只提交自身：不 stage 主控 `full-run1-extraction.md`、评审 raw 或其它路径；不改票面、手册与源码。
