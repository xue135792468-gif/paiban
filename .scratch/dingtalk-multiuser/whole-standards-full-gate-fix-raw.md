# Standards 全量门禁修复 delta 复核（32c91e9…97cade5，只读）

- 基点 `d0309daf18ba62aee181d8e6e05200704ecfbfaf`（首轮 full 1265/1 fail/0 error/7 skip，EXIT1，SOURCE_IDENTICAL1）；固定候选 `97cade5427433b407730d343ad476ad2eef16b0c`；交付 diff `32c91e9…97cade5` 仅 2 文件（`RoleReshapeIntegrationTest.java` + `final-gate-fix-delivery.md`，66+/5−）。测试 blob 实测 `0d4923a9ca59f735a500ca83e4c5993c8c29c821`，与此前 9eb8ff5 审查源逐字相同（9eb 被 amend，不视为额外改动）。只读 `git show`/`rev-parse`，未 checkout、未改源。
- **Hard：新增 0（P1/P2/P3 全 0）。** 无产品/迁移/配置改动；V60、`DatabaseResetExtension` 保留身份表的行为均不变，仅测试口径修正。
- **Judgement：新增 0（P1/P2/P3 全 0）。** 未引入新 smell；`containsInAnyOrder` 换 `hasItem/not` 有实际用途（旧导入已移除），无遗留未用导入。
- 口径核对（无弱化）：预置三 code 用 `filteredOn(PRESET_ROLE_CODES::contains).containsExactlyInAnyOrder("super_admin","admin","teacher")`，既是精确集合又能卡住重复；三个 name 仍 `contains("超级管理员"/"教务管理员"/"老师")`；全目录 `code` `not(hasItem("head_teacher"))`；自定义角色 code/name 经真实 HTTP 断言可见，未禁止自定义显示名；未用 `hasItems` 代替严格集合判断，也未把「三身份」扩成「只许三行」。
- 独立复现：用例内 `roleRepository.save(new Role("legacy_custom_"+TestData.suffix(), ...))` 自建输入，不依赖其它测试类顺序或残留（符合《测试》「不依赖执行顺序/共享可变全局设置」），旧断言在此输入下确定性失败。
- **证据边界**：R6 报告 red 1 fail（自建 legacy_custom 触发旧断言）、green 6 pass（AiImport 2 先跑 + Role 4）均为交付方日志 `logs/final-gate-fix`，本轴未复跑；源 before/after 冻结未取得独立收据，不作冻结主张。full 仍未跑、真实 PG/钉钉租户/手机未验证，不宣称全量通过。
- 原轴计数不变：整体 Hard P1 0/P2 0/P3 0（H1 已关）；Judgement P3=1，仅 **J4**（主控不采纳、非延期、未修复）。原 Spec 菜单 P3 说明不重开。
