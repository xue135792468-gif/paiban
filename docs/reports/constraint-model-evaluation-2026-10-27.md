# 自然语言约束模型评测报告（2026-10-27）

状态：**真实模型评测已于 2026-10-27 在服务器执行；结论 FAILED，未达放行门槛。**

## 结论

- 可用模型只有 `gemini-3-flash`（provider 2）。C01–C22 每句 3 次：**明确支持 27/54，边界 9/12**，未达到 52/54 与 12 次零误转的门槛。
- 费用：按名义单价 0.001/0.002 元每 1K token 计算，本次用量 0.1960 元（免费渠道，实际不计费）；最坏费用预留 0.8382 元，远低于 20 元上限。
- **Gemini 3.8 在当前中转不可用**：`gemini-3.8-flash` 返回 HTTP 404 `Upstream request failed`；`gemini-3.8-flash-low/medium/high/tiered` 在 `HttpClient.send` 挂起；`gemini-3.1-pro` 也返回 404。以上结果不作为模型通过证据。
- 决策：**不打开自然语言约束功能**；`NATURAL_LANGUAGE_CONSTRAINTS` 保持未绑定，页面按“AI 不可用/未配置”降级，手动配置路径不受影响。
- 原始报告：[Markdown](constraint-model-evaluation-2026-10-27-gemini-3-flash.md) / [JSON](constraint-model-evaluation-2026-10-27-gemini-3-flash.json)。
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
