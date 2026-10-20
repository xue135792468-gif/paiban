package com.paiban.integration.web;

/**
 * Feishu-specific HTTP payloads, kept apart from {@link IntegrationDtos} so other
 * tickets can evolve the DingTalk contract without touching this file.
 */
public final class FeishuIntegrationDtos {

    private FeishuIntegrationDtos() {}

    /** Never contains the app secret: only whether one is stored. */
    public record FeishuConfigDto(boolean enabled, String appId, boolean hasSecret,
                                  String syncStrategy, boolean verificationTokenConfigured) {}

    /** A null field leaves the stored value untouched; a blank appSecret means "unchanged". */
    public record UpdateFeishuConfigRequest(Boolean enabled, String appId, String appSecret,
                                            String syncStrategy, String verificationToken,
                                            String encryptKey) {}
}
