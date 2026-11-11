# 票01 双轴复查整改交付记录

2026-11-11（Asia/Shanghai）。工作树 `.worktrees/dingtalk-t01-review`，分支
`codex/dingtalk-t01-review`，开工基点 `56a98c9`，收尾前合并集成最新 tip `082e326`
（fast-forward，只带一份只读研究文档）。单一 implementer，按 `t01-review.md`
的 S1–S8 / F1–F5 逐项有界整改，未扩大重构范围。

测试 seam 沿用已确认口径：HTTP→返回与最终数据库状态、迁移升级路径、页面公开行为与路由守卫；
不测私有方法、不 mock 内部服务、不复制生产判断。

## 代码变更

- `backend/src/test/.../RoleUpgradeDataPreservationTest.java`（S1）：删除第二次
  `migrate(url,"60")` 的 no-op 断言，类注释明确本用例证明升级路径与行级保全、不证明脚本可重放。
- `backend/src/main/.../settings/SettingsService.java` + `SettingsController.java`（S8）：
  新增 `putAll(Map)`（整批一个事务），保存设置改走整批写入。
- `backend/src/main/.../identity/service/UserService.java`（S7）：`lockSuperAdminAnchor()`
  拿不到锚点行时以 500 明确失败（fail closed）。
- `frontend/src/constants/roles.ts`（F2）：新增 `primaryRoleCode`（按预置身份优先级取最高），
  `primaryRoleLabel` 复用它。
- `frontend/src/views/UserAdminView.vue`（F2）：`openEdit` 按最高预置身份显示；
  未显式改身份时不上送 `roleCodes`，只有显式换身份才写单一身份。
- `frontend/src/constants/permissions.ts`（S5）：新增 `DRAFT_MANAGER_PERMISSIONS` 与
  `canManageDrafts`；`router/index.ts`、`layouts/sectionTabs.ts`、`layouts/navigation.ts`、
  `views/TimetableView.vue` 共用。
- `frontend/src/router/guard.ts`（新增，S6）+ `router/index.ts`：守卫成为唯一实现，路由直接接线。
- `frontend/src/router/roleAccess.spec.ts`（S6）：删除复制版守卫，改接 `authGuard`。
- `frontend/e2e/helpers.ts`（S2）：新增公共 `createAccount`；`seedSchool`、`seedWorkbenchScenario` 复用。
- `frontend/e2e/dingtalk-roles.spec.ts`（S2/S4/F2/QC）：删本地 `createAccount`、修过时注释、
  下拉 option 限定活动 dropdown、全校限定 `.toolbar`、补教师 `radio` 选中断言、
  新增多角色账号「只改姓名不丢身份」真实跨前后端回归（共 4 条用例）。
- `frontend/e2e/screenshots.ts`（QC）：`capture` 使用 `animations: 'disabled'`。
- `docs/adr/0035-super-admin-and-school-admin-roles.md`（S3/F5）：修正 ADR-0006 链接；
  把「不改任何权限判断点」改为「沿用权限编码/判断入口、不新增按角色名鉴权分支、
  只把只读当管理的判据按动作收紧」。历史 ADR 未改。
- `.scratch/dingtalk-multiuser/spec.md`（F1）：story 22 按已确认决定澄清。
- `.scratch/dingtalk-multiuser/issues/01-roles-reshape.md`（F4）：核对清单拆分
  `SubstitutionPlanService.plansFor` / `pickTarget` / 置换方案确认的判据。
- `.scratch/dingtalk-multiuser/execution.md`（F3）：01 进度改为「整改完成待放行」。

## 红 → 绿（真实执行）

前端统一 `cd frontend && npx vitest run --no-file-parallelism <文件>`；后端统一
`source .tools/env.sh` 后 `cd backend && ./mvnw -B -DforkCount=0 -Dtest=<类> test`（H2）。

| 项 | 红（首个失败原文） | 最小实现 | 绿 |
|---|---|---|---|
| F2 组件 | `expected "super_admin" but received "admin"`；`expected { displayName: '仅修改姓名', …(2) } to not have property "roleCodes"` → `2 failed \| 4 passed` | `primaryRoleCode` + `openEdit` 取最高身份 + 未改身份不上送 `roleCodes` | `UserAdminView.spec.ts` + `roles.spec.ts`：`Test Files 2 passed`，`Tests 9 passed` |
| S8 存储失败 | `expected: "before-batch-update" but was: "must-not-survive-the-batch"`（首字段被半写）→ `Tests run: 5, Failures: 1` | `SettingsService.putAll` 整批一个事务 + 控制器改调 | `SettingsPermissionIntegrationTest`：`Tests run: 5, Failures: 0` |
| S6 变异敏感性 | 真守卫的 `permission` 判断临时改成恒放行 → `expected 'draft-editor' to be 'forbidden'`、`expected 'users' to be 'forbidden'`（`2 failed \| 3 passed`） | 不改测试，只恢复真守卫 | `roleAccess.spec.ts`：`Tests 5 passed` |
| F2 E2E 变异敏感性 | `UserAdminView` 临时恢复「总发单一 roleCodes」→ 正式 spec 第 4 条失败，`1 failed`，退出码 1 | 恢复「未改身份不带 roleCodes」 | 正式 spec `4 passed`，退出码 0 |

未为纯重构（S2/S4/S5）造镜像测试：这些改动只更新现有断言与用例，行为断言不变。

## 定向验证汇总

- 后端定向 7 类：`RoleUpgradeDataPreservationTest 1`、`SuperAdminGuardIntegrationTest 4`、
  `AuthRbacIntegrationTest 10`、`RolePermissionMatrixIntegrationTest 6`、
  `RoleReshapeIntegrationTest 4`、`SettingsPermissionIntegrationTest 5`、
  `SettingsIntegrationTest 3` = **33 项，0 失败 / 0 错误 / 0 跳过**，`MVN_EXIT=0`。
- 前端定向：`roleAccess`/`productTrim`/`navigation`/`sectionTabs`/`TimetableView`/`sectionLayout`
  `6 files / 30 tests passed`；`UserAdminView` + `roles` `2 files / 9 tests passed`；
  收尾复跑（上述 8 个文件一次跑完，含变异恢复后的状态）`8 files / 39 tests passed`，退出码 0。
- E2E（隔离端口 28080/25173，`PAIBAN_E2E_SCREENSHOTS` 指向本工作树
  `.scratch/dingtalk-multiuser/acceptance`）：`e2e/dingtalk-roles.spec.ts` **4 passed**，退出码 0；
  截图 `dingtalk-t01-super-admin-roles / academic-settings / teacher-timetable /
  multi-role-name-edit` 四张。禁用过渡动画后教师页截图仍为「教师」蓝底选中态，颜色无异常。
- `npm run lint`（eslint --max-warnings 0）退出码 0；`npm run build`（vue-tsc + vite）退出码 0；
  `scripts/check-e2e-helpers.sh` 通过（无 spec 重复定义 helpers 导出函数）。

原始日志均为本任务临时文件（`t01-review` 工作树根 `.tmp-*.log`），记录统计与哈希后清理：

| 日志 | SHA256 |
|---|---|
| f2-red / f2-green | `7C757833C500BF24DAEDA30042ABF10C6AFE3E5EDFA0ABA4A174C0B188CA98E8` / `820E8750BDC997AD2DBAC9E03399A68E40FDC5F2DCB9AE86FFF0CE4F067102E1` |
| s8-red / s8-green | `9D67EC22836D1965B59233D39D129D8A9B2C9DE27DDB5C3EE800817083EECA1A` / `554CAEBB8781445DE810031A0EA6F8FCB77992A476621CA9273CD92F350EC1F0` |
| s6-mutation / s6-restored | `DC6DDB1CBF10012AD895080C92C54BF2ACAEECD2BE3A387E3CE30EB643CE4C35` / `21150506CDA5F96BCE6E83B5F6BC9456298D6AB359462E57A8827E31F5816962` |
| s5s6-green | `8A5CCB520FF0F26560694EA9EECC0987CD242D692D36DD8103EB08B8B98BCCBB` |
| backend-targeted | `C0ECDCDD1160C3FCED3353EC5620E4FD412AB3F19B6BDC6B520D8CF1737EB6E2` |
| e2e-roles（首轮）/ e2e-roles2（恢复后） | `7C1AA08645F67B4051A17AF8A457CC5BFCB1220FF42E2181DB90BECAE8820C4B` / `6797600271385141A3D7357C0AFBA664EAC29F29DAD46AA79C48A62A932BAEB1` |
| e2e-mutation（旧行为） | `03AA765563C0D9D4D9D2C31B9F3D565DCF6AA93F4C3B3EE10638508A9F79789D` |
| lint / build | `EA0F2477C2692D7371A63F3246FD59675E149C6B09113320A981E2C277D9D725` / `341B15E6CA89033804EB4A2C359AF1B5014A1DDA956605F45AA144A8853C0529` |
| final-frontend（8 文件 / 39 用例） | `FF39533ECD5DC7949573D4BA533A1EE87A5D1E3C2F97891D2B2B2A78C162CA27` |

## F2 问题的独立实证（主模型，真浏览器）

主模型在冻结候选 `56a98c9` 上用真实浏览器单独复现：多角色账号
`['admin','super_admin','teacher']` 只改姓名，`GET /users` 的 `roles` 变成 `['admin']`，
E2E 1 项失败、退出码 1，原始日志 SHA256
`3CCB697D803117EA93E96E053C597C68CE2AD5AE09BF197D0EB5BB7E88AA1A44`。
该实证**不并入**正式 spec 的 4 条计数；正式 spec 的第 4 条是同一行为的独立回归
（含修复后后端回读断言），并做过上面的变异敏感性验证。

## 未验证 / 边界

- 未运行真实 PostgreSQL（本机无 Docker）：V60 与 S8 的 `VARCHAR(2000)` 约束都在
  H2（PostgreSQL 模式，`ddl-auto=validate`）上验证，语义一致但未在真 PG 上复跑。
- 未跑全量 `scripts/verify.sh` 与全量前端单测（按指示只跑受影响定向测试）；
  未来全量门禁仍需一次冻结源码后的完整 verify。
- S7 的「锚点缺失」分支没有自动化用例：要构造它只能删掉种子/迁移产出的
  `super_admin` 角色行，那是不可达的产品状态；改动由超管保护与 RBAC 定向用例守住不回归。
- 未接触生产环境、真实钉钉与任何凭证。临时进程（E2E 后端/前端）随 Playwright 退出，
  临时日志与 `test-results` 在提交前清理。
