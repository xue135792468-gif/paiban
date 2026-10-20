package com.paiban.integration.web;

import jakarta.validation.constraints.NotBlank;

public final class IntegrationDtos {

    private IntegrationDtos() {}

    public record DingTalkConfigDto(boolean enabled, String appKey, boolean hasSecret,
                                    String agentId, String corpId, String syncStrategy) {}

    public record UpdateDingTalkConfigRequest(Boolean enabled, String appKey, String appSecret,
                                              String agentId, String corpId, String syncStrategy) {}

    public record DingTalkEventRequest(String type, String userId, String name, String mobile,
                                       String email, String jobNumber) {}

    public record DingTalkLoginRequest(@NotBlank(message = "authCode 不能为空") String authCode) {}
}
