package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.service.LessonDeadline;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 票 05：老师自助提交只进入「待审批」，由管理员批准/改人批准/驳回，老师可撤回，
 * 开课后未处理则作废。全部断言走公开 HTTP 契约，落库只作为副作用证据回查。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionApprovalIntegrationTest extends ApiIntegrationTest {

    private static final String PASSWORD = "teacher123";
    private static final ZoneId ZONE = LessonDeadline.SCHOOL_ZONE;

    /** 服务端权威时钟：默认走真实时间，只有截止用例把它推到开课之后。 */
    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>();

    @Autowired
    private SubstitutionRepository substitutions;
    @Autowired
    private SubstitutionRequestRepository requests;
    @Autowired
    private SubstitutionHistoryRepository history;
    @Autowired
    private AuditLogRepository auditLogs;

    @BeforeEach
    void fixClock() {
        now.set(Instant.now());
        Mockito.when(clock.instant()).thenAnswer(invocation -> now.get());
        Mockito.when(clock.getZone()).thenReturn(ZONE);
    }

    /**
     * 一名任课老师（申请人本人账号）、一名空闲代课老师（另一位老师的账号，
     * 用来证明「不是本人就不能撤回」）、一个没有绑定教师档案的老师账号
     * （证明两个空老师关系不能互相「算本人」），一节未来已发布课表上的课。
     */
    record Fixture(long semesterId, long classId, long otherClassId, long draftId,
                   long entryId, long originalTeacherId,
                   long substituteTeacherId, LocalDate date, String teacherToken,
                   String otherTeacherToken, String staffToken) {}

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
        String otherUsername = "t5o_" + suffix;
        as(adminToken).body(Map.of(
                        "username", otherUsername,
                        "password", PASSWORD,
                        "displayName", "另一位老师",
                        "roleCodes", List.of("teacher"),
                        "teacherId", substituteTeacherId))
                .when().post("/api/users").then().statusCode(200);
        String staffUsername = "t5s_" + suffix;
        as(adminToken).body(Map.of(
                        "username", staffUsername,
                        "password", PASSWORD,
                        "displayName", "未绑定教师",
                        "roleCodes", List.of("teacher")))
                .when().post("/api/users").then().statusCode(200);
        return new Fixture(fixture.semesterId(), fixture.classId(), fixture.otherClassId(),
                fixture.draftId(), entryId, fixture.teacherId(),
                substituteTeacherId, date, login(username, PASSWORD),
                login(otherUsername, PASSWORD), login(staffUsername, PASSWORD));
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

    @Test
    void onlyTheRequesterCanWithdrawAndOnlyWhilePending() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix());
        long requestId = submit(fixture);
        String url = "/api/substitution-requests/" + requestId + "/withdraw";

        // 别人（另一位老师、管理员）都不能替申请人撤回。
        as(fixture.otherTeacherToken()).when().post(url).then().statusCode(403);
        as(token).when().post(url).then().statusCode(403);

        as(fixture.teacherToken()).when().post(url)
                .then().statusCode(200)
                .body("status", equalTo("WITHDRAWN"))
                .body("approverUserId", equalTo(null))
                .body("approvalComment", equalTo(null));

        // 撤回不生效、不改课表。
        assertThat(substitutions.findByRequestId(requestId)).isEmpty();
        as(token).queryParam("date", fixture.date().toString())
                .when().get("/api/timetable/class/" + fixture.classId())
                .then().statusCode(200)
                .body("find { it.id == " + fixture.entryId() + " }.substituted", equalTo(false));

        // 已撤回的申请不能再撤回、也不能再批准。
        as(fixture.teacherToken()).when().post(url).then().statusCode(409);
        as(token).body(Map.of()).when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(409);

        // 撤回后可以重新发起同一实例的申请（待审批唯一只约束 SUBMITTED）。
        long resubmitted = submit(fixture);
        assertThat(resubmitted).isNotEqualTo(requestId);
        assertThat(history.findByRequestIdOrderByCreatedAtAsc(requestId))
                .extracting(SubstitutionHistory::getToStatus)
                .containsExactly(SubstitutionRequest.SUBMITTED, SubstitutionRequest.WITHDRAWN);
    }

    @Test
    void otherUsersCannotReadSomebodyElsesApplication() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix());
        long requestId = submit(fixture);
        String detailUrl = "/api/substitution-requests/" + requestId;

        // 另一位老师、以及没有绑定教师档案的老师账号都不能读别人的申请；
        // 两个空的教师关系不能因为「null 等于 null」被当成本人。
        as(fixture.otherTeacherToken()).when().get(detailUrl).then().statusCode(403);
        as(fixture.otherTeacherToken()).when().get(detailUrl + "/history").then().statusCode(403);
        as(fixture.staffToken()).when().get(detailUrl).then().statusCode(403);
        as(fixture.staffToken()).when().get(detailUrl + "/history").then().statusCode(403);

        // 申请人自己在「我的调代」里看得到，别人看不到。
        as(fixture.teacherToken()).queryParam("mine", true)
                .when().get("/api/substitution-requests")
                .then().statusCode(200)
                .body("findAll { it.id == " + requestId + " }.size()", equalTo(1))
                .body("find { it.id == " + requestId + " }.status", equalTo("SUBMITTED"));
        as(fixture.otherTeacherToken()).queryParam("mine", true)
                .when().get("/api/substitution-requests")
                .then().statusCode(200)
                .body("findAll { it.id == " + requestId + " }.size()", equalTo(0));

        // 有全部调代查看权限的管理员照常可以读到（待审批列表的数据来源）。
        as(token).when().get(detailUrl).then().statusCode(200);
        as(token).queryParam("status", "SUBMITTED")
                .when().get("/api/substitution-requests")
                .then().statusCode(200)
                .body("findAll { it.id == " + requestId + " }.size()", equalTo(1));
    }

    @Test
    void legacyRowWithoutOperatorStillBelongsToItsRequester() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix());
        long requestId = submit(fixture);

        // 模拟票 05 之前的旧行：没有真实操作账号，只有非空的申请人教师关系。
        SubstitutionRequest legacy = requests.findById(requestId).orElseThrow();
        legacy.setOperatorUserId(null);
        requests.save(legacy);

        // 仍然认「非空的申请人教师关系」，别人依旧不行。
        as(fixture.otherTeacherToken()).when()
                .post("/api/substitution-requests/" + requestId + "/withdraw").then().statusCode(403);
        as(fixture.teacherToken()).when()
                .post("/api/substitution-requests/" + requestId + "/withdraw")
                .then().statusCode(200)
                .body("status", equalTo("WITHDRAWN"));
    }

    private Instant lessonStart(Fixture fixture) {
        return fixture.date().atTime(8, 0).atZone(ZONE).toInstant();
    }

    @Test
    void approvingAfterTheLessonStartedExpiresItInsteadOfApplyingIt() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix());
        long requestId = submit(fixture);

        // 上课后才有管理员来处理：拒绝批准，并把「已作废」真正落库（不是靠回滚吞掉）。
        now.set(lessonStart(fixture).plusSeconds(60));
        as(token).body(Map.of()).when()
                .post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(409)
                .body("message", containsString("作废"));

        // 另一个请求读到的就是提交后的 EXPIRED，且没有生效行。
        as(fixture.teacherToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("EXPIRED"));
        assertThat(substitutions.findByRequestId(requestId)).isEmpty();
        assertThat(auditLogs.findAll())
                .filteredOn(log -> actionOf(log).startsWith("SUBSTITUTION_REQUEST_"))
                .extracting(SubstitutionApprovalIntegrationTest::actionOf)
                .containsExactlyInAnyOrder(
                        "SUBSTITUTION_REQUEST_SUBMITTED", "SUBSTITUTION_REQUEST_EXPIRED");

        // 已作废的单不能再被批准（也不会二次作废）。
        as(token).body(Map.of()).when()
                .post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(409);
        assertThat(history.findByRequestIdOrderByCreatedAtAsc(requestId))
                .extracting(SubstitutionHistory::getToStatus)
                .containsExactly(SubstitutionRequest.SUBMITTED, SubstitutionRequest.EXPIRED);
    }

    @Test
    void pendingListDropsOverdueApplicationsAsExpired() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix());
        long requestId = submit(fixture);

        as(token).queryParam("status", "SUBMITTED")
                .when().get("/api/substitution-requests")
                .then().statusCode(200)
                .body("findAll { it.id == " + requestId + " }.size()", equalTo(1));

        // 过了开课时刻再看待审批列表：这张单必须当场作废，并从待批结果里消失。
        now.set(lessonStart(fixture).plusSeconds(60));
        as(token).queryParam("status", "SUBMITTED")
                .when().get("/api/substitution-requests")
                .then().statusCode(200)
                .body("findAll { it.id == " + requestId + " }.size()", equalTo(0));

        // 老师「我的调代」保留全部状态，看到作废。
        as(fixture.teacherToken()).queryParam("mine", true)
                .when().get("/api/substitution-requests")
                .then().statusCode(200)
                .body("find { it.id == " + requestId + " }.status", equalTo("EXPIRED"));
        assertThat(substitutions.findByRequestId(requestId)).isEmpty();
    }

    @Test
    void approvalCanChangeTheSubstituteAndKeepsTheExpectedOne() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix());
        long requestId = submit(fixture);
        long replacement = TestData.teacher(token, "换人代课" + TestData.suffix());

        as(token).body(Map.of("substituteTeacherId", replacement, "comment", "原定老师临时有事"))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                // 期望人保留提交时的选择；实际人记录批准时换的人。
                .body("expectedSubstituteTeacherId", equalTo((int) fixture.substituteTeacherId()))
                .body("substituteTeacherId", equalTo((int) replacement));

        assertThat(substitutions.findByRequestId(requestId)).singleElement().satisfies(row ->
                assertThat(row.getSubstituteTeacherId()).isEqualTo(replacement));
        assertThat(requests.findById(requestId).orElseThrow().getExpectedSubstituteTeacherId())
                .isEqualTo(fixture.substituteTeacherId());
        as(token).queryParam("date", fixture.date().toString())
                .when().get("/api/timetable/class/" + fixture.classId())
                .then().statusCode(200)
                .body("find { it.id == " + fixture.entryId() + " }.teacherId",
                        equalTo((int) replacement));
    }

    @Test
    void approvalRejectsASubstituteWhoIsNoLongerActive() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix());
        long requestId = submit(fixture);

        // 提交后教师被停用：批准时按当前教师档案重验，不能只信提交时的快照。
        as(token).when().post("/api/teachers/" + fixture.substituteTeacherId() + "/disable")
                .then().statusCode(200);
        as(token).body(Map.of()).when()
                .post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(409)
                .body("message", containsString("不可用"));

        assertThat(substitutions.findByRequestId(requestId)).isEmpty();
        as(fixture.teacherToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("SUBMITTED"));
    }

    @Test
    void approvalRejectsWhenThePublishedBaselineHasChanged() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix());
        long requestId = submit(fixture);

        // 撤回发布会把 timetable_entry 删掉：申请指向的条目不在了，批准必须拒绝而不是挂到别的课上。
        as(token).when().post("/api/timetable-drafts/" + fixture.draftId() + "/withdraw")
                .then().statusCode(200);
        as(token).body(Map.of()).when()
                .post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(409);

        assertThat(substitutions.findByRequestId(requestId)).isEmpty();
        as(fixture.teacherToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("SUBMITTED"));
    }

    @Test
    void approvalRechecksConflictsAgainstTheCurrentTimetable() {
        String token = adminToken();
        String suffix = TestData.suffix();
        AdminSubstitutionIntegrationTest.Fixture fx = AdminSubstitutionIntegrationTest.buildFixture(
                token, suffix, ScheduleFixture.nextMonday());
        String username = "t5c_" + suffix;
        as(token).body(Map.of(
                        "username", username,
                        "password", PASSWORD,
                        "displayName", "申请人",
                        "roleCodes", List.of("teacher"),
                        "teacherId", fx.teacherAId()))
                .when().post("/api/users").then().statusCode(200);
        String teacherToken = login(username, PASSWORD);
        long requestId = as(teacherToken).body(Map.of(
                        "entryId", fx.entryAId(),
                        "effectiveDate", fx.lessonDate().toString(),
                        "reason", "家长会",
                        "expectedSubstituteTeacherId", fx.teacherCId()))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("status", equalTo("SUBMITTED"))
                .extract().jsonPath().getLong("id");

        // 提交后，管理员在工作台把另一班同槽的课也交给同一位代课老师并立即生效。
        long voucherId = as(token).body(Map.of(
                        "entryId", fx.entryBId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherCId(),
                        "reason", "另一班也请假"))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(200)
                .extract().jsonPath().getLong("voucherId");
        as(token).body(Map.of()).when()
                .post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(200);
        // 当前课表事实：这位代课老师在这个节次已经有另一班的课。
        as(token).queryParam("date", fx.lessonDate().toString())
                .when().get("/api/timetable/teacher/" + fx.teacherCId())
                .then().statusCode(200)
                .body("findAll { it.periodId == " + fx.periodId() + " }.size()",
                        equalTo(1));

        as(token).body(Map.of()).when()
                .post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(409)
                .body("message", containsString("校验"));

        assertThat(substitutions.findByRequestId(requestId)).isEmpty();
        as(teacherToken).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("SUBMITTED"));
    }
}
