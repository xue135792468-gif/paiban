package com.paiban.integration.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.web.FoundationDtos.AuditLogDto;
import com.paiban.identity.domain.Permissions;
import com.paiban.integration.feishu.FeishuUser;
import com.paiban.integration.org.FeishuOrgDataProvider;
import com.paiban.integration.org.OrgSyncResult;
import com.paiban.integration.web.FeishuIntegrationDtos.FeishuConfigDto;
import com.paiban.integration.web.FeishuIntegrationDtos.UpdateFeishuConfigRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin configuration plus the public event callback for Feishu. The official
 * Stream SDK is not embedded; production deployments forward Stream events to
 * {@code POST /api/integrations/feishu/event} (see README 已知限制).
 */
@RestController
@RequestMapping("/api")
public class FeishuIntegrationController {

    private static final Set<String> HANDLED_EVENTS = Set.of(
            "contact.user.created_v3",
            "contact.user.updated_v3",
            "contact.user.deleted_v3");

    private final SettingsService settings;
    private final FeishuOrgDataProvider orgProvider;
    private final FoundationService foundation;

    public FeishuIntegrationController(
            SettingsService settings,
            FeishuOrgDataProvider orgProvider,
            FoundationService foundation) {
        this.settings = settings;
        this.orgProvider = orgProvider;
        this.foundation = foundation;
    }

    @GetMapping("/integrations/feishu/config")
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public FeishuConfigDto config() {
        return new FeishuConfigDto(
                settings.feishuEnabled(),
                settings.feishuAppId(),
                !isBlank(settings.feishuAppSecret()),
                settings.feishuSyncStrategy(),
                !isBlank(settings.feishuVerificationToken()));
    }

    @PutMapping("/integrations/feishu/config")
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public FeishuConfigDto update(@RequestBody UpdateFeishuConfigRequest request) {
        if (request.enabled() != null) {
            settings.put(SettingsService.FEISHU_ENABLED, String.valueOf(request.enabled()));
        }
        if (request.appId() != null) {
            settings.put(SettingsService.FEISHU_APP_ID, request.appId());
        }
        if (!isBlank(request.appSecret())) {
            settings.put(SettingsService.FEISHU_APP_SECRET, request.appSecret());
        }
        if (request.syncStrategy() != null) {
            if (!List.of("feishu", "local").contains(request.syncStrategy())) {
                throw ApiException.badRequest("同步策略只能是 feishu 或 local");
            }
            settings.put(SettingsService.FEISHU_SYNC_STRATEGY, request.syncStrategy());
        }
        if (request.verificationToken() != null) {
            settings.put(SettingsService.FEISHU_VERIFICATION_TOKEN, request.verificationToken());
        }
        if (request.encryptKey() != null) {
            settings.put(SettingsService.FEISHU_ENCRYPT_KEY, request.encryptKey());
        }
        return config();
    }

    @PostMapping("/integrations/feishu/sync")
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public OrgSyncResult sync() {
        return orgProvider.sync();
    }

    @GetMapping("/integrations/feishu/logs")
    @PreAuthorize("hasAuthority('" + Permissions.INTEGRATION_MANAGE + "')")
    public List<AuditLogDto> logs() {
        return foundation.auditLogs().stream()
                .filter(log -> "FEISHU_SYNC".equals(log.action()))
                .toList();
    }

    /**
     * Public callback for Feishu events (or a Stream forwarder). Accepts both the
     * v2.0 envelope and the flat v1 shape. Unknown event types are a no-op.
     */
    @PostMapping("/integrations/feishu/event")
    public Map<String, String> event(
            @RequestHeader(value = "X-Feishu-Token", required = false) String headerToken,
            @RequestBody JsonNode body) {
        JsonNode challenge = body.get("challenge");
        if (challenge != null && !challenge.isNull()) {
            return Map.of("challenge", challenge.asText());
        }
        if (!settings.feishuEnabled()) {
            throw ApiException.badRequest("飞书集成未启用");
        }
        if (body.has("encrypt")) {
            throw ApiException.badRequest(
                    "飞书事件已加密：请在飞书开放平台关闭事件加密，或配置转发服务解密后再调用该接口");
        }
        String token = firstNonBlank(
                headerToken,
                text(body.path("header"), "token"),
                text(body, "token"));
        if (!tokenMatches(settings.feishuVerificationToken(), token)) {
            throw ApiException.forbidden("飞书事件校验失败");
        }
        EventPayload payload = parseEvent(body);
        if (payload == null) {
            return Map.of("status", "ignored");
        }
        orgProvider.applyEvent(payload.type(), payload.user());
        return Map.of("status", "ok");
    }

    private EventPayload parseEvent(JsonNode body) {
        String type = firstNonBlank(text(body, "type"), text(body.path("header"), "event_type"));
        if (type == null || !HANDLED_EVENTS.contains(type)) {
            return null;
        }
        JsonNode object = body.path("event").path("object");
        if (object.isMissingNode() || object.isNull()) {
            object = body;
        }
        FeishuUser user = new FeishuUser(
                firstNonBlank(text(object, "user_id"), text(object, "userId")),
                firstNonBlank(text(object, "open_id"), text(object, "openId")),
                text(object, "name"),
                firstNonBlank(text(object, "mobile"), text(object, "phone")),
                text(object, "email"),
                firstNonBlank(text(object, "employee_no"), text(object, "employeeNo")));
        return new EventPayload(type, user);
    }

    /** Constant-time comparison so a wrong token cannot be probed byte by byte. */
    private boolean tokenMatches(String expected, String actual) {
        return !isBlank(expected)
                && actual != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record EventPayload(String type, FeishuUser user) {}
}
