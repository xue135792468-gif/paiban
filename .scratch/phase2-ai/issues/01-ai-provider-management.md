# 01: AI 供应商与模型管理

**What to build:** 实现完整的 AI 供应商管理面板：供应商 CRUD（预设 DeepSeek/Gemini/通义/智谱 + 自定义 OpenAI 兼容端点）、模型 CRUD（每供应商下挂多个模型，标记 vision_capable）、连接测试、统一/分开任务分配模式、参数折叠面板。完成后，admin 能在设置页添加 DeepSeek 供应商并配置 API Key，系统自动拉取模型列表，admin 能测试连通性，能设置"所有 AI 任务统一用 deepseek-flash"或"数据导入用 deepseek-flash、调代推荐用 deepseek-v4-pro"。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] `ai_provider` 表：id、名称、类型（preset/custom）、base_url、api_key（AES-256 加密）、启用状态
- [x] `ai_model` 表：id、provider_id、模型名、显示名、vision_capable、启用状态、参数 JSON
- [x] `ai_task_config` 表：任务类型（data_import/substitution_recommend）、分配模式（unified/custom）、provider_id、model_id
- [x] 预设供应商模板：DeepSeek、Gemini、通义千问、智谱 GLM（含默认 base_url 与模型列表）
- [x] 自定义供应商：用户填写名称、base_url、api_key、模型名列表
- [x] 连接测试接口：POST `/api/ai-providers/{id}/test`，调用轻量接口验证连通性
- [x] 统一/分开模式切换：统一模式时所有任务用同一模型；分开模式时每个任务独立选模型
- [x] 空值处理：未接入供应商时下拉为空；第一个接入的自动成为默认
- [x] 前端：供应商管理页面（列表 + 展开模型子列表 + 添加/编辑/删除 + 连接测试按钮）
- [x] 前端：模型参数折叠面板（默认收起为小箭头，展开显示 temperature/max_tokens 等）
- [x] REST Assured 集成测试：供应商 CRUD、模型 CRUD、连接测试、任务分配切换
- [x] 安全：API Key 加密存储，日志永不打印明文
