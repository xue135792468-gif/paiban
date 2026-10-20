package com.paiban.common.settings;

import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.Permissions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
            SettingsService.DINGTALK_CORP_ID,
            SettingsService.DINGTALK_EVENT_TOKEN,
            SettingsService.DINGTALK_SYNC_STRATEGY,
            SettingsService.FEISHU_ENABLED,
            SettingsService.FEISHU_APP_ID,
            SettingsService.FEISHU_APP_SECRET,
            SettingsService.FEISHU_VERIFICATION_TOKEN,
            SettingsService.FEISHU_ENCRYPT_KEY,
            SettingsService.FEISHU_SYNC_STRATEGY,
            SettingsService.SCHOOL_NAME,
            SettingsService.SCHOOL_TYPE,
            SettingsService.PUBLIC_BASE_URL,
            SettingsService.AI_SUBSTITUTE_RECOMMENDATION,
            SettingsService.AI_NATURAL_LANGUAGE_CONSTRAINTS,
            SettingsService.AI_IMPORT_CONFIDENCE_THRESHOLD);

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.SETTINGS_MANAGE + "')")
    public Map<String, String> all() {
        return settings.all();
    }

    /**
     * Minimal settings every signed-in user needs for UI mode rendering.
     * Deliberately narrow so integration secrets never reach non-admins.
     */
    @GetMapping("/public")
    public Map<String, String> publicSettings() {
        return Map.of(
                "schoolType", settings.schoolType(),
                "schoolName", settings.schoolName());
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

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.SETTINGS_MANAGE + "')")
    public Map<String, String> uploadLogo(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择图片文件");
        }
        try {
            byte[] bytes = file.getBytes();
            String extension = imageExtension(bytes);
            Path directory = Paths.get("uploads");
            Files.createDirectories(directory);
            String filename = "logo-" + System.currentTimeMillis() + "." + extension;
            Path target = directory.resolve(filename).toAbsolutePath();
            Files.write(target, bytes);
            settings.put(SettingsService.SCHOOL_LOGO_PATH, target.toString());
            return settings.all();
        } catch (java.io.IOException ex) {
            throw ApiException.badRequest("保存 Logo 失败: " + ex.getMessage());
        }
    }

    /** Validates the real file signature; SVG is rejected to avoid stored XSS. */
    private String imageExtension(byte[] bytes) {
        if (bytes.length > 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "png";
        }
        if (bytes.length > 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        throw ApiException.badRequest("只支持 PNG / JPG 图片（不允许 SVG）");
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
        if (SettingsService.SCHOOL_TYPE.equals(key)
                && !List.of("junior", "senior").contains(value)) {
            throw ApiException.badRequest("学校类型只能是 junior 或 senior");
        }
        if ((SettingsService.DINGTALK_ENABLED.equals(key)
                || SettingsService.FEISHU_ENABLED.equals(key)
                || SettingsService.AI_SUBSTITUTE_RECOMMENDATION.equals(key)
                || SettingsService.AI_NATURAL_LANGUAGE_CONSTRAINTS.equals(key))
                && !List.of("true", "false").contains(value)) {
            throw ApiException.badRequest(key + " 只能是 true 或 false");
        }
        if (SettingsService.FEISHU_SYNC_STRATEGY.equals(key)
                && !List.of("feishu", "local").contains(value)) {
            throw ApiException.badRequest("飞书同步策略只能是 feishu 或 local");
        }
        if (SettingsService.AI_IMPORT_CONFIDENCE_THRESHOLD.equals(key)) {
            try {
                double threshold = Double.parseDouble(value);
                if (threshold < 0 || threshold > 1) {
                    throw ApiException.badRequest("低置信度阈值必须在 0 到 1 之间");
                }
            } catch (NumberFormatException ex) {
                throw ApiException.badRequest("低置信度阈值必须是数字");
            }
        }
    }
}
