# 02: AI 网关与策略路由

**What to build:** 实现统一的 AI 调用网关：定义 `AiGateway` 接口（chat / chatWithVision），策略模式实现 DeepSeek/Gemini/OpenAI 兼容三种策略，配置驱动从数据库加载供应商与模型配置，运行时根据任务类型路由到对应模型，AI 调用失败时降级返回明确错误。完成后，业务代码只需注入 `AiGateway` 并调用 `chat(messages)`，无需关心底层是 DeepSeek 还是 Gemini。

**Blocked by:** 01

**Status:** done

- [x] `AiGateway` 接口：`chat(messages, options)` 与 `chatWithVision(messages, images, options)`
- [x] `AiStrategy` 策略接口 + 三种实现：`DeepSeekStrategy`、`GeminiStrategy`、`OpenAiCompatibleStrategy`
- [x] 策略路由：根据 `ai_task_config` 表的任务类型找到对应 provider/model，实例化对应策略
- [x] 配置缓存：启动时加载供应商配置到内存，配置变更时刷新
- [x] 超时与重试：默认 30s 超时，失败重试 2 次，重试间隔 1s
- [x] 降级策略：AI 调用失败时返回结构化错误（error_code + message），不抛异常阻断业务
- [x] 视觉路由：chatWithVision 校验模型 vision_capable 标记，不支持时返回明确错误
- [x] 调用日志：每次调用记录到 `ai_call_log`（任务类型、供应商、模型、输入 token、输出 token、耗时、成功/失败）
- [x] 单元测试：fake 策略验证网关路由逻辑
- [x] 集成测试：用 DeepSeek 真实 API 测试 chat 与 chatWithVision（`DeepSeekManualIT`，设置 `DEEPSEEK_API_KEY` 手动跑，CI 跳过；视觉部分需 `DEEPSEEK_VISION_MODEL`）
