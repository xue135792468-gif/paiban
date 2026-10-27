package com.paiban.ai.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paiban.ai.AiImage;
import com.paiban.ai.AiMessage;
import com.paiban.ai.AiOptions;
import com.paiban.ai.domain.AiProvider;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code POST {baseUrl}/chat/completions} with a Bearer token — the shape shared by
 * DeepSeek, Qwen, GLM and most relay stations. Also the fallback for custom providers.
 */
@Component
public class OpenAiCompatibleStrategy implements AiStrategy {

    private static final int MAX_ERROR_BODY = 500;

    protected final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration timeout;

    public OpenAiCompatibleStrategy(
            ObjectMapper objectMapper, @Value("${paiban.ai.timeout-seconds:120}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public boolean supports(AiProvider provider) {
        if (provider == null) {
            return false;
        }
        String protocol = provider.getProtocol();
        return protocol == null || protocol.isBlank() || "openai".equalsIgnoreCase(protocol);
    }

    @Override
    public boolean fallback() {
        return true;
    }

    @Override
    public AiStrategyOutcome invoke(AiInvocation invocation) {
        Duration requestTimeout = requestTimeout(invocation);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(invocation.provider().getBaseUrl()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            String apiKey = invocation.apiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            builder.POST(HttpRequest.BodyPublishers.ofString(
                    body(invocation), StandardCharsets.UTF_8));
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return interpret(response.statusCode(), response.body());
        } catch (HttpTimeoutException ex) {
            return AiStrategyOutcome.failure(
                    "TIMEOUT", "AI 调用超时（" + requestTimeout.toSeconds() + " 秒）", null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AiStrategyOutcome.failure("PROVIDER_ERROR", "AI 调用被中断", 499);
        } catch (ConnectException ex) {
            return AiStrategyOutcome.failure("PROVIDER_ERROR", "无法连接到 AI 供应商", null);
        } catch (Exception ex) {
            return AiStrategyOutcome.failure("PROVIDER_ERROR", describe(ex), null);
        }
    }

    /** Exposed for subclasses that need the same body shape. */
    protected String body(AiInvocation invocation) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", invocation.model().getModelName());
        ArrayNode messages = root.putArray("messages");
        List<AiImage> images = invocation.request().images() == null ? List.of() : invocation.request().images();
        for (AiMessage message : invocation.request().messages()) {
            ObjectNode node = messages.addObject();
            String role = message.role() == null ? "user" : message.role();
            node.put("role", role);
            if (images.isEmpty() || !"user".equals(role)) {
                node.put("content", message.content());
            } else {
                ArrayNode parts = node.putArray("content");
                parts.addObject().put("type", "text").put("text", message.content());
                for (AiImage image : images) {
                    ObjectNode part = parts.addObject();
                    part.put("type", "image_url");
                    part.putObject("image_url").put("url", dataUrl(image));
                }
            }
        }
        AiOptions options = invocation.request().options();
        Double temperature = AiStrategyParams.temperature(
                invocation.model(), options, objectMapper);
        if (temperature != null) {
            root.put("temperature", temperature);
        }
        Integer maxTokens = AiStrategyParams.maxTokens(invocation.model(), options, objectMapper);
        if (maxTokens != null) {
            root.put("max_tokens", maxTokens);
        }
        if (AiStrategyParams.jsonMode(options)) {
            root.putObject("response_format").put("type", "json_object");
        }
        return root.toString();
    }

    /** Maps one HTTP answer onto an outcome; never surfaces the response body unchecked. */
    protected AiStrategyOutcome interpret(int status, String body) {
        if (status < 200 || status >= 300) {
            return AiStrategyOutcome.failure(
                    "PROVIDER_ERROR", "AI 供应商返回 HTTP " + status + ": " + truncate(body), status);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                String finishReason = choices.get(0).path("finish_reason").asText("");
                if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) {
                    return AiStrategyOutcome.failure(
                            "TOOL_CALL",
                            "模型返回了工具调用而不是数据，请换用支持 JSON 输出的模型",
                            status);
                }
                if ("length".equals(finishReason)) {
                    return AiStrategyOutcome.failure(
                            "TRUNCATED",
                            "AI 输出被截断（length），请增大模型输出上限或减少单次行数",
                            status);
                }
            }
            String content = null;
            if (choices.isArray() && !choices.isEmpty()) {
                content = choices.get(0).path("message").path("content").asText(null);
            }
            if (content == null) {
                return AiStrategyOutcome.failure("PROVIDER_ERROR", "AI 响应缺少内容", status);
            }
            return AiStrategyOutcome.success(
                    content, intOrNull(root.path("usage").get("prompt_tokens")),
                    intOrNull(root.path("usage").get("completion_tokens")), body);
        } catch (Exception ex) {
            return AiStrategyOutcome.failure("PROVIDER_ERROR", "无法解析 AI 响应", status);
        }
    }

    protected static String dataUrl(AiImage image) {
        return "data:" + (image.mimeType() == null ? "image/png" : image.mimeType())
                + ";base64," + Base64.getEncoder().encodeToString(image.data());
    }

    protected static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    protected static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_ERROR_BODY ? compact : compact.substring(0, MAX_ERROR_BODY);
    }

    private String describe(Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return "AI 调用失败: " + truncate(message);
    }

    private URI endpoint(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized + "/chat/completions");
    }

    /** The task may cap its own call; every other task keeps the gateway default. */
    private Duration requestTimeout(AiInvocation invocation) {
        Integer seconds = invocation.request() == null || invocation.request().options() == null
                ? null : invocation.request().options().timeoutSeconds();
        return seconds != null && seconds > 0 ? Duration.ofSeconds(seconds) : timeout;
    }
}
