package com.paiban.integration.web;

import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.identity.service.AuthService;
import com.paiban.identity.web.IdentityDtos.LoginResponse;
import com.paiban.integration.auth.FeishuAuthProvider;
import com.paiban.integration.auth.FeishuBindTokenService;
import com.paiban.integration.auth.FeishuBindingService;
import com.paiban.integration.feishu.FeishuClient;
import com.paiban.integration.feishu.FeishuUser;
import com.paiban.integration.web.IntegrationDtos.FeishuBindRequest;
import com.paiban.integration.web.IntegrationDtos.FeishuLoginRequest;
import com.paiban.integration.web.IntegrationDtos.FeishuLoginResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feishu H5免登. The authorization code is single-use, so it is exchanged exactly
 * once here; the resulting user is then resolved through {@link FeishuAuthProvider}.
 */
@RestController
@RequestMapping("/api/auth/feishu")
public class FeishuAuthController {

    private final SettingsService settings;
    private final FeishuClient client;
    private final FeishuAuthProvider authProvider;
    private final FeishuBindTokenService bindTokens;
    private final FeishuBindingService bindingService;
    private final AuthService authService;

    public FeishuAuthController(
            SettingsService settings,
            FeishuClient client,
            FeishuAuthProvider authProvider,
            FeishuBindTokenService bindTokens,
            FeishuBindingService bindingService,
            AuthService authService) {
        this.settings = settings;
        this.client = client;
        this.authProvider = authProvider;
        this.bindTokens = bindTokens;
        this.bindingService = bindingService;
        this.authService = authService;
    }

    /** Public: the login page needs to know whether to attempt H5免登 and with which App ID. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "enabled", settings.feishuEnabled(),
                "appId", settings.feishuAppId() == null ? "" : settings.feishuAppId());
    }

    @PostMapping
    public FeishuLoginResponse login(@Valid @RequestBody FeishuLoginRequest request) {
        if (!settings.feishuEnabled()) {
            throw ApiException.badRequest("飞书集成未启用");
        }
        FeishuUser feishuUser;
        try {
            feishuUser = client.exchangeAuthCode(request.code());
        } catch (RuntimeException ex) {
            throw ApiException.unauthorized("飞书免登失败，请确认账号已绑定");
        }
        if (feishuUser == null) {
            throw ApiException.unauthorized("飞书免登失败，请确认账号已绑定");
        }
        Optional<String> username = authProvider.authenticate(feishuUser);
        if (username.isPresent()) {
            LoginResponse session = authService.issueFor(username.get());
            return new FeishuLoginResponse(
                    "ok", session.token(), session.expiresInMinutes(), session.user(), null);
        }
        // No local account: hand back a short-lived bind token, never auto-provision.
        return new FeishuLoginResponse(
                "binding_required", null, null, null, bindTokens.issue(feishuUser));
    }

    /**
     * Public: the bind page exchanges the short-lived bind token for a session after
     * the teacher proves their identity with name + employee number/phone.
     */
    @PostMapping("/bind")
    public LoginResponse bind(@RequestBody FeishuBindRequest request) {
        FeishuUser identity = bindTokens.parse(request.bindToken())
                .orElseThrow(() -> ApiException.unauthorized("绑定链接已过期，请重新从飞书进入"));
        return bindingService.bind(identity, request.name(), request.employeeNo(), request.phone());
    }
}
