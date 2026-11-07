# 08: 从以前的学期复制规则

**What to build:** 规则页提供「从以前的学期复制规则」，可选任一往期学期，整套替换当前学期规则（替换前确认）。老师和课程直接对应；时间组按名字对应本学期同名组；节次按「星期 + 第几节」对应本学期作息。对应不上的规则（老师已停用或删除、节次不存在、没有同名时间组）照样复制，但先关闭，并单独列在「本学期用不上」里，由用户改或删。

**Blocked by:** 06

**Status:** done

- [x] 可选任一往期学期作为来源；替换前有确认
- [x] 可对应的规则原样复制并保持原开关状态
- [x] 老师已删、节次不存在、无同名时间组三种情况：规则复制但停用，并列入「本学期用不上」
- [x] 不复制分组（分组本就共享）；不影响现有「学期复制」功能
- [x] 复制后草稿校验按新规则生效
- [x] 集成测试覆盖上述四种情况

## 实现说明（08a 后端部分完成）

- **接口**：`POST /api/constraints/copy-rules`，body `{sourceSemesterId, targetSemesterId, baseVersion}`，权限 `CONSTRAINT_MANAGE`；`ConstraintController` 只做委托，逻辑在新 `ConstraintCopyService`。
- **整套替换**：只处理非系统规则（`TEACHER_CONFLICT` / `CLASS_CONFLICT` 既不复制也不删除）。目标学期现有非系统 `constraint_config` / `constraint_entry` 硬删除后写入源规则；写完后 `advanceVersion`、`clearWorkspaceDraft`、写 `ConstraintAudit`（decisions 空），返回 `{copiedCards, disabledCards, issues:[{type,typeName,groupKey,reason}]}`。
- **卡片语义**：源 `constraint_entry.group_key` 相同的行归一张卡，无 `group_key` 的行各自一张卡；单开关类型（无 entries 的配置行）记一张无句子的卡；entry-backed 类型没有存活行视为「未配置」，不产生卡。复制后每张卡生成新 `copy-<uuid>:<index>`，卡内所有行共用；`copiedCards` 计卡片数，`disabledCards` 计因映射失败而停用的卡。
- **映射**：
  - `teacherId` / `courseId`：全局 id 不变；不存在/已删除 → 卡停用，原因「教师已删除 / 课程已删除：#id」；`active=false` → 「教师已停用：名 / 课程已停用：名」。
  - `classId`：班级属于学期，不能按 id 直接对应；目标学期没有该班 → 卡停用，原因「行政班不在本学期：#id」。
  - `teacherGroupId` / `courseGroupId`：全局 id 不变；不存在/已删除/类型不符 → 卡停用，原因「分组已删除」或「分组类型不匹配」。
  - `timeGroupId`：按源组名找目标学期同名且存活的 `TIME` 组，换成目标组 id；找不到 → 卡停用，原因「本学期没有时间组：名」，并清掉该行 `timeGroupId`，避免误指向目标学期同 id 的组。
  - `dayOfWeek` + `periodNo`：目标作息没有该坐标 → 卡停用，原因「星期X第Y节在本学期作息中不存在」，参数保留；只有星期的固定无课日按「该星期是否有任何节次」判断。
  - 其余参数原样；config 的 weight/hard/enabled 与行的 hard/weight 原样，行 `enabled = 源开关 && 卡片可用`。
- **`invalidReferences` 补节次原因**：`ConstraintConfigService` 的 `ReferenceIndex` 增加当前学期作息槽位集合，`invalidReferencesOf` 在行没有 `timeGroupId` 时检查 `dayOfWeek + periodNo`（或仅 `dayOfWeek`），返回「星期X第Y节在本学期作息中不存在」/「星期X在本学期作息中不存在」。正常保存仍先由 `validateTimetableConditions` 拒绝，不受影响。
- **版本冲突**：`baseVersion` 非空且与目标当前版本不一致 → 409「目标学期规则已被修改…请刷新页面后重试」，不写任何行；源/目标相同 400，学期不存在/已删 404。
- **测试**：新增 `CopyRulesIntegrationTest` 10 条：正常整套复制并替换旧规则、教师已删除、班级不在目标学期、目标少一节、无同名时间组、同名时间组换 id、一行失败整卡停用、过期 `baseVersion` 409 且目标不变、系统底线保留（目标自己停用的 `TEACHER_CONFLICT` 不被源覆盖）、审计落库。与既有规则/分组测试共 39 条通过；另跑相邻 5 个约束测试类 44 条通过；`scripts/check-migrations.sh` 通过（57 个版本唯一，本票无迁移）。
- **已知边界**：`invalidReferences` 现在按目标学期加载班级，复制出来的跨学期班级规则会显示「行政班已删除：#id」并停用；08b 页面按 `invalidReferences` 提示。
- **08b 前端**：已完成，见下方「实现说明（08b 前端）」；E2E 覆盖复制确认与「用不上」列表。

## 实现说明（08b 前端部分完成）

- **入口与确认**：规则页工具栏新增「复制往期规则」（`RuleToolbar` 的 `actions-extra` slot 挂 `RuleCopyButton`）。弹窗里的来源下拉排除当前编辑的学期，并给当前学期标注「（当前）」；未选来源时确认按钮禁用。确认前明确提示「当前学期的非系统规则会被删除；两条系统底线保留」。
- **调用与结果**：确认后 `POST /constraints/copy-rules`，带上页面加载时的 `baseVersion`；成功显示「已复制 N 张规则卡，其中 M 张本学期用不上」，逐条列出 `issues` 的 `typeName：reason`（最多 8 条，其余显示「等 N 条」）。失败保留弹窗，用 `serverMessage` 显示服务端原因。关闭后 `loadState()` 刷新规则、版本与分组。
- **本学期用不上**：新增 `RuleUnusablePanel`，只收录**已停用且**句子带 `invalidReferences` 或缺槽位（`missingParts`）的卡；用户主动停用的完整卡仍留在正常列表。每卡显示句子、去重后的原因，以及「去修改」「删除」，复用现有 `openEdit` / `onRemove`。主列表改用 `usableCards`，同一张卡不会两处重复。
- **拆分**：判定逻辑在 `frontend/src/rules/unusableRules.ts`，复制调用与 409 冲突记录在 `frontend/src/rules/useRuleCopy.ts`；`RuleBuilderView.vue` 保持 594 行（≤600）。
- **测试**：新增 `src/api/rules.spec.ts`、`RuleCopyButton.spec.ts`（6 条）、`RuleUnusablePanel.spec.ts`（5 条），并在 `RuleBuilderView.spec.ts` 增 2 条（用不上区与主列表不重复、复制弹窗）。全量 vitest 89 文件 686 条通过；`npm run lint`、`npm run build`、`check-sfc-size.sh`、`check-ticket-status.sh` 通过。
- **E2E**：`frontend/e2e/copy-rules.spec.ts` 建源/目标两个学期，源学期两条教师规则（其中一名教师随后删除），目标先存旧规则；页面选来源并确认后断言结果、失效原因、正常卡与用不上区、API 两行一启用一停用且旧规则消失、刷新后失效区仍在。`rule-builder` / `teacher-groups` / `course-groups` / `time-groups` / `copy-rules` 共 8 条通过。
- **与方案偏差**：`copyFromSemester` 记录 409 冲突后继续抛出异常，让弹窗用 `serverMessage` 展示服务端原文；若吞掉异常返回 `null`，弹窗只能显示通用文案。`baseVersion` 作为组件 prop 保留但组件本身不使用，真正带版本的是父级注入的 `copy`。

