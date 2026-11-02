# 票04核心规则子任务交付

日期：2026-11-01。主模型亲自实现与检查；分支codex/weekly-t04-core，基点77113c5。

## 实现

- CLASS_UNAVAILABLE复用既有条目、分组、版本与审计，目录动态可见；本切片classId必填，05扩展全校/多班。hardOnly元数据锁定必须，保存/预览共同规划路径拒绝类型或条目的soft请求。快照构造也拒绝弱化，避免CLI原始数据绕过。
- ConstraintSettings.classUnavailable是求解、草稿校验、调课搜索及独立校验共用的匹配入口。
- 求解器在room尚未赋值时也能识别已占禁排时段；checker报告真实课程冲突；调课边剪枝只针对无法靠轮换修复的禁排目标。
- 真实空堂计数排除该班主动留空，其他班级照常计分，连堂语义不变。
- warm start使用本次求解设置，释放禁排占位供构造阶段修复；不修改源草稿、正式基线或授课任务。
- 草稿详情增加blockedSlots(classId/periodId/reason)，旧4参数构造入口仍兼容。原始占位保留，发布前校验拒绝；force=true也不能将课程放入禁排格。
- 领域词汇写入GLOSSARY；沿用现有分组控制和正式基线只读决策，无新增数据表或Flyway迁移。

## 实测

- 红：在旧基点运行WeeklyNoClassIntegrationTest，3项均失败，exit=1；新类型保存预期200而旧代码返回400，证明旧行为不能完成所要求的HTTP流程。
- 核心首次绿：WeeklyNoClassIntegrationTest 3项、WeeklyNoClassEngineTest 4项全部通过，exit=0。
- 扩展绿：`source .tools/env.sh; cd backend; MAVEN_OPTS=-Xmx768m ./mvnw -B -DforkCount=0 -Dtest=WeeklyNoClassIntegrationTest,WeeklyNoClassEngineTest,EngineConstraintTest,ConstraintCheckerTest,WarmStartSeedServiceTest,SwapPlannerTest,PlanVerifierTest test` → 117项，0失败/错误/跳过，BUILD SUCCESS，exit=0。
- 其中新增5个HTTP用例覆盖：目标标记与force拒绝、原始占位保留/修复/发布、旧草稿热启动修复且原任务/源草稿不变、软请求和非法课节原子拒绝/版本不变、暂停后恢复可用但保留条目。
- 新增4个纯规则用例覆盖：本班禁排不影响同教师其他班、软空堂对主动留空豁免且其他班仍扣40、硬空堂豁免、搜索与独立校验拒绝目标禁排。
- 以上HTTP实跑内嵌H2 test profile；真实PostgreSQL不是本轮证据，需06部署门禁或同提交CI。

## 尚未完成

AI生成的新类型隔离、26类全目录/模板兼容适配由其他有界子任务提交后合验；前端子任务d13bdda已完成组件级验证，但实际Playwright还没运行。票04整体及全量门禁未完成，不能作为发布证明。
