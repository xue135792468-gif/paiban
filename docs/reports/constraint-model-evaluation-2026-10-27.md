# 自然语言约束模型评测报告（2026-10-27）

状态：**真实评测已通过：`gemini-3.8-flash-low` 54/54、12/12（PASSED）；任务已绑定，功能开关已打开。**

## 结论

- **最终模型 `gemini-3.8-flash-low`（provider 2/model 4）满分达标**：C01–C22 每句 3 次，**明确支持 54/54，边界 12/12**；66 次全部完成，用时约 11 分钟（每次约 10 秒，低于 60 秒单次上限）。按名义单价 0.001/0.002 元每 1K token 计算，用量 0.3067 元（免费渠道实际不计费），最坏预留 1.3992 元，远低于 20 元上限。
- **通过所依赖的修复**：提示词明确“无强度词用 INHERIT”“同名保留原称呼让服务端列候选”“同对象/同类型/同强度的多个时段合并为一条”“explanation/reason 各一句话”；输出上限从 4096 提到 8192；新增 60 秒硬超时（响应体停滞也会释放，不再永久挂起）。
- 功能状态：`ai.features.natural-language-constraints=true`；`natural_language_constraints` 任务绑定 provider 2/model 4；`GET /api/constraint-proposals/availability` 返回 `{"available":true}`。
- 迭代记录：v3/v4 曾为 51/54、10–11/12（C05/C16 已修好，剩余为输出截断/超时/C22 分组差异）；最终 v5 满分。各轮报告都保留在 `docs/reports/`。
- 其他模型：`gemini-3-flash` 27/54、9/12；`gemini-3.8-flash-medium/tiered` 早先长提示下长时间不返回；`gemini-3.8-flash` 普通别名与 `gemini-3.1-pro` 404。3.8-low 是当前可用的最优选择。
- 原始报告：[v5 满分 Markdown](constraint-model-evaluation-2026-10-27-gemini-3.8-flash-low-v5.md) / [JSON](constraint-model-evaluation-2026-10-27-gemini-3.8-flash-low-v5.json)；[v4 Markdown](constraint-model-evaluation-2026-10-27-gemini-3.8-flash-low.md) / [JSON](constraint-model-evaluation-2026-10-27-gemini-3.8-flash-low.json)；[3-flash Markdown](constraint-model-evaluation-2026-10-27-gemini-3-flash.md)。
- 评测工具与预算守卫已用替身演练验证（54/54、12/12 只证明管线，不证明任何真实模型）。

## 如何执行真实评测（人工、一次性）

1. 在设置中准备可用的供应商与模型，确认每 1K 输入/输出的**可靠价格**（人民币，含换算口径）。
2. 明确本轮评测预算（默认 20 元，不得超过），并准备要比较的模型（最多 2 个，`providerId:modelId`）。
3. 以评测模式启动后端（不会暴露 HTTP 入口，CI 不使用）：

```bash
java -jar backend/target/paiban-backend-*.jar \
  --paiban.eval.enabled=true \
  --output=docs/reports/constraint-eval-run \
  --model=<providerId>:<modelId> \
  --price-in=<每1K输入价格> --price-out=<每1K输出价格> \
  --budget=20
```

4. 读取 `constraint-eval-run.md` / `.json`。达标条件：明确支持至少 52/54 次**整句**正确，
   C16/C17/C19/C20 的 12 次零误转；未达标且预算允许时才比较第二个模型。
5. 达标后可用 `--bind=true` 将任务绑定到达标模型（或在设置页手动绑定）。`20 元`只是本轮评测额度，
   不是生产日预算。

## 语料与夹具

- 语料版本：`C01-C22-v1`（`ConstraintEvaluationCorpus`）。
- 合成学校：周一至周五，每天上午 1–4、下午 5–8、晚自习 9–10；教师王明/李华/张伟甲/张伟乙；
  课程语文/数学/英语/体育/化学；行政班七年级1/2班、九年级1/2班。
- 判定只认整句：类型、对象、范围、课节、数值与必须/尽量全部正确才算一次通过；
  格式合法或后置校验挡住乱猜都不算模型理解正确。

## 预算守卫

- 每次调用前按“提示词字符数作为输入上界 + 4096 token 输出上界 × 可靠单价”预留最坏费用。
- 剩余额度不足以下一次最坏费用时立即停止并标记未跑完；失败/超时同样占用预留。
- 已用确定性替身验证：额度不足以下一次最坏费用时提前停止、无单价时零请求、超过 2 个模型直接拒绝。
