# 01: 晚自习上限只统计真正晚自习

Status: done
Priority: P1
**What to build:** 设置晚自习上限后，白天课程不再被误算，原有全局与班级例外照常生效。
**Blocked by:** None
**Spec:** ../spec.md
**Gate:** 已实现、集成、复审，并通过冻结版本完整门禁。最终执行结果、跳过项和真实数据库边界见../acceptance.md；未部署。

- [x] 先以“上限0且只有上午课”固定求解/校验分歧，证明旧行为失败。
- [x] 上午、下午不计入；晚自习恰好达到/超过上限的裁决正确。
- [x] 全天连续节次不变：上午4节+下午3节+晚上2节仍为第1–9节，晚自习计数识别时间段标签，不能硬编码第8/9节或按节号推断晚自习。
- [x] 全局、班级例外、暂停和硬/软设置不丢失，未启用规则不产生约束。
- [x] 求解与校验对是否超限一致；本票不扩大到所有阈值评分梯度重写。
- [x] 定向回归通过并记录真实命令、退出码、版本与剩余问题，最终全量由06收口。

## Delivery（历史定向记录；最终收口见06）

- 分支：`codex/weekly-t01`，基点/集成 tip `5b2a7ae`；改动 `PaibanConstraintProvider.java` + `EngineConstraintTest.java` + `ConstraintCheckerTest.java`（本票）。
- 根因：`PaibanConstraintProvider` 的 `EVENING_MAX` 走通用 `addThreshold`，按 `Lesson::getAdminClassId` 分组后统计当天**所有**课节；`ConstraintChecker.checkAggregates`/`softPenalty` 早已只收集 `period.section() == PeriodSection.EVENING`，故求解 `-1hard` 而校验 `0` 违规。
- 修复：给 `addThreshold` 增加一个 counts 谓词重载，仅对 `EVENING_MAX` 传入 `lesson -> lesson.getPeriod().getSection() == PeriodSection.EVENING`；其它阈值仍走 `lesson -> true`，连续节次、间隔、教师/班级/课程阈值行为不变。计数按时间段标签判定，不硬编码第 8/9 节。
- TDD 红：先加 `EngineConstraintTest.eveningMaxDoesNotCountMorningLessons`（上限0、仅周一第1节上午课）。运行 `MAVEN_OPTS="-Xmx512m" ./mvnw -B -DforkCount=0 -Dtest=EngineConstraintTest test` → `Tests run: 43, Failures: 1`，唯一失败为该反例，退出码 1。
- TDD 绿：改 provider 后同命令 → `Tests run: 43, Failures: 0`，退出码 0。
- 扩展回归（同 provider/checker，非镜像实现）：
  - `EngineConstraintTest`（公共 `ScoreManager` 评分）：上午不计入、下午不计入、按 `PeriodSection.EVENING` 标签计入（用第7节标签为晚自习反证不按节号）、恰好等于上限不违规、软设置仅扣软分、超限（`eveningMaxIsHard`）仍硬违规。
  - `ConstraintCheckerTest`（HTTP 草稿校验同一 `ConstraintChecker`）：白天不计入、晚自习计入、班级例外替换全局（仅 2 班报 `EVENING_MAX`）、停用配置不产生约束、软设置对白天 0 分、对晚自习按权重 40 分。
- 定向命令与退出码：`source .tools/env.sh; cd backend; MAVEN_OPTS="-Xmx512m" ./mvnw -B -DforkCount=0 -Dtest="EngineConstraintTest,ConstraintCheckerTest" test` → `Tests run: 56, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，退出码 0（EngineConstraintTest 47、ConstraintCheckerTest 9）。
- 边界与未验证项：未单独跑 HTTP 端点；其余裁决入口中 `DraftValidator`（`/validation`、发布前校验）复用同一 `ConstraintChecker`（本就直接只算晚自习），求解器经草稿产生课程。完整门禁、两轴复审与交付由票 06 收口；真实 PostgreSQL 走部署门禁或同提交 CI。


2026-11-02最终收口：本票接受项已实现并经复审，冻结版本完整门禁通过；最终结论以[统一验收](../acceptance.md)为准，以上定向与待验描述为当时快照。
