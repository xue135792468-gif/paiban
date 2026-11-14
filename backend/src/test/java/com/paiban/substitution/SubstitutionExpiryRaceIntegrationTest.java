package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionHistory;
import com.paiban.substitution.domain.SubstitutionHistoryRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.service.LessonDeadline;
import com.paiban.substitution.service.SubstitutionApprovalService;
import com.paiban.substitution.service.SubstitutionRequestService;
import com.paiban.substitution.web.SubstitutionDtos.ApproveSubstitutionRequest;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRequestDto;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A late expiry scan must not erase an approval committed after its first read. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionExpiryRaceIntegrationTest extends ApiIntegrationTest {

    @MockitoBean
    private Clock clock;

    @Autowired
    private SubstitutionApprovalService approvals;
    @Autowired
    private SubstitutionRequestService requestService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private SubstitutionRepository substitutions;
    @Autowired
    private SubstitutionHistoryRepository history;
    @Autowired
    private AuditLogRepository auditLogs;

    private final AtomicReference<Instant> now = new AtomicReference<>();
    private final ThreadLocal<Boolean> scanning = ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean pausedOnce = new AtomicBoolean();
    private final CountDownLatch expiryCandidateRead = new CountDownLatch(1);
    private final CountDownLatch resumeExpiryScan = new CountDownLatch(1);

    @BeforeEach
    void controlOnlyTheExternalClock() {
        resetIntegrationSettings();
        now.set(Instant.now());
        when(clock.getZone()).thenReturn(LessonDeadline.SCHOOL_ZONE);
        when(clock.instant()).thenAnswer(ignored -> {
            Instant observed = now.get();
            if (scanning.get() && pausedOnce.compareAndSet(false, true)) {
                // The scanner has found an overdue candidate but has not locked it yet.
                // Time is an external seam; no repository or private service is mocked.
                expiryCandidateRead.countDown();
                await(resumeExpiryScan);
            }
            return observed;
        });
    }

    @Test
    void anExpiryScanCannotReplaceAConcurrentlyCommittedApproval() throws Exception {
        String admin = adminToken();
        LocalDate monday = ScheduleFixture.nextMonday();
        ScheduleFixture.Result school = ScheduleFixture.publishedIn(
                admin, 5, TestData.semesterStarting(admin, monday));
        Map<String, Object> lesson = ScheduleFixture.classEntries(admin, school.classId()).stream()
                .filter(row -> ((Number) row.get("teacherId")).longValue() == school.teacherId())
                .findFirst().orElseThrow();
        long entryId = ((Number) lesson.get("id")).longValue();
        LocalDate lessonDate = monday.plusDays(((Number) lesson.get("dayOfWeek")).intValue() - 1L);
        long finalTeacherId = TestData.teacher(admin, "清扫竞态接课" + TestData.suffix());
        long requestId = as(admin).body(Map.of(
                        "entryId", entryId,
                        "effectiveDate", lessonDate.toString(),
                        "expectedSubstituteTeacherId", finalTeacherId,
                        "reason", "管理员代申请，核对过期与批准交错"))
                .when().post("/api/substitution-requests")
                .then().statusCode(200).body("status", equalTo("SUBMITTED"))
                .extract().jsonPath().getLong("id");

        CountDownLatch approvalPrepared = new CountDownLatch(1);
        CountDownLatch commitApproval = new CountDownLatch(1);
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SubstitutionRequestDto> approval = executor.submit(() ->
                    outerTransaction.execute(status -> {
                        SubstitutionRequestDto approved = approvals.approve(
                                "admin", requestId, new ApproveSubstitutionRequest(null, "批准"));
                        approvalPrepared.countDown();
                        // The decision has passed its deadline check, but is not committed.
                        await(commitApproval);
                        return approved;
                    }));
            await(approvalPrepared);

            // A has checked before class starts. B now observes a time after the entire
            // lesson day, while A still holds the semester lock and its write is uncommitted.
            now.set(lessonDate.plusDays(1).atStartOfDay(LessonDeadline.SCHOOL_ZONE).toInstant());
            Future<List<SubstitutionRequestDto>> pending = executor.submit(() -> {
                scanning.set(true);
                try {
                    return requestService.list("admin", school.semesterId(), "SUBMITTED", false);
                } finally {
                    scanning.remove();
                }
            });
            await(expiryCandidateRead);

            // B really read the old pending row. Commit A before B takes its locks.
            commitApproval.countDown();
            assertThat(approval.get(20, TimeUnit.SECONDS).status()).isEqualTo(SubstitutionRequest.EFFECTIVE);
            resumeExpiryScan.countDown();
            assertThat(pending.get(20, TimeUnit.SECONDS)).isEmpty();

            // Observe the result through HTTP, with database/history as additional evidence.
            now.set(Instant.now());
            as(admin).when().get("/api/substitution-requests/" + requestId)
                    .then().statusCode(200).body("status", equalTo("EFFECTIVE"));
            as(admin).queryParam("date", lessonDate.toString())
                    .when().get("/api/timetable/class/" + school.classId())
                    .then().statusCode(200)
                    .body("find { it.id == " + entryId + " }.teacherId", equalTo((int) finalTeacherId));
            assertThat(substitutions.findByRequestId(requestId)).singleElement().satisfies(row ->
                    assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE));
            assertThat(history.findByRequestIdOrderByCreatedAtAsc(requestId))
                    .extracting(SubstitutionHistory::getToStatus)
                    .containsExactly(SubstitutionRequest.SUBMITTED, SubstitutionRequest.EFFECTIVE);
            assertThat(auditLogs.findAll()).noneMatch(log ->
                    "SUBSTITUTION_REQUEST_EXPIRED".equals(log.getAction()));
        } finally {
            commitApproval.countDown();
            resumeExpiryScan.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            now.set(Instant.now());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).as("controlled transaction interleaving").isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while coordinating transactions", ex);
        }
    }
}
