package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.integration.auth.AuthCredentials;
import com.paiban.integration.auth.FeishuAuthProvider;
import com.paiban.integration.feishu.FakeFeishuClient;
import com.paiban.integration.feishu.FeishuMessage;
import com.paiban.integration.feishu.FeishuUser;
import com.paiban.integration.notification.FeishuNotificationChannel;
import com.paiban.integration.org.FeishuOrgDataProvider;
import com.paiban.integration.org.OrgSyncResult;
import com.paiban.notification.channel.CompositeNotificationChannel;
import com.paiban.notification.channel.NotificationChannel;
import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 01 has no HTTP endpoints of its own: the Feishu adapters are exercised
 * directly as beans, while teachers/users/settings are created through the
 * existing REST contract so the test stays at public seams.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FeishuAdapterIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FeishuAuthProvider authProvider;

    @Autowired
    private FeishuOrgDataProvider orgProvider;

    @Autowired
    private FeishuNotificationChannel feishuChannel;

    @Autowired
    private CompositeNotificationChannel compositeChannel;

    @Autowired
    private FakeFeishuClient fakeClient;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private NotificationLogRepository notificationLogs;

    @BeforeEach
    void clearFakeState() {
        resetIntegrationSettings();
        fakeClient.clearMessages();
    }

    @AfterEach
    void disableIntegration() {
        try {
            configure(false);
        } catch (RuntimeException ignored) {
            // best effort: never leak an enabled integration into the next test class
        }
    }

    private void configure(boolean enabled) {
        // Login and notification both need the explicit active platform, not the derived one.
        settingsService.put(SettingsService.ACTIVE_PLATFORM,
                enabled ? SettingsService.PLATFORM_FEISHU : SettingsService.PLATFORM_NONE);
        as(adminToken()).body(Map.of(
                        "integration.feishu.enabled", String.valueOf(enabled),
                        "integration.feishu.app-id", "test-app-id",
                        "integration.feishu.app-secret", "test-app-secret",
                        "integration.feishu.sync-strategy", "feishu"))
                .when().put("/api/settings")
                .then().statusCode(200);
    }

    private Long teacherId(String name) {
        return as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200).extract().jsonPath()
                .getLong("find { it.name == '" + name + "' }.id");
    }

    private Long createUser(String username, String password, String displayName, Long teacherId) {
        return as(adminToken()).body(Map.of(
                        "username", username,
                        "password", password,
                        "displayName", displayName,
                        "roleCodes", List.of("teacher"),
                        "teacherId", teacherId))
                .when().post("/api/users")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    @Test
    void disabledIntegrationReturnsEmptySync() {
        OrgSyncResult result = orgProvider.sync();

        assertThat(result.created()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.message()).contains("未启用");
        assertThat(authProvider.authenticate(new AuthCredentials(null, null, "fake:EMP001"))).isEmpty();
    }

    @Test
    void enabledSyncImportsTeachersFromFeishu() {
        configure(true);

        OrgSyncResult result = orgProvider.sync();

        assertThat(result.created()).isEqualTo(2);
        as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200)
                .body("name", hasItem("张三"))
                .body("name", hasItem("李四"));
        Teacher zhang = teachers.findByFeishuUserId("fs-001").orElseThrow();
        assertThat(zhang.getFeishuOpenId()).isEqualTo("ou-001");
        assertThat(zhang.getEmployeeNo()).isEqualTo("EMP001");
        assertThat(zhang.isActive()).isTrue();
    }

    @Test
    void feishuAuthRequiresAnExistingLocalAccount() {
        configure(true);
        orgProvider.sync();

        // Teacher exists but no local account is linked: rejected, never auto-provisioned.
        assertThat(authProvider.authenticate(new AuthCredentials(null, null, "fake:EMP001"))).isEmpty();

        String username = "fs-auth-" + TestData.suffix();
        createUser(username, "teacher123", "张三", teacherId("张三"));

        assertThat(authProvider.authenticate(new AuthCredentials(null, null, "fake:EMP001")))
                .contains(username);
        assertThat(authProvider.authenticate(new AuthCredentials(null, null, "fake:UNKNOWN"))).isEmpty();
    }

    @Test
    void notificationSendsAndLogsFeishuPlatform() {
        configure(true);
        orgProvider.sync();
        Long userId = createUser(
                "fs-notify-" + TestData.suffix(), "teacher123", "张三", teacherId("张三"));

        boolean sent = feishuChannel.send(new NotificationChannel.Message(
                "SUBSTITUTION_EFFECTIVE_ORIGINAL",
                List.of(userId),
                Map.of("courseName", "数学"),
                "调代通知",
                "您有一节课需要调代",
                "/substitutions?requestId=1"));

        assertThat(sent).isTrue();
        List<NotificationLog> logs = notificationLogs.findAll();
        assertThat(logs).anySatisfy(log -> {
            assertThat(log.getChannel()).isEqualTo("feishu");
            assertThat(log.getPlatform()).isEqualTo("feishu");
            assertThat(log.isSuccess()).isTrue();
        });
        assertThat(fakeClient.sentMessages()).singleElement().satisfies((FeishuMessage message) -> {
            assertThat(message.openId()).isEqualTo("ou-001");
            assertThat(message.userId()).isEqualTo("fs-001");
            assertThat(message.card()).isTrue();
            assertThat(message.link()).isEqualTo("/substitutions?requestId=1");
        });
    }

    @Test
    void compositeDegradesToLocalWhenFeishuHasNoBoundRecipients() {
        configure(true);
        Long teacherId = createUserTeacher("王五");
        Long userId = createUser("fs-local-" + TestData.suffix(), "teacher123", "王五", teacherId);

        boolean sent = compositeChannel.send(new NotificationChannel.Message(
                "SUBSTITUTION_EFFECTIVE_ORIGINAL",
                List.of(userId),
                Map.of("courseName", "语文"),
                "调代通知",
                "您有一节课需要调代",
                null));

        assertThat(sent).isTrue();
        assertThat(fakeClient.sentMessages()).isEmpty();
        assertThat(notificationLogs.findAll())
                .anySatisfy(log -> assertThat(log.getChannel()).isEqualTo("local"));
    }

    @Test
    void deletedEventDeactivatesTeacher() {
        configure(true);
        orgProvider.sync();

        orgProvider.applyEvent(
                "contact.user.deleted_v3",
                new FeishuUser("fs-002", "ou-002", "李四", null, null, "EMP002"));

        Teacher li = teachers.findByFeishuUserId("fs-002").orElseThrow();
        assertThat(li.isActive()).isFalse();
    }

    private Long createUserTeacher(String name) {
        return as(adminToken()).body(Map.of("name", name))
                .when().post("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }
}
