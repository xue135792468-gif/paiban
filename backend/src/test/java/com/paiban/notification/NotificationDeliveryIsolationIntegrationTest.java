package com.paiban.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.integration.dingtalk.DingTalkClient;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotificationDeliveryIsolationIntegrationTest extends ApiIntegrationTest {

    private static final String PASSWORD = "teacher123";
    private static final String APPROVAL_ACTION = "SUBSTITUTION_REQUEST_APPROVED";
    private static final String REJECTION_CONSTRAINT = "ck_qa_reject_substitution_approval_audit";

    @MockitoBean
    private DingTalkClient dingTalkClient;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("notificationExecutor")
    private ThreadPoolTaskExecutor notificationExecutor;

    @BeforeEach
    void resetNotificationSettings() {
        resetIntegrationSettings();
    }

    @Test
    void realHttpCommitFailureDoesNotDeliverAndConstraintProvesTheApprovalTailWasReached()
            throws Exception {
        String admin = adminToken();
        Fixture fixture = prepare(admin);
        CopyOnWriteArrayList<ExternalNotification> externalNotifications =
                new CopyOnWriteArrayList<>();
        when(dingTalkClient.sendWorkNotification(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    externalNotifications.add(new ExternalNotification(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3)));
                    return true;
                });
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        boolean constraintInstalled = false;
        try {
            addApprovalRejectionConstraint();
            constraintInstalled = true;

            Response failedApproval = as(admin)
                    .body(Map.of("comment", "提交失败不得推送"))
                    .when().post("/api/substitution-requests/" + fixture.requestId() + "/approve");
            failedApproval.then()
                    .statusCode(500)
                    .body("message", equalTo("服务器内部错误"));

            awaitNotificationExecutorIdle();
            assertThat(externalNotifications)
                    .as("业务事务回滚后不得出现真实外部发送")
                    .isEmpty();
            assertThat(requestStatus(fixture.requestId())).isEqualTo("SUBMITTED");
            assertThat(count("select count(*) from substitution where request_id = ?",
                    fixture.requestId())).isZero();
            assertThat(count("select count(*) from substitution_history"
                    + " where request_id = ? and to_status = 'EFFECTIVE'",
                    fixture.requestId())).isZero();
            assertThat(count("select count(*) from audit_log where action = ?",
                    APPROVAL_ACTION)).isZero();
            assertThat(count("select count(*) from notification_log")).isZero();
            assertThat(count("select count(*) from \"notification\"")).isZero();

            dropApprovalRejectionConstraint();
            constraintInstalled = false;

            as(admin)
                    .body(Map.of("comment", "约束撤除后的正例"))
                    .when().post("/api/substitution-requests/" + fixture.requestId() + "/approve")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("EFFECTIVE"));

            awaitExternalNotifications(externalNotifications, 2);
            awaitNotificationExecutorIdle();
            assertThat(requestStatus(fixture.requestId())).isEqualTo("EFFECTIVE");
            assertThat(count("select count(*) from substitution where request_id = ?",
                    fixture.requestId())).isOne();
            assertThat(count("select count(*) from substitution_history"
                    + " where request_id = ? and to_status = 'EFFECTIVE'",
                    fixture.requestId())).isOne();
            assertThat(count("select count(*) from audit_log where action = ?",
                    APPROVAL_ACTION)).isOne();
            assertThat(count("select count(*) from notification_log"
                    + " where success = true and template_code in (?, ?)",
                    "SUBSTITUTION_APPROVAL_APPROVED",
                    "SUBSTITUTION_EFFECTIVE_SUBSTITUTE")).isEqualTo(2);
            assertThat(externalNotifications)
                    .extracting(ExternalNotification::userId)
                    .containsExactlyInAnyOrder(fixture.applicantUid(), fixture.substituteUid());
            assertThat(externalNotifications)
                    .filteredOn(notification -> fixture.applicantUid().equals(notification.userId()))
                    .singleElement()
                    .satisfies(notification -> {
                        assertThat(notification.title()).isNotBlank();
                        assertThat(notification.markdown()).contains("代课已批准", "约束撤除后的正例");
                        assertThat(notification.link()).contains("/substitutions/my?requestId=");
                    });
            assertThat(externalNotifications)
                    .filteredOn(notification -> fixture.substituteUid().equals(notification.userId()))
                    .singleElement()
                    .satisfies(notification -> {
                        assertThat(notification.title()).isNotBlank();
                        assertThat(notification.markdown()).isNotBlank();
                        assertThat(notification.link()).contains("/substitutions?requestId=");
                    });
        } finally {
            if (constraintInstalled) {
                dropApprovalRejectionConstraint();
            }
            awaitNotificationExecutorIdle();
        }
    }

    @Test
    void fullNotificationQueueKeepsApprovedEffectAndRecordsIndependentQueueFailures()
            throws Exception {
        String admin = adminToken();
        Fixture fixture = prepare(admin);
        CopyOnWriteArrayList<ExternalNotification> externalNotifications =
                new CopyOnWriteArrayList<>();
        when(dingTalkClient.sendWorkNotification(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    externalNotifications.add(new ExternalNotification(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3)));
                    return true;
                });
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch activeWorkerStarted = new CountDownLatch(1);
        int queueCapacity = notificationExecutor.getQueueCapacity();
        notificationExecutor.execute(() -> {
            activeWorkerStarted.countDown();
            awaitRelease(releaseWorkers);
        });
        try {
            assertThat(activeWorkerStarted.await(10, TimeUnit.SECONDS))
                    .as("专用通知 worker 应先进入可控阻塞")
                    .isTrue();
            for (int queued = 0; queued < queueCapacity; queued++) {
                notificationExecutor.execute(() -> awaitRelease(releaseWorkers));
            }
            assertThat(notificationExecutor.getThreadPoolExecutor().getQueue().size())
                    .as("测试必须填满实际配置的队列容量")
                    .isEqualTo(queueCapacity);

            as(admin)
                    .body(Map.of("comment", "通知队列满时审批仍提交"))
                    .when().post("/api/substitution-requests/" + fixture.requestId() + "/approve")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("EFFECTIVE"));

            awaitQueueFailureCount(2);
            assertThat(requestStatus(fixture.requestId())).isEqualTo("EFFECTIVE");
            assertThat(count("select count(*) from substitution where request_id = ?",
                    fixture.requestId())).isOne();
            assertThat(count("select count(*) from notification_log where success = true")).isZero();
            assertThat(count("select count(*) from \"notification\"")).isZero();
            assertThat(externalNotifications)
                    .as("队列拒绝时不得回落到真实外部发送")
                    .isEmpty();

            List<QueueFailure> failures = jdbc.query(
                    "select template_code, recipient, success, error_message"
                            + " from notification_log order by id",
                    (resultSet, rowNumber) -> new QueueFailure(
                            resultSet.getString("template_code"),
                            resultSet.getString("recipient"),
                            resultSet.getBoolean("success"),
                            resultSet.getString("error_message")));
            assertThat(failures)
                    .extracting(QueueFailure::templateCode)
                    .containsExactlyInAnyOrder(
                            "SUBSTITUTION_APPROVAL_APPROVED",
                            "SUBSTITUTION_EFFECTIVE_SUBSTITUTE");
            assertThat(failures)
                    .extracting(QueueFailure::recipient)
                    .containsExactlyInAnyOrder(
                            "user:" + fixture.applicantUserId(),
                            "teacher:" + fixture.substituteTeacherId());
            assertThat(failures)
                    .allSatisfy(failure -> {
                        assertThat(failure.success()).isFalse();
                        assertThat(failure.errorCode()).isEqualTo("DELIVERY_QUEUE_REJECTED");
                    });
        } finally {
            releaseWorkers.countDown();
            awaitNotificationExecutorIdle();
        }
    }

    @Test
    void disablingQueuedRecipientsOverHttpSuppressesExternalDelivery()
            throws Exception {
        String admin = adminToken();
        Fixture fixture = prepare(admin);
        CopyOnWriteArrayList<ExternalNotification> externalNotifications =
                new CopyOnWriteArrayList<>();
        when(dingTalkClient.sendWorkNotification(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    externalNotifications.add(new ExternalNotification(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3)));
                    return true;
                });
        settingsService.put(SettingsService.DINGTALK_ENABLED, "true");

        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        notificationExecutor.execute(() -> {
            workerStarted.countDown();
            awaitRelease(releaseWorker);
        });
        try {
            assertThat(workerStarted.await(10, TimeUnit.SECONDS))
                    .as("专用通知 worker 应先进入可控阻塞")
                    .isTrue();

            as(admin)
                    .body(Map.of("comment", "先入队再停用收件账号"))
                    .when().post("/api/substitution-requests/" + fixture.requestId() + "/approve")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("EFFECTIVE"));
            awaitQueuedNotificationCount(2);

            as(admin)
                    .when().post("/api/users/" + fixture.applicantUserId() + "/disable")
                    .then().statusCode(200);
            as(admin)
                    .when().post("/api/users/" + fixture.substituteUserId() + "/disable")
                    .then().statusCode(200);
            assertThat(accountEnabled(fixture.applicantUserId())).isFalse();
            assertThat(accountEnabled(fixture.substituteUserId())).isFalse();

            releaseWorker.countDown();
            awaitNotificationExecutorIdle();
            assertThat(externalNotifications)
                    .as("工作者释放时必须重读停用状态，不能再调用第三方")
                    .isEmpty();
            assertThat(count("select count(*) from notification_log where success = true")).isZero();
            assertThat(count("select count(*) from \"notification\"")).isZero();
            assertThat(requestStatus(fixture.requestId())).isEqualTo("EFFECTIVE");
            assertThat(count("select count(*) from substitution where request_id = ?",
                    fixture.requestId())).isOne();
        } finally {
            releaseWorker.countDown();
            awaitNotificationExecutorIdle();
        }
    }

    private Fixture prepare(String admin) {
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
        long substituteTeacherId = TestData.teacher(admin, "隔离接课" + TestData.suffix());
        String applicantUsername = "isolation_applicant_" + TestData.suffix();
        long applicantUserId = as(admin).body(Map.of(
                        "username", applicantUsername,
                        "password", PASSWORD,
                        "displayName", "隔离申请人",
                        "roleCodes", List.of("teacher"),
                        "teacherId", schedule.teacherId()))
                .when().post("/api/users")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");
        String applicant = login(applicantUsername, PASSWORD);
        String substituteUsername = "isolation_substitute_" + TestData.suffix();
        long substituteUserId = as(admin).body(Map.of(
                        "username", substituteUsername,
                        "password", PASSWORD,
                        "displayName", "隔离接课人",
                        "roleCodes", List.of("teacher"),
                        "teacherId", substituteTeacherId))
                .when().post("/api/users")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");

        String applicantUid = "qa-applicant-" + TestData.suffix();
        String substituteUid = "qa-substitute-" + TestData.suffix();
        bindDingTalkUser(schedule.teacherId(), applicantUid);
        bindDingTalkUser(substituteTeacherId, substituteUid);

        long requestId = as(applicant).body(Map.of(
                        "entryId", entryId,
                        "effectiveDate", lessonDate.toString(),
                        "reason", "隔离 QA",
                        "expectedSubstituteTeacherId", substituteTeacherId))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("status", equalTo("SUBMITTED"))
                .extract().jsonPath().getLong("id");
        return new Fixture(
                requestId,
                applicantUserId,
                substituteUserId,
                substituteTeacherId,
                applicantUid,
                substituteUid);
    }

    private void bindDingTalkUser(long teacherId, String userId) {
        Teacher teacher = teachers.findById(teacherId).orElseThrow();
        teacher.setDingtalkUserId(userId);
        teachers.save(teacher);
    }

    private String requestStatus(long requestId) {
        return jdbc.queryForObject(
                "select status from substitution_request where id = ?",
                String.class,
                requestId);
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private boolean accountEnabled(long userId) {
        Boolean enabled = jdbc.queryForObject(
                "select enabled from app_user where id = ?",
                Boolean.class,
                userId);
        return Boolean.TRUE.equals(enabled);
    }

    private void addApprovalRejectionConstraint() {
        jdbc.execute("alter table audit_log add constraint " + REJECTION_CONSTRAINT
                + " check (action <> '" + APPROVAL_ACTION + "')");
    }

    private void dropApprovalRejectionConstraint() {
        jdbc.execute("alter table audit_log drop constraint " + REJECTION_CONSTRAINT);
    }

    private void awaitExternalNotifications(
            CopyOnWriteArrayList<ExternalNotification> notifications,
            int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (notifications.size() >= expected) {
                return;
            }
            pauseBriefly();
        }
        throw new AssertionError("10 秒内没有出现预期的外部通知，实际 " + notifications.size());
    }

    private void awaitNotificationExecutorIdle() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (notificationExecutor.getActiveCount() > 0
                || notificationExecutor.getThreadPoolExecutor().getQueue().size() > 0) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("通知 executor 未在 10 秒内空闲");
            }
            pauseBriefly();
        }
    }

    private void awaitQueuedNotificationCount(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (notificationExecutor.getThreadPoolExecutor().getQueue().size() >= expected) {
                return;
            }
            pauseBriefly();
        }
        throw new AssertionError("10 秒内没有看到预期数量的已入队通知");
    }

    private void awaitQueueFailureCount(int expected) {
        String sql = "select count(*) from notification_log"
                + " where success = false and error_message = 'DELIVERY_QUEUE_REJECTED'";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (count(sql) >= expected) {
                return;
            }
            pauseBriefly();
        }
        throw new AssertionError("10 秒内没有看到队列拒绝记录");
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            release.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待通知 worker 释放时被中断", ex);
        }
    }

    private static void pauseBriefly() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待通知可观察结果时被中断", ex);
        }
    }

    private record Fixture(
            long requestId,
            long applicantUserId,
            long substituteUserId,
            long substituteTeacherId,
            String applicantUid,
            String substituteUid) {}

    private record ExternalNotification(String userId, String title, String markdown, String link) {}

    private record QueueFailure(
            String templateCode,
            String recipient,
            boolean success,
            String errorCode) {}
}
