package com.paiban.common.settings;

import com.paiban.common.policy.ExchangeRankingPreset;
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

    public static final String SUBSTITUTION_SEARCH_WINDOW_DAYS = "substitution.search-window-days";
    public static final String SUBSTITUTION_MAX_PLAN_TEACHERS = "substitution.max-plan-teachers";
    public static final String SUBSTITUTION_RANKING_PRESET = "substitution.ranking-preset";
    /** Extra notification recipients, stored as a comma-separated list of user ids. */
    public static final String SUBSTITUTION_NOTIFY_RECIPIENTS = "substitution.notify.recipients";
    public static final String NOTIFICATION_SCOPE = "notification.scope";
    public static final String DINGTALK_ENABLED = "integration.dingtalk.enabled";
    public static final String DINGTALK_APP_KEY = "integration.dingtalk.app-key";
    public static final String DINGTALK_APP_SECRET = "integration.dingtalk.app-secret";
    public static final String DINGTALK_AGENT_ID = "integration.dingtalk.agent-id";
    public static final String DINGTALK_CORP_ID = "integration.dingtalk.corp-id";
    public static final String DINGTALK_EVENT_TOKEN = "integration.dingtalk.event-token";
    public static final String DINGTALK_SYNC_STRATEGY = "integration.dingtalk.sync-strategy";
    public static final String PLATFORM_NONE = "none";
    public static final String PLATFORM_DINGTALK = "dingtalk";
    public static final String SCHOOL_NAME = "school.name";
    public static final String SCHOOL_LOGO_PATH = "school.logo-path";
    public static final String PUBLIC_BASE_URL = "public.base-url";
    public static final String AI_SUBSTITUTE_RECOMMENDATION = "ai.features.substitute-recommendation";
    public static final String AI_NATURAL_LANGUAGE_CONSTRAINTS = "ai.features.natural-language-constraints";
    public static final String AI_IMPORT_CONFIDENCE_THRESHOLD = "ai.import.confidence-threshold";

    private final AppSettingRepository repository;

    private final String defaultNotificationScope;
    private final boolean defaultDingtalkEnabled;
    private final String defaultDingtalkAppKey;
    private final String defaultDingtalkAppSecret;
    private final String defaultDingtalkAgentId;
    private final String defaultDingtalkSyncStrategy;
    private final String defaultSchoolLogoPath;

    public SettingsService(
            AppSettingRepository repository,
            @Value("${paiban.notification.scope:minimal}") String defaultNotificationScope,
            @Value("${paiban.integration.dingtalk.enabled:false}") boolean defaultDingtalkEnabled,
            @Value("${paiban.integration.dingtalk.app-key:}") String defaultDingtalkAppKey,
            @Value("${paiban.integration.dingtalk.app-secret:}") String defaultDingtalkAppSecret,
            @Value("${paiban.integration.dingtalk.agent-id:}") String defaultDingtalkAgentId,
            @Value("${paiban.integration.dingtalk.sync-strategy:dingtalk}") String defaultDingtalkSyncStrategy,
            @Value("${paiban.export.school-logo-path:}") String defaultSchoolLogoPath) {
        this.repository = repository;
        this.defaultNotificationScope = defaultNotificationScope;
        this.defaultDingtalkEnabled = defaultDingtalkEnabled;
        this.defaultDingtalkAppKey = defaultDingtalkAppKey;
        this.defaultDingtalkAppSecret = defaultDingtalkAppSecret;
        this.defaultDingtalkAgentId = defaultDingtalkAgentId;
        this.defaultDingtalkSyncStrategy = defaultDingtalkSyncStrategy;
        this.defaultSchoolLogoPath = defaultSchoolLogoPath;
    }

    public String get(String key, String fallback) {
        return repository.findById(key).map(AppSetting::getValue).orElse(fallback);
    }

    /** Cross-day swap search window in natural days; 7 by default, 1-30. */
    public int substitutionSearchWindowDays() {
        return clampInt(get(SUBSTITUTION_SEARCH_WINDOW_DAYS, "7"), 1, 30, 7);
    }

    /** Maximum lessons a single exchange plan may move (the cycle length); 6 by default, 2-10. */
    public int exchangeMaxMovedLessons() {
        return clampInt(get(SUBSTITUTION_MAX_PLAN_TEACHERS, "6"), 2, 10, 6);
    }

    /**
     * Legacy name for {@link #exchangeMaxMovedLessons()}.
     *
     * @deprecated the setting has always capped the number of moved lessons,
     *     not the number of distinct teachers; use {@link #exchangeMaxMovedLessons()}.
     */
    @Deprecated
    public int substitutionMaxPlanTeachers() {
        return exchangeMaxMovedLessons();
    }

    /** min-impact | min-teachers | stable-timetable. */
    public String substitutionRankingPreset() {
        String value = get(SUBSTITUTION_RANKING_PRESET, "min-impact");
        return switch (value) {
            case "min-impact", "min-teachers", "stable-timetable" -> value;
            default -> "min-impact";
        };
    }

    /** The shared ordering preset resolved from the legacy ranking-preset key. */
    public ExchangeRankingPreset exchangeRankingPreset() {
        return ExchangeRankingPreset.fromCode(substitutionRankingPreset());
    }

    /** User accounts that always receive substitution notices, on top of the scope. */
    public java.util.List<Long> substitutionRecipientUserIds() {
        String raw = get(SUBSTITUTION_NOTIFY_RECIPIENTS, "");
        if (raw.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // A stale or hand-edited value must never break notifications.
            }
        }
        return ids;
    }

    private int clampInt(String raw, int min, int max, int fallback) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ex) {
            return fallback;
        }
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

    /** DingTalk is the only supported integration platform. */
    public String activePlatform() {
        return dingtalkEnabled() ? PLATFORM_DINGTALK : PLATFORM_NONE;
    }

    /** True when the given platform is the one currently in charge. */
    public boolean isActivePlatform(String platform) {
        return platform != null && platform.equals(activePlatform());
    }

    public String schoolName() {
        return get(SCHOOL_NAME, "示例中学");
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

    /**
     * 「保存设置」是一次业务动作，不是 N 次独立写入：整批一个事务，
     * 任何一项失败（含数据库列约束）都不留下半写结果。校验仍由控制器先做。
     */
    @Transactional
    public void putAll(Map<String, String> values) {
        values.forEach(this::put);
    }

    @Transactional(readOnly = true)
    public Map<String, String> all() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SUBSTITUTION_SEARCH_WINDOW_DAYS, String.valueOf(substitutionSearchWindowDays()));
        values.put(SUBSTITUTION_MAX_PLAN_TEACHERS, String.valueOf(substitutionMaxPlanTeachers()));
        values.put(SUBSTITUTION_RANKING_PRESET, substitutionRankingPreset());
        values.put(NOTIFICATION_SCOPE, notificationScope());
        values.put(DINGTALK_ENABLED, String.valueOf(dingtalkEnabled()));
        values.put(DINGTALK_APP_KEY, dingtalkAppKey());
        values.put(DINGTALK_AGENT_ID, dingtalkAgentId());
        values.put(DINGTALK_CORP_ID, dingtalkCorpId());
        values.put(DINGTALK_EVENT_TOKEN, dingtalkEventToken());
        values.put(DINGTALK_SYNC_STRATEGY, dingtalkSyncStrategy());
        values.put(SCHOOL_NAME, schoolName());
        values.put(SCHOOL_LOGO_PATH, schoolLogoPath());
        values.put(PUBLIC_BASE_URL, publicBaseUrl());
        values.put(AI_SUBSTITUTE_RECOMMENDATION, String.valueOf(aiSubstituteRecommendation()));
        values.put(AI_NATURAL_LANGUAGE_CONSTRAINTS, String.valueOf(aiNaturalLanguageConstraints()));
        values.put(AI_IMPORT_CONFIDENCE_THRESHOLD, String.valueOf(aiImportConfidenceThreshold()));
        return values;
    }
}
