package com.paiban.integration.platform;

import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.service.FoundationService;
import com.paiban.integration.org.DingTalkOrgDataProvider;
import com.paiban.integration.org.FeishuOrgDataProvider;
import com.paiban.integration.org.OrgSyncResult;
import com.paiban.integration.web.PlatformDtos.PlatformStatusDto;
import org.springframework.stereotype.Service;

/**
 * One-click switch between the IM platforms (ticket 05).
 *
 * <p>Deliberately non-transactional: the settings writes commit on their own and the
 * directory sync performs network calls. Local business data (teachers, classes,
 * courses, timetable, substitutions) is never touched — only the platform flags move.
 */
@Service
public class PlatformSwitchService {

    private static final String DINGTALK = "dingtalk";
    private static final String FEISHU = "feishu";
    private static final String NONE = "none";

    private final SettingsService settings;
    private final DingTalkOrgDataProvider dingtalkOrg;
    private final FeishuOrgDataProvider feishuOrg;
    private final FoundationService foundation;

    public PlatformSwitchService(
            SettingsService settings,
            DingTalkOrgDataProvider dingtalkOrg,
            FeishuOrgDataProvider feishuOrg,
            FoundationService foundation) {
        this.settings = settings;
        this.dingtalkOrg = dingtalkOrg;
        this.feishuOrg = feishuOrg;
        this.foundation = foundation;
    }

    public PlatformStatusDto status() {
        return new PlatformStatusDto(
                settings.activePlatform(),
                settings.dingtalkEnabled(),
                settings.feishuEnabled(),
                isConfigured(settings.dingtalkAppKey(), settings.dingtalkAppSecret()),
                isConfigured(settings.feishuAppId(), settings.feishuAppSecret()));
    }

    /** Switches the active platform, syncs the target directory and audits the move. */
    public PlatformStatusDto switchTo(String platform) {
        if (!SettingsService.isPlatform(platform)) {
            throw ApiException.badRequest("当前平台只能是 none / dingtalk / feishu");
        }
        requireConfigured(platform);
        String from = settings.activePlatform();

        settings.put(SettingsService.ACTIVE_PLATFORM, platform);
        settings.put(SettingsService.DINGTALK_ENABLED, String.valueOf(DINGTALK.equals(platform)));
        settings.put(SettingsService.FEISHU_ENABLED, String.valueOf(FEISHU.equals(platform)));

        OrgSyncResult sync = syncTarget(platform);
        int successRows = sync == null ? 0 : sync.created() + sync.updated();
        String detail = "从 " + from + " 切换到 " + platform
                + (sync == null ? "；未触发组织同步" : "；" + sync.message());
        foundation.audit("system", "PLATFORM_SWITCH", "integration", detail, successRows, 0);
        return status();
    }

    private OrgSyncResult syncTarget(String platform) {
        if (DINGTALK.equals(platform)) {
            return dingtalkOrg.sync();
        }
        if (FEISHU.equals(platform)) {
            return feishuOrg.sync();
        }
        return null;
    }

    private void requireConfigured(String platform) {
        if (DINGTALK.equals(platform)
                && !isConfigured(settings.dingtalkAppKey(), settings.dingtalkAppSecret())) {
            throw ApiException.badRequest("启用钉钉前请先在「钉钉集成」中填写 AppKey 与 AppSecret");
        }
        if (FEISHU.equals(platform)
                && !isConfigured(settings.feishuAppId(), settings.feishuAppSecret())) {
            throw ApiException.badRequest("启用飞书前请先在「飞书集成」中填写 App ID 与 App Secret");
        }
    }

    private static boolean isConfigured(String first, String second) {
        return !isBlank(first) && !isBlank(second);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
