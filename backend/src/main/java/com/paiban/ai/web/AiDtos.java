package com.paiban.ai.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AiDtos {

    private AiDtos() {}

    public record ModelDto(
            Long id,
            Long providerId,
            String modelName,
            String displayName,
            boolean visionCapable,
            boolean enabled,
            String paramsJson,
            Instant createdAt) {}

    public record ProviderDto(
            Long id,
            String name,
            String type,
            String baseUrl,
            boolean hasApiKey,
            String apiKeyMasked,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt,
            List<ModelDto> models) {}

    public record SaveModelRequest(
            @NotBlank(message = "模型名不能为空") @Size(max = 200) String modelName,
            @Size(max = 200) String displayName,
            Boolean visionCapable,
            Boolean enabled,
            String paramsJson) {}

    public record SaveProviderRequest(
            @NotBlank(message = "供应商名称不能为空") @Size(max = 100) String name,
            @NotBlank(message = "供应商类型不能为空") String type,
            @NotBlank(message = "Base URL 不能为空") @Size(max = 500) String baseUrl,
            String apiKey,
            Boolean enabled,
            @Valid List<SaveModelRequest> models) {}

    /** A preset vendor template; {@code code} is the frontend identifier. */
    public record PresetDto(String code, String name, String baseUrl, List<String> models) {}

    public record ConnectionTestDto(boolean success, String message, long latencyMs, List<String> modelNames) {}

    public record TaskConfigDto(
            String taskType,
            boolean configured,
            Long providerId,
            String providerName,
            Long modelId,
            String modelName,
            boolean visionCapable) {}

    public record TaskConfigBundleDto(String mode, List<TaskConfigDto> tasks) {}

    public record TaskAssignment(String taskType, Long providerId, Long modelId) {}

    public record SaveTaskConfigRequest(
            @NotBlank(message = "分配模式不能为空") String mode,
            Long providerId,
            Long modelId,
            List<TaskAssignment> tasks) {}

    /** Per-task AI availability; null fields are dropped by the serializer when unconfigured. */
    public record AiTaskStatusDto(boolean configured, Long providerId, Long modelId, Boolean visionCapable) {}

    public record AiStatusDto(boolean configured, Map<String, AiTaskStatusDto> tasks) {}
}
