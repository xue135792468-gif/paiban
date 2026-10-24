package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end swap flow at the HTTP seam: submit, generate, approve the whole
 * cycle, overlay, notify, record and score. The draft is seeded through
 * repositories so each teacher's availability is deterministic, then published
 * through the real API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SwapFlowIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 9, 1);

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    private record World(long semesterId, long class1, long class2,
                         Map<Integer, Long> periodByNo, Map<String, Long> teachers,
                         Map<String, Long> courses, Map<String, Long> rooms,
                         long chineseEntry, long mathEntry, long englishEntry) {}

    private World prepare(String token, String suffix) {
        return prepare(token, suffix, true);
    }

    private World prepare(String token, String suffix, boolean blockSameDay) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 2, "afternoonCount", 2, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        List<Map<String, Object>> periodRows = as(token)
                .when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<Integer, Long> periodByNo = new LinkedHashMap<>();
        periodRows.stream()
                .filter(row -> ((Number) row.get("dayOfWeek")).intValue() == 1)
                .forEach(row -> periodByNo.put(((Number) row.get("periodNo")).intValue(),
                        ((Number) row.get("id")).longValue()));

        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        long class1 = TestData.adminClass(token, semesterId, gradeId, "高一1班" + suffix);
        long class2 = TestData.adminClass(token, semesterId, gradeId, "高一2班" + suffix);

        Map<String, Long> teachers = new LinkedHashMap<>();
        teachers.put("A", TestData.teacher(token, "语文老师" + suffix));
        teachers.put("B", TestData.teacher(token, "数学老师" + suffix));
        teachers.put("C", TestData.teacher(token, "英语老师" + suffix));
        teachers.put("D", TestData.teacher(token, "空闲老师" + suffix));

        Map<String, Long> courses = new LinkedHashMap<>();
        courses.put("语文", TestData.course(token, "语文" + suffix));
        courses.put("数学", TestData.course(token, "数学" + suffix));
        courses.put("英语", TestData.course(token, "英语" + suffix));

        Map<String, Long> rooms = new LinkedHashMap<>();
        for (int i = 1; i <= 4; i++) {
            rooms.put("R" + i, TestData.room(token, "教室" + i + suffix));
        }

        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("调课测试" + suffix);
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = drafts.save(draft).getId();

        long chinese = place(draftId, class1, courses.get("语文"), teachers.get("A"), periodByNo.get(1), rooms.get("R1"));
        long math = place(draftId, class1, courses.get("数学"), teachers.get("B"), periodByNo.get(2), rooms.get("R2"));
        long english = place(draftId, class1, courses.get("英语"), teachers.get("C"), periodByNo.get(3), rooms.get("R3"));
        // When blockSameDay is set, teacher B is busy on Monday period 1, forcing a
        // three-teacher cycle; otherwise the plain two-teacher cycle is available.
        place(draftId, class2, courses.get("数学"), teachers.get("B"),
                periodByNo.get(blockSameDay ? 1 : 4), rooms.get("R4"));

        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish").then().statusCode(200);

        // Publishing copies draft lessons into timetable_entry with new ids, so read
        // the published ids back by course instead of reusing the draft lesson ids.
        List<Map<String, Object>> published = as(token)
                .queryParam("view", "baseline")
                .when().get("/api/timetable/class/" + class1)
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<Long, Long> entryByCourse = new LinkedHashMap<>();
        published.forEach(row -> entryByCourse.put(
                ((Number) row.get("courseId")).longValue(),
                ((Number) row.get("id")).longValue()));
        long chineseEntry = entryByCourse.get(courses.get("语文"));
        long mathEntry = entryByCourse.get(courses.get("数学"));
        long englishEntry = entryByCourse.get(courses.get("英语"));

        return new World(semesterId, class1, class2, periodByNo, teachers, courses, rooms,
                chineseEntry, mathEntry, englishEntry);
    }

    private long place(long draftId, long classId, long courseId, long teacherId,
                       long periodId, long roomId) {
        DraftLesson lesson = new DraftLesson();
        lesson.setDraftId(draftId);
        lesson.setAssignmentId(0L);
        lesson.setLessonIndex(1);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periodId);
        lesson.setRoomId(roomId);
        return draftLessons.save(lesson).getId();
    }

    private String teacherUser(String token, String username, long teacherId) {
        as(token).body(Map.of(
                        "username", username,
                        "password", "teacher123",
                        "displayName", username,
                        "roleCodes", List.of("teacher"),
                        "teacherId", teacherId))
                .when().post("/api/users").then().statusCode(200);
        return login(username, "teacher123");
    }

    private long submitSwap(String token, long entryId, long targetPeriodId) {
        return as(token).body(Map.of(
                        "entryId", entryId,
                        "effectiveDate", MONDAY.toString(),
                        "reason", "临时有事",
                        "type", "SWAP",
                        "targetPeriodId", targetPeriodId))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("type", equalTo("SWAP"))
                .extract().jsonPath().getLong("id");
    }

    @Test
    void swapRequestGeneratesAThreeTeacherPlanAndApprovesItAsOneUnit() {
        String admin = adminToken();
        String suffix = TestData.suffix();
        World world = prepare(admin, suffix);
        String teacherA = teacherUser(admin, "swapa_" + suffix, world.teachers().get("A"));
        String teacherB = teacherUser(admin, "swapb_" + suffix, world.teachers().get("B"));
        String teacherC = teacherUser(admin, "swapc_" + suffix, world.teachers().get("C"));

        long requestId = submitSwap(teacherA, world.chineseEntry(), world.periodByNo().get(2));

        Response generated = as(admin).when().post("/api/substitution-requests/" + requestId + "/plans")
                .then().statusCode(200)
                .body("plans", hasSize(1))
                .body("plans[0].cycleLength", equalTo(3))
                .body("plans[0].impactDays", equalTo(1))
                .body("plans[0].impactTeachers", equalTo(3))
                .body("plans[0].moves", hasSize(3))
                .body("plans[0].rankingPreset", equalTo("min-impact"))
                .extract().response();
        long planId = generated.jsonPath().getLong("plans[0].id");
        int softPenalty = generated.jsonPath().getInt("plans[0].softPenalty");

        as(admin).when().post("/api/substitution-plans/" + planId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));

        // Overlay: the Chinese lesson now sits at Monday period 2 with a 调 marker.
        as(admin).queryParam("date", MONDAY.toString())
                .when().get("/api/timetable/class/" + world.class1())
                .then().statusCode(200)
                .body("find { it.id == " + world.chineseEntry() + " }.periodNo", equalTo(2))
                .body("find { it.id == " + world.chineseEntry() + " }.substituted", equalTo(true))
                .body("find { it.id == " + world.chineseEntry() + " }.substitutionType", equalTo("SWAP"))
                .body("find { it.id == " + world.chineseEntry() + " }.teacherId",
                        equalTo((int) world.teachers().get("A").longValue()));

        // Baseline view is untouched.
        as(admin).queryParam("view", "baseline").queryParam("date", MONDAY.toString())
                .when().get("/api/timetable/class/" + world.class1())
                .then().statusCode(200)
                .body("find { it.id == " + world.chineseEntry() + " }.periodNo", equalTo(1))
                .body("find { it.id == " + world.chineseEntry() + " }.substituted", equalTo(false));

        // Every involved teacher gets their own move, not a cover notice.
        for (String token : List.of(teacherB, teacherC)) {
            as(token).when().get("/api/notifications/recent")
                    .then().statusCode(200)
                    .body("title", hasItem("您的调课已生效"));
        }

        // Records carry the type and the plan id; stats never count a swap as a cover.
        as(admin).queryParam("type", "SWAP")
                .when().get("/api/substitutions")
                .then().statusCode(200)
                .body("$", hasSize(3))
                .body("type", org.hamcrest.Matchers.everyItem(equalTo("SWAP")))
                .body("planId", org.hamcrest.Matchers.everyItem(equalTo((int) planId)));
        for (long teacherId : List.of(world.teachers().get("A"), world.teachers().get("B"),
                world.teachers().get("C"))) {
            as(admin).queryParam("teacherId", teacherId)
                    .when().get("/api/substitutions/stats")
                    .then().statusCode(200)
                    .body("substitutedOut", equalTo(0))
                    .body("substitutedIn", equalTo(0))
                    .body("swapParticipation", equalTo(1));
        }

        // The plan's reported soft score is what the verifier recomputed.
        String csv = as(admin).queryParam("type", "SWAP")
                .when().get("/api/substitutions/export")
                .then().statusCode(200).extract().asString();
        assertThat(csv).contains("类型").contains("调课");

        assertThat(softPenalty).isZero();
        as(admin).when().get("/api/substitution-requests/" + requestId + "/history")
                .then().statusCode(200)
                .body("toStatus", hasItem("EFFECTIVE"));
    }

    @Test
    void approvesATwoTeacherSameDayCycleAsOneUnit() {
        String admin = adminToken();
        String suffix = TestData.suffix();
        World world = prepare(admin, suffix, false);
        String teacherA = teacherUser(admin, "twoa_" + suffix, world.teachers().get("A"));
        teacherUser(admin, "twob_" + suffix, world.teachers().get("B"));

        long requestId = submitSwap(teacherA, world.chineseEntry(), world.periodByNo().get(2));
        long planId = as(admin).when().post("/api/substitution-requests/" + requestId + "/plans")
                .then().statusCode(200)
                .body("plans[0].cycleLength", equalTo(2))
                .body("plans[0].impactTeachers", equalTo(2))
                .body("plans[0].moves", hasSize(2))
                .extract().jsonPath().getLong("plans[0].id");

        as(admin).when().post("/api/substitution-plans/" + planId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));

        as(admin).queryParam("type", "SWAP")
                .when().get("/api/substitutions")
                .then().statusCode(200)
                .body("$", hasSize(2))
                .body("type", org.hamcrest.Matchers.everyItem(equalTo("SWAP")));
    }

    @Test
    void approvingAStalePlanWritesNothing() {
        String admin = adminToken();
        String suffix = TestData.suffix();
        World world = prepare(admin, suffix);
        String teacherA = teacherUser(admin, "stalea_" + suffix, world.teachers().get("A"));
        teacherUser(admin, "staleb_" + suffix, world.teachers().get("B"));
        teacherUser(admin, "stalec_" + suffix, world.teachers().get("C"));
        teacherUser(admin, "staled_" + suffix, world.teachers().get("D"));

        long requestId = submitSwap(teacherA, world.chineseEntry(), world.periodByNo().get(2));
        long planId = as(admin).when().post("/api/substitution-requests/" + requestId + "/plans")
                .then().statusCode(200).extract().jsonPath().getLong("plans[0].id");

        // Someone else changes the published timetable before the plan is approved.
        long coverRequest = as(admin).body(Map.of(
                        "entryId", world.mathEntry(),
                        "effectiveDate", MONDAY.toString(),
                        "reason", "占用目标节次",
                        "type", "COVER",
                        "expectedSubstituteTeacherId", world.teachers().get("D")))
                .when().post("/api/substitution-requests")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        as(admin).body(Map.of("substituteTeacherId", world.teachers().get("D")))
                .when().post("/api/substitution-requests/" + coverRequest + "/approve")
                .then().statusCode(200);

        int before = as(admin).queryParam("type", "SWAP")
                .when().get("/api/substitutions").then().statusCode(200)
                .extract().jsonPath().getList("$").size();

        as(admin).when().post("/api/substitution-plans/" + planId + "/approve")
                .then().statusCode(409);

        as(admin).queryParam("type", "SWAP")
                .when().get("/api/substitutions").then().statusCode(200)
                .body("$", hasSize(before));
        as(admin).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("PENDING_APPROVAL"));
    }

    @Test
    void aRequestCannotTargetAnEmptySlotOrPassAWeekdayMismatch() {
        String admin = adminToken();
        String suffix = TestData.suffix();
        World world = prepare(admin, suffix);
        String teacherA = teacherUser(admin, "guarda_" + suffix, world.teachers().get("A"));

        // Monday period 4 is empty for this class: "只挪不补" is out of scope.
        long requestId = submitSwap(teacherA, world.chineseEntry(), world.periodByNo().get(4));
        as(admin).when().post("/api/substitution-requests/" + requestId + "/plans")
                .then().statusCode(200)
                .body("plans", hasSize(0))
                .body("diagnoses.code", hasItem("TARGET_EMPTY"));

        // A date whose weekday does not match the lesson is rejected at submit time.
        as(teacherA).body(Map.of(
                        "entryId", world.chineseEntry(),
                        "effectiveDate", MONDAY.plusDays(1).toString(),
                        "type", "SWAP",
                        "targetPeriodId", world.periodByNo().get(2)))
                .when().post("/api/substitution-requests")
                .then().statusCode(400);
    }

}
