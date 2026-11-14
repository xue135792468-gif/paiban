# 03-A3 有界回归：免登身份匹配六条规则

2026-11-11。分支 `codex/dingtalk-t03-matching`，工作树 `.worktrees/dingtalk-t03-matching`，
基点 `30ba7c3`（已含 integration `fe759dd` + C 绑定保护 + A1 HTTP adapter + A2 登录类型 + 首登全链绿）。
交付跑基点为 `cb7cb58`（A3 + 密码总钥匙守卫；该 ref 后来被 merger 以 message-amend 变成同 tree 的
`a853597`，内容一致）。之后按约定以 `--no-ff` 合入 integration `51dfe3a`
（main 的 5 项 PWD 测试 + ADR-0036，**未改 provisioner**）。全程未 reset / rebase / amend。

**这不是 03 整票完成**，也不与主线程的密码总钥匙守卫 / 并发用例重叠：
本切片只把「免登时系统认谁」的六条既有规则钉成回归。

## 写集

| 文件 | 改动 |
|---|---|
| `backend/src/test/java/com/paiban/integration/DingTalkIdentityMatchingIntegrationTest.java` | 新测试类，6 例 |
| `.scratch/dingtalk-multiuser/t03-matching-delivery.md` | 本交付记录 |

未触碰生产代码、旧测试、迁移、其它票据。没有为了让测试变绿而修改任何实现。

## Seam

- 入口只有公开 HTTP：`POST /api/auth/dingtalk`（body 只有 `authCode`）、`/api/teachers`、`/api/users`、
  `/api/account-audits`、`/api/auth/me`。
- 外部身份来自真实 Adapter 路径：`FakeDingTalkClient.addUser` → `authCode = fake:<jobNo>` →
  `exchangeAuthCode` 换 userid → `getUser` 取姓名。前端不自报姓名。
- 不调用 provisioner 私有配对方法、不 mock 内部 Service、不断言调用次数。
- 直接读库只有一处：用户名被占用时「原账号哈希未变」（浏览器不可见的既有账号事实）。

## 用例与钉住的规则

各例独立 H2 `paiban_identity_matching`（`MODE=PostgreSQL`），每例重置集成设置与假通讯录；
`DatabaseResetExtension` 会清业务表但保留 `app_user`，所以 `@AfterEach` 只删本用例拥有的
教师关联账号与自建的无关占位账号，seed `admin` 不动。

| # | 场景 | 断言要点 |
|---|---|---|
| 1 | 两份未软删档案挂同一 UID（root 接口不设唯一约束） | 401 + `code=AMBIGUOUS_IDENTITY`；两份 UID 不变；无新账号；无 AUTO_PROVISION / LINK 审计。两份档案姓名不同，故歧义只能来自 UID 重复而不是同名计数 |
| 2 | 同名两人：一人已绑定另一 UID 并挂管理账号，另一人未绑定 | 200，配对**未绑定**的普通老师（`user.teacherId`、`roles=[teacher]`、`loginType=DINGTALK`）；已绑定的管理员档案 UID 与账号都不动 |
| 3 | 同名两人都未绑定，其中一人关联管理账号 | 401 `AMBIGUOUS_IDENTITY`：先按规格计数，不把受保护档案过滤掉再假称唯一；两人 UID 仍为空，普通候选无账号，管理员账号仍是 PASSWORD |
| 4 | 唯一未绑定教师 `active=false` | 仍开号并可登录：`user.enabled=true`、`loginType=DINGTALK`、JWT `/api/auth/me` 200；教师 `active` 保持 false —— 排课资格 ≠ 账号停用 |
| 5 | 目标名 `dt_<teacherId>` 被无关手工 PASSWORD 账号占用 | 不复用：新账号 id/用户名都不同、用户名 ≤64、`teacherId`/`loginType` 正确；原账号哈希逐字不变、类型仍 PASSWORD、未挂到该教师；原账号仍能用密码登录 |
| 6 | 教师已由超管显式绑定 UID，且有两个启用中的历史密码账号 | 稳定复用最小 ID（`loginType` 仍 PASSWORD，不转类型）；无 LINK / AUTO_PROVISION 审计；停用较小 ID 后复用一个启用账号，账号总数仍为 2，不新开号 |

## 测试证据（`-B -DforkCount=0`，只跑这一个 class）

| 阶段 | 提交点 | exit | 结果 |
|---|---|---|---|
| merge 前 | `30ba7c3` | 0 | `Tests run: 6, Failures: 0, Errors: 0`（36–39 s） |
| 交付跑基点 | `cb7cb58` | 0 | `Tests run: 6, Failures: 0, Errors: 0`，`BUILD SUCCESS`（36.30 s） |

合入 `51dfe3a` 之后没有再跑这 6 条：该合入只新增／改动其它测试与文档，且
`DingTalkAccountProvisioner` / `DingTalkBindingPolicy` 相对 `30ba7c3` 是空 diff（`git diff --stat` 无输出）。

日志（gitignored，保留真实退出码，未接 `| tee`）：
`.scratch/dingtalk-multiuser/logs/t03-matching-targeted.log`（交付跑）、
`.scratch/dingtalk-multiuser/logs/t03-matching-targeted-premerge.log`（merge 前同命令）。

**首次即绿，未伪造 red**：这些行为在 `30ba7c3` 已由生产代码实现（`DingTalkAccountProvisioner.resolveTeacher`
的 UID-优先/同名计数顺序、`DingTalkBindingPolicy`、`unusedAccountName`、按 `AccountUser.id` 最小值复用），
按任务约定回归允许首次绿。用例按规格写，不按实现结构写；实现若改变匹配顺序或用名生成规则，断言会按预期失败。

## 未跑 / 边界

- 未跑 `scripts/verify.sh` 全量与 Playwright（按票据约定留到八票收尾）；未开第二个 JVM。
- 未覆盖：并发首登与竞争配对（主线程 A3 并发切片）、密码总钥匙守卫（主线程）、
  HTTP adapter 协议/脱敏（A1）、停用账号拒绝（`ACCOUNT_DISABLED`，A3 已有归属）。
- 若后续实现调整「历史多账号复用」或自动账号命名规则，用例 5/6 是预期会红的哨兵，届时按新规则改测试而不是放宽断言。
