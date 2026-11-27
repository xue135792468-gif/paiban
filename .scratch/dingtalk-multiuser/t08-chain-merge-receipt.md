# 08 后端整链合并接收单

2026-11-13。整合主控在集成工作树 `dingtalk-t08-chain` 交付完成后独立核对并合入 `codex/dingtalk-multiuser`。本记录只陈述已实测事实与证据边界，不复跑测试、不构建、不跑 full/browser/Vitest。

## 输入与结论

- 集成基线（合并前）：`a5d28de`（`codex/dingtalk-multiuser`）
- 交付源：`codex/dingtalk-t08-chain` 最终 `148cc0d`（源码 `e167c04` + 文档统计修正 `148cc0d`），交付工作树 tracked 干净、无未跟踪文件
- **合并提交**：`527ca2f`（`--no-ff`，父提交 `a5d28de` + `148cc0d`），合并策略 ort，无冲突，未触发 pre-commit/pre-push
- 合并后 `git diff 148cc0d..527ca2f` 为空；`backend/src` 与交付树内容一致

## 合并写集（相对 a5d28de，源码 + 测试 + 交付文档）

| 路径 | 类型 | 变更 |
|---|---|---|
| `backend/src/main/java/com/paiban/integration/dingtalk/FakeDingTalkClient.java` | M | +15 |
| `backend/src/test/java/com/paiban/integration/DingTalkAcceptanceChainIntegrationTest.java` | A | +184 |
| `backend/src/test/java/com/paiban/integration/dingtalk/FakeDingTalkAcceptanceIdentityTest.java` | A | +35 |
| `.scratch/dingtalk-multiuser/t08-chain-delivery.md` | A | +17 |

合计 4 文件 / +251 / -0。产品侧唯一改动为 `FakeDingTalkClient`：新增 `fake:acceptance:caseId` ↔ `dd-acceptance-caseId` 隔离身份与同一 `[A-Za-z0-9_-]{1,40}` 解析校验。`HttpDingTalkClient`、`DingTalkClient`、`DingTalkProbeClient`、迁移与前端均未改动，无新增产品端点。

## 独立核对项

1. **旧默认目录未变**：`resetToDefault()`、`allUsers()`、分页与失败/游标注入均未被 diff 触及；`FakeDingTalkAcceptanceIdentityTest` 断言目录仍为 `dd-001, dd-002`，`fake:EMP001/EMP002` 仍映射原用户，`fake:UNKNOWN` 仍抛 `IllegalArgumentException`。
2. **fake 条件装配未变**：类注解仍为 `@Component` + `@ConditionalOnProperty(name = "paiban.integration.dingtalk.fake", havingValue = "true")`，未改 `HttpDingTalkClient` 的取反条件，测试未新增组件或 profile。
3. **合成身份未加入目录**：`acceptanceIdentity` 为按需构造的私有静态工厂，不写入 `usersByDepartment`，`listUsers` 不返回；越界/非法格式在 `exchangeAuthCode` 与 `getUser` 两侧均抛出。
4. **整链测试边界**：独立 H2 上下文、真实免登 POST→JWT→`/api/auth/me`；三教师（申请人 / 原期望 / 最终接课）与教务账号分离；待批仅 `SUBMITTED`、无生效行与发送；改人批准后期望保留、按最终人生效，核操作人/审批人、历史主体、审计与课表；spy 仅观察对外 `sendWorkNotification`，FIFO executor 屏障后恰好申请人 + 最终老师各一条，并断言两者无影子账号。

## 原始运行证据（logs/t08-chain，20 文件 / 644,608 字节）

| 轮次 | 原始 exit | 源码稳定 | 结果 |
|---|---|---|---|
| fake-red | 1 | SOURCE_IDENTICAL=1 | 2 条中 1 通过 1 错误：旧 Fake 以 `IllegalArgumentException: 未知的测试工种: acceptance:chain_08-A` 拒绝新身份 —— 新身份能力的公开边界红例，非产品业务回归 |
| chain-run1 | 1 | SOURCE_IDENTICAL=1 | 3 条中 Fake 2 通过、整链 1 失败：`expected "DINGTALK" but was "dingtalk"`，断言通道大小写写错（既有契约为小写 `dingtalk`），属测试期望错误，非产品 red；该断言之前的认证/审批/课表/审计/外发对象断言均已执行 |
| chain-run2 | 0 | SOURCE_IDENTICAL=1 | 26 条全通过（Fake 2 + 整链 1 + 既有 `DingTalkIntegrationTest` 23），0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS |

- `SHA256SUMS.txt` 19 条逐值复算（`Get-FileHash -Algorithm SHA256`）：19/19 一致，覆盖三轮 before/after 清单、source-patch、exit、log、stability 与 `run-targeted.sh`；`fake-red-source.patch` 为空文件哈希（源码冻结），符合记录。
- 每轮 `before`/`after` 清单文件自身哈希相同（`chain-run1` 83F4EA…、`chain-run2` B0C5E2…、`fake-red` A5E106…），即运行前后源码快照一致；chain-run1 与 chain-run2 仅整链测试文件一项不同，与通道大小写修正吻合。

## 源码规范化哈希核对（避免算法混用）

分两步，不做跨算法逆推：

1. **逐文件 SHA-256 对清单**：交付工作树当前 `backend/src` 全部 636 个文件，按 `chain-run2-before.sha256` 与 `chain-run2-after.sha256` 逐值比较 —— 636/636 一致、636/636 一致（清单为 `sha256sum` 原始 CRLF 字节，故与磁盘原始字节比，不换算 Git SHA-1）。
2. **规范化 LF Git 对象对提交**：上述 636 文件按 CRLF→LF 规范化后与 `148cc0d` 树内容逐一比较 —— 636/636 一致。三个交付源码文件另单独核 `git hash-object`（LF 规范化副本）与 `148cc0d:<path>` 相等：
   - `FakeDingTalkClient.java` → `368c4ca8…`（=HEAD blob），记录 SHA-256 `ffe8ff9f…` = 磁盘现值
   - `DingTalkAcceptanceChainIntegrationTest.java` → `58161ef1…`（=HEAD blob），记录 SHA-256 `7940461a…` = 磁盘现值
   - `FakeDingTalkAcceptanceIdentityTest.java` → `83bd6caf…`（=HEAD blob），记录 SHA-256 `b1f570d8…` = 磁盘现值
3. **合并后 Git 对象同一**：`527ca2f:<path>` 与 `148cc0d:<path>` 上述三个源码文件及交付文档 blob 逐一相等（`368c4ca8…` / `58161ef1…` / `83bd6caf…` / `05233b90…`）。

注：交付工作树中两个新测试文件在工作区为 LF，集成工作区按 `core.autocrlf=true` 落地为 CRLF；两侧规范化后内容一致，Git 对象同一，`git status` 无行尾噪声，故不构成差异。

## 日志归档

交付 `logs/t08-chain` 绝对路径目录按文件 Copy 至集成同名目录：20/20 文件、SHA-256 全一致、644,608 字节一致。`logs/` 命中 `.gitignore:18`，故保留为未跟踪忽略文件，不强迫 track。

## 状态与资源

- 集成工作树合并后 tracked 干净；未跟踪项仅为既有预审证据、`.tmp/`、主控独立 `t08-main-acceptance.md`（未 stage），本单提交只 stage 本 receipt。
- 未执行任何删除（含空目录）、cleanup 或 worktree 移除；09 已拒绝事项不重试。未调用其他 agent。
- 本任务临时文件限于集成 `.scratch/dingtalk-multiuser/.runtime/t08-merge/`（约 10 MB），按禁令不做删除。未启动常驻进程，无需关停。
- 开工时资源：内存 15.73 GB / 可用 6.23 GB，E: 可用 6.44 GB，未并发重活。

## 证据边界

本条为合并与哈希核对记录，不构成验收通过：无 full `verify.sh`、无 Playwright/浏览器、无真实 PostgreSQL、无真实钉钉租户送达。整链证据为 H2 + fake 装配，最终评审与全量门禁仍按主控计划在收口后串行执行。
