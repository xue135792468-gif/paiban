# AI 供应商管理：多供应商多模型 + 统一/分开任务分配

参考 ZCode 模型管理面板的交互模式，实现完整的 AI 供应商与模型管理：

- **供应商管理**：支持预设供应商（DeepSeek、Gemini、通义、智谱等）与自定义供应商（OpenAI 兼容端点，含中转站），每个供应商配置 Base URL + API Key
- **模型管理**：每个供应商下挂多个模型，模型可标记"支持视觉"（`vision_capable`）
- **连接测试**：每个供应商可独立测试连通性
- **任务分配**：支持"统一模式"（所有 AI 任务用同一模型）与"分开模式"（每个任务独立选模型），默认统一
- **空值处理**：未接入供应商时下拉为空；第一个接入的自动成为默认
- **参数折叠**：模型的高级参数（temperature、max_tokens 等）默认收起为小箭头，展开后可修改，默认用系统预设值

## 为什么

- 初中教务老师不是技术人员，但 AI 供应商管理是运营刚需（换供应商、换模型、测试连通性）
- ZCode 的交互模式已被验证：供应商列表 → 展开显示模型 → 每模型独立启用/测试
- 统一/分开的分配策略覆盖了"省事"与"精细控制"两种用户偏好

## Consequences

- `ai_provider` 表：id、名称、类型（预设/自定义）、base_url、api_key（加密存储）、启用状态
- `ai_model` 表：id、provider_id、模型名、显示名、vision_capable、启用状态、参数 JSON
- `ai_task_config` 表：任务类型（data_import / substitution_recommend 等）、分配模式（unified / custom）、provider_id、model_id
- 视觉模型与文本模型共用同一套供应商管理机制，通过 `vision_capable` 标记区分
- API Key 加密存储（AES-256），日志中永不打印明文
