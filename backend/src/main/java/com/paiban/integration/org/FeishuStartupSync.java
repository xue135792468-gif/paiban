package com.paiban.integration.org;

import com.paiban.common.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Spec 03 「启动时全量拉取」: when Feishu is enabled and is the active platform,
 * pull the directory once after the application is ready.
 *
 * <p>This is best-effort: a failed fetch is logged and never blocks startup. Tests
 * and e2e profiles switch it off so their state stays deterministic.
 */
@Component
public class FeishuStartupSync {

    private static final Logger log = LoggerFactory.getLogger(FeishuStartupSync.class);

    private final SettingsService settings;
    private final FeishuOrgDataProvider orgProvider;
    private final boolean syncOnStartup;

    public FeishuStartupSync(
            SettingsService settings,
            FeishuOrgDataProvider orgProvider,
            @Value("${paiban.integration.feishu.sync-on-startup:true}") boolean syncOnStartup) {
        this.settings = settings;
        this.orgProvider = orgProvider;
        this.syncOnStartup = syncOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!syncOnStartup
                || !settings.feishuEnabled()
                || !settings.isActivePlatform(SettingsService.PLATFORM_FEISHU)) {
            return;
        }
        try {
            OrgSyncResult result = orgProvider.sync();
            log.info("启动时飞书组织同步完成: {}", result.message());
        } catch (RuntimeException ex) {
            log.warn("启动时飞书组织同步失败: {}", ex.getMessage());
        }
    }
}
