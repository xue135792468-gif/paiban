package com.paiban.integration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.deletion.DeletionRestoreHandler;
import com.paiban.common.deletion.RecentDeletion;
import com.paiban.common.deletion.RecentDeletionWriter;
import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Ticket 04's “clear DingTalk configuration” action. DingTalk is stored as settings
 * keys, so deletion snapshots every key and then blanks them; the recycle bin can put
 * the values back for 7 days. The snapshot carries the secret (it is recycle-bin data,
 * not an audit row); the audit detail deliberately only names the action and label.
 */
@Service
public class DingtalkConfigLifecycleService implements DeletionRestoreHandler {

    static final String ENTITY_TYPE = "DINGTALK_CONFIG";
    private static final String KIND_LABEL = "钉钉配置";
    private static final long ENTITY_ID = 0L;

    private final SettingsService settings;
    private final RecentDeletionWriter deletionWriter;
    private final ObjectMapper objectMapper;

    public DingtalkConfigLifecycleService(
            SettingsService settings,
            RecentDeletionWriter deletionWriter,
            ObjectMapper objectMapper) {
        this.settings = settings;
        this.deletionWriter = deletionWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> delete() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(SettingsService.DINGTALK_ENABLED, String.valueOf(settings.dingtalkEnabled()));
        snapshot.put(SettingsService.DINGTALK_APP_KEY, settings.dingtalkAppKey());
        snapshot.put(SettingsService.DINGTALK_APP_SECRET, settings.dingtalkAppSecret());
        snapshot.put(SettingsService.DINGTALK_AGENT_ID, settings.dingtalkAgentId());
        snapshot.put(SettingsService.DINGTALK_CORP_ID, settings.dingtalkCorpId());
        snapshot.put(SettingsService.DINGTALK_EVENT_TOKEN, settings.dingtalkEventToken());
        snapshot.put(SettingsService.DINGTALK_SYNC_STRATEGY, settings.dingtalkSyncStrategy());

        String json;
        try {
            json = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("钉钉配置快照无法序列化", ex);
        }

        settings.put(SettingsService.DINGTALK_ENABLED, "false");
        settings.put(SettingsService.DINGTALK_APP_KEY, "");
        settings.put(SettingsService.DINGTALK_APP_SECRET, "");
        settings.put(SettingsService.DINGTALK_AGENT_ID, "");
        settings.put(SettingsService.DINGTALK_CORP_ID, "");
        settings.put(SettingsService.DINGTALK_EVENT_TOKEN, "");

        String batchId = deletionWriter.record(
                ENTITY_TYPE, ENTITY_ID, KIND_LABEL, KIND_LABEL, json);
        return Map.of("batchId", batchId, "deletedCount", 1);
    }

    @Override
    public boolean supports(String entityType) {
        return ENTITY_TYPE.equalsIgnoreCase(entityType);
    }

    @Override
    public void restore(RecentDeletion deletion) {
        JsonNode snapshot = readSnapshot(deletion.getSnapshotJson());
        if (snapshot == null || !snapshot.isObject()) {
            throw ApiException.conflict("钉钉配置快照缺失，无法恢复");
        }
        snapshot.fields().forEachRemaining(entry ->
                settings.put(entry.getKey(), entry.getValue().asText("")));
    }

    @Override
    public void restore(Long entityId) {
        throw ApiException.conflict("钉钉配置缺少恢复快照，无法恢复");
    }

    private JsonNode readSnapshot(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
