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
 * Anthropic Claude Messages API:
 * {@code POST {baseUrl}/v1/messages}, key in {@code x-api-key}, version header required.
 * A base URL that already ends in {@code /v1} is respected; a full {@code /messages}
 * endpoint is used as-is.
 */
@Component
public class AnthropicStrategy implements AiStrategy {

    private static final int MAX_ERROR_BODY = 500;
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration timeout;

    public AnthropicStrategy(
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
        return provider != null && "anthropic".equalsIgnoreCase(provider.getProtocol());
    }

    @Override
    public AiStrategyOutcome invoke(AiInvocation invocation) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(invocation.provider().getBaseUrl()))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("anthropic-version", ANTHROPIC_VERSION);
            String apiKey = invocation.apiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("x-api-key", apiKey);
            }
            builder.POST(HttpRequest.BodyPublishers.ofString(
                    body(invocation), StandardCharsets.UTF_8));
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return interpret(response.statusCode(), response.body());
        } catch (HttpTimeoutException ex) {
            return AiStrategyOutcome.failure("TIMEOUT", "AI 调用超时（" + timeout.toSeconds() + " 秒）", null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AiStrategyOutcome.failure("PROVIDER_ERROR", "AI 调用被中断", 499);
        } catch (ConnectException ex) {
            return AiStrategyOutcome.failure("PROVIDER_ERROR", "无法连接到 AI 供应商", null);
        } catch (Exception ex) {
            return AiStrategyOutcome.failure("PROVIDER_ERROR", describe(ex), null);
        }
    }

    String body(AiInvocation invocation) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", invocation.model().getModelName());
        AiOptions options = invocation.request().options();
        Integer maxTokens = AiStrategyParams.maxTokens(invocation.model(), options, objectMapper);
        root.put("max_tokens", maxTokens == null ? DEFAULT_MAX_TOKENS : maxTokens);
        Double temperature = AiStrategyParams.temperature(invocation.model(), options, objectMapper);
        if (temperature != null) {
            root.put("temperature", temperature);
        }

        StringBuilder system = new StringBuilder();
        ArrayNode messages = root.putArray("messages");
        List<AiImage> images = invocation.request().images() == null ? List.of() : invocation.request().images();
        for (AiMessage message : invocation.request().messages()) {
            String role = message.role() == null ? "user" : message.role();
            if ("system".equals(role)) {
                if (system.length() > 0) {
                    system.append('\n');
                }
                system.append(message.content() == null ? "" : message.content());
                continue;
            }
            ObjectNode node = messages.addObject();
            node.put("role", "assistant".equals(role) ? "assistant" : "user");
            if (images.isEmpty() || !"user".equals(role)) {
                node.put("content", message.content() == null ? "" : message.content());
            } else {
                ArrayNode parts = node.putArray("content");
                parts.addObject().put("type", "text").put("text", message.content() == null ? "" : message.content());
                for (AiImage image : images) {
                    ObjectNode part = parts.addObject();
                    part.put("type", "image");
                    ObjectNode source = part.putObject("source");
                    source.put("type", "base64");
                    source.put("media_type", image.mimeType() == null ? "image/png" : image.mimeType());
                    source.put("data", Base64.getEncoder().encodeToString(image.data()));
                }
            }
        }
        if (messages.isEmpty()) {
            messages.addObject().put("role", "user").put("content", "请按系统提示输出。");
        }
        if (AiStrategyParams.jsonMode(options)) {
            if (system.length() > 0) {
                system.append('\n');
            }
            system.append("只输出一个 JSON 对象，不要 Markdown 代码块或额外文字。");
        }
        if (system.length() > 0) {
            root.put("system", system.toString());
        }
        return root.toString();
    }

    AiStrategyOutcome interpret(int status, String body) {
        if (status < 200 || status >= 300) {
            return AiStrategyOutcome.failure(
                    "PROVIDER_ERROR", "AI 供应商返回 HTTP " + status + ": " + truncate(body), status);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode content = root.path("content");
            if (!content.isArray() || content.isEmpty()) {
                String type = root.path("error").path("type").asText(null);
                String message = root.path("error").path("message").asText(null);
                return AiStrategyOutcome.failure(
                        "PROVIDER_ERROR",
                        message == null ? "AI 响应缺少内容" + (type == null ? "" : "（" + type + "）") : message,
                        status);
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText(""));
                }
            }
            if (text.isEmpty()) {
                return AiStrategyOutcome.failure("PROVIDER_ERROR", "AI 响应缺少文本内容", status);
            }
            JsonNode usage = root.path("usage");
            return AiStrategyOutcome.success(
                    text.toString(), intOrNull(usage.get("input_tokens")),
                    intOrNull(usage.get("output_tokens")), body);
        } catch (Exception ex) {
            return AiStrategyOutcome.failure("PROVIDER_ERROR", "无法解析 AI 响应", status);
        }
    }

    private URI endpoint(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/messages")) {
            return URI.create(normalized);
        }
        if (normalized.endsWith("/v1")) {
            return URI.create(normalized + "/messages");
        }
        return URI.create(normalized + "/v1/messages");
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return "AI 调用失败: " + truncate(message);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_ERROR_BODY ? compact : compact.substring(0, MAX_ERROR_BODY);
    }
}
