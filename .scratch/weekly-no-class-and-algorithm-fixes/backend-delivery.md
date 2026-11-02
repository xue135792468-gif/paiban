# 票05 后端交付（全校/多班范围与容量提示）

日期：2026-11-01。分支 `codex/weekly-t05-backend`，基点 `5724a43`，实施前合并
`a5c8237`，收口前合并 `10216f5` 与 `089a2d5`。只改 `backend/**`，另加本文件。

## 实现

- `CLASS_UNAVAILABLE` 的 `classId` 改为可选。省略或 JSON `null` 才表示全校，覆盖当前及
  以后新增的行政班；显式空白字符串在保存时被拒绝（`全校范围请省略该参数或传 null`），
  任何非空但无法解析或指不到本学期的 classId 在匹配时一律为 false，绝不扩大为全校。
- 多班沿用既有 entry 展开：每个 `classId × 时段` 一条，靠 `groupKey` 归并为一条要求，
  不新增 `classIds` 存储字段（多余参数仍按「未知参数」拒绝）。
- 同一 `groupKey` 的全校行与选班行互斥；同一 `groupKey` 的班级×时段必须是完整矩形，
  缺角时列出缺失组合并拒绝保存，避免载入后自动补出别的班/别的节。
- 去重维度只在 `CLASS_UNAVAILABLE` 上增加 `groupKey`：不同要求可以重叠同一位置，同一
  要求内部重复仍拒绝；其余 25 类（含其它 slot-grouped 类型）去重签名不变。
- 重叠与暂停按并集生效：暂停一条要求只撤掉它自己的行，与之重叠的另一条仍继续禁排。
- 新增 `ClassCapacityPlanner`（无状态纯计算）与 `ClassCapacityService`（只读
  assignment / 行政班 / 作息，规则以 `ConstraintSettings` 入参传入，不依赖约束服务，
  无循环注入）。可用位置 = 真实 `(dayOfWeek, periodNo)` 去重集合减去本班有效禁排并集；
  空作息集合视为 0 个可用位置。
- `ConstraintPreviewDto` 增加 `warnings: List<String>`，保留两参构造。`preview` 用 plan
  生成的未保存规则（经 planned 映射转成同一个 `ConstraintSettings`）算容量告警；无效
  规划不出告警；告警不阻止保存。未新增 GET 路由。
- `SchedulingService.start` 在 `startLock` 内、建 job 之前校验容量，不足返回 400 且不新建
  job；`run` 在真正开排前用当前 settings 再校验，不足直接失败，不启动求解、不写草稿、
  不改授课任务。

## 改动文件

主代码

- `backend/src/main/java/com/paiban/scheduling/domain/ConstraintType.java`
- `backend/src/main/java/com/paiban/scheduling/solver/ConstraintEntryMatcher.java`
- `backend/src/main/java/com/paiban/scheduling/service/ConstraintConfigService.java`
- `backend/src/main/java/com/paiban/scheduling/service/ClassCapacityPlanner.java`（新增）
- `backend/src/main/java/com/paiban/scheduling/service/ClassCapacityService.java`（新增）
- `backend/src/main/java/com/paiban/scheduling/service/SchedulingService.java`
- `backend/src/main/java/com/paiban/scheduling/web/ConstraintDtos.java`

测试

- `backend/src/test/java/com/paiban/scheduling/ClassUnavailableScopeTest.java`（新增，6）
- `backend/src/test/java/com/paiban/scheduling/service/ClassCapacityPlannerTest.java`（新增，5）
- `backend/src/test/java/com/paiban/scheduling/WeeklyNoClassSchoolScopeIntegrationTest.java`（新增，12）
- `backend/src/test/java/com/paiban/scheduling/ClassCapacityIntegrationTest.java`（新增，3）

## 红

1. `ClassUnavailableScopeTest,ClassCapacityPlannerTest` 在基点代码：exit=1，
   `ClassCapacityPlanner` 不存在，3 处测试编译错误。
2. `WeeklyNoClassSchoolScopeIntegrationTest`（实现范围校验之前）：exit=1，9 项中
   5 失败 1 错误——兄弟组同位置被误判重复、全校与选班混用被接受、缺角矩形被接受、
   模板跨学期断言失败。
3. `ClassCapacityIntegrationTest` 首跑：exit=1，3 项中 2 失败；其中「保存即 400」是测试
   自身把两个同类配置分开提交造成的编排错误（已修）。这一轮没有留下一条干净的容量红灯，
   真实红灯依据是基点不存在 `ClassCapacityPlanner`/容量校验（上面第 1 条的编译红），
   以及 `start`/`run` 原代码路径中没有任何容量判断。

## 绿

- 定向 14 项：`ClassUnavailableScopeTest` 6 + `ClassCapacityPlannerTest` 5 +
  `ClassCapacityIntegrationTest` 3，0 失败/错误/跳过，BUILD SUCCESS，exit=0。
- 定向 23 项（加模板/工作区/审计后的范围类）：0 失败/错误，exit=0。
- 合并 `10216f5` 后广定向 107 项（含 04 旧用例、引擎、模板兼容、目录）：0 失败/错误，
  exit=0。
- 合并 `089a2d5` 后广定向 235 项：0 失败/错误/跳过，BUILD SUCCESS，exit=0
  （命令：`MAVEN_OPTS=-Xmx512m ./mvnw -B -DforkCount=0 -Dtest=... test`）。
- 队列用例加强清理后复查 `ClassCapacityIntegrationTest`：3 项通过，exit=0，整类 24.7s；
  blocker 与 queued 都在 `finally` 取消并等待终态，未把 blocker 自然跑完当成队列已验证。

## 覆盖

- HTTP：全校禁排作用于既有班与保存后新建的班；指定两班不影响第三班；非法/外学期/不存在
  的 classId 被拒且不扩大；显式空白 classId 被拒而省略 classId 即全校。
- HTTP：同组重复拒绝、兄弟组重叠允许、暂停一条后另一条仍生效、同组全校+选班被拒、
  班级×时段缺角被拒；其它 slot-grouped 类型（`TEACHER_UNAVAILABLE`）去重行为不变。
- HTTP：多班/全校模板保存、跨学期无映射拒绝与显式映射成功；工作区保存 2 班×2 时段共 4 条
  真实展开条目后原样恢复且 errors/invalidReferences 为空；保存后审计记录最终全校范围。
- 纯计算：40 位置、39 课时、2 个去重禁排 → 39/38/差 1；重叠只扣一次；选班只减本班；
  空作息为 0 容量。
- HTTP：`/constraints/preview` 返回 warnings 且保存仍返回 200；`start` 不足返回 400、
  无 job、授课任务不变；排队中改规则后 `run` 复检失败（附班名/39/38/差 1）、无草稿。

## 限制与未覆盖

- 仅在内嵌 H2 test profile 实跑；真实 PostgreSQL 需 06 部署门禁或同提交 CI 证据。
- 未跑 `scripts/verify.sh --full`、Playwright 与 e2e（资源约 4GB，按票约束禁止）；
  前端三个 Playwright 场景与组件测试由集成侧执行。
- 权限 403、baseVersion 冲突、已发布基线不变与后续调课目标拦截由主线程在独立测试文件
  覆盖（`089a2d5` 已含发布边界用例），不计入本文件的证据。
- 容量只是必要条件：容量足够不代表其它冲突可解，提示文案与代码都不作可解承诺。
- 新类型的自然语言生成仍明确不支持，本次没有触碰 AI 目录/规范化路径。
