package com.paiban.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.crypto.ApiKeyCipher;
import com.paiban.ai.domain.AiModel;
import com.paiban.ai.domain.AiModelRepository;
import com.paiban.ai.domain.AiProvider;
import com.paiban.ai.domain.AiProviderRepository;
import com.paiban.ai.domain.AiTaskConfig;
import com.paiban.ai.domain.AiTaskConfigRepository;
import com.paiban.ai.event.AiConfigChangedEvent;
import com.paiban.ai.web.AiDtos.ConnectionTestDto;
import com.paiban.ai.web.AiDtos.ModelDto;
import com.paiban.ai.web.AiDtos.PresetDto;
import com.paiban.ai.web.AiDtos.ProviderDto;
import com.paiban.ai.web.AiDtos.SaveModelRequest;
import com.paiban.ai.web.AiDtos.SaveProviderRequest;
import com.paiban.common.web.ApiException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiProviderService {

    private static final List<PresetDto> PRESETS = List.of(
            new PresetDto("deepseek", "DeepSeek", "https://api.deepseek.com",
                    List.of("deepseek-flash", "deepseek-v4-pro")),
            new PresetDto("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta",
                    List.of("gemini-2.0-flash", "gemini-2.5-pro")),
            new PresetDto("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    List.of("qwen-max", "qwen-plus", "qwen-vl-max")),
            new PresetDto("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
                    List.of("glm-4-plus", "glm-4v-plus")));

    private static final int MAX_PARAMS_JSON_LENGTH = 4000;

    private final AiProviderRepository providers;
    private final AiModelRepository models;
    private final AiTaskConfigRepository taskConfigs;
    private final ApiKeyCipher cipher;
    private final AiConnectionTester connectionTester;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public AiProviderService(
            AiProviderRepository providers,
            AiModelRepository models,
            AiTaskConfigRepository taskConfigs,
            ApiKeyCipher cipher,
            AiConnectionTester connectionTester,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events) {
        this.providers = providers;
        this.models = models;
        this.taskConfigs = taskConfigs;
        this.cipher = cipher;
        this.connectionTester = connectionTester;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<PresetDto> presets() {
        return PRESETS;
    }

    @Transactional(readOnly = true)
    public List<ProviderDto> list() {
        return providers.findAllByOrderByIdAsc().stream()
                .map(provider -> toDto(provider, models.findByProviderIdOrderByIdAsc(provider.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProviderDto get(Long id) {
        AiProvider provider = requireProvider(id);
        return toDto(provider, models.findByProviderIdOrderByIdAsc(id));
    }

    @Transactional
    public ProviderDto create(SaveProviderRequest request) {
        String name = request.name().trim();
        providers.findByName(name).ifPresent(existing -> {
            throw ApiException.conflict("供应商名称已存在: " + name);
        });
        AiProvider provider = new AiProvider();
        provider.setName(name);
        provider.setType(validateType(request.type()));
        provider.setBaseUrl(validateBaseUrl(request.baseUrl()));
        provider.setEnabled(request.enabled() == null || request.enabled());
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            provider.setApiKeyCipher(cipher.encrypt(request.apiKey().trim()));
        }
        Instant now = Instant.now();
        provider.setCreatedAt(now);
        provider.setUpdatedAt(now);
        providers.save(provider);

        if (request.models() != null) {
            for (SaveModelRequest modelRequest : request.models()) {
                createModel(provider.getId(), modelRequest);
            }
        }
        configChanged("provider-created");
        return get(provider.getId());
    }

    @Transactional
    public ProviderDto update(Long id, SaveProviderRequest request) {
        AiProvider provider = requireProvider(id);
        String name = request.name().trim();
        providers.findByName(name)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw ApiException.conflict("供应商名称已存在: " + name);
                });
        provider.setName(name);
        provider.setType(validateType(request.type()));
        provider.setBaseUrl(validateBaseUrl(request.baseUrl()));
        if (request.enabled() != null) {
            provider.setEnabled(request.enabled());
        }
        // A blank key keeps the stored one; the UI never receives the plaintext.
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            provider.setApiKeyCipher(cipher.encrypt(request.apiKey().trim()));
        }
        provider.setUpdatedAt(Instant.now());
        configChanged("provider-updated");
        return toDto(provider, models.findByProviderIdOrderByIdAsc(id));
    }

    @Transactional
    public void delete(Long id) {
        AiProvider provider = requireProvider(id);
        taskConfigs.deleteByProviderId(id);
        models.deleteAll(models.findByProviderIdOrderByIdAsc(id));
        providers.delete(provider);
        configChanged("provider-deleted");
    }

    @Transactional
    public ModelDto createModel(Long providerId, SaveModelRequest request) {
        AiProvider provider = requireProvider(providerId);
        String modelName = request.modelName().trim();
        models.findByProviderIdAndModelName(providerId, modelName).ifPresent(existing -> {
            throw ApiException.conflict("模型已存在: " + modelName);
        });
        AiModel model = new AiModel();
        model.setProviderId(providerId);
        model.setModelName(modelName);
        model.setDisplayName(displayName(request.displayName(), modelName));
        model.setVisionCapable(Boolean.TRUE.equals(request.visionCapable()));
        model.setEnabled(request.enabled() == null || request.enabled());
        model.setParamsJson(validateParamsJson(request.paramsJson()));
        model.setCreatedAt(Instant.now());
        models.save(model);
        applyDefaultAssignmentIfEmpty(provider, model);
        configChanged("model-created");
        return toDto(model);
    }

    @Transactional
    public ModelDto updateModel(Long providerId, Long modelId, SaveModelRequest request) {
        requireProvider(providerId);
        AiModel model = models.findByIdAndProviderId(modelId, providerId)
                .orElseThrow(() -> ApiException.notFound("模型不存在: " + modelId));
        String modelName = request.modelName().trim();
        models.findByProviderIdAndModelName(providerId, modelName)
                .filter(existing -> !existing.getId().equals(modelId))
                .ifPresent(existing -> {
                    throw ApiException.conflict("模型已存在: " + modelName);
                });
        model.setModelName(modelName);
        model.setDisplayName(displayName(request.displayName(), modelName));
        if (request.visionCapable() != null) {
            model.setVisionCapable(request.visionCapable());
        }
        if (request.enabled() != null) {
            model.setEnabled(request.enabled());
        }
        // Omitted params keep their stored value; send an empty string to clear them.
        if (request.paramsJson() != null) {
            model.setParamsJson(validateParamsJson(request.paramsJson()));
        }
        configChanged("model-updated");
        return toDto(model);
    }

    @Transactional
    public void deleteModel(Long providerId, Long modelId) {
        requireProvider(providerId);
        AiModel model = models.findByIdAndProviderId(modelId, providerId)
                .orElseThrow(() -> ApiException.notFound("模型不存在: " + modelId));
        taskConfigs.deleteByModelId(modelId);
        models.delete(model);
        configChanged("model-deleted");
    }

    @Transactional(readOnly = true)
    public ConnectionTestDto testConnection(Long id) {
        AiProvider provider = requireProvider(id);
        try {
            return connectionTester.test(provider.getBaseUrl(), cipher.decrypt(provider.getApiKeyCipher()));
        } catch (IllegalStateException ex) {
            return new ConnectionTestDto(false, "API Key 解密失败，请重新保存密钥", 0, List.of());
        }
    }

    @Transactional(readOnly = true)
    public List<String> fetchModels(Long id) {
        return testConnection(id).modelNames();
    }

    /**
     * The first model ever configured becomes the default for every task, in
     * unified mode. Later models never overwrite an existing assignment.
     */
    private void applyDefaultAssignmentIfEmpty(AiProvider provider, AiModel model) {
        if (taskConfigs.count() > 0) {
            return;
        }
        for (AiTaskType taskType : AiTaskType.values()) {
            AiTaskConfig config = new AiTaskConfig();
            config.setTaskType(taskType);
            config.setMode("unified");
            config.setProviderId(provider.getId());
            config.setModelId(model.getId());
            config.setUpdatedAt(Instant.now());
            taskConfigs.save(config);
        }
    }

    private AiProvider requireProvider(Long id) {
        return providers.findById(id).orElseThrow(() -> ApiException.notFound("AI 供应商不存在: " + id));
    }

    /** Routing may have changed, so the gateway's cached configuration must be dropped. */
    private void configChanged(String source) {
        events.publishEvent(new AiConfigChangedEvent(source));
    }

    private String validateType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase();
        if (!List.of("preset", "custom").contains(normalized)) {
            throw ApiException.badRequest("供应商类型只能是 preset 或 custom");
        }
        return normalized;
    }

    static String validateBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || !List.of("http", "https").contains(scheme.toLowerCase()) || uri.getHost() == null) {
                throw ApiException.badRequest("Base URL 必须是以 http:// 或 https:// 开头的完整地址");
            }
            return value;
        } catch (java.net.URISyntaxException ex) {
            throw ApiException.badRequest("Base URL 格式不正确: " + baseUrl);
        }
    }

    private String displayName(String requested, String modelName) {
        return requested == null || requested.isBlank() ? modelName : requested.trim();
    }

    private String validateParamsJson(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return null;
        }
        String trimmed = paramsJson.trim();
        if (trimmed.length() > MAX_PARAMS_JSON_LENGTH) {
            throw ApiException.badRequest("模型参数 JSON 过长（最多 " + MAX_PARAMS_JSON_LENGTH + " 字符）");
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            if (node == null || !node.isObject()) {
                throw ApiException.badRequest("模型参数必须是 JSON 对象");
            }
            return trimmed;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.badRequest("模型参数不是合法的 JSON");
        }
    }

    private ProviderDto toDto(AiProvider provider, List<AiModel> providerModels) {
        String masked = null;
        if (provider.getApiKeyCipher() != null) {
            try {
                masked = ApiKeyCipher.mask(cipher.decrypt(provider.getApiKeyCipher()));
            } catch (IllegalStateException ex) {
                masked = "****";
            }
        }
        return new ProviderDto(
                provider.getId(),
                provider.getName(),
                provider.getType(),
                provider.getBaseUrl(),
                provider.getApiKeyCipher() != null,
                masked,
                provider.isEnabled(),
                provider.getCreatedAt(),
                provider.getUpdatedAt(),
                providerModels.stream().map(this::toDto).toList());
    }

    private ModelDto toDto(AiModel model) {
        return new ModelDto(
                model.getId(),
                model.getProviderId(),
                model.getModelName(),
                model.getDisplayName(),
                model.isVisionCapable(),
                model.isEnabled(),
                model.getParamsJson(),
                model.getCreatedAt());
    }
}
