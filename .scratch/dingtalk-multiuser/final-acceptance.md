# 钉钉多用户：本地最终验收完成

2026-11-13，原规格01–08八票全部done。集成分支`codex/dingtalk-multiuser`，起点`2e5e585f6ed80d5024af6b734162a70829b067cf`；最后完整验收源`c20d1182f71ec3dc87512cc86d030d360385681c`。主控实际执行`scripts/verify.sh --full`，host退出0、`==> verify: ok`；以下是当前结论，后方失败/修复过程为保留的历史记录。

|门禁|本轮实际结果|
|---|---|
|后端Maven verify|总1265：1258通过、7跳过、0失败/错误；包含H2旧库升级5项、真实HTTP钉钉整链1项|
|前端lint / build|全部通过|
|前端单元|106文件、803项全部通过|
|仓库检查|verify中的8项后置检查全部通过|
|Playwright|155项全部通过，0失败/跳过（21.2m）|
|源码证明|HEAD前后均为上述审源，1071个受检文件before/after相同；Git对象差异0、额外0|

运行采用项目推荐的`VITEST_MAX_WORKERS=4`与`PAIBAN_MVN_EXTRA_ARGS=-DforkCount=0`，隔离端口28080/25173；直接退出码、摘要、跳过原因、源清单及原始22份日志的SHA表见[已跟踪收据](acceptance/final-full-run3/README.md)。本轮单一完整门禁通过，没有把此前分段绿色拼成整体通过。通过后收口只改票面、文档与验收收据，应用/测试源码不再变化。

|票|交付|状态|
|---|---|---|
|[01](issues/01-roles-reshape.md)|三角色、字段权限与超管保护|done|
|[02](issues/02-account-audit.md)|账号操作审计与受控查看页|done|
|[03](issues/03-dingtalk-auto-provision.md)|免登、自动配对与管理员首次绑定保护|done|
|[04](issues/04-sync-deactivation.md)|通讯录预演、排除及确认停用|done|
|[05](issues/05-substitution-approval.md)|老师申请、教务审批、期望/最终教师与状态历史|done|
|[06](issues/06-notification-narrowing.md)|通知收敛、异步发送边界与发送记录|done|
|[07](issues/07-dingtalk-settings-and-guide.md)|安全配置、连接诊断与学校图文说明|done|
|[08](issues/08-end-to-end-acceptance.md)|HTTP整链、H2升级保全、手机真实导航及全量验收|done|

双轴整特性复查与逐次delta均已处置，详见[完整复查](whole-review.md)。确认的硬问题、主控新增手机入口M1及两个旧回归断言均关闭；Spec运行冻结证据P2由本次完整退出0及源证明正式关闭。Standards原J4及unit两条P3判断建议由主控不采纳、非延期，原稿和理由保留，不伪称已代码修复。菜单对少数组合自定义角色的落点变化已说明，预置角色权限和默认本人课表不变。

7项后端跳过分别为真实AI密钥缺失2项、真实工作簿参数缺失2项、未开性能基准2项、无Docker的PostgresContainerIT 1项。H2旧库演练保全21张非空业务表的旧列逐值数据及授权有意变更，仍不证明真实PostgreSQL；学校钉钉租户、H5实机和通知实际送达尚未验收，执行清单见[学校说明书](../../../docs/runbooks/dingtalk-school-setup.md)。本分支未推送、未部署，生产发布不在本轮完成范围。

测试服务及浏览器进程已退出，四个本任务隔离端口无监听。自动审批此前拒绝临时缓存清理，仅给出`blocked by policy`，相关缓存/工作树保留，见[09](issues/09-temporary-cache-cleanup.md)；09不属于原八张功能票，未伪称已清理。

## 历史过程（原始记录保留）

下方为各阶段写作时点，当前状态以本页顶部结论与最后完整门禁收据为准。

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

## 第二轮完整门禁：后端通过，前端旧路由断言失败

实际审源777565898cb275cad9c05eb2341804372dacfdff；主控host退出1，verify.exit.txt=EXIT=1，SOURCE_IDENTICAL=1。1071个受检文件的Git对象与该提交完全相同，source-object-proof.txt中差异0/额外0。日志logs/final-full-run2；没有覆盖第一轮。

后端1265项，0失败/0错误/7跳过，即1258通过，BUILD SUCCESS；RoleReshape四项在全套上下文通过，后端源冻结证据已补强，整体放行仍须最终full绿色。前端lint/build通过；单元106文件中105通过/1失败，803项中802通过/1失败。唯一productTrim.spec.ts:92的设置子路由精确旧列表漏掉账号审计account-audit，这是票02新增路由的契约测试遗漏，不应删实际路由来迎合断言。后置仓库检查/Playwright尚未执行，不借此宣布前端或完整门禁通过。

唯一worker只修这一unit文件，保留精确路由集合并新增audit:view:account权限断言，产品与后端不改。定向/小delta补核后，先独立运行此前被阻断的后置检查和全部浏览器测试，集中消化尚未覆盖的旧回归；最后再串行完整verify。全量成功前原八票状态不done，未push/部署。

## 第二轮unit修正已整合，进入尚未执行的验证

候选d9b0a5b与接收8d924e0；只修productTrim.spec.ts，保留完整路由集合精确断言并补账号审计name/权限，不改产品/后端。独立merger核3份日志复制SHA一致，20项定向通过；source-hashes编辑对照的限制已校正交付说明。两轴补核与主控处置见whole-review.md：无待施工硬问题，Standards两P3建议不采纳，Spec原验证P2仍待实际冻结重跑。

主控remaining-guards-run1实际完成verify中的8项后置仓库检查，全部EXIT=0/SOURCE_IDENTICAL=1；1071个受检对象与95e4f88匹配。此为独立静态检查轮，不是完整verify或浏览器通过。后续统一runner在新集成源串行targeted20与全E2E，浏览器使用28518/25518；之后才最终完整verify，源不再施工时才统一done。

## 剩余验证预检全部通过（冻结源1a1396b）

主控实际执行remaining-e2e-run1，先集成定向3文件20项通过/直接EXIT=0，再全Playwright 155项全部通过、0跳过（21.3m）/直接EXIT=0。整个runner host退出0，preflight.exit=0，SOURCE_IDENTICAL=1，HEAD前后同为1a1396b88e04bccdd125603189289b280f55b832。

1071个受检源对象与该提交逐一一致，差异0/额外0，source-object-proof.txt=RUN_SOURCE_EQUALS_COMMIT=1。15份原始日志/退出/清单/doctor记录已有SHA256SUMS.txt；它提供真实运行前后的全源冻结证据，覆盖上轮worker仅记录编辑基线对照的限制。独立静态检查remaining-guards-run1的8项也均通过；不能将这些分段预检包装成完整verify已通过。

28518/25518监听0，integration标记Java/Node/Chrome/headless进程0，预检进程全部结束。所有源/测试不再施工，收口说明提交后只运行最后完整verify（final-full-run3）；P2验证证据已补齐，按最终放行约定待完整闸门EXIT=0后正式关项。通过后只允许文档收口。
