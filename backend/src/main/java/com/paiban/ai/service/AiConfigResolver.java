package com.paiban.ai.service;

import com.paiban.ai.AiTaskType;
import com.paiban.ai.crypto.ApiKeyCipher;
import com.paiban.ai.domain.AiModel;
import com.paiban.ai.domain.AiModelRepository;
import com.paiban.ai.domain.AiProvider;
import com.paiban.ai.domain.AiProviderRepository;
import com.paiban.ai.domain.AiTaskConfig;
import com.paiban.ai.domain.AiTaskConfigRepository;
import com.paiban.ai.event.AiConfigChangedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns {@code ai_task_config} rows into the provider/model pair an AI task runs on.
 *
 * <p>Resolutions are cached in memory; every provider, model and task-configuration
 * write publishes {@link AiConfigChangedEvent}, which drops the cache so the next call
 * sees the new routing immediately.
 */
@Component
public class AiConfigResolver {

    /** Marker for "resolved and unusable" so a missing configuration is not re-read on every call. */
    private static final AiTarget UNRESOLVED = new AiTarget(null, null, null);

    private final AiTaskConfigRepository taskConfigs;
    private final AiProviderRepository providers;
    private final AiModelRepository models;
    private final ApiKeyCipher cipher;
    private final Map<AiTaskType, AiTarget> cache = new ConcurrentHashMap<>();

    public AiConfigResolver(
            AiTaskConfigRepository taskConfigs,
            AiProviderRepository providers,
            AiModelRepository models,
            ApiKeyCipher cipher) {
        this.taskConfigs = taskConfigs;
        this.providers = providers;
        this.models = models;
        this.cipher = cipher;
    }

    /** Cached lookup; empty when the task has no enabled provider/model pair. */
    public Optional<AiTarget> resolve(AiTaskType taskType) {
        if (taskType == null) {
            return Optional.empty();
        }
        AiTarget cached = cache.computeIfAbsent(taskType, this::load);
        return cached == UNRESOLVED ? Optional.empty() : Optional.of(cached);
    }

    /** True when every AI task slot resolves to an enabled provider and model. */
    public boolean configured() {
        for (AiTaskType taskType : AiTaskType.values()) {
            if (resolve(taskType).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Per-task status for {@code GET /api/ai/status}. */
    public Map<String, AiTaskStatus> status() {
        Map<String, AiTaskStatus> tasks = new LinkedHashMap<>();
        for (AiTaskType taskType : AiTaskType.values()) {
            AiTarget target = resolve(taskType).orElse(null);
            tasks.put(
                    taskType.value(),
                    target == null
                            ? new AiTaskStatus(false, null, null, null)
                            : new AiTaskStatus(
                                    true,
                                    target.provider().getId(),
                                    target.model().getId(),
                                    target.model().isVisionCapable()));
        }
        return tasks;
    }

    public void invalidate() {
        cache.clear();
    }

    /** After commit, so a concurrent read cannot cache the pre-change routing again. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onConfigChanged(AiConfigChangedEvent event) {
        invalidate();
    }

    /** Warm the cache at startup so the first business call does not pay for the lookup. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            invalidate();
            for (AiTaskType taskType : AiTaskType.values()) {
                resolve(taskType);
            }
        } catch (RuntimeException ex) {
            // A cold or unavailable database must not stop the application from starting.
            invalidate();
        }
    }

    private AiTarget load(AiTaskType taskType) {
        AiTaskConfig config = taskConfigs.findByTaskType(taskType).orElse(null);
        if (config == null || config.getProviderId() == null || config.getModelId() == null) {
            return UNRESOLVED;
        }
        AiProvider provider = providers.findById(config.getProviderId()).orElse(null);
        if (provider == null || !provider.isEnabled()) {
            return UNRESOLVED;
        }
        AiModel model = models.findById(config.getModelId()).orElse(null);
        if (model == null || !model.isEnabled() || !provider.getId().equals(model.getProviderId())) {
            return UNRESOLVED;
        }
        return new AiTarget(provider, model, decrypt(provider));
    }

    private String decrypt(AiProvider provider) {
        try {
            return cipher.decrypt(provider.getApiKeyCipher());
        } catch (RuntimeException ex) {
            // An undecryptable key degrades to "no key"; the provider call will then fail loudly.
            return null;
        }
    }

    /** Status shape consumed by {@code GET /api/ai/status}. */
    public record AiTaskStatus(boolean configured, Long providerId, Long modelId, Boolean visionCapable) {}
}
