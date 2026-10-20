# 二期跨票据接口契约

Status: binding for tickets 02–06

本文件固定票据之间共享的命名、数据形状与迁移编号。实现票据时先读本文件；若确有冲突，先在报告里指出再改。

## 迁移编号（固定，禁止变更）

| 版本 | 归属 | 内容 |
|---|---|---|
| V13 | 01 | `ai_provider`、`ai_model`、`ai_task_config` |
| V15 | 02 | `ai_call_log` |
| V16 | 03 | `ai_import_log` |

05 不新增迁移（`school.type` 存入现有 `app_setting`）。04 不新增迁移（推荐记录写入 `ai_call_log`）。06 原则上不新增迁移；若缺列，用 V17。

## Java 包与类型（02 建立，03/04/06 复用）

包 `com.paiban.ai`：

```java
public enum AiTaskType { DATA_IMPORT, SUBSTITUTION_RECOMMEND }

public record AiMessage(String role, String content) {}      // role: system | user | assistant
public record AiImage(String mimeType, byte[] data) {}

public record AiOptions(
        Double temperature,   // null = 模型参数默认
        Integer maxTokens,    // null = 模型参数默认
        boolean jsonMode) {}

public record AiRequest(
        AiTaskType taskType,
        List<AiMessage> messages,
        List<AiImage> images,     // chat() 时为 null/空
        AiOptions options) {}

public record AiResult(
        boolean success,
        String content,           // 纯文本或 JSON 字符串
        String errorCode,         // 失败时非空，如 NOT_CONFIGURED / VISION_NOT_SUPPORTED / TIMEOUT / PROVIDER_ERROR
        String errorMessage,
        Long providerId,
        String providerName,
        Long modelId,
        String modelName,
        Integer inputTokens,
        Integer outputTokens,
        long latencyMs,
        String rawResponse) {}

public interface AiGateway {
    AiResult chat(AiRequest request);
    AiResult chatWithVision(AiRequest request);
    boolean configured();                      // 至少一个启用的 provider + model + 两条任务配置
}
```

- 策略接口 `AiStrategy`，实现 `DeepSeekStrategy`、`GeminiStrategy`、`OpenAiCompatibleStrategy`。
- 网关按 `request.taskType()` 从 `ai_task_config` 解出 provider/model，再选策略；配置变更后缓存失效。
- 超时 30s，失败重试 2 次（间隔 1s）；4xx 不重试（429 除外）。
- 所有失败返回 `AiResult`，不向业务层抛异常，不阻断业务。
- `chatWithVision` 在模型 `vision_capable=false` 时直接返回 `VISION_NOT_SUPPORTED`。

## `ai_call_log`（V15，票据 02）

列：`id`、`task_type`、`provider_id`、`provider_name`、`model_id`、`model_name`、`user_id`、`subject_type`、`subject_id`、`success`、`error_code`、`error_message`、`input_tokens`、`output_tokens`、`latency_ms`、`request_json` CLOB、`response_json` CLOB、`created_at`。

- `request_json` / `response_json` 截断到 20000 字符，**绝不包含 API Key**。
- `subject_type` / `subject_id`：调代推荐写 `SUBSTITUTION_REQUEST` + 申请 id；数据导入可空。
- 索引：`created_at`、`task_type`、`user_id`。
- 每次 `AiGateway` 调用（含失败）都写一条。

## AI 状态接口（02，04 前端依赖）

`GET /api/ai/status`：登录用户可读（不要 settings:manage），返回：

```json
{
  "configured": true,
  "tasks": {
    "data_import": {"configured": true, "providerId": 1, "modelId": 2, "visionCapable": true},
    "substitution_recommend": {"configured": true, "providerId": 1, "modelId": 2}
  }
}
```

## `ai_import_log`（V16，票据 03，06 只读不扩表）

列：`id`、`user_id`、`username`、`import_type`（`excel`/`text`/`image`）、`target_type`（二期固定 `teacher`）、`file_name`、`file_size`、`file_path`、`source_text` CLOB、`preview_json` CLOB、`confirmed_json` CLOB、`result_json` CLOB、`ai_explanation` CLOB、`ai_raw_response` CLOB、`status`（`PREVIEW`/`EDITED`/`COMMITTED`/`FAILED`）、`row_count`、`imported_count`、`created_at`、`updated_at`。

- 文件落在 `uploads/ai-import/`，`GET /api/ai/import/sessions/{id}/file` 鉴权后回放（图片作缩略图，Excel 返回原文件）。
- 03 应一次性建全上述列，06 不再扩展。

## 数据导入预览形状（03）

```json
{
  "sessionId": 12,
  "targetType": "teacher",
  "columns": [{"field": "name", "label": "姓名", "required": true}],
  "rows": [
    {
      "values": {"name": "张三", "subject": "语文", "phone": "138..."},
      "confidence": {"name": 0.99, "subject": 0.72},
      "lowConfidence": ["subject"]
    }
  ],
  "mappings": [{"source": "姓名", "target": "name", "note": "原始「姓名」列 → 系统 name 字段"}],
  "explanation": "……",
  "threshold": 0.8
}
```

AI 结构化输出（系统 Prompt 强制 JSON）：

```json
{
  "mappings": [{"source": "姓名", "target": "name", "note": "..."}],
  "rows": [{"values": {"name": "张三"}, "confidence": {"name": 0.99}}],
  "explanation": "……"
}
```

## 调代推荐（04）

- `POST /api/substitution-requests/{id}/recommend`，权限 `SUBSTITUTION_APPROVE`。
- 返回：`{"requestId": 1, "recommendations": [{"teacherId": 7, "name": "李老师", "reason": "...", "score": 92}], "providerName": "...", "modelName": "..."}`，固定最多 3 人。
- AI 输出结构：`[{"teacherId": 7, "name": "李老师", "reason": "...", "score": 92}]`。
- 候选池必须来自后端规则查询（同学科、当天该时段无课、本学期代课次数），AI 不得凭空造 id；返回的 id 不在候选池则丢弃。
- 调用日志写 `ai_call_log`，`subject_type=SUBSTITUTION_REQUEST`、`subject_id=申请 id`，`request_json` 含请假上下文与候选池，`response_json` 含 AI 原始推荐。

## 历史与审计（06）

- `GET /api/ai/history`：admin（`SETTINGS_MANAGE`）可读，合并 `ai_import_log` 与 `ai_call_log`，按时间倒序；筛选 `from`、`to`、`userId`、`taskType`、`providerId`、`type`；分页 `page`/`size`。
- 每条记录至少含：`type`（`import`/`call`）、`taskType`、`username`、`createdAt`、`summary`、`detail`。
- `GET /api/ai/history/export?format=xlsx|pdf`：同一筛选条件导出。
- 调代推荐详情需关联 `substitution_request` 展示最终选择（`substituteTeacherId` 或申请期望代课人），06 通过 `subject_id` 查询。

## 共享约定

- 权限统一复用 `Permissions.SETTINGS_MANAGE`（admin 全权限）与 `Permissions.SUBSTITUTION_APPROVE`；不新增权限点。
- 错误响应沿用 `ApiException.badRequest/notFound` 与现有全局异常处理。
- 前后端文案中文；前端统一 `@/api/http`。
- 集成测试统一 `extends ApiIntegrationTest` + `@ActiveProfiles("test")`；AI 调用一律注入 fake `AiGateway`（`@TestConfiguration` + `@Primary`），**禁止 CI 访问外网**。
