package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.notification.service.NotificationService;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 05: switching the active IM platform keeps every local business row,
 * flips the integration flags, syncs the target directory and leaves an audit row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PlatformSwitchIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private NotificationLogRepository notificationLogs;

    @Autowired
    private NotificationService notificationService;

    @AfterEach
    void resetPlatform() {
        try {
            as(adminToken()).body(Map.of("platform", "none"))
                    .when().put("/api/integrations/platform")
                    .then().statusCode(200);
        } catch (RuntimeException ignored) {
            // best effort: never leak an active platform into the next test class
        }
    }

    private void configureDingtalk(boolean enabled) {
        as(adminToken()).body(Map.of(
                        "enabled", enabled,
                        "appKey", "test-key",
                        "appSecret", "test-secret",
                        "agentId", "1000",
                        "syncStrategy", "dingtalk"))
                .when().put("/api/integrations/dingtalk/config")
                .then().statusCode(200);
    }

    private void configureFeishu(boolean enabled) {
        as(adminToken()).body(Map.of(
                        "enabled", enabled,
                        "appId", "test-app-id",
                        "appSecret", "test-app-secret",
                        "syncStrategy", "feishu"))
                .when().put("/api/integrations/feishu/config")
                .then().statusCode(200);
    }

    private io.restassured.response.Response switchTo(String platform) {
        return as(adminToken()).body(Map.of("platform", platform))
                .when().put("/api/integrations/platform");
    }

    @Test
    void switchingToFeishuKeepsLocalDataAndAuditsTheSwitch() {
        configureDingtalk(true);
        configureFeishu(true);
        long semesterId = TestData.semester(adminToken());
        long gradeId = TestData.grade(adminToken(), semesterId, "高一");
        long classId = TestData.adminClass(adminToken(), semesterId, gradeId, "高一(1)班");
        long teacherId = TestData.teacher(adminToken(), "张三");

        switchTo("feishu")
                .then().statusCode(200)
                .body("activePlatform", equalTo("feishu"))
                .body("feishuEnabled", equalTo(true))
                .body("dingtalkEnabled", equalTo(false))
                .body("feishuConfigured", equalTo(true))
                .body("dingtalkConfigured", equalTo(true));

        as(adminToken()).when().get("/api/integrations/platform")
                .then().statusCode(200)
                .body("activePlatform", equalTo("feishu"))
                .body("feishuEnabled", equalTo(true))
                .body("dingtalkEnabled", equalTo(false));

        assertThat(auditLogs.findAll())
                .filteredOn(log -> "PLATFORM_SWITCH".equals(log.getAction()))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getDetail()).contains("从 dingtalk 切换到 feishu");
                    assertThat(log.getSuccessRows()).isGreaterThanOrEqualTo(0);
                });

        as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200)
                .body("id", org.hamcrest.Matchers.hasItem((int) teacherId));
        as(adminToken()).when().get("/api/classes?semesterId=" + semesterId)
                .then().statusCode(200)
                .body("id", org.hamcrest.Matchers.hasItem((int) classId));
    }

    @Test
    void switchingToNoneDisablesBothPlatforms() {
        configureDingtalk(true);
        configureFeishu(true);

        switchTo("none")
                .then().statusCode(200)
                .body("activePlatform", equalTo("none"))
                .body("feishuEnabled", equalTo(false))
                .body("dingtalkEnabled", equalTo(false));
    }

    @Test
    void unconfiguredTargetPlatformIsRejected() {
        configureDingtalk(true);

        switchTo("feishu").then().statusCode(400);
    }

    @Test
    void invalidPlatformIsRejected() {
        switchTo("wechat").then().statusCode(400);
    }

    @Test
    void onlyTheActivePlatformChannelSendsNotifications() {
        configureDingtalk(true);
        configureFeishu(true);
        long teacherId = TestData.teacher(adminToken(), "张三");
        as(adminToken()).body(Map.of(
                        "name", "张三",
                        "employeeNo", "EMP-" + TestData.suffix(),
                        "dingtalkUserId", "dd-001",
                        "feishuUserId", "fs-001",
                        "feishuOpenId", "ou-001"))
                .when().put("/api/teachers/" + teacherId)
                .then().statusCode(200);
        Long userId = as(adminToken()).body(Map.of(
                        "username", "notify-" + TestData.suffix(),
                        "password", "teacher123",
                        "displayName", "张三",
                        "roleCodes", List.of("teacher"),
                        "teacherId", teacherId))
                .when().post("/api/users")
                .then().statusCode(200).extract().jsonPath().getLong("id");

        switchTo("feishu").then().statusCode(200);

        notificationService.notify(
                "SUBSTITUTION_EFFECTIVE_ORIGINAL",
                List.of(userId),
                Map.of("courseName", "数学"),
                "/substitutions?requestId=1");

        List<NotificationLog> logs = notificationLogs.findAll();
        assertThat(logs).anySatisfy(log -> {
            assertThat(log.getChannel()).isEqualTo("feishu");
            assertThat(log.isSuccess()).isTrue();
        });
        assertThat(logs).noneSatisfy(log -> assertThat(log.getChannel()).isEqualTo("dingtalk"));
    }

    @Test
    void auditRowIsPresentInTheFoundationAuditFeed() {
        configureFeishu(true);

        switchTo("feishu").then().statusCode(200);

        as(adminToken()).when().get("/api/foundation/audit-logs")
                .then().statusCode(200)
                .body("action", org.hamcrest.Matchers.hasItem("PLATFORM_SWITCH"));
        assertThat(auditLogs.findAll()).extracting(AuditLog::getAction)
                .contains("PLATFORM_SWITCH");
    }
}
