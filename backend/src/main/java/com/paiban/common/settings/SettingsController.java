package com.paiban.common.settings;

import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.Permissions;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final List<String> EDITABLE = List.of(
            SettingsService.SUBSTITUTION_APPROVAL_MODE,
            SettingsService.NOTIFICATION_SCOPE,
            SettingsService.DINGTALK_ENABLED,
            SettingsService.DINGTALK_APP_KEY,
            SettingsService.DINGTALK_APP_SECRET,
            SettingsService.DINGTALK_AGENT_ID,
            SettingsService.DINGTALK_SYNC_STRATEGY,
            SettingsService.SCHOOL_NAME,
            SettingsService.SCHOOL_LOGO_PATH,
            SettingsService.AI_SUBSTITUTE_RECOMMENDATION,
            SettingsService.AI_NATURAL_LANGUAGE_CONSTRAINTS);

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.SETTINGS_MANAGE + "')")
    public Map<String, String> all() {
        return settings.all();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('" + Permissions.SETTINGS_MANAGE + "')")
    public Map<String, String> update(@RequestBody Map<String, String> values) {
        values.forEach((key, value) -> {
            if (!EDITABLE.contains(key)) {
                throw ApiException.badRequest("不支持修改的配置项: " + key);
            }
            validate(key, value);
        });
        values.forEach(settings::put);
        return settings.all();
    }

    private void validate(String key, String value) {
        if (SettingsService.SUBSTITUTION_APPROVAL_MODE.equals(key)
                && !List.of("approval", "direct").contains(value)) {
            throw ApiException.badRequest("审批模式只能是 approval 或 direct");
        }
        if (SettingsService.NOTIFICATION_SCOPE.equals(key)
                && !List.of("minimal", "with-grade-leader", "with-admin").contains(value)) {
            throw ApiException.badRequest("通知范围只能是 minimal / with-grade-leader / with-admin");
        }
        if ((SettingsService.DINGTALK_ENABLED.equals(key)
                || SettingsService.AI_SUBSTITUTE_RECOMMENDATION.equals(key)
                || SettingsService.AI_NATURAL_LANGUAGE_CONSTRAINTS.equals(key))
                && !List.of("true", "false").contains(value)) {
            throw ApiException.badRequest(key + " 只能是 true 或 false");
        }
    }
}
