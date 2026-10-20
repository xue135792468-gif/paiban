package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.integration.feishu.FakeFeishuClient;
import com.paiban.integration.feishu.FeishuMessage;
import com.paiban.notification.domain.Notification;
import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.notification.domain.NotificationRepository;
import com.paiban.notification.service.NotificationService;
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
 * Ticket 04: substitution notifications go out as Feishu interactive cards,
 * degrade to the in-system notification centre when Feishu is unreachable or
 * the recipient is unbound, and every attempt is tagged with its platform.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FeishuNotificationIntegrationTest extends ApiIntegrationTest {

    private static final String TEMPLATE = "SUBSTITUTION_EFFECTIVE_ORIGINAL";
    private static final String LINK = "/substitutions?requestId=7";

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private FakeFeishuClient fakeClient;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private NotificationLogRepository notificationLogs;

    @BeforeEach
    void resetFakeClient() {
        fakeClient.clearMessages();
        fakeClient.failAll(false);
    }

    @AfterEach
    void disableIntegrations() {
        fakeClient.failAll(false);
        fakeClient.clearMessages();
        try {
            settings(Map.of(
                    "integration.feishu.enabled", "false",
                    "integration.dingtalk.enabled", "false"));
        } catch (RuntimeException ignored) {
            // best effort: never leak an enabled integration into the next test class
        }
    }

    private void settings(Map<String, String> values) {
        as(adminToken()).body(values)
                .when().put("/api/settings")
                .then().statusCode(200);
    }

    private void enableFeishu() {
        settings(Map.of(
                "integration.feishu.enabled", "true",
                "integration.feishu.app-id", "test-app-id",
                "integration.feishu.app-secret", "test-app-secret",
                "integration.feishu.sync-strategy", "feishu"));
    }

    private Teacher teacher(String name, String feishuUserId, String feishuOpenId) {
        Teacher teacher = new Teacher();
        teacher.setName(name);
        teacher.setEmployeeNo("EMP-" + TestData.suffix());
        teacher.setFeishuUserId(feishuUserId);
        teacher.setFeishuOpenId(feishuOpenId);
        return teachers.save(teacher);
    }

    private Long createUser(Long teacherId) {
        return as(adminToken()).body(Map.of(
                        "username", "fs-notify-" + TestData.suffix(),
                        "password", "teacher123",
                        "displayName", "张三",
                        "roleCodes", List.of("teacher"),
                        "teacherId", teacherId))
                .when().post("/api/users")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    private Map<String, Object> variables() {
        return Map.of(
                "courseName", "数学",
                "className", "高一(1)班",
                "date", "2026-10-22",
                "periodLabel", "周四第3节",
                "originalTeacher", "张三",
                "substituteTeacher", "李四",
                "roomName", "A101");
    }

    private void notifySubstitution(Long userId) {
        notificationService.notify(TEMPLATE, List.of(userId), variables(), LINK);
    }

    @Test
    void substitutionNotificationIsSentAsCardAndLoggedAsFeishu() {
        enableFeishu();
        Long userId = createUser(teacher("张三", "fs-001", "ou-001").getId());

        notifySubstitution(userId);

        assertThat(fakeClient.sentMessages()).singleElement().satisfies((FeishuMessage sent) -> {
            assertThat(sent.openId()).isEqualTo("ou-001");
            assertThat(sent.userId()).isEqualTo("fs-001");
            assertThat(sent.card()).isTrue();
            assertThat(sent.templateCode()).isEqualTo(TEMPLATE);
            assertThat(sent.title()).isEqualTo("调代申请已批准");
            assertThat(sent.link()).isEqualTo(LINK);
            assertThat(sent.content())
                    .contains("**课程**：数学")
                    .contains("**时间**：2026-10-22 周四第3节")
                    .contains("**班级**：高一(1)班")
                    .contains("**原教师**：张三")
                    .contains("**代课教师**：李四")
                    .contains("**教室**：A101");
        });
        assertThat(notificationLogs.findAll()).anySatisfy(log -> {
            assertThat(log.getChannel()).isEqualTo("feishu");
            assertThat(log.getPlatform()).isEqualTo("feishu");
            assertThat(log.isSuccess()).isTrue();
        });
        assertThat(notifications.findAll()).isEmpty();
    }

    @Test
    void feishuFailureDegradesToInSystemNotification() {
        enableFeishu();
        Long userId = createUser(teacher("张三", "fs-001", "ou-001").getId());
        fakeClient.failAll(true);

        notifySubstitution(userId);

        assertThat(fakeClient.sentMessages()).isEmpty();
        assertThat(notifications.findAll()).singleElement().satisfies((Notification notification) -> {
            assertThat(notification.getRecipientUserId()).isEqualTo(userId);
            assertThat(notification.getTitle()).isEqualTo("调代申请已批准");
            assertThat(notification.getLink()).isEqualTo(LINK);
        });
        assertThat(notificationLogs.findAll())
                .anySatisfy(log -> {
                    assertThat(log.getChannel()).isEqualTo("feishu");
                    assertThat(log.getPlatform()).isEqualTo("feishu");
                    assertThat(log.isSuccess()).isFalse();
                })
                .anySatisfy(log -> {
                    assertThat(log.getChannel()).isEqualTo("local");
                    assertThat(log.getPlatform()).isEqualTo("system");
                    assertThat(log.isSuccess()).isTrue();
                });
    }

    @Test
    void unboundRecipientDegradesToInSystemNotification() {
        enableFeishu();
        Long userId = createUser(teacher("王五", null, null).getId());

        notifySubstitution(userId);

        assertThat(fakeClient.sentMessages()).isEmpty();
        assertThat(notifications.findAll()).singleElement()
                .satisfies(notification -> assertThat(notification.getRecipientUserId()).isEqualTo(userId));
        assertThat(notificationLogs.findAll()).anySatisfy(log -> {
            assertThat(log.getChannel()).isEqualTo("local");
            assertThat(log.getPlatform()).isEqualTo("system");
            assertThat(log.isSuccess()).isTrue();
        });
    }

    @Test
    void dingtalkChannelLogsDingtalkPlatform() {
        settings(Map.of(
                "integration.dingtalk.enabled", "true",
                "integration.dingtalk.app-key", "test-key",
                "integration.dingtalk.app-secret", "test-secret",
                "integration.dingtalk.agent-id", "1000"));
        Teacher teacher = teacher("张三", null, null);
        teacher.setDingtalkUserId("dd-001");
        teachers.save(teacher);
        Long userId = createUser(teacher.getId());

        notifySubstitution(userId);

        assertThat(notificationLogs.findAll()).anySatisfy(log -> {
            assertThat(log.getChannel()).isEqualTo("dingtalk");
            assertThat(log.getPlatform()).isEqualTo("dingtalk");
            assertThat(log.isSuccess()).isTrue();
        });
        assertThat(notifications.findAll()).isEmpty();
    }
}
