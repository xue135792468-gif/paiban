package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.UserService;
import com.paiban.substitution.domain.SubstitutionHistory;
import com.paiban.substitution.domain.SubstitutionHistoryRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.service.SubstitutionApprovalService;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Forces an old caller identity in the same EntityManager, alongside real HTTP changes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionApprovalAuthorizationSnapshotTest extends ApiIntegrationTest {

    @Autowired private UserService users;
    @Autowired private SubstitutionApprovalService approvals;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SubstitutionRepository substitutions;
    @Autowired private SubstitutionHistoryRepository history;
    @Autowired private AuditLogRepository auditLogs;

    @BeforeEach
    void localOnly() {
        resetIntegrationSettings();
    }

    @Test
    void aCachedApproverCannotApproveAfterAnIndependentRoleRevocation() {
        String rootToken = adminToken();
        LocalDate monday = ScheduleFixture.nextMonday();
        ScheduleFixture.Result school = ScheduleFixture.publishedIn(
                rootToken, 5, TestData.semesterStarting(rootToken, monday));
        Map<String, Object> lesson = ScheduleFixture.classEntries(rootToken, school.classId())
                .stream().findFirst().orElseThrow();
        LocalDate date = monday.plusDays(((Number) lesson.get("dayOfWeek")).intValue() - 1L);
        long substitute = TestData.teacher(rootToken, "权限快照接课" + TestData.suffix());
        long requestId = as(rootToken).body(Map.of(
                        "entryId", ((Number) lesson.get("id")).longValue(),
                        "effectiveDate", date.toString(),
                        "expectedSubstituteTeacherId", substitute,
                        "reason", "核对锁内决定使用最新账号权限"))
                .when().post("/api/substitution-requests")
                .then().statusCode(200).body("status", equalTo(SubstitutionRequest.SUBMITTED))
                .extract().jsonPath().getLong("id");
        String actorName = "role_snapshot_" + TestData.suffix();
        String testPassword = java.util.UUID.randomUUID().toString();
        long actorId = createUser(rootToken, actorName, testPassword, "admin");
        String actorToken = login(actorName, testPassword);

        ApiException refusal = null;
        try {
            new TransactionTemplate(transactionManager).execute(status -> {
                AppUser cached = users.getByUsername(actorName);
                assertThat(users.hasPermission(cached, Permissions.SUBSTITUTION_APPROVE)).isTrue();

                // This real server request commits in a separate transaction. The actor's
                // next HTTP operation already observes Teacher permissions, while this
                // EntityManager deliberately still holds the earlier admin association.
                as(rootToken).body(Map.of("roleCodes", List.of("teacher")))
                        .when().put("/api/users/" + actorId)
                        .then().statusCode(200).body("roles", hasItem("teacher"));
                as(actorToken).when().get("/api/auth/me")
                        .then().statusCode(200)
                        .body("permissions", not(hasItem(Permissions.SUBSTITUTION_APPROVE)));
                assertThat(users.hasPermission(cached, Permissions.SUBSTITUTION_APPROVE))
                        .as("the forced old identity must really remain cached before the decision")
                        .isTrue();

                approvals.approve(actorName, requestId, null);
                return null;
            });
        } catch (ApiException ex) {
            // Catch outside TransactionTemplate: no swallowed rollback-only transaction.
            refusal = ex;
        }

        assertThat(refusal).as("revoked approval permission must refuse the decision").isNotNull();
        assertThat(refusal.getStatus().value()).isEqualTo(403);
        as(rootToken).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200).body("status", equalTo(SubstitutionRequest.SUBMITTED));
        assertThat(substitutions.findByRequestId(requestId)).isEmpty();
        assertThat(history.findByRequestIdOrderByCreatedAtAsc(requestId))
                .extracting(SubstitutionHistory::getToStatus)
                .containsExactly(SubstitutionRequest.SUBMITTED);
        assertThat(auditLogs.findAll()).extracting(AuditLog::getAction)
                .doesNotContain("SUBSTITUTION_REQUEST_APPROVED");
    }
}
