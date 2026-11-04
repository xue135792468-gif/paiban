package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 12 through the public REST surface: grade-scoped solving keeps the other
 * grades' periods fixed, shared-teacher daily limits count across grades, a missing
 * other-grade schedule is a synchronous 409, hard conflicts name the blocking teacher,
 * and publishing remains a whole-school operation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ScheduleByGradeIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private PeriodRepository periods;

    private record World(long semesterId, long gradeA, long gradeB, long classA, long classB,
                         long sharedTeacher, long courseA, long courseB,
                         long assignmentA, long assignmentB,
                         long mondayFirst, long mondaySecond,
                         long tuesdayFirst, long tuesdaySecond) {}

    private World prepare(String token) {
        String suffix = TestData.suffix();
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 2))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);

        long gradeA = TestData.grade(token, semesterId, "高一" + suffix);
        long gradeB = TestData.grade(token, semesterId, "高二" + suffix);
        long classA = TestData.adminClass(token, semesterId, gradeA, "高一1班" + suffix);
        long classB = TestData.adminClass(token, semesterId, gradeB, "高二1班" + suffix);
        long sharedTeacher = TestData.teacher(token, "共享教师" + suffix);
        long courseA = TestData.course(token, "语文" + suffix);
        long courseB = TestData.course(token, "数学" + suffix);
        TestData.room(token, "教室" + suffix);

        long assignmentA = assignment(token, classA, courseA, sharedTeacher, 2);
        long assignmentB = assignment(token, classB, courseB, sharedTeacher, 2);

        List<Period> monday = periodsOf(semesterId, 1);
        List<Period> tuesday = periodsOf(semesterId, 2);
        return new World(semesterId, gradeA, gradeB, classA, classB, sharedTeacher,
                courseA, courseB, assignmentA, assignmentB,
                monday.get(0).getId(), monday.get(1).getId(),
                tuesday.get(0).getId(), tuesday.get(1).getId());
    }

    private long assignment(String token, long classId, long courseId, long teacherId, int weekly) {
        Response response = as(token).body(Map.of(
                        "classId", classId,
                        "courseId", courseId,
                        "teacherId", teacherId,
                        "weeklyLessons", weekly))
                .when().post("/api/assignments");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private List<Period> periodsOf(long semesterId, int day) {
        return periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId).stream()
                .filter(period -> period.getDayOfWeek() == day)
                .sorted((left, right) -> Integer.compare(left.getPeriodNo(), right.getPeriodNo()))
                .toList();
    }

    /**
     * A deterministic, unpublished source draft. Both grades already have lessons, so the
     * grade-scoped run can keep grade B fixed without needing a published baseline (which
     * would make the semester unpublishable).
     */
    private long sourceDraft(World world) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(world.semesterId());
        draft.setName("来源草稿");
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        TimetableDraft saved = drafts.save(draft);

        addLesson(saved.getId(), world.assignmentB(), 0, world.classB(), world.courseB(),
                world.sharedTeacher(), world.mondayFirst());
        addLesson(saved.getId(), world.assignmentB(), 1, world.classB(), world.courseB(),
                world.sharedTeacher(), world.mondaySecond());
        addLesson(saved.getId(), world.assignmentA(), 0, world.classA(), world.courseA(),
                world.sharedTeacher(), world.tuesdayFirst());
        addLesson(saved.getId(), world.assignmentA(), 1, world.classA(), world.courseA(),
                world.sharedTeacher(), world.tuesdaySecond());
        return saved.getId();
    }

    private void addLesson(long draftId, long assignmentId, int index, long classId,
                           long courseId, long teacherId, long periodId) {
        DraftLesson lesson = new DraftLesson();
        lesson.setDraftId(draftId);
        lesson.setAssignmentId(assignmentId);
        lesson.setLessonIndex(index);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periodId);
        lesson.setLocked(false);
        draftLessons.save(lesson);
    }

    private long startJob(String token, World world, List<Long> gradeIds, Long sourceDraftId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("maxSeconds", 5);
        body.put("gradeIds", gradeIds);
        if (sourceDraftId != null) {
            body.put("sourceDraftId", sourceDraftId);
        }
        Response response = as(token).queryParam("semesterId", world.semesterId())
                .body(body)
                .when().post("/api/scheduling-jobs");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private Map<String, Object> awaitJob(String token, long jobId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> job = as(token).when().get("/api/scheduling-jobs/" + jobId)
                    .then().statusCode(200).extract().jsonPath().getMap("$");
            String status = String.valueOf(job.get("status"));
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(status)) {
                return job;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("排课任务超时未结束: " + jobId);
    }

    private List<Map<String, Object>> lessons(String token, long draftId) {
        return as(token).when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200)
                .extract().jsonPath().getList("lessons");
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private void saveTeacherMaxPerDay(String token, long semesterId, long teacherId, int max) {
        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(Map.of(
                        "type", "TEACHER_MAX_PER_DAY",
                        "weight", 60,
                        "hard", true,
                        "enabled", true,
                        "entries", List.of(Map.of("params",
                                Map.of("teacherId", teacherId, "maxPerDay", max)))))))
                .when().put("/api/constraints")
                .then().statusCode(200);
    }

    private void saveTeacherUnavailableEverywhere(
            String token, long semesterId, long teacherId) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Period period : periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)) {
            entries.add(Map.of("params", Map.of(
                    "teacherId", teacherId,
                    "dayOfWeek", period.getDayOfWeek(),
                    "periodNo", period.getPeriodNo())));
        }
        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(Map.of(
                        "type", "TEACHER_UNAVAILABLE",
                        "weight", 100,
                        "hard", true,
                        "enabled", true,
                        "entries", entries))))
                .when().put("/api/constraints")
                .then().statusCode(200);
    }

    @Test
    void sharedTeacherDailyLimitCountsTheOtherGradesFixedLessons() {
        String token = adminToken();
        World world = prepare(token);
        saveTeacherMaxPerDay(token, world.semesterId(), world.sharedTeacher(), 2);
        long sourceDraftId = sourceDraft(world);

        long jobId = startJob(token, world, List.of(world.gradeA()), sourceDraftId);
        Map<String, Object> job = awaitJob(token, jobId, 60);
        assertThat(job.get("status")).isEqualTo("COMPLETED");
        List<?> jobGrades = (List<?>) job.get("gradeIds");
        assertThat(jobGrades.stream().map(value -> ((Number) value).longValue()).toList())
                .containsExactly(world.gradeA());

        List<Map<String, Object>> draft = lessons(
                token, ((Number) job.get("draftId")).longValue());
        List<Map<String, Object>> classB = draft.stream()
                .filter(row -> number(row, "classId") == world.classB()).toList();
        List<Map<String, Object>> classA = draft.stream()
                .filter(row -> number(row, "classId") == world.classA()).toList();
        assertThat(classB).hasSize(2);
        assertThat(classB).allSatisfy(row -> assertThat(number(row, "dayOfWeek")).isEqualTo(1));
        assertThat(classA).hasSize(2);
        assertThat(classA).allSatisfy(row -> {
            assertThat(row.get("periodId")).isNotNull();
            // Monday already holds grade B's two lessons; the shared teacher's daily
            // total of two forbids grade A from adding a third there.
            assertThat(number(row, "dayOfWeek")).isNotEqualTo(1);
        });
    }

    @Test
    void otherGradesLessonsKeepTheirSourcePeriods() {
        String token = adminToken();
        World world = prepare(token);
        long sourceDraftId = sourceDraft(world);

        long jobId = startJob(token, world, List.of(world.gradeA()), sourceDraftId);
        Map<String, Object> job = awaitJob(token, jobId, 60);
        assertThat(job.get("status")).isEqualTo("COMPLETED");

        List<Map<String, Object>> source = lessons(token, sourceDraftId);
        List<Map<String, Object>> result = lessons(
                token, ((Number) job.get("draftId")).longValue());
        Map<Integer, Long> sourcePeriods = new LinkedHashMap<>();
        source.stream().filter(row -> number(row, "classId") == world.classB())
                .forEach(row -> sourcePeriods.put(
                        ((Number) row.get("lessonIndex")).intValue(), number(row, "periodId")));
        Map<Integer, Long> resultPeriods = new LinkedHashMap<>();
        result.stream().filter(row -> number(row, "classId") == world.classB())
                .forEach(row -> resultPeriods.put(
                        ((Number) row.get("lessonIndex")).intValue(), number(row, "periodId")));

        assertThat(resultPeriods).isEqualTo(sourcePeriods);
        assertThat(resultPeriods.values())
                .containsExactlyInAnyOrder(world.mondayFirst(), world.mondaySecond());
    }

    @Test
    void scopedStartRefusesWhenAnotherGradeHasNoScheduleToKeep() {
        String token = adminToken();
        World world = prepare(token);

        as(token).queryParam("semesterId", world.semesterId())
                .body(Map.of("maxSeconds", 5, "gradeIds", List.of(world.gradeA())))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(409)
                .body("message", containsString("其他年级还没有已排课表"));
    }

    @Test
    void scopedStartRefusesWhenSourceDraftLeavesAnotherGradeUnscheduled() {
        String token = adminToken();
        World world = prepare(token);
        long sourceDraftId = sourceDraft(world);
        DraftLesson unscheduled = draftLessons.findByDraftIdOrderByIdAsc(sourceDraftId).stream()
                .filter(row -> row.getAdminClassId() == world.classB())
                .findFirst()
                .orElseThrow();
        unscheduled.setPeriodId(null);
        draftLessons.save(unscheduled);

        as(token).queryParam("semesterId", world.semesterId())
                .body(Map.of(
                        "maxSeconds", 5,
                        "gradeIds", List.of(world.gradeA()),
                        "sourceDraftId", sourceDraftId))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(409)
                .body("message", containsString("其他年级还没有已排课表"));
    }

    @Test
    void hardTeacherConflictProducesTeacherDiagnostics() {
        String token = adminToken();
        World world = prepare(token);
        long sourceDraftId = sourceDraft(world);
        saveTeacherUnavailableEverywhere(token, world.semesterId(), world.sharedTeacher());

        long jobId = startJob(token, world, List.of(world.gradeA()), sourceDraftId);
        Map<String, Object> job = awaitJob(token, jobId, 60);
        assertThat(job.get("status")).isEqualTo("COMPLETED");
        assertThat(job.get("resultPublishable")).isEqualTo(false);

        List<Map<String, Object>> diagnostics =
                (List<Map<String, Object>>) job.get("diagnostics");
        assertThat(diagnostics).isNotEmpty();
        Map<String, Object> teacherRow = diagnostics.stream()
                .filter(row -> number(row, "teacherId") == world.sharedTeacher())
                .findFirst()
                .orElseThrow();
        assertThat((String) teacherRow.get("teacherName")).contains("共享教师");
        assertThat(((Number) teacherRow.get("hardCount")).intValue()).isPositive();
        List<?> diagnosticGrades = (List<?>) teacherRow.get("gradeIds");
        assertThat(diagnosticGrades.stream().map(value -> ((Number) value).longValue()).toList())
                .contains(world.gradeA(), world.gradeB());
    }

    @Test
    void publishingTheScopedDraftStillCoversTheWholeSchool() {
        String token = adminToken();
        World world = prepare(token);
        long sourceDraftId = sourceDraft(world);

        long jobId = startJob(token, world, List.of(world.gradeA()), sourceDraftId);
        Map<String, Object> job = awaitJob(token, jobId, 60);
        assertThat(job.get("status")).isEqualTo("COMPLETED");
        long draftId = ((Number) job.get("draftId")).longValue();

        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200);

        List<Map<String, Object>> classA = as(token)
                .queryParam("view", "baseline")
                .when().get("/api/timetable/class/" + world.classA())
                .then().statusCode(200).extract().jsonPath().getList("$");
        List<Map<String, Object>> classB = as(token)
                .queryParam("view", "baseline")
                .when().get("/api/timetable/class/" + world.classB())
                .then().statusCode(200).extract().jsonPath().getList("$");
        assertThat(classA).hasSize(2);
        assertThat(classB).hasSize(2);
        assertThat(classB).allSatisfy(row -> assertThat(
                ((Number) row.get("periodId")).longValue())
                .isIn(world.mondayFirst(), world.mondaySecond()));
    }
}
