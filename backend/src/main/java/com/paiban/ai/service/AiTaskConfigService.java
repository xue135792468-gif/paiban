package com.paiban.ai.service;

import com.paiban.ai.AiTaskType;
import com.paiban.ai.domain.AiModel;
import com.paiban.ai.domain.AiModelRepository;
import com.paiban.ai.domain.AiProvider;
import com.paiban.ai.domain.AiProviderRepository;
import com.paiban.ai.domain.AiTaskConfig;
import com.paiban.ai.domain.AiTaskConfigRepository;
import com.paiban.ai.event.AiConfigChangedEvent;
import com.paiban.ai.web.AiDtos.SaveTaskConfigRequest;
import com.paiban.ai.web.AiDtos.TaskAssignment;
import com.paiban.ai.web.AiDtos.TaskConfigBundleDto;
import com.paiban.ai.web.AiDtos.TaskConfigDto;
import com.paiban.common.web.ApiException;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTaskConfigService {

    public static final String MODE_UNIFIED = "unified";
    public static final String MODE_CUSTOM = "custom";

    private final AiTaskConfigRepository taskConfigs;
    private final AiProviderRepository providers;
    private final AiModelRepository models;
    private final ApplicationEventPublisher events;

    public AiTaskConfigService(
            AiTaskConfigRepository taskConfigs,
            AiProviderRepository providers,
            AiModelRepository models,
            ApplicationEventPublisher events) {
        this.taskConfigs = taskConfigs;
        this.providers = providers;
        this.models = models;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public TaskConfigBundleDto get() {
        List<AiTaskConfig> rows = taskConfigs.findAllByOrderByIdAsc();
        String mode = rows.stream()
                .map(AiTaskConfig::getMode)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(MODE_UNIFIED);
        Map<AiTaskType, AiTaskConfig> byType = new EnumMap<>(AiTaskType.class);
        rows.forEach(row -> byType.put(row.getTaskType(), row));
        List<TaskConfigDto> tasks = Arrays.stream(AiTaskType.values())
                .map(type -> toDto(type, byType.get(type)))
                .toList();
        return new TaskConfigBundleDto(mode, tasks);
    }

    @Transactional
    public TaskConfigBundleDto save(SaveTaskConfigRequest request) {
        String mode = request.mode() == null ? "" : request.mode().trim();
        if (!MODE_UNIFIED.equals(mode) && !MODE_CUSTOM.equals(mode)) {
            throw ApiException.badRequest("分配模式只能是 unified 或 custom");
        }
        if (MODE_UNIFIED.equals(mode)) {
            saveUnified(request.providerId(), request.modelId());
        } else {
            saveCustom(request.tasks());
        }
        events.publishEvent(new AiConfigChangedEvent("task-config"));
        return get();
    }

    private void saveUnified(Long providerId, Long modelId) {
        if (providerId == null || modelId == null) {
            throw ApiException.badRequest("统一模式需要选择供应商与模型");
        }
        validateAssignment(providerId, modelId);
        for (AiTaskType taskType : AiTaskType.values()) {
            upsert(taskType, MODE_UNIFIED, providerId, modelId);
        }
    }

    private void saveCustom(List<TaskAssignment> assignments) {
        Map<AiTaskType, TaskAssignment> byType = new LinkedHashMap<>();
        if (assignments != null) {
            for (TaskAssignment assignment : assignments) {
                AiTaskType taskType = AiTaskType.fromValue(assignment.taskType());
                if (byType.put(taskType, assignment) != null) {
                    throw ApiException.badRequest("任务类型重复: " + assignment.taskType());
                }
            }
        }
        for (AiTaskType taskType : AiTaskType.values()) {
            TaskAssignment assignment = byType.get(taskType);
            if (assignment == null || (assignment.providerId() == null && assignment.modelId() == null)) {
                taskConfigs.findByTaskType(taskType).ifPresent(taskConfigs::delete);
                continue;
            }
            if (assignment.providerId() == null || assignment.modelId() == null) {
                throw ApiException.badRequest(
                        "任务 " + taskType.value() + " 需要同时选择供应商与模型");
            }
            validateAssignment(assignment.providerId(), assignment.modelId());
            upsert(taskType, MODE_CUSTOM, assignment.providerId(), assignment.modelId());
        }
    }

    private void validateAssignment(Long providerId, Long modelId) {
        AiProvider provider = providers.findById(providerId)
                .orElseThrow(() -> ApiException.badRequest("供应商不存在: " + providerId));
        if (!provider.isEnabled()) {
            throw ApiException.badRequest("供应商已停用: " + provider.getName());
        }
        AiModel model = models.findById(modelId)
                .orElseThrow(() -> ApiException.badRequest("模型不存在: " + modelId));
        if (!Objects.equals(model.getProviderId(), providerId)) {
            throw ApiException.badRequest("模型不属于该供应商: " + model.getModelName());
        }
        if (!model.isEnabled()) {
            throw ApiException.badRequest("模型已停用: " + model.getModelName());
        }
    }

    private void upsert(AiTaskType taskType, String mode, Long providerId, Long modelId) {
        AiTaskConfig config = taskConfigs.findByTaskType(taskType).orElseGet(AiTaskConfig::new);
        config.setTaskType(taskType);
        config.setMode(mode);
        config.setProviderId(providerId);
        config.setModelId(modelId);
        config.setUpdatedAt(Instant.now());
        taskConfigs.save(config);
    }

    private TaskConfigDto toDto(AiTaskType taskType, AiTaskConfig config) {
        if (config == null || config.getProviderId() == null || config.getModelId() == null) {
            return new TaskConfigDto(taskType.value(), false, null, null, null, null, false);
        }
        AiProvider provider = providers.findById(config.getProviderId()).orElse(null);
        AiModel model = models.findById(config.getModelId()).orElse(null);
        boolean configured = provider != null
                && provider.isEnabled()
                && model != null
                && model.isEnabled()
                && Objects.equals(model.getProviderId(), provider.getId());
        return new TaskConfigDto(
                taskType.value(),
                configured,
                config.getProviderId(),
                provider == null ? null : provider.getName(),
                config.getModelId(),
                model == null ? null : model.getModelName(),
                model != null && model.isVisionCapable());
    }
}
