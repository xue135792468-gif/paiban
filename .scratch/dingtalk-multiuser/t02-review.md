# 票02 双轴复查与整改决定

日期2026-11-11。审查固定区间 `60a7d9d...4eb1886`；Standards/Spec独立只读，未跑服务或改文件。

## Standards

硬违规0，判断项3（1中、2低）。

- S1 中：V61仅把旧APP_USER启停用、users/IMPORT归为ACCOUNT，旧DISABLE_USER/ENABLE_USER/IMPORT没有进入动作目录；升级后显示英文码且动作筛选不命中。定位V61:20、AccountAuditView:121、accountAuditChange:50。
- S2 低：复合PUT先改姓名/角色，再applyDisable/Enable取before，之后UPDATE又包含启停；事件前后口径重叠。resetPassword前后是同一份无密码快照。定位UserService:130–161、200。
- S3 低：新spec的clickOption重复helpers.ts:332的pickOption；CODING_STANDARDS要求下拉交互只在公共helper实现。建议给控件稳定testid并复用。

最严重：S1，升级库旧账号事件的显示与动作筛选不完整。

## Spec

发现4项（全低），无遗漏、无越界。

- F1：旧记录无target ID/快照、动作码未映射，账号/动作筛选不完整。对应story42「按操作类型和账号筛选」。
- F2：改身份被并入修改账号，动作下拉没有改身份。对应审计动作目录单列改身份。
- F3：复合PUT先记启停再记UPDATE，两条事件重复覆盖同一状态变化。对应story43改前/改后。
- F4：重置密码快照正确地不含秘密，但变更列是“—”；应明确显示已重置且内容不记录。

最严重：F1，历史记录可查性不足。其余六类手工操作、逐账号导入、三身份权限、SQL分页筛选、敏感字段allowlist、03/04预留均已确认成立。

## 统筹整改决定（全部在一个implementer中完成）

1. S1/F1：保留历史原文与原动作码，不猜测、不解析detail去关联账号，更不能按当前账号快照伪造旧before/after。旧API用户名没有字符模式约束，文本解析无法保证可靠。增加旧码只读兼容别名：DISABLE_USER→ACCOUNT_DISABLE、ENABLE_USER→ACCOUNT_ENABLE、IMPORT→ACCOUNT_IMPORT（仅ACCOUNT分类）；DTO以规范码返回，SQL动作过滤同时匹配规范码及旧别名，其他类别的IMPORT不受影响。未知历史码页面给中文兜底，不直接露英文码。
2. 对旧记录缺失的目标ID/快照诚实展示：保留可读旧描述与整批成功/失败数，区分历史记录、整批摘要、逐账号记录；UI/交付说明“升级前未记录目标账号ID或逐账号明细的记录，无法按账号精确筛选”。不能恢复原本没写过的信息，禁止伪造关联。新逐账号事件仍必须完整支持稳定ID过滤。旧description只经文本渲染，不v-html，不引入原请求或实体序列化。
3. 加一个V60→V61旧日志保全用例，种入启用/停用/导入及非账号日志，验证原有字段/原动作值保留、category正确分类；另用真实HTTP证明新动作筛选能命中旧别名、教务旧接口仍不泄漏。无须修改V1–V60，也不需要新增迁移版本。
4. F2：单列ACCOUNT_ROLE_CHANGE“改身份”；保留ACCOUNT_UPDATE用于“修改资料”（姓名/关联教师等），所以目录为原9个需求动作加资料修改共10类。角色集合真正变化才用ROLE_CHANGE；角色没变但资料变用UPDATE；完全无变化不伪造事件。同步文案、测试与交付记录，不改历史ADR。
5. S2/F3：所有守卫先验证，锁/刷新保留。复合PUT先完成并记录身份/资料阶段（before旧值→after新资料但原启用状态），再做启停并记录（before刚才的新资料+原启用→after新资料+新启用），两条形成连续不重叠的链。不要把整单初始before再传给第二条而重复身份变化。单独启停只一条；角色与姓名一起变可合为一条ROLE_CHANGE。补公开HTTP复合编辑回归，断言完整前后状态和顺序。
6. F4：密码值/哈希不进快照，重置事件同样的可见快照可保留；页面按动作显示“密码已重置（内容不记录）”。补公开展示行为回归。
7. S3及真实浏览器失败：两个select加稳定data-testid，删除spec本地clickOption，复用已有pickOption（允许当前公共helper的既有行为，不重构其他页面）。保留控件可访问名称断言、真实筛选/干扰账号/前后变化/顺序/三角色拒绝等业务断言。目标完整可见label可作为公共helper精确选项文字；ROLE_CHANGE的API与页面预期同步更新。

## 主模型真实浏览器证据

- 冻结源HEAD4eb1886，`bash scripts/e2e.sh e2e/account-audit.spec.ts`：退出1，1失败/2通过，3.9分钟。根因在133行直接click只读combobox内部input，被el-select__placeholder挡住；之前账号过滤、三条实际事件与中文变更已通过。日志SHA256 `752AB776C249DB0E9EFD59C98949848C61A5899A781B2E9DCB33C8DCA6B1D748`。
- 单变量诊断：只把该click临时改为press ArrowDown，用同一超级管理员用例、30秒上限重跑：1通过（用例3.3秒，总22秒），退出0。日志SHA256 `8E69301D397BCD45A7559DA3C32E1F8A3435A6C202CB686E4DCBB2B5BB193A16`。已撤掉临时改动，正式修法集中到共享picker。无需再重复180秒红例或二分。
- 截图已由主模型查看，正常显示目标账号的停用/改身份/建号三个事件。日志在集成工作树的本目录；未读取或披露trace中的认证头。两个测试服务已退出，28080/25173无监听。
- 主模型doctor：内存5.1GiB、磁盘7.4GiB、开发端口空闲；Docker/PG缺失，因此本次仅H2 e2e+假钉钉，不是生产/真实PG验收。

整改后再跑受影响定向测试和3条完整E2E，复查放行前不进下一施工票；全量verify仍留到全部票结束后一次执行。票状态保持implemented，先不要done。

## 整改结果（2026-11-11，单一 implementer，分支 `codex/dingtalk-t02-review`）

逐项落实；票状态保持 implemented。未改 V1–V60、未新增迁移版本、未改历史 ADR，未开始 03/04 业务。

| 项 | 结果与验证 |
|---|---|
| 1 S1/F1 旧动作别名 | `AccountAuditAction` 增加只读别名 `DISABLE_USER→ACCOUNT_DISABLE`、`ENABLE_USER→ACCOUNT_ENABLE`、`IMPORT→ACCOUNT_IMPORT`（仅 ACCOUNT 分类）；库中原值不动，DTO 返回规范码，SQL 过滤同时匹配规范码与旧码。`AccountAuditIntegrationTest#legacyAccountCodesAreReadOnlyAliasesOfTheirCanonicalActions` 覆盖 HTTP 命中、原文保留、目标 ID/快照仍为 null。 |
| 2 历史缺失信息如实展示 | DTO 增加 `detail/successRows/failedRows`；页面区分「历史记录（未记录目标账号）」「整批摘要」「逐账号记录」，旧描述仅文本插值（无 v-html、无原请求/实体序列化），并常驻说明「升级前未记录…无法按账号精确筛选」。 |
| 3 V60→V61 保全 | 新增 `AccountAuditUpgradeDataPreservationTest`：旧启停/账号导入归为 ACCOUNT，教师导入等保持 null；原动作、原文、成功失败数与时间戳逐行不变，target/before/after 列保持 null。该用例首跑即绿（V61 分类本身正确，作为边界钉住）。 |
| 4 F2 改身份单列 | 动作目录 10 类：新增 `ACCOUNT_ROLE_CHANGE`（改身份），`ACCOUNT_UPDATE` 改为「修改资料」；角色集合真正变化才写 ROLE_CHANGE，仅资料变化写 UPDATE，无变化不写事件。集成与页面/规格用例同步。 |
| 5 S2/F3 复合 PUT | 守卫先验证；先落身份/资料并记一条（旧启用状态），再落启停并记第二条（从新资料 + 旧启用状态出发），形成连续不重叠链。`compositeEditRecordsConsecutiveNonOverlappingEvents` 断言完整前后状态与顺序。 |
| 6 F4 重置密码展示 | 快照仍不含密码/哈希；页面按动作显示「密码已重置（内容不记录）」，单测与集成断言 detail 可读。 |
| 7 S3/E2E | 两个下拉加稳定 `data-testid`，spec 删除本地 `clickOption`，复用公共 `pickOption`（传完整可见 label）；保留可访问名称与全部业务断言。真实浏览器 3/3 通过（25.7s），截图 4 张。 |

另：复查基线发现 `ExtendedLifecycleIntegrationTest` 两条旧断言仍在通用日志里找 `DISABLE_USER/ENABLE_USER`，自 4eb1886 起即为红（实测 2 失败）。已改为在账号审计接口断言 `ACCOUNT_DISABLE/ACCOUNT_ENABLE`，复跑 2/2 绿。

证据日志（未提交）：`.scratch/dingtalk-multiuser/logs/`；截图：`.scratch/dingtalk-multiuser/acceptance/dingtalk-t02-*.png`。
