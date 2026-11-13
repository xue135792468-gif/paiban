# Standards 轴增量复核：unit-fix（raw，≤10 行）
基点：base 95e4f88...candidate d9b0a5b2（`git diff --stat` 仅 frontend/src/router/productTrim.spec.ts + .scratch/dingtalk-multiuser/final-unit-fix-delivery.md；产品 router 源未改，diff 中无 router hunk）。
断言对照（精确度净增强，未削弱）：settings 由 3 项 toEqual 精确列表 → 4 项 toEqual 精确列表（general/users/account-audit/recycle-bin），新增 `settings[2].name` 与 `settings[2].meta?.permission === "audit:view:account"` 精确断言；同文件其余 workbench/data/substitution/legacy 精确断言逐字未动；无 toEqual→toContain、无快照放宽、无 skip/only、无新增 timeout 或 toPass。
规范核对（CODING_STANDARDS 测试条）：顺序独立✔（本用例不依赖执行序）；无外网/fake 需求✔；E2E 替代问题不适用（纯 router 单测）；迁移/审计双写/事务/权限常量/前端 SFC 拆分各条不适用于本增量；未触代码风格与空白检查条。
Hard：0（无硬违规）。
Judgement：P3×2 ——（a）`settings[2]` 位置索引耦合顺序，虽上一行 toEqual 已钉死顺序，仍建议 find(r=>r.path==='account-audit') 自解释，可留待后续；（b）注释/交付文档重复票号语义，属可接受文档重复。
Fowler 基线：无 Duplicated Code / Mysterious Name / Speculative Generality / Middle Man 命中（新增仅 3 行断言，无抽象）；既有 `mainChildren[6]` 索引为存量，不计增量。
未验边界：20 项定向 pass、803→全量绿、source-hash bec5→714 为编辑前后对照（非运行冻结）均由主控 runner 合入后统一复核；真实 router 定义与权限常量未读（超出本次只读范围）；未运行任何构建/测试/网络。
结论：增量仅测试精度修正，可合入集成；无阻断项；原 J4 P3「不采纳」整体结论不重开。
