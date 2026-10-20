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
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google Gemini: {@code POST {baseUrl}/models/{model}:generateContent} with the key in
 * {@code x-goog-api-key}. Images travel inline as base64 {@code inlineData} parts.
 */
@Component
public class GeminiStrategy implements AiStrategy {

    private static final int MAX_ERROR_BODY = 500;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration timeout;

    public GeminiStrategy(ObjectMapper objectMapper, @Value("${paiban.ai.timeout-seconds:30}") long timeoutSeconds) {
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
        String baseUrl = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().toLowerCase(Locale.ROOT);
        return baseUrl.contains("gemini") || baseUrl.contains("generativelanguage");
    }

    @Override
    public AiStrategyOutcome invoke(AiInvocation invocation) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(invocation))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            String apiKey = invocation.apiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("x-goog-api-key", apiKey);
            }
            builder.POST(HttpRequest.BodyPublishers.ofString(body(invocation), StandardCharsets.UTF_8));
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
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return AiStrategyOutcome.failure("PROVIDER_ERROR", "AI 调用失败: " + truncate(message), null);
        }
    }

    String body(AiInvocation invocation) {
        ObjectNode root = objectMapper.createObjectNode();
        List<AiImage> images = invocation.request().images() == null ? List.of() : invocation.request().images();
        ArrayNode contents = root.putArray("contents");
        ArrayNode systemParts = objectMapper.createArrayNode();
        for (AiMessage message : invocation.request().messages()) {
            String role = message.role() == null ? "user" : message.role();
            if ("system".equals(role)) {
                systemParts.addObject().put("text", message.content());
                continue;
            }
            ObjectNode content = contents.addObject();
            content.put("role", "assistant".equals(role) ? "model" : "user");
            ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text", message.content() == null ? "" : message.content());
            for (AiImage image : images) {
                ObjectNode inline = parts.addObject().putObject("inlineData");
                inline.put("mimeType", image.mimeType() == null ? "image/png" : image.mimeType());
                inline.put("data", Base64.getEncoder().encodeToString(image.data()));
            }
        }
        if (!systemParts.isEmpty()) {
            root.putObject("systemInstruction").set("parts", systemParts);
        }
        ObjectNode config = root.putObject("generationConfig");
        AiOptions options = invocation.request().options();
        Double temperature = AiStrategyParams.temperature(invocation.model(), options, objectMapper);
        if (temperature != null) {
            config.put("temperature", temperature);
        }
        Integer maxTokens = AiStrategyParams.maxTokens(invocation.model(), options, objectMapper);
        if (maxTokens != null) {
            config.put("maxOutputTokens", maxTokens);
        }
        if (AiStrategyParams.jsonMode(options)) {
            config.put("responseMimeType", "application/json");
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
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                String blocked = root.path("promptFeedback").path("blockReason").asText(null);
                String message = blocked == null ? "AI 响应缺少候选结果" : "AI 拒绝生成内容: " + blocked;
                return AiStrategyOutcome.failure("PROVIDER_ERROR", message, status);
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode part : candidates.get(0).path("content").path("parts")) {
                String value = part.path("text").asText(null);
                if (value != null) {
                    text.append(value);
                }
            }
            if (text.isEmpty()) {
                String finish = candidates.get(0).path("finishReason").asText(null);
                return AiStrategyOutcome.failure(
                        "PROVIDER_ERROR", finish == null ? "AI 响应缺少内容" : "AI 未返回内容: " + finish, status);
            }
            JsonNode usage = root.path("usageMetadata");
            return AiStrategyOutcome.success(
                    text.toString(), intOrNull(usage.get("promptTokenCount")),
                    intOrNull(usage.get("candidatesTokenCount")), body);
        } catch (Exception ex) {
            return AiStrategyOutcome.failure("PROVIDER_ERROR", "无法解析 AI 响应", status);
        }
    }

    private URI endpoint(AiInvocation invocation) {
        String baseUrl = invocation.provider().getBaseUrl() == null ? "" : invocation.provider().getBaseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String model = invocation.model().getModelName();
        if (model.startsWith("models/")) {
            model = model.substring("models/".length());
        }
        return URI.create(baseUrl + "/models/" + model + ":generateContent");
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_ERROR_BODY ? compact : compact.substring(0, MAX_ERROR_BODY);
    }
}
