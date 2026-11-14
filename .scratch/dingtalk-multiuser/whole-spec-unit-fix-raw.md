# 独立复查 · Spec 轴小 delta（productTrim.spec.ts 路由列表）
- 基点 95e4f88eb99e523181e4ac8f04739cf330a9aa4e → 候选 d9b0a5b208f5aa1f0b2b2140635c87f57d97743d；`git diff base...candidate` 仅 frontend/src/router/productTrim.spec.ts + .scratch/dingtalk-multiuser/final-unit-fix-delivery.md，产品 router 源码未动。
- 规格对齐：spec:105「新增权限点账号审计查看，只给超级管理员」、spec:153「审计查看页只有持该权限的人能打开」——settings 下缺 account-audit 属测试快照过期，补列表是纠偏，非放宽。
- 实现对齐：router/index.ts:205-211 name=account-audit、meta.permission=audit:view:account，位于 users 之后、recycle-bin 之前 → settings[2] 指向正确；guard.ts:19-20 按 to.meta.permission 调 hasPermission，断言 meta 等同 route guard 入参，未以「UI 是否可见」替代权限语义。
- 逐项强断言：settings 路径精确 toEqual 四项（原 general/users/recycle-bin 保留且卡顺序）；新增 name 与 meta.permission 两处精确断言；其余断言逐字未改。
- 缺失/越界：本 delta 无。未新增权限判定点、未改产品代码、未触碰其它快照；roleAccess/navigation 语义由各自 spec 覆盖。
- 计数：P0 0 / P1 0 / P2 1 / P3 0。P2 为证据边界：source before bec5ad3 → after 714aa18 仅为编辑对照，非 runtime 冻结；targeted 20 pass / exit 0 依赖其日志，完整冻结由主控统一 runner（targeted20 → E2E 全套）产出（沿用上轮 sourceRole P2，不重开原 P3/J4）。
- 未验证：本 delta 未复跑测试/构建/网络；full 侧 1265 pass 7 skip 与 lint/build 为主控转述，未独立复核；E2E 全套与 803 项 Vitest 全绿仍待统一 runner。
- 结论：断言收紧后与规格和 router 实现一致，无覆盖净损失，可接受进入统一冻结复跑。
