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

    private record Context(ScheduleFixture.Result fixture, String teacherToken, String substituteToken,
                           String classHeadToken, long teacherA, long teacherB, long classId,
                           long entryId, LocalDate date, String username) {}

    private Context prepare(String token, String suffix) {
        // 未来学期：批准路径受开课截止约束（规格第 6 节），批准要真正落地必须锚在未来。
        LocalDate monday = ScheduleFixture.nextMonday();
        ScheduleFixture.Result fixture =
                ScheduleFixture.publishedIn(token, 5, TestData.semesterStarting(token, monday));
        List<Map<String, Object>> entries = ScheduleFixture.classEntries(token, fixture.classId());
        Map<String, Object> entry = entries.stream()
                .filter(item -> ((Number) item.get("teacherId")).longValue() == fixture.teacherId())
                .findFirst()
                .orElseThrow();
        long entryId = ((Number) entry.get("id")).longValue();
        int dayOfWeek = ((Number) entry.get("dayOfWeek")).intValue();
        LocalDate date = monday.plusDays(dayOfWeek - 1L);

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
        // 班主任角色已下线（票 01）：这位班主任现在是普通老师账号，只用于对照「按本班」不再扩权。
        String headUsername = "head_" + suffix;
        as(token).body(Map.of(
                        "username", headUsername,
                        "password", "teacher123",
                        "displayName", "班主任",
                        "roleCodes", List.of("teacher"),
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
    void directModeTakesEffectImmediately() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());

        as(context.teacherToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));

        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true));
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
        LocalDate date = ScheduleFixture.nextMonday()
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
    void repeatedCoverRetiresPreviousEffectiveRow() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long firstRequest = submit(context, context.teacherToken(), context.teacherB());

        // 再次调整（乙 → 另一位无课教师）：允许提交并直接生效，旧行退出、新行接管。
        long secondSubstituteId = TestData.teacher(token, "第二任代课" + TestData.suffix());
        long secondRequest = as(context.teacherToken()).body(Map.of(
                        "entryId", context.entryId(),
                        "effectiveDate", context.date().toString(),
                        "reason", "换一位代课人",
                        "expectedSubstituteTeacherId", secondSubstituteId))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .extract().jsonPath().getLong("id");

        assertThat(substitutions.findByRequestId(secondRequest)).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE);
            assertThat(row.getSubstituteTeacherId()).isEqualTo(secondSubstituteId);
            assertThat(row.getLessonDate()).isEqualTo(context.date());
        });
        Substitution firstRow = substitutions.findByRequestId(firstRequest).get(0);
        assertThat(substitutions.findById(firstRow.getId()).orElseThrow().getStatus())
                .isEqualTo(Substitution.CANCELLED);
        // 同一实例同一时刻只有一条 EFFECTIVE。
        assertThat(substitutions.findByEntryId(context.entryId()))
                .filteredOn(row -> Substitution.EFFECTIVE.equals(row.getStatus()))
                .hasSize(1);

        // 课表上这次授课只出现一次，教师是新代课人。
        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("findAll { it.id == " + context.entryId() + " }.size()", equalTo(1))
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) secondSubstituteId));
    }

    @Test
    void coversOnDifferentWeeksResolveIndependently() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long secondSubstituteId = TestData.teacher(token, "第二周代课" + TestData.suffix());

        // 同一基线条目、两个不同周的日期各生效一笔一次性代课。
        long firstRequest = submit(context, context.teacherToken(), context.teacherB());

        LocalDate secondWeekDate = context.date().plusDays(7);
        long secondRequest = as(context.teacherToken()).body(Map.of(
                        "entryId", context.entryId(),
                        "effectiveDate", secondWeekDate.toString(),
                        "reason", "第二周再次请假",
                        "expectedSubstituteTeacherId", secondSubstituteId))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .extract().jsonPath().getLong("id");

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
    void allViewsAgreeOnTheDatedInstance() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());

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
