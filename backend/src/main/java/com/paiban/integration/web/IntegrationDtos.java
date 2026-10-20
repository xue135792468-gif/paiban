package com.paiban.integration.web;

import com.paiban.identity.web.IdentityDtos.UserSummary;
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

    public record FeishuLoginRequest(@NotBlank(message = "code 不能为空") String code) {}

    /**
     * {@code status="ok"} carries the session; {@code status="binding_required"} carries
     * only the short-lived {@code bindToken} used by the self-service binding flow.
     */
    public record FeishuLoginResponse(String status, String token, Long expiresInMinutes,
                                      UserSummary user, String bindToken) {}
}
