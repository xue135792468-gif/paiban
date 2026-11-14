# 复核修正案 · 两条 P3 前提独立核对（原 raw 保留不改）
- 实读依据：V2__identity.sql:11-15 `code VARCHAR(64) NOT NULL UNIQUE`；Role.java:30 `@Column(nullable = false, unique = true, length = 64)`；application.yml:105 `jdbc:h2:mem:paiban_test...DB_CLOSE_DELAY=-1`、:111 `ddl-auto: validate`；DatabaseResetExtension.java:23/29 明确保留 role 等身份表。
- P3-1「跨轮累积」→ 撤回跨轮部分：内存库随 Maven fork 退出即销毁，残留只可能存在于同一轮同 JVM 的类之间，不可能跨 Maven 轮次叠加。
- P3-1 限缩保留：用例自种 legacy_custom_<suffix> 未清理，仅可能影响同一 full 轮中后跑的身份/角色用例；由新 full 实跑定性，不预先断言为产品缺陷。
- reset 保留身份表是本次 given（目录含自定义角色正是被测输入，旧 AiImport 基础导入已种 custom），非测试卫生缺陷。
- P3-2「null code 触发 NPE」→ 撤回为缺陷：NOT NULL UNIQUE + nullable=false 下真实持久化→HTTP 目录不可达；若将来破坏约束，测试以异常报红而非漏绿，属 fail-loud。
- P3-2 限缩保留：filteredOn(Set.of(..)::contains) 遇 null 抛 NPE 而非 assertion failure，仅影响失败可读性，无覆盖损失。
- 计数修订：P0 0 / P1 0 / P2 1（证据未冻结，待 full 产 EXIT + source before/after）/ P3 0（原两条各降为上述限缩备注）。
- 未复核边界：未跑任何测试/构建；未读 RoleUpgradeDataPreservationTest、e2e 与全 repo，本修正仅基于所列文件静态核对。
