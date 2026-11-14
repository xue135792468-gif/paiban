# 票 05 Standards 轴增量复查

固定范围：`6611b3c4b0e2ea91478c9cd227fe31ad102b545e...849a3856fd125391afe72679d26b1ea221e02b8c`。原报告及原始计数 P1=1、P2=3、P3=2 保持不变。

## 原发现关闭核对

- **P1 已关闭**：`frontend/e2e/substitution-approval.spec.ts:49-56` 已传表单项 Locator；首项下拉集中在 `frontend/e2e/helpers.ts:369-377`。run3 四条受影响 E2E 全部通过、exit 0、源码同一。
- **P2 已关闭**：`SubstitutionMapper.java:74-82` 仅旧 `EFFECTIVE` 才查生效行，测试见 `SubstitutionApprovalIntegrationTest.java:216`。
- **P2 已关闭**：reject/withdraw 在 `SubstitutionApprovalService.java:197-200,240-243` 已按截止转 `EXPIRED`；HTTP 回归见同名测试类 `:294,:358`。
- **P2 已关闭**：`types.ts:166` 已映射为“系统撤销”，组件与纯函数断言已同步。
- **P3 已关闭**：授权在请求锁后刷新当前账号，见 `SubstitutionApprovalService.java:113-114,194-195,234-235,337-343`；AuthSnapshot 已由红转绿。
- **P3 已关闭**：`SubstitutionMineTable.vue:109-119` 按 ID 判改人，异 ID 同名时附档案号；公开 DOM 回归通过。

证据：后端四类 23/0/0 绿；前端定向 17 例绿；run3 4/4 Playwright 绿。

## 新增问题

硬违反：**P1=0，P2=0，P3=0**。

- **P3 判断项／possible smell，不计硬违反**：`backend/src/main/java/com/paiban/substitution/service/SubstitutionApprovalService.java:197-200,240-243`。触发：未来调整“课后自动作废”的判定、文案或副作用时，需要同时改 reject/withdraw 两处；漏改会让两条入口状态漂移。依据 Fowler **Duplicated Code**。最小修正：抽一个私有 `expireIfOverdue(request, username)`，两入口共用。

## Fowler 基线

Mysterious Name 0；Feature Envy 0；Data Clumps 0；Primitive Obsession 0；Repeated Switches 0；Shotgun Surgery 0；Divergent Change 0；Speculative Generality 0；Message Chains 0；Middle Man 0；Refused Bequest 0。Duplicated Code 仅上述 P3 判断项。

## 限界

已实读固定增量、聚合处置、相关生产/测试/E2E、中央红绿日志及 run3；未读另轴 raw 报告，未测试、构建、联网、启动服务或修改其他文件。真实 PostgreSQL、企业钉钉及整票全量门禁仍未由本轴验证。
