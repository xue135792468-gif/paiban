# 复盘 1–3 验收

Status: done

基点 `88b8ce1fa9f59756ea36dfeac199e5b915643d7a`；交付分支 `codex/retro-verification`。实施范围见 [plan.md](plan.md)。本批不推送、不部署，不改原钉钉八票的业务源码。

## 已实施

1. `CODING_STANDARDS.md` 增加角色、设备、默认落地页与可见入口的 E2E 判断标准；保留深链接、刷新恢复和权限拒绝场景。
2. `verify.sh` 先跑八项仓库检查、前端 lint/build/unit，再后端 compile/verify，E2E 最后；Docker 为 `--deploy` 的提前前置。
3. `verify-run.sh` 只运行真实门禁，采集运行前后源字节与真实退出码；原始日志仅本地保留。归档包含元数据与校验表，提交钩子校验暂存 Git 字节，CI 校验 HEAD 字节。

## 已执行验证

| 验证 | 实际结果 | 边界 |
|---|---|---|
| 编排公有脚本回归 | 81 passed / 0 failed | 假 npm/Maven/Docker；含带空格/中文的 Playwright 产物参数，不充当业务验收 |
| 收据公有 CLI 回归 | 26 tests / OK | 隔离 Git 夹具；含失败 7、漂移 86、新增/删除/暂存源、HEAD 变化、路径、Unicode、CRLF、重签矛盾、暂存篡改与日志隔离 |
| 原有 `verify-deploy.sh` 套件 | 9 个既有套件通过 | 两处无 Docker 的 compose 配置检查跳过；一处 Windows 的 Unix mode 600 检查跳过 |
| Bash 语法 | 5 个相关入口通过 | verify、两个收据入口、编排测试、pre-commit |
| Python 语法 | 两个 Python 文件通过 | `py_compile` |
| CI 配置核对 | backend/frontend/e2e 三个 job 与基点完全一致 | 新检查只接入既有部署脚本 job |

日志在本工作树 `.runtime/retro-verification/`，不进入 Git。编排原脚本上的真实反证为 8 条顺序/失败停止断言失败，重排后全绿；收据实现的语法/Windows 编码与夹具问题曾使初轮失败，已修复，不将这些实现错误冒充需求反证。归档链接逃逸用例修复前真实返回 0、修复后拒绝。

## 真实门禁与收据

`retro-deploy-preflight-20261114` 在实现提交 `4af564686f1c3420790008b169b487947f7266b1` 上实际运行：没有 Docker，命令与工具退出均为 1；1076 个源文件前后一致，归档有效。这是预期的前置拒绝，不是部署通过。

收据：[Docker 前置拒绝](../verification/retro-deploy-preflight-20261114/receipt.txt)。

真实 `--quick` 已完成，执行源码 `d6ef18b3d775b353f4cd617a415db8e516de3f3b`，本地时间 2026-11-14 01:52:17–01:57:51，命令与工具退出均为 0。八项仓库检查、前端 lint/build、106 文件的 803 项单测、后端 `-DskipTests compile` 全部通过；1076 个源文件前后一致，无漂移。构建有 bundle 体积以及既有 Java deprecated/unchecked 告警，未阻断。

收据：[真实快速门禁](../verification/retro-quick-20261114/receipt.txt)。两份归档在 Git 索引中共 12 文件，`--staged` 与正常 pre-commit 均实际校验通过。

归档提交 `1d346c9b745727822354807ee855ae700e2abf89` 上，无参数 HEAD 校验实际读取 2 份收据的 12 个 Git 对象，0 mismatch / 0 missing / 0 unlisted / 0 metadata problems。独立按快速门禁 source-after 清单重算当前 1076 个源文件的字节 SHA256/长度，全部一致；相对测试源码 `d6ef18b` 的四个源范围 Git diff 为 0，相对基点的 backend/frontend diff 为 0。其后只更新本验收文档。

完整后端测试、Playwright、真实 PostgreSQL、钉钉租户/真机不在本批验收中冒称通过；原八票的既有完整验收记录仍在原目录。

## 独立复查

Standards / Spec：首轮候选 `4af5646`，修复定向复核候选 `d6ef18b`；两轴均确认修复有效、无新阻塞。逐项发现、处置和低优先取舍见 [review.md](review.md)。

## 交付边界

原 `backend/`、`frontend/` 相对基点无业务变更。实跑后仅提交文档和收据，交付前核对当前源与测试快照相同。未推送、未部署。所有测试的 fake 编排、真实快速门禁、外部服务验收分开记录。

本批两名实施 worker 与两名评审者均结束；`retro-verify-order`、`retro-verify-receipts` 临时工作树及其临时分支已移除，共享 `.tools` 与 `frontend/node_modules` 仍存在。失败/成功原始日志保存在交付工作树 `.runtime/retro-verification/`，仅作本地证据。

**清理阻塞**：自动审批在执行前拒绝了本批运行临时目录/缓存清理，仅返回 `blocked by policy`，未提供进一步原因；该命令未执行，以下路径保留，不用其他方法重试：本工作树 `.runtime/retro-verification/tmp`、两次 `.runtime/verification/<run-id>/{tmp,npm-cache,screenshots}`、`.runtime/retro-receipts/cases`、`.runtime/retro-order`、`scripts/__pycache__`、`scripts/tests/__pycache__`。这不影响已通过的源/收据校验。原 DingTalk09 的拒绝路径从未触碰。
