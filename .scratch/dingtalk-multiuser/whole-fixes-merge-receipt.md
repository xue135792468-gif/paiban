# 整特性复查整改 + 手机导航入口 合并接收单

2026-11-13。独立 merger 在集成工作树 `dingtalk-integration`（分支 `codex/dingtalk-multiuser`）接收唯一
fixer Turing 的两阶段整改并合入。本单只记录本次实测与只读核对的事实，以及证据边界；不复跑测试、
不构建、不跑 Vitest/browser/full，不 push。

## 输入与结论

- 集成基线（合并前）：`de8dbd0`（`docs: bound mobile navigation repair and preserve first review delta`），
  tracked 干净、HEAD 未移动。
- 交付源：`codex/dingtalk-whole-review-fixes` 最终 `52cbf131382f769a25dc5e7b781ad4f8abcbebce`。
  构成为阶段一内容 `6871b6c`（Standards 统一整改）+ 阶段二内容 `64ac275`（手机导航入口）+
  合并 `52cbf13`（把 `de8dbd0` 并入候选，父 `64ac275` + `de8dbd0`）；交付工作树 tracked clean，
  主控已确认 Turing completed、finalSHA 不再变动。
- **合并提交**：`261be4af89a5e20c2fb793a0ee79659247712f17`，`--no-ff`，父提交
  `de8dbd0` + `52cbf13`，ort 策略，无冲突，走正常 hooks（未 `--no-verify`）。
- 合并结果树 `2fceb80a06963a0b1f08410798ea59edab33a863` 与 `52cbf13^{tree}` **完全相同**；
  `de8dbd0` 已是 `52cbf13` 的祖先，故合并等价于落候选树，无额外内容。

## 源与候选 Git 对象一致性（一次 ls-tree + 一次 hash-object batch）

13 个源文件（阶段一 9 个去重后的 8 个 + 阶段二 5 个，`dingtalk-acceptance.spec.ts` 两阶段共有）：
工作树 `git hash-object` 结果与 `52cbf13` 树内 blob、与合并后 `HEAD` 树内 blob 逐条相等，0 差异；
合并后工作树再取一次 hash-object 仍与 `52cbf13` 相等。

| 文件 | blob |
|---|---|
| `frontend/e2e/helpers.ts` | `36e410f8d1…` |
| `frontend/e2e/dingtalk-sync.spec.ts` | `3465b84713…` |
| `frontend/e2e/substitution-approval.spec.ts` | `473b08a0c9…` |
| `frontend/e2e/account-audit.spec.ts` | `8133830965…` |
| `frontend/e2e/notification-delivery-logs.spec.ts` | `761c0a021f…` |
| `frontend/e2e/substitution.spec.ts` | `3e3f326451…` |
| `frontend/e2e/dingtalk-acceptance.spec.ts` | `d93199bd26…` |
| `frontend/src/views/SettingsView.vue` | `79ae0f681e…` |
| `frontend/src/layouts/MainLayout.vue` | `4cc8b899fc…` |
| `frontend/src/layouts/MainNavigationMenu.vue` | `03e80e5024…` |
| `frontend/src/layouts/navigation.ts` | `2301a717a6…` |
| `frontend/src/layouts/navigation.spec.ts` | `9e50990792…` |
| `backend/src/main/java/com/paiban/notification/service/NotificationService.java` | `80098128b6…` |

只读快照 `dingtalk-standards-fixes-snapshot`（HEAD `d7ee6ff`，clean）核第一阶段：其 9 个文件
`hash-object` 与 `d7ee6ff` 树内 blob 全部相等；其中 8 个非 acceptance 文件在 `d7ee6ff` 与 `52cbf13`
之间也完全相同（acceptance spec 由阶段二 `64ac275` 合法改写，`4760bd28…` → `d93199bd…`）。

## 合并写集（`de8dbd0..52cbf13`，15 文件 / +331 / -69）

- 后端唯一改动：`NotificationService.java` **-8 行**（仅删两个零调用空方法）；`backend/` 之外无其他 Java 改动。
- 前端产品源 4 个：`MainLayout.vue`（+91/-…）、`MainNavigationMenu.vue`（新增 65）、`navigation.ts`、
  `SettingsView.vue`（3 行换常量）；测试 7 个（helpers + 6 spec，含 acceptance）。
- 文档 2 个：`whole-review-fixes-delivery.md`、`whole-review-mobile-nav-delivery.md`。
- 主控自有 `frontend/e2e/dingtalk-mobile-navigation.spec.ts` 在 `de8dbd0..52cbf13` 内**零改动**
  （由 `de8dbd0` 带入），业务断言、角色与合法入口未被 fixer 触碰；未改路由表、权限定义、默认落地页。

## 只读核对项（逐文件）

1. **H1（P2）四 collector 复用**：`account-audit`、`dingtalk-sync`、`substitution-approval`、
   `notification-delivery-logs` 均改为 `trackBrowserErrors(page)`；监听范围与原断言原样保留
   （`substitution-approval:36`、`notification-delivery-logs:23` 为 `afterEach` 断空；
   `account-audit:152,216`、`dingtalk-sync:108,267` 为用例体内断空），未 skip、未吞错误。
2. **J5 纠正（主控 corrigendum，untracked，本单未 stage）**：J5「account-audit/dingtalk-sync 无
   afterEach 即无消费」为误报 —— 两文件各有两处体内 `expect(browserErrors).toEqual([])`，与上条
   行号一致。更正后计数：Hard P1 0 / P2 0 / P3 0；Judgement P1 0 / P2 0 / **P3 1**（仅 J4）。
3. **J1（P3）日期共享 helper**：`helpers.ts:78 export async function fillDateInput(...)`，
   三处调用（`substitution-approval:40`、`substitution:56`、`dingtalk-acceptance:87`），私有
   `pickDate` 已删；日期值、锚点与断言未变。
4. **J2（P3）Settings 常量**：`SettingsView.vue:204` 引入 `INTEGRATION_MANAGE_PERMISSION`、
   `:237` 使用；`canViewDeliveryLogs` 仍 `substitution:view`（`:238`），未新增权限字典。
5. **J3（P3）空退役方法**：`NotificationService.java` 全文 grep `substitutionPlanFailed` /
   `substitutionVerifyAlert` 0 命中；`public void notify(` 保留（`:55`），无业务行为变化。
6. **阶段二手机导航**：桌面 aside 用 `menu-test-id="layout-menu"` + `:with-tour-anchors="true"`
   （MainLayout：8-13）；header 左侧按钮 `aria-label="打开导航"`（19-28，`.nav-toggle` 默认
   `display:none`、`@media (max-width:900px)` 显示，259/335）；抽屉 `title="导航"`、`direction="ltr"`、
   `destroy-on-close`、`menu-test-id="mobile-layout-menu"`、`:with-tour-anchors="false"`、
   `@select="navOpen = false"`（55-70）。桌面与抽屉共用同一 `visibleGroups` + `activeMenu`，
   无手机专用权限分支；共享组件 `MainNavigationMenu.vue` 只以 `menuTestId`/`withTourAnchors` 区分。
7. **老师中央 resolver**：`navigation.ts:114-118` 为『有 `substitution:submit` 且不具备完整
   `WORKBENCH_MANAGER_PERMISSIONS`』→ `/substitution/requests`；`navigation.spec.ts:69` 断言完整
   管理组合仍 `/substitution`，`:91` 断言老师落 `/substitution/requests`；默认落地与路由表未动。

## 原始证据（原样复制，不删改、不补造）

交付工作树 `.runtime/logs/` 的普通文件已复制到 `logs/whole-review-fixes/`（`logs/` 被
`.gitignore` 忽略，故不入库、不 stage），共 **17 文件**，逐文件 `Get-FileHash -Algorithm SHA256`
与源文件相等（17/17，0 不一致）。

| 轮次 | 文件 | 字节 | 观察到的事实 |
|---|---|---|---|
| R1 | `R1-eslint.log` | 209 | 仅 npm 升级 notice，exit 0（交付方报告） |
| R2 | `R2-e2e-helpers.log` | 54 | `e2e helpers ok (no spec redefines an exported helper)` |
| R3 | `R3-vue-tsc.log` | 0 | 无输出；exit 0（交付方报告） |
| R4 | `R4-e2e-tsc.log` | 0 | 无输出；exit 0（交付方报告） |
| R5 | `R5-maven.log` | 168 | 首次 `MSYS_NO_PATHCONV=1` 误设：`ClassNotFoundException … classworlds.launcher`，测试未启动，**非业务 red** |
| R6 | `R6-maven.log` / `R6.exit` | 48793 / 7 | `EXIT=0`；日志含 `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` |
| R7 | `R7-eslint2.log` | 441 | 初轮 1 warning（`menuTestId` require-default-prop），保留不改写 |
| R7b | `R7b-eslint2.log` | 0 | 无输出；exit 0（交付方报告，无独立 exit 文件） |
| R8 / R8b | `R8-vitest-nav.log` / `R8b-vitest-nav.log` | 429 / 429 | `Test Files 2 passed`，`Tests 14 passed` |
| R9 | `R9-vuetsc2.log` | 0 | 无输出；exit 0（交付方报告） |
| R10 | `R10-e2e-tsc.log` | 0 | 无输出；strict 新 7 spec 的 exit 0 由交付方报告，本单未见 exit 文件，不代其断言 |
| — | `phase2-freeze-before/after.sha256` | 481 / 481 | 两份内容与自身 SHA256（`0d1f00ae…`）相同，即阶段二 5 源运行前后一致 |
| — | `writeset-before/after.sha256` | 969 / 969 | 9 条逐值不同：这是**改动前/改动后**的写集哈希，不是测试运行前后源冻结，不作为 runtime freeze 证据 |

## 未由本单验证 / 边界

- browser、Playwright、`verify.sh --full`、Vitest 全量与 PostgreSQL/真实钉钉均未由本单启动或复跑。
  主控此刻在交付工作树以 `52cbf13` 冻结源跑 `whole-fixes-browser-run1`（受影响 32 tests / 12 files），
  已报 M1 新例 ok9、原全链 ok4，其余仍在跑；**本单不据此宣称整特性浏览器通过**。
- 阶段二无 Java 改动，未复跑 Maven；R6 的 12 项后端证据沿用阶段一原始日志。
- 未删除任何文件（cache/empty/finally/worktree/09 均保留）；未启动任何常驻进程；未 push。
- 提交只 stage 本接收单；主控 untracked 的 corrigendum 与其他 raw/evidence 未 stage、未改动。
