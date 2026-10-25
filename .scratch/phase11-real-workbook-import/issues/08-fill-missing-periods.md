# 08: 文件里有作息表还没有的节次时，一键把缺的节次补进作息

**What to build:** 导入预览目前只在行级报「该星期没有第 N 节」，用户得自己去「学期与作息 → 作息表」手工加格子再重新解析。真实文件里节次数常常和默认作息对不上（默认上午 4 + 下午 4 = 8 节，有的学校 9 节、有晚自习）。缺的节次应当以一个**可执行入口**呈现：预览里列出「文件里出现了但作息没有的节次」，给一个按钮按现有节次的规律补齐（沿用最后一节的时长/间隔，标签用「第 N 节」），补完自动刷新预览。

**Blocked by:** None（2026-10-25 与用户讨论导入真实文件时提出）

**Status:** done（已上线 2026-10-25）

- [x] 预览能算出「文件引用但当前学期没有」的 (星期, 节次) 集合，并在页面列出来
- [x] 提供一键补齐：按现有节次的时长与间隔生成缺失节次，写入作息后自动刷新预览
- [x] 已发布课表的学期不允许改作息（沿用现有 409 语义），此时只提示不可补
- [x] 补齐是显式动作，不做「导入时自动建作息」——学期与作息仍由人工确认
- [x] 单元/集成测试：缺 3 节的文件 → 补 → 预览无阻塞项

**实现说明：**

接口（都在 `AiImportController`，会话必须是自己的或调用方有 `foundation:manage`）：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/ai/import/sessions/{id}/missing-periods` | `foundation:manage`（沿用导入预览） | 只读差集：`{semesterId, semesterName, published, items[]}`，每项 `{dayOfWeek, periodNo, status: PROPOSED/MANUAL, label, section, startTime, endTime, reason}` |
| POST | `/api/ai/import/sessions/{id}/missing-periods` | `foundation:manage` **且** `semester:manage` | 请求 `{semesterId, items:[{dayOfWeek, periodNo, startTime, endTime}]}`，返回 `{created, plan}`（plan 即刷新后的同一个差集） |

权限口径：GET 依赖控制器类级注解；POST 的方法级 `@PreAuthorize` 会覆盖类级，所以两条权限都显式写在注解里，服务层再用 `ImportPermissions.requireSemesterManage` 复验（常量取自 `Permissions.SEMESTER_MANAGE`，没有角色名字符串）。

差集来源：只用会话已保存的 `preview_json.rows`（工作簿与单目标课表预览同形），经 `SlotText.day` / `SlotText.periodNo` 提取 `(星期, 节次)`，去重后与当前学期 `period` 比较。`SlotText` 读不出的值直接跳过，原行错误保持原样（提交时仍报「无法识别星期/节次」），不猜数字。不重新解析文件、不新增模型调用。

`preview_json` 缺失或无法解析时**不返回空差集**：读接口与写接口都抛 409「预览数据不可用，请重新解析」。空差集会被当成「学期没有缺节」，是把没读到的数据说成没问题，属于静默成功。

推算规则（`PeriodGapPlanner`，纯函数、无 IO、全部 `LocalTime`）：

1. 同日有前面锚点：沿用该节时长与当日课间间隔，接在其后；
2. 同日有后面锚点而前面没有（补在当天第一节之前）：用后面锚点的时长，向前推同样的间隔；
3. 中间空档：推算结果必须整段结束于后一个锚点开始之前；
4. 同一天一个课节都没有：取其他星期同一个 `periodNo` 的起止时间；各天不一致 → 人工；
5. 缺的 `periodNo` 在所有星期都不存在 → 人工。

转人工判据（`reason` 直接展示）：没有任何可参照作息；其他星期同一节次作息不一致；其他星期课间间隔不一致；既有课节时间重叠（间隔为负）；没有可参照的课间间隔；推算结果放不进上下锚点之间；落在上午与下午（或任意两个时段）之间，跨时段边界；推算结果跨午夜（加减分钟越过 24:00）。人工项标黄，给「去作息管理」入口。

同一轮里后面的缺失节次以同轮推算出的前一节为锚点（第 10 节跟着第 9 节的推算走），一次补齐与逐个补结果一致。

幂等与过期语义（`MissingPeriodsService.apply`，单事务）：

- 学期一致：请求 `semesterId` 必须等于当前学期，否则 409「方案已过期，请刷新预览」；
- 仍未发布：`SemesterService.lockPeriodsForAppend` 先取学期行锁（`PESSIMISTIC_WRITE`）再复用发布守卫，已发布 → 409（沿用「该学期已发布课表，不能修改作息表」）；
- 锚点未变化：在锁内重跑同一个估算，请求里的 `startTime`/`endTime` 必须与重算结果逐项一致，否则 409 且一行不写；
- 已存在槽位跳过：重复确认返回 `created=0`，不重复建；
- 任何一项不可推算（`MANUAL`）都不能通过接口写入。

追加实现方式：`SemesterService.appendPeriods` 只 `periods.saveAll(新增行)`，按 `(dayOfWeek, periodNo)` 跳过已存在槽位；**不调用 `replacePeriods`、不更新、不删除**。集成测试断言补齐后既有 8 节的 id/label/startTime/endTime 与补齐前完全一致。

前端：`frontend/src/components/aiImport/MissingPeriodsPanel.vue`（+ `missingPeriods.ts` 纯格式化/加载体），只在整本工作簿预览且未入库时渲染。可推算项默认勾选、显示时段与起止时间；人工项标黄并给作息管理入口；已发布学期只显示「该学期已发布课表，作息不可补充」。确认框逐项列出「周X 第N节 时段 起–止」，确认后 POST，用响应里的 `plan` 刷新清单并 `emit('applied')` 让页面重新 GET 会话预览；只补了可靠子集时剩余差集继续可见，差集为空时面板整个消失，不显示「全部完成」。

测试：

- `PeriodGapPlannerTest`（纯函数，12 条）：中间空档、日末追加、跨日同节次参照、参照矛盾、无参照、间隔为负、跨午夜、放不进空档、跨时段边界、已存在跳过、重复槽位去重、单项失败不影响其他项。
- `MissingPeriodsIntegrationTest`（公开 REST + 受控假 AI，8 条）：缺第 9/10 节的预览返回 3 项 PROPOSED 与推算时间；确认后 `period` 追加 3 行、既有行 id/标签/时间全部不变、随后提交生成草稿；重复确认 `created=0` 且总数不变；预览与确认之间改动作息 → 409 且无写入；已发布学期 → 差集全部转人工并给出原因、写接口 409；补节 → 刷新预览全程假 AI 调用次数不变；**提交 MANUAL 项 → 409 且 `period` 一行不写**（没有任何规则能推算的槽位不许经接口写进去）；**`preview_json` 置空 → GET/POST 都 409「预览数据不可用」**（不返回空差集）。
- `cd backend && ./mvnw -B test` → Tests run: 634, Failures: 0, Errors: 0, Skipped: 5（5 条 skip 是既有默认跳过的 Testcontainers/外部服务用例）。
- 前端：`MissingPeriodsPanel.spec.ts`（6 条）：列表与勾选、确认提交、已发布只提示、人工项入口，以及**只补可靠子集后剩余差集仍渲染且不出现「全部完成」**。
- 前端：`npm run lint`、`npm run build`、`npm run test:unit`、`bash scripts/check-sfc-size.sh` 通过。

遗留风险：

1. 推算只依据课节号顺序与相邻课节；若学校作息本身不单调（课节号与时间顺序不一致），结果转人工而不是硬算——这是有意的保守选择。
2. 「日末追加」沿用最后一节的时段（上午最后一节之后补的仍记上午），是否属于晚自习由学校在作息管理里改；本票不推断晚自习边界。
3. 已发布学期的差集仍会显示条目（全部 MANUAL + 原因），以便用户知道文件比作息多哪些节；页面不提供任何写入入口。

**背景：** 为什么不做全自动推断——作息是学校的制度数据（起止时间、午休、晚自习都不在文件里），且一经发布不可修改，猜错会长期固化。ADR-0026；讨论见 2026-10-25 会话。
