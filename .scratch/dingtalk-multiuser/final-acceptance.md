# 最终完整门禁（待修复后重跑）

本地集成分支codex/dingtalk-multiuser，起点2e5e585f6ed80d5024af6b734162a70829b067cf。01–07专项及双轴已放行，08专项（后端整链、H2旧库升级、手机真实菜单到审批整链）完成。最后源码和评审证据见whole-review.md、t08-main-acceptance.md；尚未统一done、未push/部署。

## 首轮完整门禁：失败，保留原始记录

实际审源d0309daf18ba62aee181d8e6e05200704ecfbfaf；主控直接执行verify.sh --full，VITEST_MAX_WORKERS=4、PAIBAN_MVN_EXTRA_ARGS=-DforkCount=0，日志logs/final-full-run1。host退出1，verify.exit.txt=EXIT=1，source-stability.txt=SOURCE_IDENTICAL=1。后端1265项，1失败/0错误/7跳过，24:40min；即1257通过。Maven verify失败后set -e退出，后续前端lint/build/unit、仓库级检查、Playwright均未在本轮执行。不能把此前定向绿色充当本轮前端通过。

唯一失败RoleReshapeIntegrationTest.presetRolesAreSuperAdminSchoolAdminAndTeacher，把整个角色目录精确当成super_admin/admin/teacher三项；实际还存在AiImportIntegrationTest两项权限用例自建的foundation_only_*自定义角色。DatabaseResetExtension有意保留身份六表，spec/V60保护自定义角色，失败属于新增测试的错误目录范围。保留原始失败TXT/XML在本轮日志目录，不改产品角色/迁移/全局reset来迎合测试。

唯一修复worker在dingtalk-final-gate-fix隔离工作树修同一测试：显式自建自定义角色使红例独立复现；随后精确检查预置集合与名字、全目录无head_teacher、自定义角色可见。禁止按自定义显示名推断退休角色。修后定向绿、两轴小delta、合入和说明落盘后，才允许新的完整门禁；新运行单独目录，不覆盖首轮。首轮失败证明原计划“只需一次”的前提不成立，重跑由此真实失败触发。

## 未验证边界

本机无Docker，真实PostgresContainerIT未跑；H2升级不替代真实PG。Fake/离线手机视口不证明学校租户、实机或通知实际送达；学校手册另列真实验收。自动审批拒绝缓存删除仅返回blocked by policy，09与相关工作树/缓存保留，不换手段删除。
