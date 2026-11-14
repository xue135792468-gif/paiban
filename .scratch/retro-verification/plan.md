# Retro 1–3 落实

2026-11-14，用户明确授权落实复盘前三项。基点88b8ce1fa9f59756ea36dfeac199e5b915643d7a；工作分支codex/retro-verification。只调整代理环境、验证工具和评审标准；原钉钉八票及业务源码的已验收状态保持独立。

## 范围和分工

1. 主控：CODING_STANDARDS真实用户入口判断标准、工具文档/导航、CI与钩子接线、整合及独立复查。
2. order worker：scripts/verify.sh，scripts/tests/test-verify-order.sh。其他文件只读。
3. receipts worker：scripts/verify-run.sh，scripts/verification_receipts.py，scripts/check-verification-receipts.sh，scripts/tests/test_verification_receipts.py。其他文件只读。

所有worker使用group/deepseek/high；每个独立工作树，先定向测试，交付固定SHA。当前无发布、推送或生产操作授权。

## 1：真实入口标准

关键用户流程声明角色、设备、真实起点与页面动作链。主流程由默认落地页点击进入；直接URL访问用于明确的深链接、刷新恢复或权限拒绝场景。已有手机导航回归继续保留，不为复盘再造重复测试。此为审稿时的语义判断，不以机械禁止page.goto替代。

## 2：快检查优先

verify.sh保留default/--quick/--full/--deploy和全部现有检查、参数、隔离与退出语义。仓库检查及前端lint/build/unit先于后端compile/verify，E2E仍最后。--deploy缺Docker作为已知前置应尽早失败；--full缺Docker仍明确跳过真实PG。CI原有并行job保持。

公有脚本回归通过隔离小仓库与假命令观察：快阶段失败时后端/E2E没有启动；成功时所需阶段各执行一次；quick不跑后端测试或E2E；full包含E2E；deploy传testcontainers且无Docker时非零；无效参数失败。假命令只验证编排，不充当业务测试证据。

## 3：正式验证收据工具契约

- `bash scripts/verify-run.sh <run-id> [--quick|--full|--deploy]`，默认--full；执行真实scripts/verify.sh，不对外提供任意命令替换接口。
- run-id只能是安全的单段标识，拒绝绝对路径、分隔符和`.`/`..`。已存在的run禁止覆盖；输出实际解析路径必须留在本工作树内，拒绝指向工作区外的输出链接。
- 本地原始日志/临时目录放`.runtime/verification/<run-id>/`；可归档元数据放`.scratch/verification/<run-id>/`。均不得自动stage/commit。TEMP/TMP/TMPDIR、npm缓存及截图均留在该次运行目录。
- 开始前要求tracked工作树干净，且backend/frontend/scripts/.githooks四个源范围没有未跟踪非忽略源。历史.scratch缓存不因此被拒绝。快照枚举该四个范围的tracked源，记录Git版本绑定以及实际字节SHA256；执行结束再采集同一范围，区分真正运行前后与编辑基线。
- 收据记录run-id、mode、命令、UTC开始/结束、HEAD前后、命令真实退出码、工具退出码、源是否一致。源码变化即使命令成功也返回86；无变化时保留命令非零退出码；仅两者成功且归档有效才返回0。参数/前置失败非零，不伪造测试通过。
- 原始verify.log仅本地保留；归档包括日志摘要/摘要哈希、源码before/after清单、收据和校验表，不自动复制含请求上下文的大日志。归档不解释或冒称真实PG/外部服务已验证，保留原脚本的跳过输出。
- 归档生成局部.gitattributes，保留原始字节并识别CRLF。`bash scripts/check-verification-receipts.sh --staged`校验暂存Git对象与校验表；无参数检查HEAD内已归档收据。允许工作区校验的显式模式，但不能拿工作区校验冒称Git对象校验。校验不写Git索引。
- 归档检查校验完整性，不把保留的失败运行当作损坏收据，也不把校验通过当作业务测试成功。原始大日志始终仅本地保留。
- 写入UTF-8和显式换行；支持空格及Unicode文件名。Git路径安全传参，不用eval或拼接shell命令。源文件/输出边界异常明确失败，禁止读取工作区外目标后当作已绑定源码。

公有CLI回归至少覆盖：命令通过、命令非零、源变化、未跟踪源、重复run-id/路径逃逸、带空格和Unicode的源名、CRLF字节在Git对象中保持、篡改暂存收据检测。测试用隔离Git小仓库和假verify.sh，全部夹具在工作树.runtime内；新建夹具清理前校验真实路径与归属，历史09拒绝目录绝不触碰。

## 验收

定向编排/收据测试、脚本语法与字节完整性检查、独立Standards/Spec复核，以及新门禁真实--quick实跑。这里不把假编排测试当成真实后端/E2E验证；业务Java/Vue/E2E未改，原钉钉完整验收记录保留。发布前仍按完整/真实PG门禁执行。
