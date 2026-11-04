package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.foundation.domain.TeachingClassRepository;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票 10 的影响预览契约：规则范围从积木句子推导，课节数/冲突只来自该学期最新的
 * DRAFT，并且和草稿校验共用同一个 {@code ConstraintChecker}。整个接口只读。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RuleImpactIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private TeachingClassRepository teachingClasses;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Test
    void teacherRuleReportsTheTeachersClassesAndSlots() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        Map<String, Long> periods = periodIds(token, semesterId);
        long grade = TestData.grade(token, semesterId, "影响年级" + TestData.suffix());
        long classA = TestData.adminClass(token, semesterId, grade, "影响一班" + TestData.suffix());
        long classB = TestData.adminClass(token, semesterId, grade, "影响二班" + TestData.suffix());
        long classC = TestData.adminClass(token, semesterId, grade, "影响三班" + TestData.suffix());
        long course = TestData.course(token, "影响课程" + TestData.suffix());
        long teacher = TestData.teacher(token, "影响教师" + TestData.suffix());
        assign(semesterId, classA, course, teacher);
        assign(semesterId, classB, course, teacher);

        Response impact = impact(token, semesterId, List.of(teacherUnavailable(teacher, 1, 2)));

        impact.then().statusCode(200)
                .body("errors", org.hamcrest.Matchers.empty())
                .body("lessonCount", org.hamcrest.Matchers.equalTo(0));
        List<Integer> classIds = impact.jsonPath().getList("classes.id", Integer.class);
        assertThat(classIds).contains((int) classA, (int) classB);
        assertThat(classIds).doesNotContain((int) classC);
        assertThat(impact.jsonPath().getList("teachers.id", Integer.class)).contains((int) teacher);
        assertThat(impact.jsonPath().getList("slots.dayOfWeek", Integer.class)).containsExactly(1);
        assertThat(impact.jsonPath().getList("slots.periodNo", Integer.class)).containsExactly(2);
    }

    @Test
    void classRuleReportsOnlyThatClass() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long grade = TestData.grade(token, semesterId, "班级范围" + TestData.suffix());
        long classA = TestData.adminClass(token, semesterId, grade, "范围一班" + TestData.suffix());
        TestData.adminClass(token, semesterId, grade, "范围二班" + TestData.suffix());

        Response impact = impact(token, semesterId, List.of(classUnavailable(classA, 2, 3)));

        impact.then().statusCode(200);
        assertThat(impact.jsonPath().getList("classes.id", Integer.class)).containsExactly((int) classA);
        assertThat(impact.jsonPath().getList("teachers.id", Integer.class)).isEmpty();
        assertThat(impact.jsonPath().getList("slots.dayOfWeek", Integer.class)).containsExactly(2);
        assertThat(impact.jsonPath().getList("slots.periodNo", Integer.class)).containsExactly(3);
    }

    @Test
    void schoolRuleReportsEveryClassAndTeacher() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long grade = TestData.grade(token, semesterId, "全校范围" + TestData.suffix());
        TestData.adminClass(token, semesterId, grade, "全校一班" + TestData.suffix());
        TestData.adminClass(token, semesterId, grade, "全校二班" + TestData.suffix());
        long teacher = TestData.teacher(token, "全校教师" + TestData.suffix());

        Response impact = impact(token, semesterId, List.of(schoolUnavailable(1, 4)));

        impact.then().statusCode(200);
        assertThat(impact.jsonPath().getList("classes.id", Integer.class)).hasSize(2);
        assertThat(impact.jsonPath().getList("teachers.id", Integer.class)).contains((int) teacher);
        assertThat(impact.jsonPath().getList("slots.periodNo", Integer.class)).containsExactly(4);
    }

    @Test
    void draftLessonsInsideTheScopeProduceCountAndConflicts() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        Map<String, Long> periods = periodIds(token, semesterId);
        long grade = TestData.grade(token, semesterId, "草稿影响" + TestData.suffix());
        long classA = TestData.adminClass(token, semesterId, grade, "草稿一班" + TestData.suffix());
        long classB = TestData.adminClass(token, semesterId, grade, "草稿二班" + TestData.suffix());
        long course = TestData.course(token, "草稿课程" + TestData.suffix());
        long teacher = TestData.teacher(token, "草稿教师" + TestData.suffix());
        long room = TestData.room(token, "草稿教室" + TestData.suffix());
        long draftId = draft(semesterId, "影响预览草稿" + TestData.suffix());
        placeLesson(draftId, classA, course, teacher, periods.get("1-2"), room, 1);
        placeLesson(draftId, classB, course, teacher, periods.get("1-5"), room, 2);

        Response impact = impact(token, semesterId, List.of(schoolUnavailable(1, 2)));

        impact.then().statusCode(200)
                .body("lessonCount", org.hamcrest.Matchers.equalTo(1))
                .body("conflicts", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty()));
        assertThat(impact.jsonPath().getList("conflicts.type", String.class))
                .contains("CLASS_UNAVAILABLE");
        assertThat(impact.jsonPath().getList("conflicts.lessonId", Long.class))
                .allMatch(lessonId -> lessonId != null);
    }

    @Test
    void aSemesterWithoutDraftReportsNoLessonsAndNoConflicts() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);

        Response impact = impact(token, semesterId, List.of(schoolUnavailable(2, 6)));

        impact.then().statusCode(200)
                .body("lessonCount", org.hamcrest.Matchers.equalTo(0))
                .body("conflicts", org.hamcrest.Matchers.empty());
    }

    @Test
    void invalidRulesComeBackAsErrorsInsteadOfServerErrors() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);

        Response impact = impact(token, semesterId, List.of(Map.of(
                "type", "COURSE_UNAVAILABLE",
                "weight", 100,
                "hard", true,
                "enabled", true,
                "entries", List.of(Map.of(
                        "params", Map.of("dayOfWeek", 1, "periodNo", 2),
                        "enabled", true,
                        "hard", true,
                        "groupKey", "pt-invalid:0")))));

        impact.then().statusCode(200)
                .body("errors", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty()))
                .body("conflicts", org.hamcrest.Matchers.empty());
    }

    // ------------------------------------------------------------------ helpers

    private Response impact(String token, long semesterId, List<Map<String, Object>> configs) {
        return as(token)
                .body(Map.of("semesterId", semesterId, "configs", configs))
                .when().post("/api/rules/impact");
    }

    private Map<String, Object> schoolUnavailable(int dayOfWeek, int periodNo) {
        return config("CLASS_UNAVAILABLE", List.of(entry(
                Map.of("dayOfWeek", dayOfWeek, "periodNo", periodNo), "pt-school:0")));
    }

    private Map<String, Object> classUnavailable(long classId, int dayOfWeek, int periodNo) {
        return config("CLASS_UNAVAILABLE", List.of(entry(
                Map.of("classId", classId, "dayOfWeek", dayOfWeek, "periodNo", periodNo),
                "pt-class:0")));
    }

    private Map<String, Object> teacherUnavailable(long teacherId, int dayOfWeek, int periodNo) {
        return config("TEACHER_UNAVAILABLE", List.of(entry(
                Map.of("teacherId", teacherId, "dayOfWeek", dayOfWeek, "periodNo", periodNo),
                "pt-teacher:0")));
    }

    private Map<String, Object> config(String type, List<Map<String, Object>> entries) {
        return Map.of(
                "type", type,
                "weight", 100,
                "hard", true,
                "enabled", true,
                "entries", entries);
    }

    private Map<String, Object> entry(Map<String, Object> params, String groupKey) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("params", params);
        row.put("enabled", true);
        row.put("hard", true);
        row.put("groupKey", groupKey);
        return row;
    }

    private void assign(long semesterId, long classId, long courseId, long teacherId) {
        // Creating an admin class already creates its teaching class.
        long teachingClassId = teachingClasses.findByAdminClassId(classId)
                .orElseThrow()
                .getId();
        Assignment assignment = new Assignment();
        assignment.setSemesterId(semesterId);
        assignment.setAdminClassId(classId);
        assignment.setTeachingClassId(teachingClassId);
        assignment.setCourseId(courseId);
        assignment.setTeacherId(teacherId);
        assignment.setWeeklyLessons(2);
        assignments.save(assignment);
    }

    private long draft(long semesterId, String name) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName(name);
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        return drafts.save(draft).getId();
    }

    private void placeLesson(long draftId, long classId, long courseId, long teacherId,
                             Long periodId, long roomId, int index) {
        DraftLesson lesson = new DraftLesson();
        lesson.setDraftId(draftId);
        lesson.setAssignmentId(0L);
        lesson.setLessonIndex(index);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periodId);
        lesson.setRoomId(roomId);
        draftLessons.save(lesson);
    }

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }

    private Map<String, Long> periodIds(String token, long semesterId) {
        List<Map<String, Object>> rows = as(token)
                .when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<String, Long> bySlot = new LinkedHashMap<>();
        rows.forEach(row -> bySlot.put(
                ((Number) row.get("dayOfWeek")).intValue() + "-"
                        + ((Number) row.get("periodNo")).intValue(),
                ((Number) row.get("id")).longValue()));
        return bySlot;
    }
}
