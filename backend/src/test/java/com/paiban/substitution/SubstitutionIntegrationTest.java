package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SubstitutionRepository substitutions;

    @AfterEach
    void resetApprovalMode() {
        try {
            as(adminToken()).body(Map.of("substitution.approval-mode", "approval"))
                    .when().put("/api/settings").then().statusCode(200);
            as(adminToken()).body(Map.of("userIds", List.of()))
                    .when().put("/api/settings/substitution-recipients").then().statusCode(200);
        } catch (RuntimeException ignored) {
            // best effort cleanup
        }
    }

    private record Context(ScheduleFixture.Result fixture, String teacherToken, String substituteToken,
                           String headTeacherToken, long teacherA, long teacherB, long classId,
                           long entryId, LocalDate date, String username) {}

    private Context prepare(String token, String suffix) {
        ScheduleFixture.Result fixture = ScheduleFixture.published(token, 5);
        List<Map<String, Object>> entries = ScheduleFixture.classEntries(token, fixture.classId());
        Map<String, Object> entry = entries.stream()
                .filter(item -> ((Number) item.get("teacherId")).longValue() == fixture.teacherId())
                .findFirst()
                .orElseThrow();
        long entryId = ((Number) entry.get("id")).longValue();
        int dayOfWeek = ((Number) entry.get("dayOfWeek")).intValue();
        LocalDate date = LocalDate.of(2025, 9, 1).plusDays(dayOfWeek - 1L);

        // A teacher with no assignments, so the substitute is always free at that period.
        long substituteTeacherId = TestData.teacher(token, "代课老师" + suffix);

        String username = "sub_" + suffix;
        as(token).body(Map.of(
                        "username", username,
                        "password", "teacher123",
                        "displayName", "原任课",
                        "roleCodes", List.of("teacher"),
                        "teacherId", fixture.teacherId()))
                .when().post("/api/users").then().statusCode(200);
        String substituteUsername = "subs_" + suffix;
        as(token).body(Map.of(
                        "username", substituteUsername,
                        "password", "teacher123",
                        "displayName", "代课人",
                        "roleCodes", List.of("teacher"),
                        "teacherId", substituteTeacherId))
                .when().post("/api/users").then().statusCode(200);

        List<Map<String, Object>> classRows = as(token).queryParam("semesterId", fixture.semesterId())
                .when().get("/api/classes")
                .then().statusCode(200).extract().jsonPath().getList("$");
        long headTeacherId = classRows.stream()
                .filter(item -> ((Number) item.get("id")).longValue() == fixture.classId())
                .map(item -> ((Number) item.get("headTeacherId")).longValue())
                .findFirst()
                .orElseThrow();
        String headUsername = "head_" + suffix;
        as(token).body(Map.of(
                        "username", headUsername,
                        "password", "teacher123",
                        "displayName", "班主任",
                        "roleCodes", List.of("head_teacher"),
                        "teacherId", headTeacherId))
                .when().post("/api/users").then().statusCode(200);

        return new Context(
                fixture,
                login(username, "teacher123"),
                login(substituteUsername, "teacher123"),
                login(headUsername, "teacher123"),
                fixture.teacherId(),
                substituteTeacherId,
                fixture.classId(),
                entryId,
                date,
                username);
    }

    private long submit(Context context, String token, Long expectedSubstitute) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("entryId", context.entryId());
        body.put("effectiveDate", context.date().toString());
        body.put("reason", "家中有事");
        if (expectedSubstitute != null) {
            body.put("expectedSubstituteTeacherId", expectedSubstitute);
        }
        Response response = as(token).body(body)
                .when().post("/api/substitution-requests");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    @Test
    void approvalModeRunsTheFullFlowAndOverlaysTimetable() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());

        long requestId = submit(context, context.teacherToken(), context.teacherB());
        as(context.teacherToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("PENDING_APPROVAL"))
                .body("approvalMode", equalTo("approval"));

        as(token).queryParam("status", "PENDING_APPROVAL")
                .when().get("/api/substitution-requests")
                .then().statusCode(200)
                .body("id", hasItem((int) requestId));

        as(token).body(Map.of("substituteTeacherId", context.teacherB(), "comment", "同意"))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .body("substituteTeacherName", org.hamcrest.Matchers.notNullValue());

        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true))
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) context.teacherB()));

        as(token).queryParam("view", "baseline").queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(false))
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) context.teacherA()));

        as(token).when().get("/api/substitution-requests/" + requestId + "/history")
                .then().statusCode(200)
                .body("$", hasSize(4))
                .body("toStatus", hasItem("SUBMITTED"))
                .body("toStatus", hasItem("EFFECTIVE"));

        Response substituteStats = as(token).queryParam("teacherId", context.teacherB())
                .when().get("/api/substitutions/stats")
                .then().statusCode(200)
                .body("substitutedIn", greaterThanOrEqualTo(1))
                .extract().response();
        // The substitute had no weekly lessons and gains the covered one.
        assertThat(substituteStats.jsonPath().getInt("weeklyLessons")).isZero();
        assertThat(substituteStats.jsonPath().getInt("netLessons")).isEqualTo(1);

        Response originalStats = as(token).queryParam("teacherId", context.teacherA())
                .when().get("/api/substitutions/stats")
                .then().statusCode(200).extract().response();
        int originalWeekly = originalStats.jsonPath().getInt("weeklyLessons");
        assertThat(originalWeekly).isGreaterThan(0);
        assertThat(originalStats.jsonPath().getInt("netLessons")).isEqualTo(originalWeekly - 1);

        as(context.teacherToken()).when().get("/api/substitutions/my")
                .then().statusCode(200)
                .body("$", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    void configuredRecipientAccountsAlsoReceiveEffectiveNotices() {
        String token = adminToken();
        String suffix = TestData.suffix();
        String watcherName = "watch_" + suffix;
        createUser(token, watcherName, "teacher123", "teacher");
        Context context = prepare(token, suffix);
        long watcherId = as(token).when().get("/api/users").then().statusCode(200)
                .extract().jsonPath().getLong("find { it.username == '" + watcherName + "' }.id");
        as(token).body(Map.of("userIds", List.of(watcherId)))
                .when().put("/api/settings/substitution-recipients")
                .then().statusCode(200)
                .body("userIds", hasItem((int) watcherId));
        as(token).when().get("/api/settings/substitution-recipients")
                .then().statusCode(200)
                .body("userIds", hasItem((int) watcherId))
                .body("options.username", hasItem(watcherName));

        long requestId = submit(context, context.teacherToken(), context.teacherB());
        as(token).body(Map.of("substituteTeacherId", context.teacherB(), "comment", "同意"))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200);

        String watcherToken = login(watcherName, "teacher123");
        as(watcherToken).when().get("/api/notifications/recent")
                .then().statusCode(200)
                .body("title", hasItem("调代已生效"));
    }

    @Test
    void approvalSendsNotificationsToOriginalAndSubstitute() {
        String token = adminToken();
        as(token).body(Map.of("notification.scope", "with-admin"))
                .when().put("/api/settings").then().statusCode(200);
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());
        as(token).body(Map.of("substituteTeacherId", context.teacherB()))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200);

        as(context.teacherToken()).when().get("/api/notifications/unread-count")
                .then().statusCode(200)
                .body("count", greaterThanOrEqualTo(1));

        as(context.teacherToken()).when().get("/api/notifications/recent")
                .then().statusCode(200)
                .body("title", hasItem("调代申请已批准"))
                .body("content", hasItem(containsString("代课")));

        as(context.substituteToken()).when().get("/api/notifications/recent")
                .then().statusCode(200)
                .body("title", hasItem("您有一节代课安排"));

        as(context.headTeacherToken()).when().get("/api/notifications/recent")
                .then().statusCode(200)
                .body("title", hasItem("本班课表有调代"));

        as(context.teacherToken()).when().post("/api/notifications/read-all")
                .then().statusCode(200);
        as(context.teacherToken()).when().get("/api/notifications/unread-count")
                .then().statusCode(200)
                .body("count", equalTo(0));
    }

    @Test
    void directModeTakesEffectImmediately() {
        String token = adminToken();
        as(token).body(Map.of("substitution.approval-mode", "direct"))
                .when().put("/api/settings").then().statusCode(200);

        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());

        as(context.teacherToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .body("approvalMode", equalTo("direct"));

        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true));
    }

    @Test
    void modeSwitchDoesNotAffectRequestsAlreadyInFlow() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());

        as(token).body(Map.of("substitution.approval-mode", "direct"))
                .when().put("/api/settings").then().statusCode(200);

        as(token).body(Map.of("substituteTeacherId", context.teacherB()))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));
    }

    @Test
    void rejectionNotifiesRequester() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());

        as(token).body(Map.of("comment", "当天已有安排"))
                .when().post("/api/substitution-requests/" + requestId + "/reject")
                .then().statusCode(200)
                .body("status", equalTo("REJECTED"))
                .body("approvalComment", equalTo("当天已有安排"));

        as(context.teacherToken()).when().get("/api/notifications/recent")
                .then().statusCode(200)
                .body("title", hasItem("调代申请被驳回"));
    }

    @Test
    void teacherCannotSubmitForAnotherTeachersLesson() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        List<Map<String, Object>> entries = ScheduleFixture.classEntries(token, context.classId());
        Map<String, Object> otherEntry = entries.stream()
                .filter(item -> ((Number) item.get("teacherId")).longValue()
                        == context.fixture().otherTeacherId())
                .findFirst()
                .orElseThrow();
        LocalDate date = LocalDate.of(2025, 9, 1)
                .plusDays(((Number) otherEntry.get("dayOfWeek")).intValue() - 1L);

        as(context.teacherToken()).body(Map.of(
                        "entryId", ((Number) otherEntry.get("id")).longValue(),
                        "effectiveDate", date.toString(),
                        "expectedSubstituteTeacherId", context.teacherA()))
                .when().post("/api/substitution-requests")
                .then().statusCode(403);
    }

    @Test
    void dateWeekdayMustMatchTheLesson() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        LocalDate wrongDate = context.date().plusDays(1);
        if (wrongDate.getDayOfWeek().getValue() == context.date().getDayOfWeek().getValue()) {
            wrongDate = context.date().plusDays(2);
        }

        as(context.teacherToken()).body(Map.of(
                        "entryId", context.entryId(),
                        "effectiveDate", wrongDate.toString(),
                        "expectedSubstituteTeacherId", context.teacherB()))
                .when().post("/api/substitution-requests")
                .then().statusCode(400);
    }

    @Test
    void oneTimeCoverChangesOnlyTheSpecifiedDate() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());
        as(token).body(Map.of("substituteTeacherId", context.teacherB(), "comment", "同意"))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));

        // 指定日期：代课生效，教师换成代课人。
        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true))
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) context.teacherB()));

        // 下周同槽：恢复常规安排，一次调代不延续到以后各周。
        LocalDate nextWeekSameSlot = context.date().plusDays(7);
        as(token).queryParam("date", nextWeekSameSlot.toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(false))
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) context.teacherA()));
    }

    @Test
    void substituteTeacherSeesTheLessonInTheirTimetable() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());
        as(token).body(Map.of("substituteTeacherId", context.teacherB()))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200);

        // 接替者在该日期恰好看到这节课一次。
        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/teacher/" + context.teacherB())
                .then().statusCode(200)
                .body("findAll { it.id == " + context.entryId() + " }.size()", equalTo(1))
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true));

        // 被解除安排的原教师在该日期不再看到这节课（ADR-0030：按最终有效教师过滤）。
        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/teacher/" + context.teacherA())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }", equalTo(null));
    }

    @Test
    void coversOnDifferentWeeksResolveIndependently() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long secondSubstituteId = TestData.teacher(token, "第二周代课" + TestData.suffix());

        // 同一基线条目、两个不同周的日期各生效一笔一次性代课。
        long firstRequest = submit(context, context.teacherToken(), context.teacherB());
        as(token).body(Map.of("substituteTeacherId", context.teacherB()))
                .when().post("/api/substitution-requests/" + firstRequest + "/approve")
                .then().statusCode(200);

        LocalDate secondWeekDate = context.date().plusDays(7);
        long secondRequest = as(context.teacherToken()).body(Map.of(
                        "entryId", context.entryId(),
                        "effectiveDate", secondWeekDate.toString(),
                        "reason", "第二周再次请假",
                        "expectedSubstituteTeacherId", secondSubstituteId))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("status", equalTo("PENDING_APPROVAL"))
                .extract().jsonPath().getLong("id");
        as(token).body(Map.of("substituteTeacherId", secondSubstituteId))
                .when().post("/api/substitution-requests/" + secondRequest + "/approve")
                .then().statusCode(200);

        // 同周期槽不同周分别显示各自的实例，互不覆盖。
        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) context.teacherB()))
                .body("find { it.id == " + context.entryId() + " }.lessonDate",
                        equalTo(context.date().toString()));
        as(token).queryParam("date", secondWeekDate.toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) secondSubstituteId))
                .body("find { it.id == " + context.entryId() + " }.lessonDate",
                        equalTo(secondWeekDate.toString()));
        // 第三周没有任何实例命中，回到基线。
        as(token).queryParam("date", context.date().plusDays(14).toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(false));
    }

    @Test
    void roomViewFollowsTheInstanceOnItsDate() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long newRoomId = TestData.room(token, "临时教室" + TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());
        as(token).body(Map.of(
                        "substituteTeacherId", context.teacherB(),
                        "newRoomId", newRoomId))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200);

        // 授课日：该实例出现在新教室，原教室不再有这节课。
        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/room/" + newRoomId)
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true));
        // 原教室的视角：先拿到基线教室 id 再断言当天与下周。
        long baselineRoomId = as(token).queryParam("view", "baseline")
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .extract().jsonPath().getLong("find { it.id == " + context.entryId() + " }.roomId");

        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/room/" + baselineRoomId)
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }", equalTo(null));

        // 下周同一槽位：恢复基线教室，新教室不再有这节课。
        as(token).queryParam("date", context.date().plusDays(7).toString())
                .when().get("/api/timetable/room/" + baselineRoomId)
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(false));
        as(token).queryParam("date", context.date().plusDays(7).toString())
                .when().get("/api/timetable/room/" + newRoomId)
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }", equalTo(null));
    }

    @Test
    void allViewsAgreeOnTheDatedInstance() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());
        as(token).body(Map.of("substituteTeacherId", context.teacherB()))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200);

        // 班级、教师、全校三个视角对同一实例给出一致结果，行上带实例日期。
        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) context.teacherB()))
                .body("find { it.id == " + context.entryId() + " }.lessonDate",
                        equalTo(context.date().toString()))
                .body("find { it.id == " + context.entryId() + " }.targetLessonDate", equalTo(null));

        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/teacher/" + context.teacherB())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.lessonDate",
                        equalTo(context.date().toString()));

        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/school")
                .then().statusCode(200)
                .body("entries.find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) context.teacherB()));

        // 导出走同一读取模型：授课日与下周都能导出且不报错。
        as(token).queryParam("view", "latest").queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId() + "/export/excel")
                .then().statusCode(200);
        as(token).queryParam("view", "latest")
                .queryParam("date", context.date().plusDays(7).toString())
                .when().get("/api/timetable/class/" + context.classId() + "/export/excel")
                .then().statusCode(200);
    }

    @Test
    void legacySubstitutionRowKeepsOverridingFollowingWeeks() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());
        as(token).body(Map.of("substituteTeacherId", context.teacherB()))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200);

        // 把生效行改回旧格式（lesson_date 置空），模拟迁移前的历史数据：不转换、不解释。
        Substitution row = substitutions.findByRequestId(requestId).get(0);
        row.setLessonDate(null);
        row.setTargetLessonDate(null);
        substitutions.save(row);

        // 旧格式语义保持改动前行为：生效日起持续覆盖后续各周。
        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true))
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) context.teacherB()))
                .body("find { it.id == " + context.entryId() + " }.lessonDate", equalTo(null));
        as(token).queryParam("date", context.date().plusDays(7).toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true))
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) context.teacherB()));
        as(token).queryParam("date", context.date().plusDays(28).toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true));
    }
}
