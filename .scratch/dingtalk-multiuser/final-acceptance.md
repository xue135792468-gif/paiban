# 最终完整门禁（待修复后重跑）

本地集成分支codex/dingtalk-multiuser，起点2e5e585f6ed80d5024af6b734162a70829b067cf。01–07专项及双轴已放行，08专项（后端整链、H2旧库升级、手机真实菜单到审批整链）完成。最后源码和评审证据见whole-review.md、t08-main-acceptance.md；尚未统一done、未push/部署。

## 首轮完整门禁：失败，保留原始记录

实际审源d0309daf18ba62aee181d8e6e05200704ecfbfaf；主控直接执行verify.sh --full，VITEST_MAX_WORKERS=4、PAIBAN_MVN_EXTRA_ARGS=-DforkCount=0，日志logs/final-full-run1。host退出1，verify.exit.txt=EXIT=1，source-stability.txt=SOURCE_IDENTICAL=1。后端1265项，1失败/0错误/7跳过，24:40min；即1257通过。Maven verify失败后set -e退出，后续前端lint/build/unit、仓库级检查、Playwright均未在本轮执行。不能把此前定向绿色充当本轮前端通过。

唯一失败RoleReshapeIntegrationTest.presetRolesAreSuperAdminSchoolAdminAndTeacher，把整个角色目录精确当成super_admin/admin/teacher三项；实际还存在AiImportIntegrationTest两项权限用例自建的foundation_only_*自定义角色。DatabaseResetExtension有意保留身份六表，spec/V60保护自定义角色，失败属于新增测试的错误目录范围。保留原始失败TXT/XML在本轮日志目录，不改产品角色/迁移/全局reset来迎合测试。

唯一修复worker在dingtalk-final-gate-fix隔离工作树修同一测试：显式自建自定义角色使红例独立复现；随后精确检查预置集合与名字、全目录无head_teacher、自定义角色可见。禁止按自定义显示名推断退休角色。修后定向绿、两轴小delta、合入和说明落盘后，才允许新的完整门禁；新运行单独目录，不覆盖首轮。首轮失败证明原计划“只需一次”的前提不成立，重跑由此真实失败触发。

## 未验证边界

本机无Docker，真实PostgresContainerIT未跑；H2升级不替代真实PG。Fake/离线手机视口不证明学校租户、实机或通知实际送达；学校手册另列真实验收。自动审批拒绝缓存删除仅返回blocked by policy，09与相关工作树/缓存保留，不换手段删除。

## 首轮失败的有界修复与整合

唯一修复候选97cade5427433b407730d343ad476ad2eef16b0c已FF整合；接收单97948360763497a145d8442bfb65d2130665030a。相对32c91e9仅1测试源与交付文档，产品/迁移/reset/其他测试不变。测试blob为0d4923a9ca59f735a500ca83e4c5993c8c29c821，与green时源hash一致。

独立输入red：只自种legacy_custom并保留旧目录断言，1项失败、direct exit1；修正后green：AiImport权限2项在先、RoleReshape4项在后，共6项通过、direct exit0。用例保留预置code精确三项/三name、整目录无head_teacher、合法自定义code/name HTTP可见。只按code判退休角色，不限制任何自定义显示名。四份原始日志复制后SHA256一致。每轮只有源hash与directexit，没有独立运行before/after冻结收据，此限制不被后补文件掩盖；下一完整门禁另产生全源冻结证明。

Standards小delta新Hard0/Judgement0，原J4 P3不采纳不变；Spec小delta尚待最终补核。定向绿与整合不代表full已通过。首轮18份日志/退出/源清单/报告已有SHA256SUMS.txt；7项跳过的XML与原因见full-run1-extraction.md。

### Spec补核后的放行条件

替代轴确认断言纠偏、不弱化，原始P2=1（验证收据缺口）、P3=2（累积/null假设）见whole-review.md原文和主控逐项处置。P2保持待验证，下一冻结完整门禁的直接exit及source-before/after相同才能关闭；两条P3前提已核，非施工项。没有待修产品/规格代码缺陷，可进入用于关闭验证缺口的完整重跑，但通过前不done、不发布。

原Spec轴更正已到：两条P3撤回为非缺陷，最新计数P2=1/P3=0，仅验证缺口待下一完整门禁关闭；原始报告和更正均保留，见whole-review.md。全部整改与评审说明已落盘，进入新的冻结完整门禁，不复写首轮失败。
