package com.paiban.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.common.settings.SettingsService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.PermissionRepository;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.support.ApiIntegrationTest;
import io.restassured.http.ContentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SettingsPermissionIntegrationTest extends ApiIntegrationTest {

    @Autowired AppUserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired PasswordEncoder passwords;

    private Long userId;
    private Long roleId;
    private String settingsToken;

    @BeforeEach
    void settingsOnlyAccount() {
        String name = "settings_fixture_" + UUID.randomUUID().toString().substring(0, 8);
        Role role = new Role(name, "业务设置测试身份");
        role.setPermissions(Set.of(permissions.findByCode(Permissions.SETTINGS_MANAGE).orElseThrow()));
        role = roles.save(role);
        roleId = role.getId();
        AppUser user = new AppUser();
        user.setUsername(name);
        user.setDisplayName("业务设置测试账号");
        user.setPasswordHash(passwords.encode("settings-test-password"));
        user.setRoles(Set.of(role));
        userId = users.save(user).getId();
        settingsToken = login(name, "settings-test-password");
        settingsService.put(SettingsService.DINGTALK_CORP_ID, "synthetic-corp");
        settingsService.put(SettingsService.DINGTALK_EVENT_TOKEN, "synthetic-event-token");
    }

    @AfterEach
    void removeIdentityFixture() {
        if (userId != null) users.deleteById(userId);
        if (roleId != null) roles.deleteById(roleId);
    }

    @Test
    void businessSettingsReaderCannotReadIntegrationFields() {
        Map<String, Object> visible = as(settingsToken).get("/api/settings")
                .then().statusCode(200).extract().jsonPath().getMap("");
        assertThat(visible).containsKey(SettingsService.SCHOOL_NAME);
        assertThat(visible.keySet()).noneMatch(key -> key.startsWith("integration.dingtalk."));
    }

    @Test
    void unauthorizedMixedUpdateDoesNotWriteAnyField() {
        as(settingsToken).body(Map.of(SettingsService.SCHOOL_NAME, "before-mixed-update"))
                .put("/api/settings").then().statusCode(200);
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(SettingsService.SCHOOL_NAME, "must-not-be-written");
        changes.put(SettingsService.DINGTALK_CORP_ID, "must-not-replace-corp");
        as(settingsToken).body(changes).put("/api/settings").then().statusCode(403);
        assertThat(as(settingsToken).get("/api/settings").then().statusCode(200)
                .extract().jsonPath().getString("'school.name'"))
                .isEqualTo("before-mixed-update");
        assertThat(as(adminToken()).get("/api/settings").then().statusCode(200)
                .extract().jsonPath().getString("'integration.dingtalk.corp-id'"))
                .isEqualTo("synthetic-corp");
    }

    @Test
    void ordinarySettingsUpdateReturnsOnlyAuthorizedFields() {
        Map<String, Object> visible = as(settingsToken)
                .body(Map.of(SettingsService.SCHOOL_NAME, "updated-school"))
                .put("/api/settings").then().statusCode(200).extract().jsonPath().getMap("");
        assertThat(visible).containsEntry(SettingsService.SCHOOL_NAME, "updated-school");
        assertThat(visible.keySet()).noneMatch(key -> key.startsWith("integration.dingtalk."));
    }

    /**
     * 票 01 复查 S8：一次保存多项设置是一个业务动作。
     *
     * <p>首字段合法、第二字段超 {@code app_setting.setting_value} 的 2000 字符上限时，
     * 请求必须整体失败，且首字段不能留下半写结果——这里故意不校验长度，让 2000 字符
     * 这一真实数据库约束去拒绝，避免用应用层校验把本用例变成空断言。
     */
    @Test
    void batchUpdateRejectedByTheColumnLimitLeavesTheFirstFieldUntouched() {
        String admin = adminToken();
        as(admin).body(Map.of(SettingsService.SCHOOL_NAME, "before-batch-update"))
                .put("/api/settings").then().statusCode(200);

        Map<String, String> batch = new LinkedHashMap<>();
        batch.put(SettingsService.SCHOOL_NAME, "must-not-survive-the-batch");
        batch.put(SettingsService.PUBLIC_BASE_URL, "h".repeat(2001));

        int status = as(admin).body(batch).put("/api/settings").then().extract().statusCode();
        assertThat(status).as("2000 字符上限必须拒绝整批保存").isGreaterThanOrEqualTo(400);
        assertThat(as(admin).get("/api/settings").then().statusCode(200)
                .extract().jsonPath().getString("'school.name'"))
                .isEqualTo("before-batch-update");
    }

    @Test
    void logoUploadResponseDoesNotBypassSettingsVisibility() throws Exception {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aHuAAAAAASUVORK5CYII=");
        Map<String, Object> visible = as(settingsToken).contentType(ContentType.MULTIPART)
                .multiPart("file", "synthetic.png", png, "image/png")
                .post("/api/settings/logo").then().statusCode(200).extract().jsonPath().getMap("");
        Path uploaded = Path.of((String) visible.get(SettingsService.SCHOOL_LOGO_PATH)).toAbsolutePath().normalize();
        try {
            assertThat(uploaded).startsWith(Path.of("uploads").toAbsolutePath().normalize());
            assertThat(Files.exists(uploaded)).isTrue();
            assertThat(visible.keySet()).noneMatch(key -> key.startsWith("integration.dingtalk."));
        } finally {
            if (uploaded.startsWith(Path.of("uploads").toAbsolutePath().normalize())) {
                Files.deleteIfExists(uploaded);
            }
        }
    }
}
