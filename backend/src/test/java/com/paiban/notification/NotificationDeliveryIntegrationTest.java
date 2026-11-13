package com.paiban.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.integration.dingtalk.DingTalkClient;
import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.notification.domain.NotificationRepository;
import com.paiban.notification.service.NotificationService;
import com.paiban.notification.service.NotificationTemplates;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.NotificationExecutorTestSupport;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotificationDeliveryIntegrationTest extends ApiIntegrationTest {

    private static final String PASSWORD = "teacher123";

    @MockitoBean
    private DingTalkClient dingTalkClient;

    @Autowired
    private TeacherRepository teachers;
    @Autowired
    private SubstitutionRepository substitutions;
    @Autowired
    private NotificationLogRepository notificationLogs;
    @Autowired
    private NotificationRepository localNotifications;
    @Autowired
    private NotificationService notifications;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    @Qualifier("notificationExecutor")
    private ThreadPoolTaskExecutor notificationExecutor;

    private CountDownLatch releaseDingTalk = new CountDownLatch(0);

    @BeforeEach
    void resetNotificationSettings() {
        resetIntegrationSettings();
    }

    @AfterEach
    void releaseBlockedDelivery() {
        releaseDingTalk.countDown();
    }

    @Test
    void approvalReturnsBeforeBlockedDingTalkAndPersistsIndependentDeliveryLogsAfterCommit()
            throws Exception {
        String admin = adminToken();
        Fixture fx = prepare(admin, true, "uid-applicant", "uid-substitute");

        CountDownLatch deliveryStarted = new CountDownLatch(1);
        releaseDingTalk = new CountDownLatch(1);
        when(dingTalkClient.sendWorkNotification(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    deliveryStarted.countDown();
                    releaseDingTalk.await(15, TimeUnit.SECONDS);
                    return true;
                });
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        CompletableFuture<Response> approval = CompletableFuture.supplyAsync(() ->
                as(admin).body(Map.of("comment", "同意"))
                        .when().post("/api/substitution-requests/" + fx.requestId() + "/approve"));
        try {
            Response response = approval.get(5, TimeUnit.SECONDS);
            response.then().statusCode(200)
                    .body("status", equalTo("EFFECTIVE"));

            assertThat(deliveryStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(substitutions.findByRequestId(fx.requestId()))
                    .singleElement()
                    .extracting(Substitution::getStatus)
                    .isEqualTo(Substitution.EFFECTIVE);
            assertThat(notificationLogs.findAll()).isEmpty();

            releaseDingTalk.countDown();
            awaitDeliveryLogs(2);
            assertThat(notificationLogs.findAll())
                    .extracting(NotificationLog::getTemplateCode)
                    .containsExactlyInAnyOrder(
                            "SUBSTITUTION_APPROVAL_APPROVED",
                            "SUBSTITUTION_EFFECTIVE_SUBSTITUTE");
            assertThat(notificationLogs.findAll())
                    .filteredOn(log -> "SUBSTITUTION_APPROVAL_APPROVED".equals(
                            log.getTemplateCode()))
                    .singleElement()
                    .satisfies(log -> {
                        assertThat(log.getRecipient()).isEqualTo("uid-applicant");
                        assertThat(log.getContent()).contains("代课已批准", "同意");
                    });
            assertThat(notificationLogs.findAll())
                    .filteredOn(log -> "SUBSTITUTION_EFFECTIVE_SUBSTITUTE".equals(
                            log.getTemplateCode()))
                    .singleElement()
                    .satisfies(log -> assertThat(log.getRecipient()).isEqualTo("uid-substitute"));
        } finally {
            releaseDingTalk.countDown();
        }
    }

    @Test
    void rejectionNotifiesTheActualApplicantAccountWithTheReason() {
        String admin = adminToken();
        Fixture fx = prepare(admin, true, "uid-applicant", "uid-substitute");
        when(dingTalkClient.sendWorkNotification(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        String reason = "当天已有教研活动";
        as(admin).body(Map.of("reason", reason))
                .when().post("/api/substitution-requests/" + fx.requestId() + "/reject")
                .then().statusCode(200)
                .body("status", equalTo("REJECTED"));

        awaitDeliveryLogs(1);
        assertThat(notificationLogs.findAll()).singleElement().satisfies(log -> {
            assertThat(log.getTemplateCode()).isEqualTo(
                    NotificationTemplates.SUBSTITUTION_APPROVAL_REJECTED);
            assertThat(log.getRecipient()).isEqualTo("uid-applicant");
            assertThat(log.getContent()).contains(reason);
            assertThat(log.isSuccess()).isTrue();
        });
    }

    @Test
    void delegatedApprovalSeparatesActualOperatorExpectedTeacherAndFinalTeacher() {
        String admin = adminToken();
        LocalDate monday = ScheduleFixture.nextMonday();
        ScheduleFixture.Result schedule = ScheduleFixture.publishedIn(
                admin, 5, TestData.semesterStarting(admin, monday));
        Map<String, Object> entry = ScheduleFixture.classEntries(admin, schedule.classId()).stream()
                .filter(row -> ((Number) row.get("teacherId")).longValue() == schedule.teacherId())
                .findFirst()
                .orElseThrow();
        long entryId = ((Number) entry.get("id")).longValue();
        LocalDate lessonDate = monday.plusDays(
                ((Number) entry.get("dayOfWeek")).longValue() - 1L);
        long expectedTeacherId = TestData.teacher(admin, "期望代课" + TestData.suffix());
        long finalTeacherId = TestData.teacher(admin, "最终代课" + TestData.suffix());

        String operatorUsername = "delegate_operator_" + TestData.suffix();
        as(admin).body(Map.of(
                        "username", operatorUsername,
                        "password", PASSWORD,
                        "displayName", "代发操作人",
                        "roleCodes", List.of("super_admin"),
                        "teacherId", schedule.otherTeacherId()))
                .when().post("/api/users").then().statusCode(200);
        String operatorToken = login(operatorUsername, PASSWORD);
        bindDingTalkUser(schedule.teacherId(), "uid-original");
        bindDingTalkUser(schedule.otherTeacherId(), "uid-operator");
        bindDingTalkUser(expectedTeacherId, "uid-expected");
        bindDingTalkUser(finalTeacherId, "uid-final");

        long requestId = as(operatorToken).body(Map.of(
                        "entryId", entryId,
                        "effectiveDate", lessonDate.toString(),
                        "reason", "管理员代发",
                        "expectedSubstituteTeacherId", expectedTeacherId))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("status", equalTo("SUBMITTED"))
                .extract().jsonPath().getLong("id");
        when(dingTalkClient.sendWorkNotification(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        as(admin).body(Map.of(
                        "comment", "批准时换人",
                        "substituteTeacherId", finalTeacherId))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .body("substituteTeacherId", equalTo((int) finalTeacherId));

        awaitDeliveryLogs(2);
        assertThat(notificationLogs.findAll())
                .filteredOn(log -> "SUBSTITUTION_APPROVAL_APPROVED".equals(
                        log.getTemplateCode()))
                .singleElement()
                .satisfies(log -> assertThat(log.getRecipient()).isEqualTo("uid-operator"));
        assertThat(notificationLogs.findAll())
                .filteredOn(log -> "SUBSTITUTION_EFFECTIVE_SUBSTITUTE".equals(
                        log.getTemplateCode()))
                .singleElement()
                .satisfies(log -> assertThat(log.getRecipient()).isEqualTo("uid-final"));
        assertThat(notificationLogs.findAll())
                .extracting(NotificationLog::getRecipient)
                .doesNotContain("uid-original", "uid-expected");
    }

    @Test
    void failedDingTalkStopsAtThreeAttemptsThenUsesLocalFallback() {
        String admin = adminToken();
        Fixture fx = prepare(admin, true, "uid-applicant", "uid-substitute");
        when(dingTalkClient.sendWorkNotification(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        as(admin).body(Map.of("comment", "重试边界"))
                .when().post("/api/substitution-requests/" + fx.requestId() + "/approve")
                .then().statusCode(200);
        awaitLocalNotifications(2);

        verify(dingTalkClient, times(3)).sendWorkNotification(
                eq("uid-applicant"), anyString(), anyString(), anyString());
        verify(dingTalkClient, times(3)).sendWorkNotification(
                eq("uid-substitute"), anyString(), anyString(), anyString());
        assertThat(notificationLogs.findAll())
                .filteredOn(log -> "dingtalk".equals(log.getChannel()))
                .hasSize(2)
                .allSatisfy(log -> assertThat(log.isSuccess()).isFalse());
        assertThat(notificationLogs.findAll())
                .filteredOn(log -> "local".equals(log.getChannel()))
                .hasSize(2)
                .allSatisfy(log -> assertThat(log.isSuccess()).isTrue());
    }

    @Test
    void deliveryRereadsDisabledStateAndDeduplicatesMultipleAccountsByUid() {
        String admin = adminToken();
        Fixture fx = prepare(admin, true, "uid-applicant", "uid-substitute");
        String secondUsername = "async_substitute_two_" + TestData.suffix();
        as(admin).body(Map.of(
                        "username", secondUsername,
                        "password", PASSWORD,
                        "displayName", "第二接课账号",
                        "roleCodes", List.of("teacher"),
                        "teacherId", fx.substituteTeacherId()))
                .when().post("/api/users").then().statusCode(200);
        as(admin).when().post("/api/users/" + fx.substituteUserId() + "/disable")
                .then().statusCode(200);
        when(dingTalkClient.sendWorkNotification(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        as(admin).body(Map.of("comment", "多账号去重"))
                .when().post("/api/substitution-requests/" + fx.requestId() + "/approve")
                .then().statusCode(200);
        awaitDeliveryLogs(2);

        verify(dingTalkClient, times(1)).sendWorkNotification(
                eq("uid-applicant"), anyString(), anyString(), anyString());
        verify(dingTalkClient, times(1)).sendWorkNotification(
                eq("uid-substitute"), anyString(), anyString(), anyString());
        assertThat(notificationLogs.findAll())
                .filteredOn(log -> "dingtalk".equals(log.getChannel()))
                .hasSize(2);
    }

    @Test
    void onlyDisabledLocalAccountsSuppressDingTalkAndLocalCoverDelivery() {
        String admin = adminToken();
        Fixture fx = prepare(admin, true, "uid-applicant", "uid-substitute");
        as(admin).when().post("/api/users/" + fx.substituteUserId() + "/disable")
                .then().statusCode(200);
        when(dingTalkClient.sendWorkNotification(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        as(admin).body(Map.of("comment", "停用账号不发送"))
                .when().post("/api/substitution-requests/" + fx.requestId() + "/approve")
                .then().statusCode(200);
        awaitDeliveryLogs(2);

        verify(dingTalkClient, times(0)).sendWorkNotification(
                eq("uid-substitute"), anyString(), anyString(), anyString());
        assertThat(notificationLogs.findAll())
                .filteredOn(log -> "SUBSTITUTION_EFFECTIVE_SUBSTITUTE".equals(
                        log.getTemplateCode()))
                .hasSize(2)
                .allSatisfy(log -> assertThat(log.isSuccess()).isFalse());
        assertThat(localNotifications.findAll())
                .noneMatch(notification -> "SUBSTITUTION_EFFECTIVE_SUBSTITUTE".equals(
                        notification.getTemplateCode()));
    }

    @Test
    void rolledBackBusinessTransactionSendsNothing() {
        String admin = adminToken();
        Fixture fx = prepare(admin, true, "uid-applicant", "uid-substitute");
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try {
            transaction.executeWithoutResult(status -> {
                notifications.notify(
                        NotificationTemplates.SUBSTITUTION_APPROVAL_APPROVED,
                        List.of(fx.applicantUserId()),
                        Map.of(
                                "className", "回滚班",
                                "courseName", "语文",
                                "date", "2026-11-23",
                                "periodLabel", "周一第1节",
                                "substituteTeacher", "接课人",
                                "comment", "不应发送"),
                        "/rollback");
                throw new IllegalStateException("force rollback");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("force rollback");
        }

        NotificationExecutorTestSupport.awaitIdle(notificationExecutor);
        verifyNoInteractions(dingTalkClient);
        assertThat(notificationLogs.findAll()).isEmpty();
        assertThat(localNotifications.findAll()).isEmpty();
    }

    @Test
    void retiredPublicTemplateIsANoSendBoundary() {
        String admin = adminToken();
        Fixture fx = prepare(admin, true, "uid-applicant", "uid-substitute");
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        notifications.notify(
                NotificationTemplates.TIMETABLE_PUBLISHED,
                List.of(fx.applicantUserId()),
                Map.of("semesterName", "旧模板"),
                "/timetable");

        NotificationExecutorTestSupport.awaitIdle(notificationExecutor);
        verifyNoInteractions(dingTalkClient);
        assertThat(notificationLogs.findAll()).isEmpty();
    }

    @Test
    void disabledDingTalkKeepsEnabledLocalTeacherWithoutUidAndDoesNotInventAnAccount() {
        String admin = adminToken();
        Fixture withAccount = prepare(admin, true, null, null);

        as(admin).body(Map.of("comment", "本地降级"))
                .when().post("/api/substitution-requests/" + withAccount.requestId() + "/approve")
                .then().statusCode(200);
        awaitLocalNotifications(2);

        assertThat(localNotifications.findAll())
                .extracting(notification -> notification.getRecipientUserId())
                .containsExactlyInAnyOrder(
                        withAccount.applicantUserId(),
                        withAccount.substituteUserId());
        assertThat(notificationLogs.findAll())
                .allSatisfy(log -> {
                    assertThat(log.getChannel()).isEqualTo("local");
                    assertThat(log.isSuccess()).isTrue();
                });

        Fixture withoutAccount = prepare(admin, false, null, null);
        as(admin).body(Map.of("comment", "无账号不伪造"))
                .when().post("/api/substitution-requests/" + withoutAccount.requestId() + "/approve")
                .then().statusCode(200);
        awaitLocalNotifications(3);

        NotificationLog coverFailure = notificationLogs.findAll().stream()
                .filter(log -> "SUBSTITUTION_EFFECTIVE_SUBSTITUTE".equals(log.getTemplateCode()))
                .filter(log -> !log.isSuccess())
                .findFirst()
                .orElseThrow();
        assertThat(coverFailure.isSuccess()).isFalse();
        assertThat(coverFailure.getRecipient())
                .contains("teacher:" + withoutAccount.substituteTeacherId());
        assertThat(localNotifications.findAll()).hasSize(3);
        assertThat(localNotifications.findAll())
                .filteredOn(notification -> "SUBSTITUTION_EFFECTIVE_SUBSTITUTE".equals(
                        notification.getTemplateCode()))
                .hasSize(1);
    }

    private Fixture prepare(
            String admin,
            boolean substituteHasAccount,
            String applicantUid,
            String substituteUid) {
        LocalDate monday = ScheduleFixture.nextMonday();
        ScheduleFixture.Result schedule = ScheduleFixture.publishedIn(
                admin, 5, TestData.semesterStarting(admin, monday));
        Map<String, Object> entry = ScheduleFixture.classEntries(admin, schedule.classId()).stream()
                .filter(row -> ((Number) row.get("teacherId")).longValue() == schedule.teacherId())
                .findFirst()
                .orElseThrow();
        long entryId = ((Number) entry.get("id")).longValue();
        LocalDate lessonDate = monday.plusDays(
                ((Number) entry.get("dayOfWeek")).longValue() - 1L);
        long substituteTeacherId = TestData.teacher(admin, "异步接课" + TestData.suffix());

        String applicantUsername = "async_applicant_" + TestData.suffix();
        long applicantUserId = as(admin).body(Map.of(
                        "username", applicantUsername,
                        "password", PASSWORD,
                        "displayName", "异步申请人",
                        "roleCodes", List.of("teacher"),
                        "teacherId", schedule.teacherId()))
                .when().post("/api/users")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");
        String applicant = login(applicantUsername, PASSWORD);

        long substituteUserId = -1;
        if (substituteHasAccount) {
            String substituteUsername = "async_substitute_" + TestData.suffix();
            substituteUserId = as(admin).body(Map.of(
                            "username", substituteUsername,
                            "password", PASSWORD,
                            "displayName", "异步接课人",
                            "roleCodes", List.of("teacher"),
                            "teacherId", substituteTeacherId))
                    .when().post("/api/users")
                    .then().statusCode(200)
                    .extract().jsonPath().getLong("id");
        }
        if (applicantUid != null) {
            bindDingTalkUser(schedule.teacherId(), applicantUid);
        }
        if (substituteUid != null) {
            bindDingTalkUser(substituteTeacherId, substituteUid);
        }

        long requestId = as(applicant).body(Map.of(
                        "entryId", entryId,
                        "effectiveDate", lessonDate.toString(),
                        "reason", "异步投递边界",
                        "expectedSubstituteTeacherId", substituteTeacherId))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("status", equalTo("SUBMITTED"))
                .extract().jsonPath().getLong("id");
        return new Fixture(
                substituteTeacherId,
                applicantUserId,
                substituteUserId,
                requestId);
    }

    private record Fixture(
            long substituteTeacherId,
            long applicantUserId,
            long substituteUserId,
            long requestId) {}

    private void bindDingTalkUser(long teacherId, String userId) {
        Teacher teacher = teachers.findById(teacherId).orElseThrow();
        teacher.setDingtalkUserId(userId);
        teachers.save(teacher);
    }

    private void awaitDeliveryLogs(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (notificationLogs.count() >= expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待通知记录时被中断", ex);
            }
        }
        throw new AssertionError("10 秒内没有出现预期的通知记录");
    }

    private void awaitLocalNotifications(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (localNotifications.count() >= expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待本地通知时被中断", ex);
            }
        }
        throw new AssertionError("10 秒内没有出现预期的本地通知");
    }
}
