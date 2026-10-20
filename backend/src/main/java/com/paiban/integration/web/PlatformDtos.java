package com.paiban.integration.web;

/** Wire format for the IM platform switch (ticket 05). */
public final class PlatformDtos {

    private PlatformDtos() {}

    /**
     * {@code configured} means the credentials needed to actually use the platform
     * are present; {@code enabled} is the persisted integration switch.
     */
    public record PlatformStatusDto(String activePlatform, boolean dingtalkEnabled, boolean feishuEnabled,
                                    boolean dingtalkConfigured, boolean feishuConfigured) {}

    public record SwitchPlatformRequest(String platform) {}
}
