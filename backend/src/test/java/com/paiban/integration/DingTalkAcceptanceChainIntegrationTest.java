package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.integration.dingtalk.FakeDingTalkClient;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.notification.service.NotificationTemplates;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.NotificationExecutorTestSupport;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Real authentication, JWT, approval, transaction and outbound-fake acceptance seam. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:paiban_dingtalk_chain;"
        + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class DingTalkAcceptanceChainIntegrationTest extends ApiIntegrationTest {
    private static final String PASSWORD = "AcceptanceOnly8!";

    @MockitoSpyBean
    private FakeDingTalkClient vendor;
    @Autowired
    private AppUserRepository accounts;
    @Autowired
    private AuditLogRepository audits;
    @Autowired
    private SubstitutionRepository substitutions;
    @Autowired
    private NotificationLogRepository deliveries;
    @Autowired
    @Qualifier("notificationExecutor")
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void finishOnlyThisTestContext() {
        NotificationExecutorTestSupport.awaitIdle(executor);
        resetIntegrationSettings();
    }

    @Test
    void firstMobileLoginThroughChangedTeacherApprovalNotifiesOnlyApplicantAndFinalTeacher() {
        String superAdmin = adminToken();
        String caseId = "chain_" + TestData.suffix();
        String applicantName = "验收申请人-" + caseId;
        String applicantUid = "dd-acceptance-" + caseId;
        String finalUid = "chain-final-" + caseId;
        String finalName = "最终接课-" + caseId;
        LocalDate monday = ScheduleFixture.nextMonday().plusWeeks(1);
        ScheduleFixture.Result school = ScheduleFixture.publishedIn(
                superAdmin, 5, TestData.semesterStarting(superAdmin, monday));
        as(superAdmin).body(Map.of("name", applicantName, "employeeNo", "ACC-" + caseId))
                .put("/api/teachers/" + school.teacherId()).then().statusCode(200);
        long expectedTeacher = teacher(superAdmin, "原期望-" + caseId, "chain-expected-" + caseId);
        long finalTeacher = teacher(superAdmin, finalName, finalUid);
        String academicName = "academic_" + caseId;
        long academicId = createUser(superAdmin, academicName, PASSWORD, "admin");
        String academic = login(academicName, PASSWORD);
        as(superAdmin).body(Map.of("enabled", true, "appKey", "chain-key",
                        "appSecret", "chain-secret", "agentId", "1000", "corpId", "chain-corp"))
                .put("/api/integrations/dingtalk/config").then().statusCode(200);

        Response firstLogin = RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("authCode", "fake:acceptance:" + caseId))
                .post("/api/auth/dingtalk");
        firstLogin.then().statusCode(200)
                .body("user.teacherId", equalTo((int) school.teacherId()))
                .body("user.roles", equalTo(List.of("teacher")))
                .body("user.loginType", equalTo("DINGTALK"));
        String applicant = firstLogin.jsonPath().getString("token");
        long applicantId = firstLogin.jsonPath().getLong("user.id");
        String applicantAccount = firstLogin.jsonPath().getString("user.username");
        as(applicant).get("/api/auth/me").then().statusCode(200)
                .body("id", equalTo((int) applicantId));
        assertThat(accounts.findAllByTeacherId(school.teacherId())).singleElement()
                .satisfies(account -> assertThat(account.getPasswordHash()).isNull());
        Response accountAudit = as(superAdmin).queryParam("targetUserId", applicantId)
                .get("/api/account-audits");
        accountAudit.then().statusCode(200).body("total", equalTo(2))
                .body("items.find { it.action == 'ACCOUNT_DINGTALK_LINK' }.after.dingtalkUserId",
                        equalTo(applicantUid));
        assertThat(accountAudit.jsonPath().getList("items.action", String.class))
                .containsExactlyInAnyOrder("ACCOUNT_DINGTALK_AUTO_PROVISION", "ACCOUNT_DINGTALK_LINK");

        Map<String, Object> entry = ScheduleFixture.classEntries(superAdmin, school.classId()).stream()
                .filter(row -> ((Number) row.get("teacherId")).longValue() == school.teacherId())
                .findFirst().orElseThrow();
        long entryId = ((Number) entry.get("id")).longValue();
        LocalDate date = monday.plusDays(((Number) entry.get("dayOfWeek")).longValue() - 1);
        clearInvocations(vendor);
        long requestId = as(applicant).body(Map.of("entryId", entryId, "effectiveDate", date.toString(),
                        "reason", "整链请假", "expectedSubstituteTeacherId", expectedTeacher))
                .post("/api/substitution-requests").then().statusCode(200)
                .body("status", equalTo("SUBMITTED")).extract().jsonPath().getLong("id");
        NotificationExecutorTestSupport.awaitIdle(executor);
        assertThat(substitutions.findByRequestId(requestId)).isEmpty();
        assertThat(deliveries.findAll()).isEmpty();
        verify(vendor, times(0)).sendWorkNotification(anyString(), anyString(), anyString(), anyString());
        as(applicant).queryParam("date", date.toString())
                .get("/api/timetable/class/" + school.classId()).then().statusCode(200)
                .body("find { it.id == " + entryId + " }.teacherId", equalTo((int) school.teacherId()));

        as(academic).body(Map.of("substituteTeacherId", finalTeacher, "comment", "改由最终老师接课"))
                .post("/api/substitution-requests/" + requestId + "/approve").then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .body("expectedSubstituteTeacherId", equalTo((int) expectedTeacher))
                .body("substituteTeacherId", equalTo((int) finalTeacher))
                .body("operatorUserId", equalTo((int) applicantId))
                .body("approverUserId", equalTo((int) academicId))
                .body("approvedAt", notNullValue());
        NotificationExecutorTestSupport.awaitIdle(executor);
        as(applicant).get("/api/substitution-requests/" + requestId).then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .body("expectedSubstituteTeacherId", equalTo((int) expectedTeacher))
                .body("substituteTeacherName", equalTo(finalName));
        as(applicant).get("/api/substitution-requests/" + requestId + "/history").then().statusCode(200)
                .body("toStatus", equalTo(List.of("SUBMITTED", "EFFECTIVE")))
                .body("actor", equalTo(List.of(applicantAccount, academicName)));
        as(applicant).queryParam("date", date.toString())
                .get("/api/timetable/class/" + school.classId()).then().statusCode(200)
                .body("find { it.id == " + entryId + " }.teacherId", equalTo((int) finalTeacher))
                .body("find { it.id == " + entryId + " }.substituted", equalTo(true));
        assertThat(substitutions.findByRequestId(requestId)).singleElement().satisfies(row -> {
            assertThat(row.getSubstituteTeacherId()).isEqualTo(finalTeacher);
            assertThat(row.getLessonDate()).isEqualTo(date);
        });
        assertThat(audits.findAll()).filteredOn(log -> log.getAction().startsWith("SUBSTITUTION_REQUEST_"))
                .extracting(AuditLog::getAction, AuditLog::getActor)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("SUBSTITUTION_REQUEST_SUBMITTED", applicantAccount),
                        org.assertj.core.groups.Tuple.tuple("SUBSTITUTION_REQUEST_APPROVED", academicName));

        ArgumentCaptor<String> recipients = ArgumentCaptor.forClass(String.class);
        verify(vendor, times(2)).sendWorkNotification(recipients.capture(), anyString(), anyString(), anyString());
        assertThat(recipients.getAllValues()).containsExactlyInAnyOrder(applicantUid, finalUid);
        assertThat(deliveries.findAll()).hasSize(2).allSatisfy(log -> {
            assertThat(log.getChannel()).isEqualTo("dingtalk");
            assertThat(log.isSuccess()).isTrue();
            assertThat(log.getContent()).contains(date.toString(), entry.get("className").toString(),
                    entry.get("courseName").toString(), "第" + entry.get("periodNo") + "节");
        });
        assertThat(deliveries.findAll()).filteredOn(log -> applicantUid.equals(log.getRecipient()))
                .singleElement().satisfies(log -> {
                    assertThat(log.getTemplateCode()).isEqualTo(NotificationTemplates.SUBSTITUTION_APPROVAL_APPROVED);
                    assertThat(log.getContent()).contains(finalName, "改由最终老师接课");
                });
        assertThat(deliveries.findAll()).filteredOn(log -> finalUid.equals(log.getRecipient()))
                .singleElement().satisfies(log -> assertThat(log.getTemplateCode())
                        .isEqualTo(NotificationTemplates.SUBSTITUTION_EFFECTIVE_SUBSTITUTE));
        assertThat(accounts.findAllByTeacherId(expectedTeacher)).isEmpty();
        assertThat(accounts.findAllByTeacherId(finalTeacher)).isEmpty();
    }

    private long teacher(String token, String name, String uid) {
        return as(token).body(Map.of("name", name, "dingtalkUserId", uid))
                .post("/api/teachers").then().statusCode(200).extract().jsonPath().getLong("id");
    }
}
