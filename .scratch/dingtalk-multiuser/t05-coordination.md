# 05 施工协调快照（不是交付证明）

## 2026-11-12 02:36 最新状态（下方早期快照保留）

- 集成 `c487df3b84e25b497286d9803fac9ab9a2146745` 已含后端 `03743b4`、前端 `b767f1d`、五例并发 `c4eaeb0`、主控过期竞态 `2950110` 及首轮最小 UI/E2E 整改 `188ae98`；仍未放行，06–08 未施工。
- 双轴原始审源 `10b3e8b...6611b3c`，最终原文与主控判定见 [t05-review.md](t05-review.md)，分别保留 Standards 与 Spec 的计数和优先级。物理审源快照工作树为 `dingtalk-t05-review-snapshot`，只读。
- 当前唯一生产整改 worker：Dirac，`01a1cbb8-7f76-7642-a326-fe698a1b363d`，`group/deepseek` high，工作树 `dingtalk-t05-review-fixes`。统一修 Mapper 的旧效果回填、reject/withdraw 课后作废、锁后刷新当前 AppUser、系统撤销与同名展示、公共 E2E helper 及唯一申请行断言；原始 reviewer 报告不由它改写。
- 主控独立 QA：`dingtalk-t05-authz-race` 的 `6f05e1830143e20d7dbdcd753347c28939f64ba8` 仅增加权限旧快照测试和说明。真实角色变更、新 auth/me、同 EM 旧权限形成确定性红；生产修复后必须原样转绿。已普通合入 Dirac 分支（`e2a38ee`），尚未并入集成；不能把这条刻意保留的红当作交付完成。
- Dirac 前端 checkpoint `d86b286` 已有定向组件、lint/类型证据；剩后端先补行为红，再跑审批普通/并发/过期竞态/权限快照四类定向。主控随后运行受影响 E2E、实际查看结果截图，并做两轴独立增量复核；05 通过后才派 06。
- 第一、第二轮浏览器失败、trace、截图、真实退出与源哈希均分别保留于 `logs/t05-browser-run1/2`、`.runtime/t05-browser-run1/2`、`evidence/t05-browser-run1/2`，两轮源稳定；端口/服务已结束。具体统计只看复查记录，不从旧快照推断通过。
- 原 backend/UI/concurrency writers 与两个首轮 reviewers 已完成并关闭；merger 已完成前一批固定 SHA 合并后关闭。不得移除任何被审批拒绝过清理的整个工作树；新增 QA/runtime 路径也尚未清理，不称已单独获准删除。

## 早期施工快照

2026-11-11约23:55。原八票01/02/04/03已经各自施工、整改复核放行，均暂implemented；05正在施工；06/07/08未施工。最终全量verify尚未执行。集成分支codex/dingtalk-multiuser目前提交10b3e8b4045390265d63dee74e3389473ec51016，未推送/部署。

## 三个独立写集

- 后端James：dingtalk-t05-backend，group/deepseek high。生产、普通状态/权限/截止、旧调用方回归、V63、ADR0037与后端交付。checkpoint为16851f22f296f665fabbb88915f84b88523625e0；提交/批准两条合跑BUILD_SUCCESS（原始.runtime/checkpoint-pair.log），不是完整05。其余状态继续施工，强制旧快照与HTTP并发已移交独立测试writer。
- 前端Plato：dingtalk-t05-ui，group/deepseek high。表单/我的申请/待审批/HomeView文案、对应组件测试、approval及withdraw E2E源码。允许单文件Vitest、串行lint/tsc；不启动E2E服务。最终E2E由主线程在全部整合后跑。
- 并发Descartes：dingtalk-t05-concurrency，group/deepseek high。只新写SubstitutionApprovalConcurrencyTest.java与t05-concurrency-delivery.md，不改生产。已合固定checkpoint，当前只许运行create/approve的三个边界；withdraw/reject两项等核心完整SHA后合入再运行。已有正确锁的回归首跑绿要如实记录，不伪造红。

所有worker最终合入集成最新tip再报告不可变SHA；源写集互不覆盖。当前资源允许单文件Vitest及最多两组独立定向Maven，每次开测前复查可用内存≥2GiB且CPU不过载；不足则暂停新测。Maven-forkCount=0，GitPortable Bash，TEMP/TMP/TMPDIR与npm cache均限各自工作树。禁止全量verify/E2E/真实企业请求。

## 主线程早期质量检查

- 已实读首个提交/批准事务方向：独立具体ApprovalService+TransactionTemplate Outcome，过期先提交再向外409；当前仅核心两条绿，拒绝/撤回/过期等未放行。
- 已指出RequestService校验失败经audits.failure(...true)暗发旧告警，要求仅本申请/审批服务改false保留失败审计，工作台其它旧触发器留06。见t05-transaction-plan末尾。
- 要求comment/reason与数据库500字符上限一致，不让501字符到SQL才500；惰性过期后待审批列表重新按最新SUBMITTED过滤；mine应保留EXPIRED。
- 00:20后新增边界回归曾有两项前置错误：同PUBLISHED草稿直接再publish被既有guard409拒绝，应先withdraw再publish；手写来源/目标同日SWAP行被读模型当来源移出，并不能造出busy教师。改用既有同槽AdminSubstitutionIntegrationTest.buildFixture和公开工作台对另一班直接代课，先断实际教师已忙再批准原待批。不得为错误测试前提放开发布或改PlanSnapshot；原失败日志保留。
- 主线程另写独立SubstitutionExpiryRaceIntegrationTest，固定core8f457ac：撤掉锁后状态保护时真实HTTP读到EXPIRED（期望EFFECTIVE），1失败退出1；按原字节/哈希恢复后同例1通过退出0，日志归档logs/t05-expiry-race。该例不在并发writer原五例中，不冒称历史TDD红。
- 前端20组件例通过后，主线程实读仍发现onMounted无条件classes在无学期400且先于错误提示失败，要求先确认当前学期再挂子组件/发依赖请求；另有pending失败可被稍后mine成功清掉共享error的竞态，要求最小独立错误状态与deferred公开DOM回归。原20例不代表这两项已验证。
- 并发要求同一EntityManager强制读旧状态后交错处理，并在TransactionTemplate外捕获ApiException，避免吞异常后UnexpectedRollback误当生产回归。另有真实HTTP不变量。
- 184refs/17WT最新占号快照只有本票V63，blob与主线程hash-object均为69e299ca4aba6302d51b0518cdbbcbe0286e9777；其余任务最高62。详见migration-inventory-before-t05。

## 下一道门禁及准备材料

三writer交付→merger普通merge固定SHA（禁止amend/重写）→主线程定向回归和真实审批UI E2E→固定05开工基点10b3e8b双轴code-review→单writer整改/增量复核→05放行才进入06。

06准备见t06-delivery-boundary、t06-notification-primary-research：afterCommit有界异步外发，防30秒前端超时；真实operator结果与最终Teacher接课分开解析，未首次登录但有可信UID的接课老师不因无账号漏发；仅停用账号不发；不偷偷开号；官方卡片协议/业务码离线stub验证。07准备见t07-probe-plan及主源研究，08见t08-acceptance-plan。所有准备不算已施工。

清理限制：多个03/04缓存删除以及03 UI的runtime/截图副本联合删除已被自动审批拒绝，09记录；保留相关整个工作树，不绕过。05各writer也已被告知不要再尝试同类递归清理，只列实际运行路径与自启进程是否结束交主线程。未尝试的05路径不能写成独立被拒。真实原始日志与证据保留。
