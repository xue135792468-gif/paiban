# 算法审查证据与实现定位

日期：2026-11-01；主模型亲自审查及运行，未将算法结论委派其他模型。
版本：`4c1cc191bcd286e99e42e4e4a8b7a3ebcd68ba1d`。

## 证据边界

前一轮在同一基点运行：后端91项定向测试通过；另跑SwapPlanPropertyTest，1项测试内200个固定seed通过；前端4文件28项通过。现有测试没有覆盖以下反例，不能作为这些问题已修复的证明。临时Java复现程序与日志已按任务卫生要求清除，下面保留完整输入、观察与实现接点，实施时先恢复为正式回归测试。

## A1：晚自习统计误包含上午

- 设置：只启用EVENING_MAX，硬约束，maxPerDay=0。
- 输入：同班只有一节周一上午第1节，教室NORMAL/50人、班级40人，其他规则关闭。
- 实测：求解器 `-1hard/0soft`；ConstraintChecker.checkHard返回空列表。
- 应有：上午课程不违反晚自习上限，两侧均无违规。
- 位置：`backend/src/main/java/com/paiban/scheduling/solver/PaibanConstraintProvider.java` 的EVENING_MAX注册与addThreshold；对照 `constraint/ConstraintChecker.java` 的classEvening过滤。

## A2：软空堂评分量级不一致

- 设置：只启用CLASS_GAP_MINIMIZED，软权重40。
- 输入1：同班周一第1、3节各一课，不同教师，无冲突。输入2：第1、5节各一课。最小程序只提供两格占用位置的作息fact，原实现按节次编号跨度推算空位；正式回归还需分别构造完整连续作息和实际缺失的节次，落实主规格的新计数定义。
- 实测：两个输入求解器均 `0hard/-40soft`；校验器扣分依次40、120。
- 应有：相同权重下，3个可避免空位的代价高于1个；两侧按本规格一致计分。
- 位置：同一provider的classGapMinimized，与ConstraintChecker.softPenalty的gap分支。

## A3：合法三课轮换被提前排除

- 行政班A，三位不同教师，三间不同且足够大的教室；只启用课程C1“同一课程一天最多一节”硬约束。
- 初始：周一第1节a=C1/T1；周二第1节b=C2/T2；周二第2节c=C1/T3。初始满足该规则。
- 请求：把a从周一第1节调到周二第1节；窗口2天，最多6的现有规模选项。
- 已知合法环：a 周一1→周二1；b 周二1→周二2；c 周二2→周一1。每天总课数不变，C1每日仅1节，教师/班级/教室无冲突。
- 实测：PlanVerifier.verify对上述环返回passed=true、issues=[]；SwapPlanner.plan返回0候选、SAME_COURSE_PER_DAY。
- 根因：placeable在源课进入周二时，仅排除目标处将移出的b，没有考虑本轮后续还会移出周二第2节的c。
- 位置：`backend/src/main/java/com/paiban/substitution/plan/SwapPlanner.java` 的placeable中oncePerDay提前排除；现有score已有最终状态硬约束判断，不得通过移除最终校验修复。

## 已确认的现有交互

- `frontend/src/views/DraftEditorView.vue` 的onDropCell：空位可移动；本班占位打开候选面板，不是拖落即交换；发布后草稿不可编辑。
- `frontend/src/composables/useDraftAdjust.ts`：最多20次会话内撤销，刷新丢弃撤销栈。
- 无CLASS_UNAVAILABLE或独立停课类型；当前目录25类。

## 新规则实施接点（定位提示，非固定架构）

- 类型/快照/匹配：ConstraintType、ConstraintSettings、ConstraintEntryMatcher。
- 配置/模板：ConstraintConfigService、ConstraintTemplateService；已有classId跨学期映射和groupKey。
- 各裁决：PaibanConstraintProvider、ConstraintChecker、DraftValidator、DraftPublishReadiness、SwapPlanner、PlanVerifier、SchedulingService与warm start。
- 前端：既有约束分组控件、草稿网格与目标校验、帮助文档；不扩建第二套设置页。
- 兼容排查：已有学期类型初始化、全量保存/工作区、约束类型计数、AI parser/prompt/评测语料和模板读取。
