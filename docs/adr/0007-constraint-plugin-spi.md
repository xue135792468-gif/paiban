# 排课约束分三层：预置约束库 + 插件 SPI + （二期可选）规则 DSL

**第一层（一期交付）**：预置约束库 20-30 条常见约束（如"体育不在下午第一节""同一教师一天最多 N 节""某教师周三下午禁排"），教务在 UI 上勾选启用、填参数、调权重。

**第二层（一期预留）**：自定义约束插件 SPI——Java `ServiceLoader` 机制，任何实现 `CustomConstraint` 接口的 jar 放入 `plugins/` 目录即被自动加载，UI 上显示为可配置的新约束。

**第三层（二期可选）**：在 SPI 基础上包一层表达式 DSL（MVEL 或 Aviator），让非 Java 开发者也能写简单规则。

## 为什么

- Timefold Constraint Streams 是 Java API，最自然的扩展点就是让插件直接写 Java 代码参与约束评分
- 预置约束覆盖中学教务 90% 场景；插件 SPI 覆盖剩余 10%（学校特殊规则）
- DSL 成本最高，放二期视真实需求决定是否做

## Consequences

- `CustomConstraint` 接口要在一期就冻结公开签名（约束名、参数 schema、评分函数），避免二期插件 breakage
- 插件的沙箱隔离与安全性（恶意 jar 风险）在学校内网部署场景下不严峻，一期不做 ClassLoader 隔离，二期视需要再加
