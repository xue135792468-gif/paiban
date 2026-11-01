# 06 统一验收记录

当前阶段：源码与修复已收口，完整运行门禁尚未开始。各票仍in-progress，不能把定向通过当整批done。

## 版本与范围

- 集成分支：codex/weekly-no-class。
- 原始基点：4c1cc191bcd286e99e42e4e4a8b7a3ebcd68ba1d。
- 最后产品代码提交：4ea9444f1d5518b01477467cef7cc61bcdf2e78e。
- 冻结源码tree：backend `8413a91b92c6cd3723011b0a7a7622050e6f8922`；frontend `822887dbaf86d805ba973056b84b7baffac25b55`；scripts `db843f8b157b4610141022fd9d3ddc879aeb4b0c`。
- 无新增Flyway迁移，仓库最高V44；未部署生产或修改正式基线。

## 评审与算法判断

主模型独立判断见[algorithm-verdict.md](algorithm-verdict.md)。两轴首轮与处置、集中修复后的fresh review、最后分组碰撞修复核对分别见[round1](review-round1.md)、[round2](review-round2.md)、[round3](review-round3.md)。最后一轮Standards与Spec均无新增发现；保留的两项设计选择有明确理由，不作为未修缺陷藏起。

## 执行记录

| 层次 | 当前状态 | 证据与边界 |
|---|---|---|
| 三算法反例与有界样本 | 定向完成 | 01–03票内各版本红绿记录，不累加重叠数 |
| 单班/多班/全校/容量/模板/工作区 | 定向完成 | core/backend/scope-ui及published等delivery |
| 灰格/拖拽/发布/新班/容量浏览器 | 前版三场景通过，新版范围title待全量执行 | e2e-delivery；后续补丁只追加范围断言 |
| 分组身份追加反例 | 定向完成 | group-identity-delivery，原版四卡恢复三卡，修复后保持四卡 |
| 冻结版完整verify.sh --full | 待执行 | 启动后记录实际SHA、退出码与统计 |
| 真实PostgreSQL | 不计入本地H2 | 本机无Docker；最终同提交CI检查为交付依据 |

定向历史数据分别保留在各delivery中，不能把不同版本、重叠用例加成一个虚构总数。本文件只汇总最后完整运行结果。

## 发布与保护

发布边界、备份和回滚要求见[release-handoff.md](release-handoff.md)及部署跑册。本轮仅开发/验证；主仓库main原有17张已修改截图与其他原型资料不属于本任务，已核对main基点未改、没有暂存这些文件。任务临时worktree与进程在交付前清理。
