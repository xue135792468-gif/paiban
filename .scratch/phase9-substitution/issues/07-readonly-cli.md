# 07: 只读 CLI `plan` / `verify`

**What to build:** 一个不连数据库、不写业务数据的离线命令，供运维和教务做 what-if 与事后核查。`plan` 吃一份课表快照 JSON，输出最多 3 个候选方案或无解诊断；`verify` 吃改动前/后的课表（或快照 + 方案），输出通过或违规清单。与后端共用同一套纯求解器和校验器。

**Blocked by:** 04, 05

**Status:** done

- [x] `plan` 接受快照、请求（教师/课节/目标节次/日期）与选项（窗口天数、教师数上限、排序预设），输出最多 3 个方案；无解时输出结构化诊断；退出码 0 有解 / 2 无解
- [x] `verify` 接受改动前/后课表或快照 + 方案，输出通过或逐条违规；退出码 0 通过 / 1 违规
- [x] 支持 `--format json|table`；JSON 带 `schemaVersion=1`
- [x] 全程只读：不连数据库、不写业务数据、不产生 `substitution` 行
- [x] golden 文件测试覆盖 plan 与 verify 各一组；同一输入在 CLI 与后端纯模块产出完全一致
- [x] 输入缺少必填字段、版本不兼容、快照非法时返回可读错误，退出码 1

**实现说明：** `com.paiban.substitution.cli.SubstitutionCli`（`plan`/`verify`）是纯 Java 薄入口，不连数据库；`scripts/substitution-cli.sh` 通过 Spring Boot `PropertiesLauncher` 运行 `-Dloader.main`。JSON 输入输出带 `schemaVersion=1`；退出码 0 有解/通过、2 无解、1 错误/违规；`plan` 输出可直接作为 `verify --plan` 输入。golden 测试覆盖 plan、verify 通过/违规、无解、未知参数与预设。
