package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
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
 * A generated plan must be re-checked against the rules in force at approval time:
 * adding a teacher-unavailable entry after generation makes the whole plan fall.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SwapApprovalReverificationIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 9, 1);

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Test
    void addingAnUnavailableEntryAfterGenerationRejectsTheWholePlan() {
        String admin = adminToken();
        String suffix = TestData.suffix();
        World world = prepare(admin, suffix);
        String teacherA = teacherUser(admin, "reva_" + suffix, world.teachers().get("A"));

        long requestId = as(teacherA).body(Map.of(
                        "entryId", world.chineseEntry(),
                        "effectiveDate", MONDAY.toString(),
                        "reason", "临时有事",
                        "type", "SWAP",
                        "targetPeriodId", world.periodByNo().get(2)))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");

        long planId = as(admin).when().post("/api/substitution-requests/" + requestId + "/plans")
                .then().statusCode(200)
                .body("plans[0].cycleLength", equalTo(3))
                .extract().jsonPath().getLong("plans[0].id");

        // The plan moves teacher C into Monday period 1; that slot is now forbidden.
        as(admin).queryParam("semesterId", world.semesterId())
                .body(Map.of("configs", List.of(
                        Map.of("type", "TEACHER_CONFLICT", "params", Map.of(),
                                "weight", 100, "hard", true, "enabled", true),
                        Map.of("type", "CLASS_CONFLICT", "params", Map.of(),
                                "weight", 100, "hard", true, "enabled", true),
                        Map.of("type", "ROOM_CONFLICT", "params", Map.of(),
                                "weight", 100, "hard", true, "enabled", true),
                        Map.of("type", "TEACHER_UNAVAILABLE", "weight", 100, "hard", true,
                                "enabled", true,
                                "entries", List.of(Map.of("params", Map.of(
                                        "teacherId", world.teachers().get("C"),
                                        "dayOfWeek", 1,
                                        "periodNo", 1)))))))
                .when().put("/api/constraints")
                .then().statusCode(200);

        as(admin).when().post("/api/substitution-plans/" + planId + "/approve")
                .then().statusCode(409);

        // Nothing partial: no substitution rows, the request is still pending.
        as(admin).queryParam("type", "SWAP")
                .when().get("/api/substitutions")
                .then().statusCode(200)
                .body("$", org.hamcrest.Matchers.empty());
        as(admin).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("PENDING_APPROVAL"));
    }

    @Test
    void addingATargetEntryAfterGenerationRejectsTheWholePlan() {
        // Same safety net for the rules ticket 10/03 made multi-entry: the plan moves
        // teacher C into Monday period 1, which the teacher's first-period rule forbids.
        String admin = adminToken();
        String suffix = TestData.suffix();
        World world = prepare(admin, suffix);
        String teacherA = teacherUser(admin, "revb_" + suffix, world.teachers().get("A"));

        long requestId = as(teacherA).body(Map.of(
                        "entryId", world.chineseEntry(),
                        "effectiveDate", MONDAY.toString(),
                        "reason", "临时有事",
                        "type", "SWAP",
                        "targetPeriodId", world.periodByNo().get(2)))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");

        long planId = as(admin).when().post("/api/substitution-requests/" + requestId + "/plans")
                .then().statusCode(200)
                .body("plans[0].cycleLength", equalTo(3))
                .extract().jsonPath().getLong("plans[0].id");

        as(admin).queryParam("semesterId", world.semesterId())
                .body(Map.of("configs", List.of(
                        Map.of("type", "TEACHER_CONFLICT", "params", Map.of(),
                                "weight", 100, "hard", true, "enabled", true),
                        Map.of("type", "CLASS_CONFLICT", "params", Map.of(),
                                "weight", 100, "hard", true, "enabled", true),
                        Map.of("type", "ROOM_CONFLICT", "params", Map.of(),
                                "weight", 100, "hard", true, "enabled", true),
                        Map.of("type", "TEACHER_NO_FIRST_PERIOD", "weight", 35, "hard", true,
                                "enabled", true,
                                "entries", List.of(
                                        Map.of("params", Map.of(
                                                "teacherId", world.teachers().get("C"))),
                                        Map.of("params", Map.of(
                                                "teacherId", world.teachers().get("B"))))))))
                .when().put("/api/constraints")
                .then().statusCode(200);

        as(admin).when().post("/api/substitution-plans/" + planId + "/approve")
                .then().statusCode(409);

        as(admin).queryParam("type", "SWAP")
                .when().get("/api/substitutions")
                .then().statusCode(200)
                .body("$", org.hamcrest.Matchers.empty());
        as(admin).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("PENDING_APPROVAL"));
    }

    private record World(long semesterId, long class1, long class2,
                         Map<Integer, Long> periodByNo, Map<String, Long> teachers,
                         long chineseEntry) {}

    private World prepare(String token, String suffix) {
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
        draft.setName("审批重验" + suffix);
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = drafts.save(draft).getId();

        place(draftId, class1, courses.get("语文"), teachers.get("A"), periodByNo.get(1), rooms.get("R1"));
        place(draftId, class1, courses.get("数学"), teachers.get("B"), periodByNo.get(2), rooms.get("R2"));
        place(draftId, class1, courses.get("英语"), teachers.get("C"), periodByNo.get(3), rooms.get("R3"));
        // Teacher B is busy on Monday period 1, forcing a three-teacher cycle.
        place(draftId, class2, courses.get("数学"), teachers.get("B"), periodByNo.get(1), rooms.get("R4"));

        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish").then().statusCode(200);

        List<Map<String, Object>> published = ScheduleFixture.publishedEntries(token, class1);
        Map<String, Object> chineseRow = published.stream()
                .filter(row -> ((Number) row.get("courseId")).longValue() == courses.get("语文"))
                .findFirst().orElseThrow();
        long chineseEntry = ((Number) chineseRow.get("id")).longValue();

        return new World(semesterId, class1, class2, periodByNo, teachers, chineseEntry);
    }

    private void place(long draftId, long classId, long courseId, long teacherId,
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
        draftLessons.save(lesson);
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
}
