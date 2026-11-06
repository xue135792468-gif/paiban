# 08: 从以前的学期复制规则

**What to build:** 规则页提供「从以前的学期复制规则」，可选任一往期学期，整套替换当前学期规则（替换前确认）。老师和课程直接对应；时间组按名字对应本学期同名组；节次按「星期 + 第几节」对应本学期作息。对应不上的规则（老师已停用或删除、节次不存在、没有同名时间组）照样复制，但先关闭，并单独列在「本学期用不上」里，由用户改或删。

**Blocked by:** 06

**Status:** ready-for-agent

- [ ] 可选任一往期学期作为来源；替换前有确认
- [x] 可对应的规则原样复制并保持原开关状态
- [ ] 老师已删、节次不存在、无同名时间组三种情况：规则复制但停用，并列入「本学期用不上」
- [x] 不复制分组（分组本就共享）；不影响现有「学期复制」功能
- [x] 复制后草稿校验按新规则生效
- [x] 集成测试覆盖上述四种情况

## 实现说明（08a 后端部分完成）

- **接口**：`POST /api/constraints/copy-rules`，body `{sourceSemesterId, targetSemesterId, baseVersion}`，权限 `CONSTRAINT_MANAGE`；`ConstraintController` 只做委托，逻辑在新 `ConstraintCopyService`。
- **整套替换**：只处理非系统规则（`TEACHER_CONFLICT` / `CLASS_CONFLICT` 既不复制也不删除）。目标学期现有非系统 `constraint_config` / `constraint_entry` 硬删除后写入源规则；写完后 `advanceVersion`、`clearWorkspaceDraft`、写 `ConstraintAudit`（decisions 空），返回 `{copiedCards, disabledCards, issues:[{type,typeName,groupKey,reason}]}`。
- **卡片语义**：源 `constraint_entry.group_key` 相同的行归一张卡，无 `group_key` 的行各自一张卡；单开关类型（无 entries 的配置行）记一张无句子的卡；entry-backed 类型没有存活行视为「未配置」，不产生卡。复制后每张卡生成新 `copy-<uuid>:<index>`，卡内所有行共用；`copiedCards` 计卡片数，`disabledCards` 计因映射失败而停用的卡。
- **映射**：
  - `teacherId` / `courseId`：全局 id 不变；不存在/已删除 → 卡停用，原因「教师已删除 / 课程已删除：#id」；`active=false` → 「教师已停用：名 / 课程已停用：名」。
  - `teacherGroupId` / `courseGroupId`：全局 id 不变；不存在/已删除/类型不符 → 卡停用，原因「分组已删除」或「分组类型不匹配」。
  - `timeGroupId`：按源组名找目标学期同名且存活的 `TIME` 组，换成目标组 id；找不到 → 卡停用，原因「本学期没有时间组：名」，并清掉该行 `timeGroupId`，避免误指向目标学期同 id 的组。
  - `dayOfWeek` + `periodNo`：目标作息没有该坐标 → 卡停用，原因「星期X第Y节在本学期作息中不存在」，参数保留；只有星期的固定无课日按「该星期是否有任何节次」判断。
  - 其余参数原样；config 的 weight/hard/enabled 与行的 hard/weight 原样，行 `enabled = 源开关 && 卡片可用`。
- **`invalidReferences` 补节次原因**：`ConstraintConfigService` 的 `ReferenceIndex` 增加当前学期作息槽位集合，`invalidReferencesOf` 在行没有 `timeGroupId` 时检查 `dayOfWeek + periodNo`（或仅 `dayOfWeek`），返回「星期X第Y节在本学期作息中不存在」/「星期X在本学期作息中不存在」。正常保存仍先由 `validateTimetableConditions` 拒绝，不受影响。
- **版本冲突**：`baseVersion` 非空且与目标当前版本不一致 → 409「目标学期规则已被修改…请刷新页面后重试」，不写任何行；源/目标相同 400，学期不存在/已删 404。
- **测试**：新增 `CopyRulesIntegrationTest` 9 条：正常整套复制并替换旧规则、教师已删除、目标少一节、无同名时间组、同名时间组换 id、一行失败整卡停用、过期 `baseVersion` 409 且目标不变、系统底线保留（目标自己停用的 `TEACHER_CONFLICT` 不被源覆盖）、审计落库。与既有三个规则测试共 35 条通过；另跑相邻 5 个约束测试类 44 条通过；`scripts/check-migrations.sh` 通过（57 个版本唯一，本票无迁移）。
- **已知边界**：`classId` 不在契约 §6 的映射表内，按原 id 复制；跨学期班级会由 `invalidReferences` 显示「行政班属于其他学期」，但不会因此停用卡片（契约未列此失败项，留给 08b 页面按 `invalidReferences` 提示）。
- **剩余（08b 前端）**：规则页入口与替换确认、来源学期选择、「本学期用不上」列表（按 `disabledCards` / `issues` 展示并允许逐张启用或删除）、E2E 主路径。

