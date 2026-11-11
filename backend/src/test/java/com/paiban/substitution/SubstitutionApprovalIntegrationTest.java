package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.substitution.domain.Substitution;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.substitution.domain.SubstitutionHistory;
import com.paiban.substitution.domain.SubstitutionHistoryRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票 05：老师自助提交只进入「待审批」，由管理员批准/改人批准/驳回，老师可撤回，
 * 开课后未处理则作废。全部断言走公开 HTTP 契约，落库只作为副作用证据回查。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionApprovalIntegrationTest extends ApiIntegrationTest {

    private static final String PASSWORD = "teacher123";

    @Autowired
    private SubstitutionRepository substitutions;
    @Autowired
    private SubstitutionRequestRepository requests;
    @Autowired
    private SubstitutionHistoryRepository history;
    @Autowired
    private AuditLogRepository auditLogs;

    /** 一名任课老师、一名空闲代课老师、一节未来已发布课表上的课。 */
    record Fixture(long semesterId, long classId, long entryId, long originalTeacherId,
                   long substituteTeacherId, LocalDate date, String teacherToken) {}

    private Fixture prepare(String adminToken, String suffix) {
        // 未来学期：批准路径受真实开课截止约束，能落地的流程必须锚在未来。
        LocalDate monday = ScheduleFixture.nextMonday();
        ScheduleFixture.Result fixture = ScheduleFixture.publishedIn(
                adminToken, 5, TestData.semesterStarting(adminToken, monday));
        Map<String, Object> entry = ScheduleFixture.classEntries(adminToken, fixture.classId()).stream()
                .filter(item -> ((Number) item.get("teacherId")).longValue() == fixture.teacherId())
                .findFirst()
                .orElseThrow();
        long entryId = ((Number) entry.get("id")).longValue();
        int dayOfWeek = ((Number) entry.get("dayOfWeek")).intValue();
        LocalDate date = monday.plusDays(dayOfWeek - 1L);
        long substituteTeacherId = TestData.teacher(adminToken, "审批代课" + suffix);

        String username = "t5_" + suffix;
        as(adminToken).body(Map.of(
                        "username", username,
                        "password", PASSWORD,
                        "displayName", "申请人",
                        "roleCodes", List.of("teacher"),
                        "teacherId", fixture.teacherId()))
                .when().post("/api/users").then().statusCode(200);
        return new Fixture(fixture.semesterId(), fixture.classId(), entryId, fixture.teacherId(),
                substituteTeacherId, date, login(username, PASSWORD));
    }

    private long submit(Fixture fixture) {
        return as(fixture.teacherToken()).body(Map.of(
                        "entryId", fixture.entryId(),
                        "effectiveDate", fixture.date().toString(),
                        "reason", "家中有事",
                        "expectedSubstituteTeacherId", fixture.substituteTeacherId()))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("status", equalTo("SUBMITTED"))
                .extract().jsonPath().getLong("id");
    }

    @Test
    void submitStaysSubmittedWithoutAnyEffect() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix());
        long requestId = submit(fixture);

        // 申请人对同一张单的读取也是 SUBMITTED。
        as(fixture.teacherToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("SUBMITTED"));

        // 课表不动：这节课仍是原任课老师，没有代课标记。
        as(token).queryParam("date", fixture.date().toString())
                .when().get("/api/timetable/class/" + fixture.classId())
                .then().statusCode(200)
                .body("find { it.id == " + fixture.entryId() + " }.substituted", equalTo(false))
                .body("find { it.id == " + fixture.entryId() + " }.teacherId",
                        equalTo((int) fixture.originalTeacherId()));

        // 零生效行。
        assertThat(substitutions.findByRequestId(requestId)).isEmpty();

        // 恰好一条状态历史：进入 SUBMITTED。
        List<SubstitutionHistory> rows = history.findByRequestIdOrderByCreatedAtAsc(requestId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getFromStatus()).isNull();
            assertThat(row.getToStatus()).isEqualTo(SubstitutionRequest.SUBMITTED);
        });

        // 恰好一条同事务成功审计。
        assertThat(auditLogs.findAll())
                .filteredOn(log -> actionOf(log).endsWith("SUBMITTED"))
                .hasSize(1);
    }

    private static String actionOf(AuditLog log) {
        return log.getAction() == null ? "" : log.getAction();
    }

    @Test
    void approvalMakesTheCoverEffective() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix());
        long requestId = submit(fixture);

        as(token).body(Map.of("comment", "同意，注意交接"))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .body("substituteTeacherId", equalTo((int) fixture.substituteTeacherId()))
                .body("expectedSubstituteTeacherId", equalTo((int) fixture.substituteTeacherId()))
                .body("approvalComment", equalTo("同意，注意交接"))
                .body("approvedAt", notNullValue())
                .body("approverUserId", notNullValue());

        // 课表按批准结果生效：这节课换成代课老师。
        as(token).queryParam("date", fixture.date().toString())
                .when().get("/api/timetable/class/" + fixture.classId())
                .then().statusCode(200)
                .body("find { it.id == " + fixture.entryId() + " }.substituted", equalTo(true))
                .body("find { it.id == " + fixture.entryId() + " }.teacherId",
                        equalTo((int) fixture.substituteTeacherId()));

        // 一笔生效行，且只覆盖批准的那个授课实例。
        assertThat(substitutions.findByRequestId(requestId)).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE);
            assertThat(row.getSubstituteTeacherId()).isEqualTo(fixture.substituteTeacherId());
            assertThat(row.getLessonDate()).isEqualTo(fixture.date());
        });

        // 状态历史：提交 + 批准各一条。
        assertThat(history.findByRequestIdOrderByCreatedAtAsc(requestId))
                .extracting(SubstitutionHistory::getToStatus)
                .containsExactly(SubstitutionRequest.SUBMITTED, SubstitutionRequest.EFFECTIVE);
        // 正常动作各一条同事务审计。
        assertThat(auditLogs.findAll())
                .filteredOn(log -> actionOf(log).startsWith("SUBSTITUTION_REQUEST_"))
                .extracting(SubstitutionApprovalIntegrationTest::actionOf)
                .containsExactlyInAnyOrder(
                        "SUBSTITUTION_REQUEST_SUBMITTED", "SUBSTITUTION_REQUEST_APPROVED");
    }

    @Test
    void rejectionNeedsAReasonAndLeavesNoEffect() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix());
        long requestId = submit(fixture);
        String url = "/api/substitution-requests/" + requestId + "/reject";

        // 理由必填：空体、空白、超长都在入口被拒，不会等到写库才失败。
        as(token).body(Map.of()).when().post(url).then().statusCode(400);
        as(token).body(Map.of("reason", "   ")).when().post(url).then().statusCode(400);
        as(token).body(Map.of("reason", "理由".repeat(251))).when().post(url).then().statusCode(400);

        String reason = "当天已有教研活动，请改约";
        as(token).body(Map.of("reason", reason)).when().post(url)
                .then().statusCode(200)
                .body("status", equalTo("REJECTED"))
                .body("approvalComment", equalTo(reason))
                .body("approverUserId", notNullValue());

        // 驳回不产生生效行，也不改课表。
        assertThat(substitutions.findByRequestId(requestId)).isEmpty();
        as(token).queryParam("date", fixture.date().toString())
                .when().get("/api/timetable/class/" + fixture.classId())
                .then().statusCode(200)
                .body("find { it.id == " + fixture.entryId() + " }.substituted", equalTo(false));

        // 老师能在「我的调代」里看到状态与驳回理由。
        as(fixture.teacherToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("REJECTED"))
                .body("approvalComment", equalTo(reason));

        assertThat(history.findByRequestIdOrderByCreatedAtAsc(requestId))
                .extracting(SubstitutionHistory::getToStatus)
                .containsExactly(SubstitutionRequest.SUBMITTED, SubstitutionRequest.REJECTED);
        assertThat(auditLogs.findAll())
                .filteredOn(log -> actionOf(log).startsWith("SUBSTITUTION_REQUEST_"))
                .extracting(SubstitutionApprovalIntegrationTest::actionOf)
                .containsExactlyInAnyOrder(
                        "SUBSTITUTION_REQUEST_SUBMITTED", "SUBSTITUTION_REQUEST_REJECTED");
    }
}
