# 首轮门禁原始记录提取（主控）

审源d0309daf18ba62aee181d8e6e05200704ecfbfaf；仅提取已结束的final-full-run1，不是重跑。机械worker超过有界时限后已关停、未交付文件，本提取由主控直接核对原始Maven日志及四类Surefire XML完成。

- 直接verify.exit.txt=EXIT=1；source-stability.txt=SOURCE_IDENTICAL=1，source-refs前后相同。
- Maven最终1265项：失败1、错误0、跳过7，通过1257，BUILD FAILURE，24:40min。
- 唯一失败RoleReshapeIntegrationTest.presetRolesAreSuperAdminSchoolAdminAndTeacher：全目录多出两个foundation_only_*自定义角色；失败TXT/XML已单独复制归档。
- 跳过DeepSeekManualIT 2项：未设置DEEPSEEK_API_KEY。
- 跳过ManualImportIntegrationTest 2项：未设置paiban.realWorkbook系统属性。
- 跳过PerformanceBenchmarkIT 2项：未设置perf系统属性。
- 跳过PostgresContainerIT 1项：未设置testcontainers系统属性；verify日志同时明确无Docker。这1项已包括在7项之内，不再叠加。
- 四类原始TEST-*.xml已复制到logs/final-full-run1。原因来自各testcase/skipped的message，不从类名推断。
- Maven失败后后续前端lint/build/unit、shell仓库检查与Playwright全部未执行。首轮不能放行原八票。
