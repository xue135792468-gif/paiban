# 08 剩余两切片串行合并接收单

2026-11-13。独立 merger 在集成工作树 `E:/排课系统/.worktrees/dingtalk-integration`（分支 `codex/dingtalk-multiuser`，合并前 `26edaa6`）串行核对并合入 08 剩余两片。本记录只写实测事实与边界：不重跑测试/构建/full/浏览器/Vitest，不改交付源，不推送。

## 合并结论

| 顺序 | 交付源分支 | 交付 SHA | 合并提交 | 结果 |
|---|---|---|---|---|
| ① | `codex/dingtalk-t08-browser` | `fecb656513896ec3a4384aab4ccc47b822b3e60c` | `fa306eaccd787a9e36560c6d6018e7417086460f` | `--no-ff`，ort，无冲突，未触发 hook 拒绝 |
| ② | `codex/dingtalk-t08-upgrade` | `20d67060ac0a96faf4b06d9f93e2e5bc60526cf6` | `be48fc0e3b08013c4ffbd7d716d9385169ce6e8d` | `--no-ff`，ort，无冲突，父提交 `fa306ea` + `20d6706` |

两片交付源父提交均为 `26edaa6`（与合并前集成 HEAD 同一），工作树 tracked 干净、无常驻进程；无文件删除、无 cleanup、无 worktree 移除、无 `--no-verify`。

合并后等价性：`git diff fecb656 fa306ea` 为空（4 个交付 blob 逐一相等）；`git diff 20d6706 be48fc0` 的差异仅为第一片带来的 4 个 e2e/文档文件，`OldDatabaseUpgradeAcceptanceTest.java`（`45ea8030…`）与 `t08-upgrade-delivery.md`（`e352f35f…`）在两侧 blob 完全相同。

## ① 浏览器切片（fecb656）

写集 = 交付单声明：4 文件 / +243 / −40，无产品代码改动。

| 路径 | 类型 | blob |
|---|---|---|
| `frontend/e2e/helpers.ts` | M | `38ee8eb114d7a9133038bd540c22cc3024ccc8c5` |
| `frontend/e2e/dingtalk-login.spec.ts` | M | `23559ae785b8c0c1c4f261409f900b9b579e23aa` |
| `frontend/e2e/dingtalk-acceptance.spec.ts` | A | `1be808916c6b5c203e1faa9fd92a9fc1a8d61adc` |
| `.scratch/dingtalk-multiuser/t08-browser-delivery.md` | A | `57328ddb6cb74bab47e8acf6b0f3b9baf62d3570` |

独立核对项：

1. `git diff --name-status 26edaa6 fecb656` 恰为上表 4 项，无第五个路径；产品前端/后端零改动。
2. `helpers.ts` 实际差异只三处：新增 `DINGTALK_UA` 与从 03 原样移入的 `installFakeWindowDd`；`seedSchool` 增 `options.teacherAccount?: boolean`，默认（`!== false`）仍创建原密码账号；`classEntries` 返回值补 `teacherId: number` 类型。未弱化既有断言。
3. 源稳定收据：run1/run2 `source-stability.txt` 均 `SOURCE_IDENTICAL=1`；run2 `post-commit-source-stability.txt` 实测存在且内容为 `HEAD=fecb656513896ec3a4384aab4ccc47b822b3e60c` + `POST_COMMIT_SOURCE_IDENTICAL=1`。
4. 收据哈希对提交：`source-hashes-postcommit.txt` 中对应三文件条目 `1be808916c6b…`（第 648 行）、`23559ae785b8…`（650）、`38ee8eb114d7…`（659），与 `fecb656` 树 blob 逐一相同；`source-paths/hashes` 三份清单（before/after/postcommit）文件自身哈希一致（`B5A51DD6…` / `11E371C1…`），即运行前后与提交后源快照同一。
5. 原始退出码：run1 `playwright.exit.txt` = `EXIT=0`（7 passed / 2.6m），run2 `EXIT=0`（7 passed / 1.9m）；运行的四个 spec 为 `dingtalk-acceptance`、`dingtalk-login`、`dingtalk-connection`、`substitution-approval`，两轮不相加。
6. 静态：`eslint.exit.txt` / `eslint-run2` / `eslint-run3` 全 `EXIT=0`；`e2e-tsc.exit.txt` 为 `EXIT=2`，日志内容为 `dingtalk-acceptance.spec.ts(51,47): error TS2339: Property 'teacherId' does not exist…`，即 classEntries 类型缺口的测试侧错误；`e2e-tsc-run2`/`run3` 均 `EXIT=0`（两份 log 为 0 字节）。`prettier.log` 无独立 exit 收据，不作通过断言。
7. **如实记录一处收据与事实的偏差**：run2 `source-refs.txt` 记 `HEAD_BEFORE=HEAD_AFTER=527ca2f47fdd…`（早期基线快照），而 `fecb656` 实际父提交是 `26edaa6`。两者不矛盾于内容：三份哈希清单相同，且 `post-commit-source-stability.txt` 已把锚点重挂到 `fecb656`，三文件 blob 与本单第 4 项一致。故按“内容一致、引用行陈旧”记录，不据此扩写成整树哈希断言。

## ② 存量升级切片（20d6706）

写集 = 2 文件 / +693 / −0：`backend/src/test/java/com/paiban/acceptance/OldDatabaseUpgradeAcceptanceTest.java`（A，604 行 / 37,262 字节）+ `.scratch/dingtalk-multiuser/t08-upgrade-delivery.md`（A，89 行）。无产品代码、无迁移改动、无他人测试改动、无票面/HANDOFF 改动。

独立核对项：

1. **测试源实算**：工作区与 `git show HEAD:` 两种取法 sha256 均为 `9AA5AA24ADB1DA37C83C478C329D8C36357C475C115EAEE915478732CDABB119`，604 行，LF 规范化后同值；blob `45ea8030b4dcd7ef05e4272a63675961e89c2e45` = 工作区 `git hash-object` 结果。与交付单声明逐字一致。
2. **588 ↔ 604 的口径**：`git diff d07ca10 20d6706 -- <test>` 为空（测试文件在两提交间未变），即 604 行在 `d07ca10` 时已是最终内容；主控先前看到的 588 行是 run3 之前的工作区状态（+16 行投影灵敏度自检），非提交间源漂移。`d07ca10 → 20d6706` 的唯一差异是交付文档 +3/−1。
3. **运行与源对应**：测试文件 mtime `16:36:58`，早于 run3（16:38）、run4（16:44）与两次提交（16:48 / 16:50）；run2 与 run3 日志去时间戳后仅余 UUID/耗时差异（96 行噪声），无断言输出差异。故 run3/run4 跑的是本单核到的 604 行内容。**但不宣称有逐轮 before/after 源哈希收据**：该工作树 `.runtime/logs` 只有 6 个普通文件，无 `.exit.txt`、无 source 清单，源对应由 mtime 次序 + 提交间 blob 相等推得，属“可核但非收据”的强度。
4. **原始日志与退出码**：4 份 Maven 日志 sha256 与交付单缩写真值 4/4 吻合——run1 `1DFFE028…DDE272`（`Tests run: 5, Failures: 1, Errors: 0` + `BUILD FAILURE`）、run2 `38FA5954…D9F887`（5/0/0，`BUILD SUCCESS`）、run3 `72D86F36…B9ABA6A`（5/0/0）、run4 `88162651…32D8C`（5/0/0）。run1 唯一失败用例为 `v60TurnsOldAuthorityIntoTheThreePresetRolesAndKeepsCustomRoles`，即退休权限点期望写错，与“非产品 red”一致；无 exit 文件，exit 由 `BUILD FAILURE/SUCCESS` 行与交付单表交叉确认。
5. **保全口径**：`PRESERVED_TABLES` 恰 21 张（`app_user`、`teacher`、`semester`、`period`、`grade`、`admin_class`、`teaching_class`、`course`、`assignment`、`timetable_draft`、`draft_lesson`、`timetable_entry`、`timetable_publish`、`scheduling_job`、`substitution_request`、`substitution_history`、`substitution`、`constraint_config`、`constraint_entry`、`ai_import_log`、`audit_log`）。投影按 V59 `information_schema` 取旧列、按 id 取整行旧值，升级后逐表断言“旧列仍全在”“种子非空”“每行每旧列逐值相等”，末尾以改名改动反向自检投影灵敏度。
6. **有意变更分开**：`role`/`user_role`/`role_permission`/`permission` 四张原生权限表不在保全投影内，由 `v60…` 用例单独断言（旧 admin→`super_admin`、`head_teacher`→`teacher`、自定义 `audit_jw` 保留、预置角色让出/新增的权限点、退休权限目录行仍保留但不再授权任何预置角色）。V61 断言 `category` 分类/快照三列不补造/`idx_audit_category_created_id`；V62 断言 `login_type` 默认与 `NOT NULL`/两个 `CHECK` 反证、`password_hash` 放开；V63 断言审批三列不补造、`idx_substitution_request_semester_status`、审批人外键拒绝/放行。
7. **数据库边界**：连接串 `jdbc:h2:mem:…;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`，类注释写明只证明 H2 PostgreSQL 兼容模式可执行与旧值不改写；未跑真实 PG、未跑 `PostgresContainerIT`。

## 日志与证据归档（Copy + 逐文件 SHA-256 比对）

| 目标目录（集成） | 文件数 | 字节 | 比对结果 |
|---|---|---|---|
| `logs/t08-browser-run1` | 10 | 253,508 | 10/10 一致 |
| `logs/t08-browser-run2` | 13 | 360,106 | 13/13 一致（含 `post-commit-source-stability.txt`） |
| `logs/t08-browser-static` | 14 | 1,396 | 14/14 一致 |
| `logs/t08-upgrade` | 6 | 239,542 | 6/6 一致（run1/2/3/4-postmerge + commit/commit-amend 钩子收据） |
| `evidence/t08-browser-run1` | 10 | 689,671 | 10/10 一致 |
| `evidence/t08-browser-run2` | 11 | 719,148 | 11/11 一致 |

`logs/` 命中 `.gitignore:18`，保持未跟踪忽略；证据图片为普通文件，仅下列 3 张 run2 图进入提交（其余 run1/run2 图保留在盘上不 stage）：

| 证据 | SHA-256 |
|---|---|
| `evidence/t08-browser-run2/t08-phone-submit.png` | `54008B0778E1268CD45667D124B222331C148475AE3D41D85F6B87C7D792DCD3` |
| `evidence/t08-browser-run2/t08-desktop-change-teacher.png` | `F0212F975AA8E5F59CD22428651395B131787CAB6C391EF6BC19E85D4BAAEA0B` |
| `evidence/t08-browser-run2/t08-phone-effective.png` | `4AD0E21FF8BA5AE53E112B882BE1B4A86D3FB5D7143F8829B19D24163F5FDB08` |

注：run2 的 `t08-phone-effective-details.png` 与 `t08-phone-effective.png` 哈希相同（同一张最终图存了两个名字），只取后者入证。

## 状态、资源与边界

- 合并后集成工作树 tracked 干净；未跟踪项为既有预审证据、`.tmp/`、主控自有 `t08-main-acceptance.md` / `t08-mobile-visual-advice.md`、主控未跟踪 `docs/runbooks/assets/dingtalk/*.png`，一律不 stage。本单只提交本 receipt 与上表 3 张图。
- 开工资源实测：内存 15.7 GB / 可用 5.8 GB，端口 8080/5173/5432 空闲，E: 剩余 6.3 GB；Docker 未安装（本机常态），本次不涉及构建或容器。未启动常驻进程，无需关停；未停止任何主控进程。
- 临时文件仅限集成 `.scratch/dingtalk-multiuser/.runtime/t08-remaining-merge/`（两个合并脚本）。未执行任何删除（含空目录/cache/worktree）。
- 证据边界：本条为合并与哈希核对记录，**不构成验收通过**。未跑 full `verify.sh`、未跑 Playwright/Vitest、无真实 PostgreSQL、无真实钉钉租户与真实手机送达；浏览器证据为离线 fake SDK 演示，升级证据为 H2 兼容模式。整特性双轴评审与最终 full 门禁待主控在收口后执行。
