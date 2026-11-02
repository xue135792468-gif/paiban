# 06 统一验收记录

> 最终追加状态：CI周一日期假设已修正并经独立核对。77e8129重新完整verify通过；06重新关闭。此前CI真实PostgreSQL已实际通过，最终同提交结果以[PR #21](https://github.com/xue135792468-gif/paiban/pull/21)检查和backend日志为准。


当前阶段：六票实现、主模型算法判断、两轴复审和本地完整门禁已完成。最终同提交GitHub CI的真实PostgreSQL检查是发布前的外部验证；本机没有Docker，不将H2替代它。本轮未合并main、未部署生产。

## 版本与范围

- 集成分支：codex/weekly-no-class。
- 原始基点：4c1cc191bcd286e99e42e4e4a8b7a3ebcd68ba1d。
- 最后产品逻辑提交：4ea9444f1d5518b01477467cef7cc61bcdf2e78e；其后274216b、2c2e52f、77e8129仅修目录/浏览器断言、日期fixture与帮助文字。
- 最终完整运行SHA：77e81297f4f3bf4dd3809ba7ea2874d26a8dad4d。
- 冻结tree：backend `0c10a163060c2a2c06f7f319056f79bbe7ba4a5a`；frontend `fdc8c6b2afd9b5184ef8c622a1b09da9ca5ea642`；scripts `db843f8b157b4610141022fd9d3ddc879aeb4b0c`。
- 完整运行后仅允许交付文档与截图变更。无新增Flyway迁移，最高V44；未修改正式基线。

## 评审与算法判断

主模型亲自完成[算法判断](algorithm-verdict.md)，结论限本批修正合格，不宣称全局最优。两轴处置及fresh review见[round1](review-round1.md)、[round2](review-round2.md)、[round3](review-round3.md)。接受项均已修复；两项保留设计有明确理由。最终目录/浏览器兼容补丁另经独立只读核对，保留旧断言含义并扩展新硬规则界面检查。

## 最终完整运行

2026-11-02 00:41:18至2026-11-02 01:20（Asia/Shanghai），在隔离集成worktree运行 `bash scripts/verify.sh --full`，原始退出码 **0**，终行 `==> verify: ok`。运行期间未改源码或测试，结束后再次核对SHA及三个tree未变。

| 层次 | 实际结果 | 边界 |
|---|---|---|
| 后端Maven verify | 1110项，0失败、0错误、8跳过 | DeepSeekManualIT 2、PerformanceBenchmarkIT 2、PostgresContainerIT 4按设置跳过 |
| 前端lint/build | 通过 | 未以构建代替运行 |
| 前端单元测试 | 61文件、465项全部通过 | 同冻结版本 |
| 仓库检查 | 全部通过 | SFC/local dates/e2e helper/isBlank/模块边界/迁移/票状态/AI超时 |
| Playwright | **141/141通过**，16.9分钟 | 单worker、隔离端口28080/25173；新规则三场景和初中模式均实际执行 |
| 真实PostgreSQL | 本地未运行 | 以该分支最终提交的PR checks和backend日志为外部证据，必须确认PostgresContainerIT正数执行且skipped=0 |

参数：PAIBAN_MVN_EXTRA_ARGS=-DforkCount=0，VITEST_MAX_WORKERS=4，MAVEN_OPTS含-Xmx2048m；临时文件与截图均定向到当前worktree。doctor资源足够，但Docker/独立数据库缺失，非“全绿”。

最终完整日志SHA-256：9F6F09877CADC28E3A0810DC75473571EE8A710E48980807203C3CD4E431E18C。日志和运行缓存为任务临时文件，统计与哈希落盘后清理。

## 失败历史与修正

1. 82c9b66首次完整运行后端1110项中2失败，8跳过：旧entry-backed计数19实际20。前端/浏览器未启动。修正保留原19成员、加入新类型并验证非threshold；定向27项通过。见[catalogue-gate-delivery](catalogue-gate-delivery.md)。
2. 274216b第二次完整运行后端、前端与仓库检查通过；浏览器133通过、3失败、5串行未运行。两处旧25目录断言和一处默认类型假设已修正；后续定向还发现并修复教师标题默认选择假设，最终三个spec10/10通过。见[e2e-catalogue-gate-delivery](e2e-catalogue-gate-delivery.md)。
3. 2c2e52f完整运行退出0（浏览器141通过，13.3分钟）；原日志SHA-256为BAF9C67768DCB6A883EAC5411B81C1F58D38C5FE18057E7D4E2B3D6D5352C109。
4. c37a70b的[CI](https://github.com/xue135792468-gif/paiban/actions/runs/41325539556)后端、前端、部署脚本通过；PostgresContainerIT实际4项0失败0错误0跳过，但浏览器140通过1失败。主模型确认旧测试在周一错误期待未来周三课禁用。仅修测试日期fixture，保留服务端真实时钟，强化实际课卡断言；定向通过、独立复核无发现。见[clock-fixture-delivery](clock-fixture-delivery.md)。
5. 77e8129再次冻结后完整运行得到上方退出0；没有用分段结果冒充整轮通过。最终提交只允许文档/证据变更，完整运行后源码tree核对不变。

历史定向数据见各delivery，不能累加不同版本及重叠测试。容量旧版公开API对照、分组碰撞反例、发布基线保护与权限/并发版本均有独立证据。

## 发布与保护

见[发布交接](release-handoff.md)及部署跑册。所有执行者worktree已移除，保留集成worktree；测试进程已退出、隔离端口释放。仅保留本功能三张验收截图，任务额外截图及缓存清理。主仓库main原有17张修改截图与其他原型不属于本任务，未暂存或清理。

本记录不声称自然语言支持新第26类、不声称性能基准或真实模型测试已运行。发布后新增规则不自动改写既有基线；仅支持每周固定留空。
