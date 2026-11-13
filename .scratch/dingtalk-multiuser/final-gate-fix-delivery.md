# 终局门禁唯一失败修复（RoleReshapeIntegrationTest）

## 原因

`presetRolesAreSuperAdminSchoolAdminAndTeacher` 把 `/api/roles` 整份角色目录断言成
`containsInAnyOrder("super_admin","admin","teacher")`。`DatabaseResetExtension` 有意保留身份六表
（含 `role`），`AiImportIntegrationTest` 用 `new Role("foundation_only_*")` 种下的自定义角色跨类存活，
目录里因此多出两条合法自定义角色 → 断言误判。规格允许旧自定义角色保留，产品/V60/全局 reset 均无需改。

## 改动（唯一测试源文件）

`backend/src/test/java/com/paiban/identity/RoleReshapeIntegrationTest.java`

- 用例内自种 `legacy_custom_<suffix>` 自定义角色，独立复现「目录含自定义角色」的输入，不依赖其它测试类顺序。
- 预置角色改为子集精确断言：`filteredOn(PRESET_ROLE_CODES::contains).containsExactlyInAnyOrder(...)`（恰好三项，卡重复/冒名）。
- 整份 `code` 数组显式 `not(hasItem("head_teacher"))`，退休角色不许存在；只按 code 排除，不限制任何自定义角色名称。
- 保留三条预置 `name` 精确断言；自定义角色的 `name` 经真实 HTTP 可见（证明目录保留历史角色）。
- 未改产品代码、V60、`DatabaseResetExtension`、AiImport 测试，未新增依赖或权限断言。

## 红绿证据（日志：`.scratch/dingtalk-multiuser/logs/final-gate-fix/`）

| 轮次 | 命令要点 | 结果 | 源 hash |
|---|---|---|---|
| red | `-Dtest=RoleReshapeIntegrationTest#presetRolesAreSuperAdminSchoolAdminAndTeacher` | exit 1；`Tests run: 1, Failures: 1`，实际 `<[super_admin, admin, teacher, legacy_custom_09e975eb]>`（red.log / red-exit.log） | 16e3926bc09ff060cdc531118e36b676cf4e139f |
| green | `-Dsurefire.runOrder=alphabetical -Dtest=RoleReshapeIntegrationTest,AiImportIntegrationTest#mappingRequiresTimetableEditBeyondFoundationManage+timetableCommitRequiresTimetableEditBeyondFoundationManage` | exit 0；`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`，AiImport 先跑（40.79 s）后 Role（11.44 s）（green.log / green-exit.log） | 0d4923a9ca59f735a500ca83e4c5993c8c29c821 |

基线（未改前）源 hash：`7eb5fc1be8bdcb1f09b4ee091e5bcfe4ce9b392e`。

归档口径披露：红/绿两轮只记录**当轮源文件 hash**（`git hash-object`）与退出码日志，
未生成「测试运行前后独立冻结」的 before/after 收据文件；此处不事后补造。强冻结证明由下一轮主控 full 提供。

## 边界

只修测试断言，不跑全量/前端，不 push/部署。交付前以 merge 方式合入最新 integration（32c91e9），
不改写 `final-acceptance.md` / `execution.md` / `HANDOFF.md` 三份主控文档。
