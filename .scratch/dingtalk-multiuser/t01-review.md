# 票01双轴复查

日期：2026-11-11。固定基点2cd0215，冻结候选56a98c9576f47e6609b8a0a7ee325faa8ca8030c。

比较命令 `git diff 2cd0215...HEAD`，提交列表 `git log 2cd0215..HEAD --oneline` 已验证非空。Standards与Spec由独立group/deepseek high上下文只读执行；以下按原轴、原编号记录，不跨轴排序。

## Standards

- S1 中：RoleUpgradeDataPreservationTest:52-57的第二次Flyway migrate(60)是已执行版本上的no-op，不能证明SQL可重放；建议直接重放SQL或另库验证。
- S2 中：dingtalk-roles.spec.ts:15自建POST /users的createAccount助手，与helpers.ts既有账号建立交互重复，违反公共E2E交互集中于helpers.ts的标准。
- S3 中：ADR-0035:3链接0006-rbac-three-preset-roles.md不存在，实际文件为0006-rbac-foundation.md。
- S4 低：dingtalk-roles.spec.ts的“后端尚未整合”注释已过时；整页option与“全校”定位缺少容器作用域。
- S5 低、判断：router/index.ts、sectionTabs.ts、TimetableView.vue三处复制scheduling:run或timetable:edit的管理判据，后续改动可能不同步。
- S6 低：roleAccess.spec.ts:60-70重写生产路由守卫，断言的是副本，真实守卫回归可能假绿。
- S7 低：UserService.lockSuperAdminAnchor丢弃Optional结果，锚点不存在时静默继续；建议fail closed。
- S8 低、判断：SettingsController逐key调用独立事务的put，数据库写入中途失败会半写，建议单一事务保存整批。

## Spec

- F1 中：story22“原来能看的东西一样都不少”与Implementation Decisions及Out of Scope明确退役班主任/本班查看矛盾。当前实现按具体决定退役本班调代记录入口，建议文档明示。
- F2 中：UserAdminView.openEdit按返回顺序find第一个预置角色；UserService按字母排序返回admin在super_admin前。多角色账号可能显示低身份，saveEdit始终发送单一roleCodes，改姓名便静默丢角色/降级。
- F3 低：票面implemented/全勾，UI交付记录与execution仍写未跑E2E、未开始复查，状态证据不同步。
- F4 低：票面核对清单把SubstitutionPlanService管理判据简写为SUBSTITUTION_APPROVE，实际为MANAGER_PERMISSIONS三项组合。
- F5 低：新ADR-0035“不改任何权限判断点”与后文把发布通知判据改成timetable:edit的记录矛盾。

两轴均确认无范围越界；Spec确认旧管理员/班主任迁移、三身份目录、只读扩权、超管并发底线、设置字段隔离、导入身份和默认本人课表的主路径符合要求。02账号审计HTTP、05审批HTTP属于后续票，不作为01遗漏。

## 统筹逐项处理决定

- S1：不采纳“迁移SQL必须可直接重复重放”的要求，Flyway版本迁移按设计执行一次。保留V59→V60真实角色和12表内容保全断言，删去仅重复调用框架的no-op断言并纠正文档，不能宣称证明SQL幂等。启动种子不回升另由IdentityDataInitializerTest验证。
- S2：接受。将新建账号公共动作放到helpers.ts，现有种子与此spec复用，保留原账号数据和断言，不退化覆盖。
- S3/S4：接受。修新ADR链接和过时注释；Element Plus下拉是teleport，option应限定到活动dropdown而非错误塞进dialog DOM；全校限定在toolbar。
- S5：接受为小幅整理。统一管理权限组合常量/判断，不引入通用权限框架；同时核对navigation工作台落地规则。
- S6：接受。生产导出真实守卫或路由工厂，测试直接接线，不复制逻辑。用临时破坏真实权限守卫的反例证明测试会失败，恢复后全绿。
- S7：接受。缺少super_admin锚点必须fail closed，不继续无锁修改。
- S8：接受。业务设置批量保存单事务；用公开HTTP测试“首字段合法、第二字段超2000字符触发真实数据库约束失败”后首字段仍为原值。app_setting.setting_value在V9及AppSetting中确为VARCHAR(2000)，不mock内部服务。
- F1：按已确认的具体Implementation Decisions与Out of Scope澄清story22：保留个人课表并新增全校只读，原班主任本班调代记录入口退役。仅消除文档承诺冲突，不恢复被明确排除的身份/能力，不需要再次产品确认。
- F2：接受，阻塞放行。编辑时按预置身份优先级显示最高身份；未主动改变身份时不发送roleCodes或完整保留原角色集合，姓名/启停/教师修改不得隐式丢角色；只有显式选择新身份才写单一角色。补多角色姓名修改和显式改身份的页面回归。
- F3：记录阶段边界。implemented是施工完成待复查，尚非done。整合后的真实验证已完成（下节），补齐记录与当前execution，不篡改旧切片当时未跑E2E的历史。
- F4/F5：接受，修核对清单及新ADR措辞为“沿用权限编码/判断入口，不新增按角色名鉴权分支，原先把只读当管理的组合按具体动作收紧”。旧历史ADR保持原样。

## 主模型已完成的整合验证

- 冻结HEAD56a98c9：RoleUpgradeDataPreservationTest 1项通过，0失败/错误/跳过，退出0；V60 Git blob为6722ec888b5820f6fa7b65f18e15bd4c545ca767，与SQL候选语义内容相同（工作树CRLF、blob LF）。日志SHA256 EDB19F83AB7DF03F0233C34C39AE52F87C5EAE0A5E95105A5021E1FF8A587035。
- 同提交 `bash scripts/e2e.sh e2e/dingtalk-roles.spec.ts`：3/3通过，33.3秒，退出0，截图在acceptance/。日志SHA256 C60F12DCB2643A4312E369B8AFC3E32F2B580EFD6867DA4E9D059B27C17D1132。
- 截图复核后做了一次临时诊断：教师radio实际checked=true、class=false，额外checked断言通过（1条，不能与前面3条相加）。截图捕获过渡动画，不是业务默认视图错误。诊断日志SHA256 5E4840D2E7986AC9197D003A5BBEEA44C003B819ED740B70E3CB1CDE11E4B43D，临时spec已删除。整改时在正式E2E保留checked断言，并让截图禁用过渡动画后留存稳定状态。
- 两次浏览器进程均已退出，28080/25173无监听。本机无Docker，未声称真实PostgreSQL或真钉钉通过。

Standards共8项（主要问题为公共E2E交互/真实守卫测试接线）；Spec共5项（主要问题为改姓名静默降级）。整改及复核完成前，不放行下一票。

## 整改处置（codex/dingtalk-t01-review，2026-11-11）

基点 56a98c9，单一 implementer 执行，全部有界改动；完整红绿与命令见
`t01-review-fixes-delivery.md`。逐项结论：

- **S1 已处置**：删除 `RoleUpgradeDataPreservationTest` 里第二次 `migrate(url,"60")` 及其断言，
  类注释写明「本用例证明 V59→V60 升级路径与行级保全，不证明脚本可重复重放」。V59→V60 的真实角色转换与
  12 张表内容保全断言全部保留。未改写「启动种子不回升」的既有覆盖（`IdentityDataInitializerTest`）。
- **S2 已处置**：`createAccount` 上收到 `frontend/e2e/helpers.ts`（支持多身份，供权限回归用），
  `seedSchool`、`seedWorkbenchScenario` 同源复用，`dingtalk-roles.spec.ts` 删除本地副本并改调公共助手。
  `scripts/check-e2e-helpers.sh` 通过。
- **S3 已处置**：ADR-0035 的 `0006-rbac-three-preset-roles.md` 改为 `0006-rbac-foundation.md`。
- **S4 已处置**：删除过时的「后端尚未整合」注释；下拉 option 限定到 `.el-select-dropdown:visible`
  的活动菜单，「全校」限定到 `.toolbar`。
- **S5 已处置**：新增 `constants/permissions.ts` 的 `DRAFT_MANAGER_PERMISSIONS` + `canManageDrafts`，
  路由 `permissionAny`、`sectionTabs.workbenchTabs`、`navigation.workbenchLandingPath`、
  `TimetableView.canManageSchedule` 全部改用它，未引入通用权限框架。
- **S6 已处置**：守卫抽到 `router/guard.ts` 的 `authGuard`，`router/index.ts` 与 `roleAccess.spec.ts`
  都直接接线。临时把真守卫的权限判断改成恒放行，矩阵测试 2 项转红；恢复后 5 项全绿。
- **S7 已处置**：`UserService.lockSuperAdminAnchor` 拿不到 `super_admin` 锚点行时以
  `500 超级管理员角色缺失，账号变更已中止` 失败关闭，不再静默无锁继续。超管保护 4 项、RBAC 10 项仍绿。
- **S8 已处置**：新增 `SettingsService.putAll`（整批一个事务），`SettingsController` 改调它。
  新增 HTTP 用例先让「首字段合法 + 第二字段 2001 字符」撞真实 `VARCHAR(2000)` 约束：修复前首字段被半写
  （红），修复后整批回滚、首字段保持原值（绿）。未放宽权限、未 mock 内部服务。
- **F1 已处置**：`spec.md` story 22 改为「保留个人课表 + 新增全校只读，本班调代记录入口随班主任身份退役」，
  与 Implementation Decisions / Out of Scope 一致；不恢复被排除的身份与能力。
- **F2 已处置**：`openEdit` 按 `PRESET_ROLES` 优先级取最高身份；未显式改身份时不上送 `roleCodes`。
  组件回归 2 条（多角色显示为超级管理员、只改姓名不带 `roleCodes`）先红后绿；真实跨前后端回归并入
  正式 `dingtalk-roles.spec.ts`（后端回读 `roles` 仍为三条）。
- **F3 已处置**：新增 `t01-review-fixes-delivery.md` 记录整合验证与红绿；`execution.md` 更新 01
  进度为「整改完成待放行」，票仍为 `implemented`，未置 done，未改写各切片当时的历史记录。
- **F4 已处置**：票面核对清单把 `SubstitutionPlanService` 的判据拆成 `plansFor`（`SUBSTITUTION_APPROVE`
  或本人）、`swapTargets` 与置换方案确认（`MANAGER_PERMISSIONS` 三项）。
- **F5 已处置**：只改本轮新 ADR-0035 的措辞（沿用权限编码/判断入口、不新增按角色名鉴权分支、
  只把「只读当管理」的判据按动作收紧），历史 ADR 一字未动。
- **额外 QC 已处置**：正式 spec 增加教师 `radio` 的 `toBeChecked`；`screenshots.capture` 使用
  `animations: 'disabled'` 后再截图。禁用动画后教师页截图仍是选中态蓝底，未见颜色异常。

主模型的独立复现（只改姓名后 `roles` 由三条变 `['admin']`，E2E 1 失败，log
SHA256 `3CCB697D803117EA93E96E053C597C68CE2AD5AE09BF197D0EB5BB7E88AA1A44`）作为 F2 的额外实证，
不并入本票正式 E2E 的 4 条计数。

## 整改后复核（56a98c9…6a13c52）

### Standards

独立审查确认原 S1–S8 全部关闭，无新增硬违规、凭证/事务/隔离回归。新增三项低风险判断：

- `roles.ts:46` 的 `isPresetRoleCode` 全仓无调用，建议删除。
- `SettingsService.java:206` 同时公开单项 put 与整批 putAll，建议把 put 私有化以免误解事务。
- `dingtalk-roles.spec.ts:20` 的上下文 wrapper 与 `Parameters<typeof createAccount>[1]` 类型较绕，建议可选 context 或直接 AccountSeed。

统筹处置：删除死导出；直接标注 AccountSeed。保留 wrapper，因为它承担创建/销毁 API 上下文，非纯转发；不把 context 变成可选参数以免扩大公共接口。保留公开 put：IntegrationController、DingtalkConfigLifecycleService、logo/收件人保存及多个测试均有真实单项调用；单项与整批事务各自完整，REQUIRED 自调用共享外层事务正确。私有化会破坏现有调用，并不能改善当前原子性。不采纳该建议，不留作未完工事项。

Standards：原8项关闭；新增3项均低风险判断，其中2项已清理、1项有据不采纳，硬违规0。

### Spec

独立审查确认 F1/F2/F3/F5 关闭；无遗漏、无越界、无新增行为错误。F4 仍有一处低风险证据引用错误：清单写 SubstitutionPlanService.pickTarget，实际方法为 swapTargets（源码167行，MANAGER_PERMISSIONS判断172–173行）。

统筹已核对源码并把票面及本轮交付记录统一更正为 swapTargets。只修证据指针，权限行为未改。

Spec：原5项关闭；复核1项低风险文档引用已修，剩余0。

### 放行

统筹核对整改 diff、后端 surefire XML 和真实页面截图。后续仅删无调用导出、显式类型标注、文档方法名修正，不改运行行为；不为这些机械整理新增镜像测试或重复整轮验收。正常提交钩子检查。01复核放行，允许02开工；依项目规则完整 verify 留至全规格施工/整改结束后一次执行，01暂保持 implemented 而非 done。
