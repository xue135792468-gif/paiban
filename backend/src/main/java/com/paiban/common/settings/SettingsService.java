package com.paiban.common.settings;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runtime settings with database overrides on top of application.yml defaults.
 * Values take effect immediately (no restart), as required by the settings centre.
 */
@Service
public class SettingsService {

    public static final String SUBSTITUTION_APPROVAL_MODE = "substitution.approval-mode";
    public static final String NOTIFICATION_SCOPE = "notification.scope";
    public static final String DINGTALK_ENABLED = "integration.dingtalk.enabled";
    public static final String DINGTALK_APP_KEY = "integration.dingtalk.app-key";
    public static final String DINGTALK_APP_SECRET = "integration.dingtalk.app-secret";
    public static final String DINGTALK_AGENT_ID = "integration.dingtalk.agent-id";
    public static final String DINGTALK_CORP_ID = "integration.dingtalk.corp-id";
    public static final String DINGTALK_EVENT_TOKEN = "integration.dingtalk.event-token";
    public static final String DINGTALK_SYNC_STRATEGY = "integration.dingtalk.sync-strategy";
    public static final String FEISHU_ENABLED = "integration.feishu.enabled";
    public static final String FEISHU_APP_ID = "integration.feishu.app-id";
    public static final String FEISHU_APP_SECRET = "integration.feishu.app-secret";
    public static final String FEISHU_VERIFICATION_TOKEN = "integration.feishu.verification-token";
    public static final String FEISHU_ENCRYPT_KEY = "integration.feishu.encrypt-key";
    public static final String FEISHU_SYNC_STRATEGY = "integration.feishu.sync-strategy";
    /** none | dingtalk | feishu; blank derives from the enabled flags for backward compatibility. */
    public static final String ACTIVE_PLATFORM = "integration.active-platform";
    public static final String SCHOOL_NAME = "school.name";
    public static final String SCHOOL_TYPE = "school.type";
    public static final String SCHOOL_LOGO_PATH = "school.logo-path";
    public static final String PUBLIC_BASE_URL = "public.base-url";
    public static final String AI_SUBSTITUTE_RECOMMENDATION = "ai.features.substitute-recommendation";
    public static final String AI_NATURAL_LANGUAGE_CONSTRAINTS = "ai.features.natural-language-constraints";
    public static final String AI_IMPORT_CONFIDENCE_THRESHOLD = "ai.import.confidence-threshold";

    private final AppSettingRepository repository;

    private final String defaultApprovalMode;
    private final String defaultNotificationScope;
    private final boolean defaultDingtalkEnabled;
    private final String defaultDingtalkAppKey;
    private final String defaultDingtalkAppSecret;
    private final String defaultDingtalkAgentId;
    private final String defaultDingtalkSyncStrategy;
    private final boolean defaultFeishuEnabled;
    private final String defaultFeishuAppId;
    private final String defaultFeishuAppSecret;
    private final String defaultFeishuVerificationToken;
    private final String defaultFeishuEncryptKey;
    private final String defaultFeishuSyncStrategy;
    private final String defaultSchoolLogoPath;

    public SettingsService(
            AppSettingRepository repository,
            @Value("${paiban.substitution.approval-mode:approval}") String defaultApprovalMode,
            @Value("${paiban.notification.scope:minimal}") String defaultNotificationScope,
            @Value("${paiban.integration.dingtalk.enabled:false}") boolean defaultDingtalkEnabled,
            @Value("${paiban.integration.dingtalk.app-key:}") String defaultDingtalkAppKey,
            @Value("${paiban.integration.dingtalk.app-secret:}") String defaultDingtalkAppSecret,
            @Value("${paiban.integration.dingtalk.agent-id:}") String defaultDingtalkAgentId,
            @Value("${paiban.integration.dingtalk.sync-strategy:dingtalk}") String defaultDingtalkSyncStrategy,
            @Value("${paiban.integration.feishu.enabled:false}") boolean defaultFeishuEnabled,
            @Value("${paiban.integration.feishu.app-id:}") String defaultFeishuAppId,
            @Value("${paiban.integration.feishu.app-secret:}") String defaultFeishuAppSecret,
            @Value("${paiban.integration.feishu.verification-token:}") String defaultFeishuVerificationToken,
            @Value("${paiban.integration.feishu.encrypt-key:}") String defaultFeishuEncryptKey,
            @Value("${paiban.integration.feishu.sync-strategy:feishu}") String defaultFeishuSyncStrategy,
            @Value("${paiban.export.school-logo-path:}") String defaultSchoolLogoPath) {
        this.repository = repository;
        this.defaultApprovalMode = defaultApprovalMode;
        this.defaultNotificationScope = defaultNotificationScope;
        this.defaultDingtalkEnabled = defaultDingtalkEnabled;
        this.defaultDingtalkAppKey = defaultDingtalkAppKey;
        this.defaultDingtalkAppSecret = defaultDingtalkAppSecret;
        this.defaultDingtalkAgentId = defaultDingtalkAgentId;
        this.defaultDingtalkSyncStrategy = defaultDingtalkSyncStrategy;
        this.defaultFeishuEnabled = defaultFeishuEnabled;
        this.defaultFeishuAppId = defaultFeishuAppId;
        this.defaultFeishuAppSecret = defaultFeishuAppSecret;
        this.defaultFeishuVerificationToken = defaultFeishuVerificationToken;
        this.defaultFeishuEncryptKey = defaultFeishuEncryptKey;
        this.defaultFeishuSyncStrategy = defaultFeishuSyncStrategy;
        this.defaultSchoolLogoPath = defaultSchoolLogoPath;
    }

    public String get(String key, String fallback) {
        return repository.findById(key).map(AppSetting::getValue).orElse(fallback);
    }

    public String approvalMode() {
        return get(SUBSTITUTION_APPROVAL_MODE, defaultApprovalMode);
    }

    public String notificationScope() {
        return get(NOTIFICATION_SCOPE, defaultNotificationScope);
    }

    public boolean dingtalkEnabled() {
        return Boolean.parseBoolean(get(DINGTALK_ENABLED, String.valueOf(defaultDingtalkEnabled)));
    }

    public String dingtalkAppKey() {
        return get(DINGTALK_APP_KEY, defaultDingtalkAppKey);
    }

    public String dingtalkAppSecret() {
        return get(DINGTALK_APP_SECRET, defaultDingtalkAppSecret);
    }

    public String dingtalkAgentId() {
        return get(DINGTALK_AGENT_ID, defaultDingtalkAgentId);
    }

    public String dingtalkCorpId() {
        return get(DINGTALK_CORP_ID, "");
    }

    public String dingtalkEventToken() {
        return get(DINGTALK_EVENT_TOKEN, "");
    }

    public String dingtalkSyncStrategy() {
        return get(DINGTALK_SYNC_STRATEGY, defaultDingtalkSyncStrategy);
    }

    public boolean feishuEnabled() {
        return Boolean.parseBoolean(get(FEISHU_ENABLED, String.valueOf(defaultFeishuEnabled)));
    }

    public String feishuAppId() {
        return get(FEISHU_APP_ID, defaultFeishuAppId);
    }

    public String feishuAppSecret() {
        return get(FEISHU_APP_SECRET, defaultFeishuAppSecret);
    }

    public String feishuVerificationToken() {
        return get(FEISHU_VERIFICATION_TOKEN, defaultFeishuVerificationToken);
    }

    public String feishuEncryptKey() {
        return get(FEISHU_ENCRYPT_KEY, defaultFeishuEncryptKey);
    }

    public String feishuSyncStrategy() {
        return get(FEISHU_SYNC_STRATEGY, defaultFeishuSyncStrategy);
    }

    /**
     * The platform currently in charge of auth, org sync and notifications.
     *
     * <p>An explicit stored value wins. Otherwise the enabled flags decide, with
     * DingTalk first so a pre-05 deployment (both flags on) keeps its behaviour.
     */
    public String activePlatform() {
        String stored = get(ACTIVE_PLATFORM, "");
        if (isPlatform(stored)) {
            return stored;
        }
        if (dingtalkEnabled()) {
            return "dingtalk";
        }
        return feishuEnabled() ? "feishu" : "none";
    }

    public static boolean isPlatform(String value) {
        return "none".equals(value) || "dingtalk".equals(value) || "feishu".equals(value);
    }

    public String schoolName() {
        return get(SCHOOL_NAME, "示例中学");
    }

    /** Junior (初中) or senior (高中) mode; UI-only, never read by business logic. */
    public String schoolType() {
        return get(SCHOOL_TYPE, "junior");
    }

    public String schoolLogoPath() {
        return get(SCHOOL_LOGO_PATH, defaultSchoolLogoPath);
    }

    /** Absolute base URL used in notifications so links open from DingTalk. */
    public String publicBaseUrl() {
        return get(PUBLIC_BASE_URL, "http://localhost:5173");
    }

    public boolean aiSubstituteRecommendation() {
        return Boolean.parseBoolean(get(AI_SUBSTITUTE_RECOMMENDATION, "false"));
    }

    public boolean aiNaturalLanguageConstraints() {
        return Boolean.parseBoolean(get(AI_NATURAL_LANGUAGE_CONSTRAINTS, "false"));
    }

    /** Cells below this AI confidence are highlighted in the import preview. */
    public double aiImportConfidenceThreshold() {
        try {
            double value = Double.parseDouble(get(AI_IMPORT_CONFIDENCE_THRESHOLD, "0.8"));
            return Math.max(0, Math.min(1, value));
        } catch (NumberFormatException ex) {
            return 0.8;
        }
    }

    @Transactional
    public void put(String key, String value) {
        AppSetting setting = repository.findById(key).orElseGet(() -> new AppSetting(key, value));
        setting.setValue(value);
        setting.setUpdatedAt(Instant.now());
        repository.save(setting);
    }

    @Transactional(readOnly = true)
    public Map<String, String> all() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SUBSTITUTION_APPROVAL_MODE, approvalMode());
        values.put(NOTIFICATION_SCOPE, notificationScope());
        values.put(DINGTALK_ENABLED, String.valueOf(dingtalkEnabled()));
        values.put(DINGTALK_APP_KEY, dingtalkAppKey());
        values.put(DINGTALK_AGENT_ID, dingtalkAgentId());
        values.put(DINGTALK_CORP_ID, dingtalkCorpId());
        values.put(DINGTALK_EVENT_TOKEN, dingtalkEventToken());
        values.put(DINGTALK_SYNC_STRATEGY, dingtalkSyncStrategy());
        values.put(FEISHU_ENABLED, String.valueOf(feishuEnabled()));
        values.put(FEISHU_APP_ID, feishuAppId());
        values.put(FEISHU_VERIFICATION_TOKEN, feishuVerificationToken());
        values.put(FEISHU_SYNC_STRATEGY, feishuSyncStrategy());
        values.put(ACTIVE_PLATFORM, activePlatform());
        values.put(SCHOOL_NAME, schoolName());
        values.put(SCHOOL_TYPE, schoolType());
        values.put(SCHOOL_LOGO_PATH, schoolLogoPath());
        values.put(PUBLIC_BASE_URL, publicBaseUrl());
        values.put(AI_SUBSTITUTE_RECOMMENDATION, String.valueOf(aiSubstituteRecommendation()));
        values.put(AI_NATURAL_LANGUAGE_CONSTRAINTS, String.valueOf(aiNaturalLanguageConstraints()));
        values.put(AI_IMPORT_CONFIDENCE_THRESHOLD, String.valueOf(aiImportConfidenceThreshold()));
        return values;
    }
}
