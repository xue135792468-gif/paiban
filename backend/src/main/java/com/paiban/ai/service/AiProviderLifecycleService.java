package com.paiban.ai.service;

import com.paiban.ai.domain.AiModel;
import com.paiban.ai.domain.AiModelRepository;
import com.paiban.ai.domain.AiProvider;
import com.paiban.ai.domain.AiProviderRepository;
import com.paiban.ai.event.AiConfigChangedEvent;
import com.paiban.common.deletion.DeletionRestoreHandler;
import com.paiban.common.deletion.RecentDeletion;
import com.paiban.common.web.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restore half of ticket 04's AI soft delete. Deleting a provider or a model only
 * stamps {@code deleted_at}; this handler clears it again. Task bindings are not
 * re-enabled automatically — the user decides which routing should run.
 */
@Service
public class AiProviderLifecycleService implements DeletionRestoreHandler {

    static final String PROVIDER_TYPE = "AI_PROVIDER";
    static final String MODEL_TYPE = "AI_MODEL";

    private final AiProviderRepository providers;
    private final AiModelRepository models;
    private final ApplicationEventPublisher events;

    public AiProviderLifecycleService(
            AiProviderRepository providers,
            AiModelRepository models,
            ApplicationEventPublisher events) {
        this.providers = providers;
        this.models = models;
        this.events = events;
    }

    @Override
    public boolean supports(String entityType) {
        return PROVIDER_TYPE.equalsIgnoreCase(entityType) || MODEL_TYPE.equalsIgnoreCase(entityType);
    }

    @Override
    public void restore(Long entityId) {
        // Without the row type we cannot know whether the id names a provider or a
        // model; the id spaces overlap, so only the recycle row is unambiguous.
        throw ApiException.conflict("AI 配置缺少删除类型，无法恢复");
    }

    @Override
    @Transactional
    public void restore(RecentDeletion deletion) {
        if (PROVIDER_TYPE.equalsIgnoreCase(deletion.getEntityType())) {
            restoreProvider(deletion.getEntityId());
        } else if (MODEL_TYPE.equalsIgnoreCase(deletion.getEntityType())) {
            restoreModel(deletion.getEntityId());
        } else {
            throw ApiException.conflict("不支持的 AI 删除类型: " + deletion.getEntityType());
        }
        events.publishEvent(new AiConfigChangedEvent("restored"));
    }

    private void restoreProvider(Long providerId) {
        AiProvider provider = providers.findById(providerId)
                .orElseThrow(() -> ApiException.notFound("AI 供应商不存在: " + providerId));
        provider.setDeletedAt(null);
        providers.save(provider);
        for (AiModel model : models.findByProviderIdOrderByIdAsc(providerId)) {
            if (model.getDeletedAt() != null) {
                model.setDeletedAt(null);
                models.save(model);
            }
        }
    }

    private void restoreModel(Long modelId) {
        AiModel model = models.findById(modelId)
                .orElseThrow(() -> ApiException.notFound("AI 模型不存在: " + modelId));
        model.setDeletedAt(null);
        models.save(model);
        // The provider may itself still be recycled; that state is not this row's.
    }
}
