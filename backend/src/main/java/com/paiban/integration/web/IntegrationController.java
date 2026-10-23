package com.paiban.integration.web;

import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.web.FoundationDtos.AuditLogDto;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.AuthService;
import com.paiban.identity.web.IdentityDtos.LoginResponse;
import com.paiban.integration.auth.AuthCredentials;
import com.paiban.integration.auth.DingTalkAuthProvider;
import com.paiban.integration.domain.DingUser;
import com.paiban.integration.org.DingTalkOrgDataProvider;
import com.paiban.integration.org.DingTalkSyncDecisions;
import com.paiban.integration.org.OrgSyncResult;
import com.paiban.integration.web.DingTalkSyncDtos.ConfirmRequest;
import com.paiban.integration.web.DingTalkSyncDtos.SyncPreviewDto;
import com.paiban.integration.web.DingTalkSyncDtos.SuspectedDecisionDto;
import com.paiban.integration.web.IntegrationDtos.DingTalkConfigDto;
import com.paiban.integration.web.IntegrationDtos.DingTalkEventRequest;
import com.paiban.integration.web.IntegrationDtos.DingTalkLoginRequest;
import com.paiban.integration.web.IntegrationDtos.UpdateDingTalkConfigRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class IntegrationController {

    private final SettingsService settings;
    private final DingTalkAuthProvider authProvider;
    private final DingTalkOrgDataProvider orgProvider;
    private final AuthService authService;
    private final FoundationService foundation;

    public IntegrationController(
            SettingsService settings,
            DingTalkAuthProvider authProvider,
            DingTalkOrgDataProvider orgProvider,
            AuthService authService,
            FoundationService foundation) {
        this.settings = settings;
        this.authProvider = authProvider;
        this.orgProvider = orgProvider;
        this.authService = authService;
        this.foundation = foundation;
    }

    @GetMapping("/integrations/dingtalk/config")
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public DingTalkConfigDto config() {
        return new DingTalkConfigDto(
                settings.dingtalkEnabled(),
                settings.dingtalkAppKey(),
                settings.dingtalkAppSecret() != null && !settings.dingtalkAppSecret().isBlank(),
                settings.dingtalkAgentId(),
                settings.dingtalkCorpId(),
                settings.dingtalkSyncStrategy());
    }

    /** Public: the login page needs to know whether to attempt H5免登 and with which corpId. */
    @GetMapping("/auth/dingtalk/config")
    public Map<String, Object> publicConfig() {
        return Map.of(
                "enabled", settings.dingtalkEnabled(),
                "corpId", settings.dingtalkCorpId());
    }

    @PutMapping("/integrations/dingtalk/config")
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public DingTalkConfigDto update(@RequestBody UpdateDingTalkConfigRequest request) {
        if (request.enabled() != null) {
            settings.put(SettingsService.DINGTALK_ENABLED, String.valueOf(request.enabled()));
        }
        if (request.appKey() != null) {
            settings.put(SettingsService.DINGTALK_APP_KEY, request.appKey());
        }
        if (request.appSecret() != null && !request.appSecret().isBlank()) {
            settings.put(SettingsService.DINGTALK_APP_SECRET, request.appSecret());
        }
        if (request.agentId() != null) {
            settings.put(SettingsService.DINGTALK_AGENT_ID, request.agentId());
        }
        if (request.corpId() != null) {
            settings.put(SettingsService.DINGTALK_CORP_ID, request.corpId());
        }
        if (request.syncStrategy() != null) {
            if (!List.of("dingtalk", "local").contains(request.syncStrategy())) {
                throw ApiException.badRequest("同步策略只能是 dingtalk 或 local");
            }
            settings.put(SettingsService.DINGTALK_SYNC_STRATEGY, request.syncStrategy());
        }
        return config();
    }

    @PostMapping("/integrations/dingtalk/sync")
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public OrgSyncResult sync() {
        return orgProvider.sync();
    }

    /**
     * Strictly read-only classification of the readable directory. Nothing is
     * written here, not even an audit row.
     */
    @PostMapping("/integrations/dingtalk/sync/preview")
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public SyncPreviewDto preview(@RequestBody(required = false) ConfirmRequest request) {
        return DingTalkSyncDtos.from(orgProvider.preview(decisions(request)));
    }

    /** Writes the selected scope; re-plans the directory so preview and write agree. */
    @PostMapping("/integrations/dingtalk/sync/confirm")
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public OrgSyncResult confirm(@RequestBody(required = false) ConfirmRequest request) {
        return orgProvider.confirm(decisions(request));
    }

    private DingTalkSyncDecisions decisions(ConfirmRequest request) {
        if (request == null) {
            return DingTalkSyncDecisions.none();
        }
        List<DingTalkSyncDecisions.Decision> suspected = new ArrayList<>();
        if (request.suspectedDecisions() != null) {
            for (SuspectedDecisionDto dto : request.suspectedDecisions()) {
                if (dto == null || dto.userId() == null) {
                    continue;
                }
                DingTalkSyncDecisions.Action action;
                try {
                    action = DingTalkSyncDecisions.Action.valueOf(
                            dto.action() == null ? "" : dto.action().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    throw ApiException.badRequest("疑似同名处理只能是 MERGE / CREATE / SKIP");
                }
                if (action == DingTalkSyncDecisions.Action.MERGE && dto.teacherId() == null) {
                    throw ApiException.badRequest("合并疑似同名用户时必须指定 teacherId");
                }
                suspected.add(new DingTalkSyncDecisions.Decision(dto.userId(), action, dto.teacherId()));
            }
        }
        return DingTalkSyncDecisions.of(
                request.excludedDepartments(), request.excludedUserIds(), suspected);
    }

    @GetMapping("/integrations/dingtalk/logs")
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public List<AuditLogDto> logs() {
        return foundation.auditLogs().stream()
                .filter(log -> "DINGTALK_SYNC".equals(log.action()))
                .toList();
    }

    /** Stream-mode callback entry point, authenticated with the configured app secret. */
    @PostMapping("/integrations/dingtalk/event")
    public Map<String, String> event(
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = "X-DingTalk-Token", required = false)
                    String token,
            @RequestBody DingTalkEventRequest request) {
        if (!settings.dingtalkEnabled()) {
            throw ApiException.badRequest("钉钉集成未启用");
        }
        String expected = settings.dingtalkEventToken();
        if (expected == null || expected.isBlank() || token == null
                || !java.security.MessageDigest.isEqual(
                        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw ApiException.forbidden("钉钉事件校验失败");
        }
        orgProvider.applyEvent(
                request.type(),
                new DingUser(request.userId(), request.name(), request.mobile(), request.email(),
                        request.jobNumber()));
        return Map.of("status", "ok");
    }

    @PostMapping("/auth/dingtalk")
    public LoginResponse login(@Valid @RequestBody DingTalkLoginRequest request) {
        if (!settings.dingtalkEnabled()) {
            throw ApiException.badRequest("钉钉集成未启用");
        }
        String username = authProvider.authenticate(new AuthCredentials(null, null, request.authCode()))
                .orElseThrow(() -> ApiException.unauthorized("钉钉免登失败，请确认账号已同步"));
        return authService.issueFor(username);
    }
}
