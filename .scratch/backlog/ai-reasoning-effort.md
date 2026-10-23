# 待定：AI 模型的思考强度配置

Status: idea（2026-10-23 记录，未排期、未拆票，等需要时再决定）

## 现象

设置中心的模型「高级参数」只有 `temperature` 与 `max_tokens`，没有“思考强度 / reasoning effort”选项。模型请求实际上完全按供应商或模型的默认行为执行。

## 代码事实（当前 main）

- 模型只存 `vision_capable`、`enabled`、`params_json`；`params_json` 是自由 JSON。
- 请求构造只读取 `temperature` 和 `maxTokens` / `max_tokens`（`AiStrategyParams`），其他键被静默忽略。
- `OpenAiCompatibleStrategy` / `DeepSeekStrategy`：只发 `temperature`、`max_tokens`、`response_format`，不发 `reasoning_effort`。
- `AnthropicStrategy`：不发 `thinking` 块，Claude 默认不开启扩展思考。
- `GeminiStrategy`：不发 `thinkingConfig`，走 Gemini 模型自己的动态思考默认值。
- 因此手写 `{"reasoning_effort":"high"}` 到 `params_json` 也不会生效。

## 为什么值得考虑

- 推理模型的思考 token 与输出 token 共用 `max_tokens`；课表导入单次 `max_tokens=8192`，高思考档位更容易触发 `length` 截断（六期真实踩过截断坑）。
- 有的中转站默认开启思考且无法从本系统关闭，排查“为什么慢/为什么被截断”时缺少可观测字段。

## 可选方案

1. **按模型加枚举（推荐）**：`跟随模型默认 / 低 / 中 / 高`，默认“跟随”，保持现状行为。后端按协议映射：
   - OpenAI 兼容 / DeepSeek：`reasoning_effort: low|medium|high`（部分新模型是 `reasoning: {effort}`，需要兼容分支）。
   - Anthropic：`thinking: {type: "enabled", budget_tokens: N}`，必须校验 `budget_tokens < max_tokens` 以及温度约束，否则上游 400。
   - Gemini：2.5 用 `generationConfig.thinkingConfig.thinkingBudget`；3 系列是 `thinkingLevel`。
2. **通用透传**：把 `params_json` 其余键原样合并进请求体。灵活但容易产生协议间非法组合，且错误只能到上游才暴露。
3. **最小版本**：只给 OpenAI 兼容协议加 `reasoning_effort`，Anthropic/Gemini 继续跟随默认。

## 需要先定的问题

- 配置粒度：按模型（简单）还是按任务再覆盖（导入建议低档、调代可用高档）。
- Anthropic 的档位到 `budget_tokens` 的映射值，以及 `max_tokens` 不足时是自动抬高还是拒绝保存。
- 是否把实际使用的档位写进 `ai_call_log`，便于解释耗时与截断。
- 中转站兼容：同一个 `model_name` 在不同 base URL 下的参数名可能不同。

## 涉及位置（实现时的入口）

- 模型字段与 DTO：`ai/domain/AiModel`、`ai/web/AiDtos`。
- 参数解析与三种策略：`ai/strategy/AiStrategyParams`、`OpenAiCompatibleStrategy`、`AnthropicStrategy`、`GeminiStrategy`。
- 设置界面：`frontend/src/views/AiProvidersView.vue` 的模型「高级参数」。
- 审计：`ai/service/AiCallLogWriter`、`ai_call_log`。

## 验收草案

- 三协议各自的请求体单测：设置“低/中/高”后出现对应字段，默认时不出现任何新字段。
- Anthropic：`budget_tokens >= max_tokens` 时保存被拒绝并给出可读原因。
- 设置界面保存/回显；旧模型（无该字段）行为不变。
