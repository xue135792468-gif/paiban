package com.paiban.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.web.AiDtos.ConnectionTestDto;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Lightweight provider probe: {@code GET {baseUrl}/models} with the decrypted key
 * as a Bearer token. Failures are returned as data (HTTP 200 + success=false),
 * never thrown, and never echo the key.
 */
@Component
public class AiConnectionTester {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_MESSAGE_LENGTH = 300;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public AiConnectionTester(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ConnectionTestDto test(String baseUrl, String apiKey) {
        long started = System.nanoTime();
        if (apiKey == null || apiKey.isBlank()) {
            return new ConnectionTestDto(false, "未配置 API Key", elapsed(started), List.of());
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(modelsUri(baseUrl))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .GET();
            // Gemini accepts the key as a query/header; send the native header too.
            if (baseUrl != null && baseUrl.contains("generativelanguage.googleapis.com")) {
                builder.header("x-goog-api-key", apiKey);
            }
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latency = elapsed(started);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ConnectionTestDto(
                        false, "连接失败: HTTP " + response.statusCode(), latency, List.of());
            }
            List<String> models = parseModels(response.body());
            String message = models.isEmpty()
                    ? "连接成功（未返回模型列表）"
                    : "连接成功，发现 " + models.size() + " 个模型";
            return new ConnectionTestDto(true, message, latency, models);
        } catch (HttpTimeoutException ex) {
            return new ConnectionTestDto(false, "连接超时（5 秒）", elapsed(started), List.of());
        } catch (ConnectException ex) {
            return new ConnectionTestDto(false, "连接失败: 无法连接到服务器", elapsed(started), List.of());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ConnectionTestDto(false, "连接被中断", elapsed(started), List.of());
        } catch (Exception ex) {
            return new ConnectionTestDto(false, sanitize("连接失败: " + ex.getMessage()), elapsed(started), List.of());
        }
    }

    private URI modelsUri(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized + "/models");
    }

    /** Accepts the OpenAI {@code data[].id} and Gemini {@code models[].name} shapes. */
    List<String> parseModels(String body) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.path("data");
        if (data.isArray()) {
            collect(data, "id", names);
        }
        JsonNode models = root.path("models");
        if (models.isArray()) {
            collect(models, "name", names);
        }
        return new ArrayList<>(names);
    }

    private void collect(JsonNode array, String field, Set<String> names) {
        for (JsonNode node : array) {
            String value = node.isTextual() ? node.asText() : node.path(field).asText(null);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.startsWith("models/")) {
                value = value.substring("models/".length());
            }
            names.add(value);
        }
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private String sanitize(String message) {
        if (message == null) {
            return "连接失败";
        }
        return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH);
    }
}
