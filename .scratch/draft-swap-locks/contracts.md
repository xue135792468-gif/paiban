# 跨票据接口与不变量

Status: accepted
日期：2026-11-02
这是主模型依据已确认规格制定的实现边界；传输字段可作兼容性细化，但不得让并行票分别定义相冲突语义。新增URL在相应后端票实现后写回实际契约，不能提前让前端猜路由。

## 1. 授课身份与锁定（01提供，07消费）

- 数据库DraftLesson已经有assignmentId、lessonIndex；草稿内组合唯一性应验证。跨草稿映射使用二者及来源授课内容签名，不使用draft_lesson.id，也不只用班级/课程/教师三元组给同科多节任意排号。
- 身份是授课任务中的具体一节；现有显式跨班换人可能改变课的当前教师，映射应保留当前草稿的教师并校验其与任务/资格的可解释关系，不能丢掉已确认的手工调整。若来源已无法唯一匹配任务，阻止并解释。
- 详情每课至少包含locked布尔值；读旧数据默认false。锁定同时固定periodId与teacherId；不承诺锁定教室，但后续变化仍受固定/专用教室硬规则约束。
- 批量锁定输入为明确lessonIds和目标locked值，携带源快照/版本；服务端校验归属、去重和实际数量。前端负责展开已选/当天/全班，但后端不能信任范围/数量声明。
- 01定义全体草稿写入口共同采用的事务互斥/乐观版本协议，02/04/07沿用。规则、设置和任务的变化也必须进入一致性检查，不只是draft行版本。

## 2. 整单变更与逆操作（02提供，04/05消费）

- 一次变化是一组lessonId及预期来源安排、目标periodId/teacherId/必要roomId，服务器重读来源并构造after快照。expected字段只用于检测过期，不是授权。
- 直接交换仍兼容现有POST /api/timetable-drafts/{id}/swap；单课移动保持既有PATCH语义。新增整单入口/客户端不得绕过同一锁定与事务保护。
- saved只在整个事务成功后成立；新硬违规列表、剩余旧问题、软偏好变化分开返回。不要沿用“换一次课成功就把整稿hard分写0”的捷径。
- 旧冲突比较需稳定违规身份与严重程度；规则message变化和aggregate违规表达不能被简单总数差掩盖。规则集已变化时重新校验，不把跨版本违规直接抵消。
- 逆操作携带整单before/after和源快照，仍走相同验证；05只在保存成功后入撤销栈，联动一单为一步，不恢复过期或他人后续状态。

## 3. 共用策略（03提供，04消费）

- maxMovedLessons读取旧substitution.max-plan-teachers，范围2–10默认6；保留旧key/客户端兼容。rankingPreset读取旧substitution.ranking-preset。
- 最小影响：影响天数→同周→去重教师数→软损失→环长；最少教师：去重人数→影响天数→同周→软损失→环长；最稳课表：软损失→影响天数→同周→去重人数→环长。保留既有稳定tie-break。
- 草稿影响天数按星期去重；同周条件恒同；软损失使用统一规则与权重。草稿最大搜索范围始终是本周的有效作息，日期窗口不适用。
- 策略快照带可比较指纹；03不得让设置值分成草稿一份、调代一份；同名共享策略不能导入substitution服务造成模块反向依赖。

## 4. 搜索、方案与应用（04提供，05消费）

- 搜索输入：draftId、sourceLessonId、targetLessonId及版本/指纹。锁定/跨班/歧义属于不可直接执行的输入，不通过搜索自动绕开。
- 方案输入输出均以明确课节身份表示；输出最多三个候选，每个有完整moves、影响课节数/教师数/天数、softPenaltyDelta、来源指纹和可应用凭证。
- 结果区分已完成搜索有候选、已完成但当前范围无候选、搜索预算耗尽；预算耗尽可有部分候选。不要仅以空列表表达所有失败。
- 直接合法交换不等待完整搜索；直接失败后自动搜索，不要求用户点击额外入口。候选必须让来源课到达指定落点且保持全班授课集合。
- 应用输入为服务器能验证的方案/凭证与源状态，不信任浏览器随意拼moves；方案在事务一致性保护内独立复核，任何过期/锁变化/新规则冲突零部分写入。
- 同一应用因网络重试不能发生二次交换；方案凭证一次性或幂等，旧凭证不能复用为反向交换。
- 方案预览不产生业务写入；取消无副作用。过期、无可用方案、未搜完与规则冲突分别反馈，前端不可把缺少候选当作已交换。

### 4.1 已实现契约（04 落地）

- 搜索：`POST /api/timetable-drafts/{id}/cycle-plans/search`，权限 `timetable:edit`。请求 `{"sourceLessonId","targetPeriodId","targetLessonId","expectedVersion","rulesFingerprint","nodeBudget","timeBudgetMs"}`；两个预算为空时用默认 200000 节点 / 1500 毫秒。响应 `{"status","message","version","fingerprint","plans"}`，`status` 为 `FOUND` / `NONE` / `BUDGET_EXHAUSTED`；`plans` 最多 3 个，每个含 `token`、`kind`（`DIRECT`/`CYCLE`）、`moves[{lessonId,fromPeriodId,toPeriodId,teacherId,courseId,classId}]`、`movedLessons`、`affectedTeachers`、`affectedDays`、`softPenaltyAfter`、`softPenaltyDelta`、`expiresAt`。搜索不改 `draft_lesson`、不改锁定、不 bump `version`，只写 token 行。
- 应用：`POST /api/timetable-drafts/{id}/cycle-plans/apply`，权限 `timetable:edit`。请求 `{"token","expectedVersion"}`；响应 `{saved,valid,version,hardConflicts,softPenaltyBefore,softPenaltyAfter,softPenaltyDelta,addedConflicts,remainingConflicts,conflicts,tokenUsed}`。
- 凭证：服务端一次性 token，有效期 **10 分钟**；`usedAt` 非空后再次应用返回 409。token 绑定 draft 版本、规则指纹、共享策略指纹、全部草稿课节（含锁定）、学期授课任务、来源/落点。
- 错误码：来源/落点/中间课锁定、跨班落点、同格多课、`targetLessonId` 不符 → 409；来源不存在或 token 不存在 → 404；未排课/落点不在本学期间 → 400；已发布/归档 → 400；权限不足 → 403；过期、已应用、课表/规则/设置变化 → 409。以上拒绝均零写入。

## 5. 指定草稿继续优化（07提供，08消费）

- 旧启动请求省略来源时保持既有策略；新增显式sourceDraftId模式，源学期、任务、锁定与授课身份快照共同参与输入。
- 显式来源不回退；current period/teacher、锁标记与未锁初始安排均来自用户选中稿，不是最近/最早/已发布稿。
- 求解pin必须实际阻止移动锁课，教师也不得被重新生成覆盖。新draftId/lesson行ID不同，继承锁的依据是稳定身份。
- 返回原draftId、新draftId（若产生）、任务状态、实际来源版本及合规/阻碍，不以任务结束等价于可发布。
- 源安排在快照后被改变时，要么使用已完整固化并标识的快照，要么拒绝过期请求；不得混合不同时刻数据。锁定冲突、全锁无自由度及不完整任务匹配均有明确反馈。

### 5.1 已实现契约（07 落地）

- 启动：`POST /api/scheduling-jobs?semesterId=`（权限 `scheduling:run`）。请求体新增可空 `sourceDraftId`；省略时走原有默认来源（`BASELINE` / 最早完整 `DRAFT` / `NONE`），语义不变。显式来源时 `warmStartSource=SOURCE_DRAFT`，不会回退到基线或别的草稿。
- 快照：启动时校验来源属于当前学期、状态为 `DRAFT`、有课节、授课任务映射完整（每个学期任务恰好 `weeklyLessons` 行、`lessonIndex` 覆盖 `0..n-1`、班级/课程与任务一致、教师存在），并把 `(assignmentId, lessonIndex)`、当前教师、时段、锁定与任务指纹冻结在 job 上；运行期间只读冻结快照。
- 锁定：锁定课以求解硬约束保持原 `periodId`，教师取来源草稿当前教师（保留手工跨班换人），新稿按稳定身份继承 `locked` 标记；原草稿及其 `version` 不变，新稿另建、行 id 全新。全锁返回 409。
- 状态：来源/学期/已发布/无课节/映射不一致/未知引用 → 400（全锁 → 409），且不创建 job。运行中任务变更（指纹不符）、快照损坏或锁无法保持 → job `FAILED`、零新稿。求解完成但未达发布标准 → job `COMPLETED` 且 `resultPublishable=false`、`resultBlockers` 列出原因，不宣称可发布。
- `JobDto` 末尾新增 `sourceDraftId`、`sourceDraftVersion`、`inheritedLockCount`、`resultPublishable`、`resultBlockers`（后者按换行拆分，无则空列表）。旧字段与顺序不变。

## 6. 验证边界

- 01/02/03/04/07后端定向验收，05/06/08各自交互E2E；09最后冻结整合版本全量验收。
- 搜索、独立复核和发布共享基础规则定义，但独立复核不复用搜索剪枝作正确性保证。单次变更允许修旧错，整稿发布必须绝对合格。
- 主模型亲自作算法合格判断。辅助审查只核对事实/遗漏，不授权外部模型修改已确认产品决定。
