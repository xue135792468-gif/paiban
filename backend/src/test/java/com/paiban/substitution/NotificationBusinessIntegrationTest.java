package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.integration.dingtalk.DingTalkClient;
import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.notification.service.NotificationTemplates;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.NotificationExecutorTestSupport;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotificationBusinessIntegrationTest extends ApiIntegrationTest {

    @MockitoBean
    private DingTalkClient dingTalkClient;

    @Autowired
    private TeacherRepository teachers;
    @Autowired
    private NotificationLogRepository notificationLogs;
    @Autowired
    @Qualifier("notificationExecutor")
    private ThreadPoolTaskExecutor notificationExecutor;

    @BeforeEach
    void resetNotificationSettings() {
        resetIntegrationSettings();
    }

    @Test
    void directCoverNotifiesOnlyTheFinalTeacherWithTheCoverDetails() {
        String admin = adminToken();
        String suffix = TestData.suffix();
        AdminSubstitutionIntegrationTest.Fixture fx =
                AdminSubstitutionIntegrationTest.buildFixture(
                        admin,
                        suffix,
                        LocalDate.now().with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)));
        bindDingTalkUser(fx.teacherAId(), "uid-original");
        bindDingTalkUser(fx.teacherCId(), "uid-final");
        when(dingTalkClient.sendWorkNotification(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        long voucherId = as(admin).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherCId(),
                        "reason", "直接代课通知"))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(200)
                .extract().jsonPath().getLong("voucherId");
        as(admin).body(Map.of("comment", "同意直接代课"))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));

        awaitNotificationLogs(1);
        assertThat(notificationLogs.findAll()).singleElement().satisfies(log -> {
            assertThat(log.getTemplateCode()).isEqualTo(
                    NotificationTemplates.SUBSTITUTION_EFFECTIVE_SUBSTITUTE);
            assertThat(log.getRecipient()).isEqualTo("uid-final");
            assertThat(log.getContent())
                    .contains("语文" + suffix)
                    .contains("周三第1节")
                    .contains(fx.lessonDate().toString());
            assertThat(log.isSuccess()).isTrue();
        });
    }

    @Test
    void swapNotifiesEachMovedTeacherWithOnlyTheirOwnMoveDetails() {
        String admin = adminToken();
        String suffix = TestData.suffix();
        AdminSwapIntegrationTest.Fixture fx = AdminSwapIntegrationTest.buildFixture(
                admin,
                suffix,
                LocalDate.now().with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)),
                false,
                false);
        bindDingTalkUser(fx.teacherChineseId(), "uid-chinese");
        bindDingTalkUser(fx.teacherMathId(), "uid-math");
        bindDingTalkUser(fx.teacherEnglishId(), "uid-english");
        when(dingTalkClient.sendWorkNotification(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        Response plans = as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.wednesday().toString(),
                        "targetDate", fx.friday().toString(),
                        "targetPeriodId", fx.fridayPeriodId(),
                        "reason", "调课通知"))
                .when().post("/api/substitution-workbench/swap-plans");
        plans.then().statusCode(200);
        long planId = plans.jsonPath().getLong("plans[0].planId");
        long voucherId = as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.wednesday().toString(),
                        "planId", planId,
                        "reason", "调课通知"))
                .when().post("/api/substitution-workbench/swap-previews")
                .then().statusCode(200)
                .extract().jsonPath().getLong("voucherId");
        as(admin).body(Map.of("comment", "同意调课"))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));

        awaitNotificationLogs(2);
        List<NotificationLog> logs = notificationLogs.findAll();
        assertThat(logs).hasSize(2);
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.getTemplateCode()).isEqualTo(
                    NotificationTemplates.SUBSTITUTION_SWAP_EFFECTIVE);
            assertThat(log.isSuccess()).isTrue();
        });
        assertThat(logs)
                .filteredOn(log -> "uid-chinese".equals(log.getRecipient()))
                .singleElement()
                .satisfies(log -> assertThat(log.getContent())
                        .contains("语文" + suffix)
                        .contains("周三第1节")
                        .contains("周五第1节")
                        .doesNotContain("数学" + suffix));
        assertThat(logs)
                .filteredOn(log -> "uid-math".equals(log.getRecipient()))
                .singleElement()
                .satisfies(log -> assertThat(log.getContent())
                        .contains("数学" + suffix)
                        .contains("周五第1节")
                        .contains("周三第1节")
                        .doesNotContain("语文" + suffix));
        assertThat(logs)
                .extracting(NotificationLog::getRecipient)
                .doesNotContain("uid-english");
    }

    @Test
    void timetablePublishNoLongerTriggersDingTalkOrCreatesDeliveryRows() {
        String admin = adminToken();
        LocalDate monday = ScheduleFixture.nextMonday();
        ScheduleFixture.Result schedule = ScheduleFixture.solvedIn(
                admin, 5, TestData.semesterStarting(admin, monday));
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        as(admin).when().post("/api/timetable-drafts/" + schedule.draftId() + "/publish")
                .then().statusCode(200);

        NotificationExecutorTestSupport.awaitIdle(notificationExecutor);
        verifyNoInteractions(dingTalkClient);
        assertThat(notificationLogs.findAll()).isEmpty();
    }

    private void bindDingTalkUser(long teacherId, String userId) {
        Teacher teacher = teachers.findById(teacherId).orElseThrow();
        teacher.setDingtalkUserId(userId);
        teachers.save(teacher);
    }

    private void awaitNotificationLogs(int expected) {
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
}
