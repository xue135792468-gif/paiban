package com.paiban.integration.service;

import com.paiban.common.settings.SettingsService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the saved AppKey and AppSecret in one short read-only REPEATABLE_READ
 * transaction. The two getters still issue two selects, so the explicit isolation
 * is what makes the key/secret pair a consistent snapshot.
 */
@Component
public class DingTalkStoredCredentialReader {

    private final SettingsService settings;

    public DingTalkStoredCredentialReader(SettingsService settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StoredCredentials read() {
        return new StoredCredentials(settings.dingtalkAppKey(), settings.dingtalkAppSecret());
    }

    public record StoredCredentials(String appKey, String appSecret) {}
}
