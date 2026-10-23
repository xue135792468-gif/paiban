# 06: 八期 E2E 与帮助文档

**What to build:** 用 Playwright 覆盖八期的四条主路径：后台进度、停止解析、部分结果与重试、放弃导入；帮助中心与 README 说明新增状态与操作。完成后，用户能按文档理解 `PARSING` / `PARTIAL` / `ABANDONED` 分别该做什么。

**Blocked by:** 02, 04, 05

**Status:** done

- [x] E2E：大课表后台解析显示进度并自动进入预览
- [x] E2E：解析中停止，已完成批次保留且可继续入库
- [x] E2E：单批失败进入部分结果，重试后得到完整预览并入库
- [x] E2E：放弃导入后无草稿，恢复入口不再继续该会话
- [x] 帮助中心补充新状态与「停止 / 重试 / 放弃」的操作说明
- [x] README 功能清单补一句八期能力
- [x] `GLOSSARY.md` 如有新增状态术语则同步

**实现说明：** 四条 E2E 加在 `frontend/e2e/ai-import.spec.ts`，本地 OpenAI 兼容 stub 按 `chunk` 逐批作答，可按批次返回 4xx 与延迟来制造 `PARTIAL` 和可观察的 `PARSING` 窗口。帮助中心 `frontend/src/help/docs/ai-import.md` 增加「后台解析与三种中间状态」表与停止/重试/放弃的操作说明；`GLOSSARY.md` 补「解析中 (ParsingInProgress)」（部分导入、停止解析、放弃导入原已存在）。
