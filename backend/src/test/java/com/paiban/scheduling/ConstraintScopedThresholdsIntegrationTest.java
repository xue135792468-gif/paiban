package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

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
 * Ticket 10/04 through the public REST surface: the five threshold types are saved
 * as entries (one global default plus per-object exceptions), a duplicate scope is
 * rejected without touching the stored configuration, and the draft verdict reads
 * the same scoped value the solver and the substitution flows do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintScopedThresholdsIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Test
    void globalDefaultPlusTeacherExceptionScopesOnlyThatTeacher() {
        String token = adminToken();
        World world = prepare(token);

        save(token, world, List.of(threshold("TEACHER_MAX_PER_DAY", true, 60, List.of(
                params(Map.of("maxPerDay", 6)),
                params(Map.of("teacherId", world.teacherA(), "maxPerDay", 3))))))
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.entries", hasSize(2))
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.entries[0].params.maxPerDay", equalTo(6))
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.entries[1].params.teacherId",
                        equalTo((int) world.teacherA()))
                // The legacy view keeps showing the first entry, i.e. the global default.
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.params.maxPerDay", equalTo(6));

        long draftId = draft(world, fourLessons(world, world.teacherA(), 1), fourLessons(world, world.teacherB(), 2));

        List<String> rows = conflicts(token, draftId, "TEACHER_MAX_PER_DAY");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).contains("张老师").contains("超过上限 3");

        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish").then().statusCode(409);
    }

    @Test
    void removingTheExceptionRestoresTheGlobalDefault() {
        String token = adminToken();
        World world = prepare(token);
        List<Map<String, Object>> entries = List.of(
                params(Map.of("maxPerDay", 6)),
                params(Map.of("teacherId", world.teacherA(), "maxPerDay", 3)));
        save(token, world, List.of(threshold("TEACHER_MAX_PER_DAY", true, 60, entries)))
                .then().statusCode(200);

        save(token, world, List.of(threshold("TEACHER_MAX_PER_DAY", true, 60,
                List.of(params(Map.of("maxPerDay", 6))))))
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.entries", hasSize(1));

        long draftId = draft(world, fourLessons(world, world.teacherA(), 1));
        assertThat(conflicts(token, draftId, "TEACHER_MAX_PER_DAY")).isEmpty();
    }

    @Test
    void exceptionsWithoutAGlobalLeaveUnlistedTeachersUnlimited() {
        String token = adminToken();
        World world = prepare(token);
        save(token, world, List.of(threshold("TEACHER_MAX_PER_DAY", true, 60, List.of(
                params(Map.of("teacherId", world.teacherA(), "maxPerDay", 3))))))
                .then().statusCode(200);

        long draftId = draft(world, fourLessons(world, world.teacherA(), 1), fourLessons(world, world.teacherB(), 2));

        List<String> rows = conflicts(token, draftId, "TEACHER_MAX_PER_DAY");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).contains("张老师");
    }

    @Test
    void aSecondEntryForTheSameScopeIsRejectedWithoutWritingAnything() {
        String token = adminToken();
        World world = prepare(token);
        save(token, world, List.of(threshold("TEACHER_MAX_PER_DAY", true, 60, List.of(
                params(Map.of("maxPerDay", 6)),
                params(Map.of("teacherId", world.teacherA(), "maxPerDay", 3))))))
                .then().statusCode(200);

        // Two global defaults, then two thresholds for the same teacher: both rejected.
        save(token, world, List.of(threshold("TEACHER_MAX_PER_DAY", true, 60, List.of(
                params(Map.of("maxPerDay", 6)),
                params(Map.of("maxPerDay", 8))))))
                .then().statusCode(400)
                .body("message", org.hamcrest.Matchers.containsString("同一作用域"));

        save(token, world, List.of(threshold("TEACHER_MAX_PER_DAY", true, 60, List.of(
                params(Map.of("teacherId", world.teacherA(), "maxPerDay", 3)),
                params(Map.of("teacherId", world.teacherA(), "maxPerDay", 5))))))
                .then().statusCode(400)
                .body("message", org.hamcrest.Matchers.containsString("同一作用域"));

        as(token).queryParam("semesterId", world.semesterId())
                .when().get("/api/constraints").then().statusCode(200)
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.entries", hasSize(2))
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.entries[1].params.maxPerDay",
                        equalTo(3));
    }

    @Test
    void objectAndRangeValidationStillApplies() {
        String token = adminToken();
        World world = prepare(token);

        // Out of the catalogue bounds (1-12 for a teacher day limit).
        save(token, world, List.of(threshold("TEACHER_MAX_PER_DAY", true, 60, List.of(
                params(Map.of("maxPerDay", 13))))))
                .then().statusCode(400);

        // A class of another semester is not a valid scope here.
        long otherSemester = TestData.semester(token);
        long otherGrade = TestData.grade(token, otherSemester, "高二" + TestData.suffix());
        long foreignClass = TestData.adminClass(token, otherSemester, otherGrade, "高二1班");
        save(token, world, List.of(threshold("CLASS_MAX_PER_DAY", true, 50, List.of(
                params(Map.of("classId", foreignClass, "maxPerDay", 4))))))
                .then().statusCode(400)
                .body("message", org.hamcrest.Matchers.containsString("班级"));

        // Unknown course ids are refused as well.
        save(token, world, List.of(threshold("COURSE_MAX_PER_DAY", true, 50, List.of(
                params(Map.of("courseId", 999_999, "maxPerDay", 1))))))
                .then().statusCode(400);
    }

    @Test
    void theOtherFourThresholdsRoundTripWithTheirOwnScopeField() {
        String token = adminToken();
        World world = prepare(token);
        save(token, world, List.of(
                threshold("CLASS_MAX_PER_DAY", true, 50, List.of(
                        params(Map.of("maxPerDay", 8)),
                        params(Map.of("classId", world.classId(), "maxPerDay", 5)))),
                threshold("COURSE_MAX_PER_DAY", true, 50, List.of(
                        params(Map.of("maxPerDay", 2)),
                        params(Map.of("courseId", world.courseId(), "maxPerDay", 1)))),
                threshold("EVENING_MAX", true, 40, List.of(
                        params(Map.of("maxPerDay", 2)),
                        params(Map.of("classId", world.classId(), "maxPerDay", 0)))),
                threshold("TEACHER_MAX_CONSECUTIVE", true, 45, List.of(
                        params(Map.of("maxConsecutive", 3)),
                        params(Map.of("teacherId", world.teacherA(), "maxConsecutive", 2))))))
                .then().statusCode(200)
                .body("find { it.type == 'CLASS_MAX_PER_DAY' }.entries[1].params.classId",
                        equalTo((int) world.classId()))
                .body("find { it.type == 'COURSE_MAX_PER_DAY' }.entries[1].params.courseId",
                        equalTo((int) world.courseId()))
                .body("find { it.type == 'EVENING_MAX' }.entries[1].params.maxPerDay", equalTo(0))
                .body("find { it.type == 'TEACHER_MAX_CONSECUTIVE' }.entries[1].params.maxConsecutive",
                        equalTo(2));

        // The course threshold counts per class and course: two maths lessons for the same
        // class on one day break its exception of 1, while the global 2 still holds.
        long draftId = draft(world, sameDay(world, world.courseId(), 2));
        assertThat(conflicts(token, draftId, "COURSE_MAX_PER_DAY")).hasSize(1);
    }

    @Test
    void aDisabledTypeIgnoresEveryEntry() {
        String token = adminToken();
        World world = prepare(token);
        save(token, world, List.of(threshold("TEACHER_MAX_PER_DAY", false, 60, List.of(
                params(Map.of("maxPerDay", 6)),
                params(Map.of("teacherId", world.teacherA(), "maxPerDay", 3))))))
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.entries", hasSize(2));

        long draftId = draft(world, fourLessons(world, world.teacherA(), 1));
        assertThat(conflicts(token, draftId, "TEACHER_MAX_PER_DAY")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private record World(long semesterId, long classId, long courseId, long roomId,
                         long teacherA, long teacherB, Map<Integer, Long> periods) {}

    private World prepare(String token) {
        String suffix = TestData.suffix();
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 2))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        long classId = TestData.adminClass(token, semesterId, gradeId, "高一1班" + suffix);
        long teacherA = TestData.teacher(token, "张老师" + suffix);
        long teacherB = TestData.teacher(token, "李老师" + suffix);
        long courseId = TestData.course(token, "数学" + suffix);
        long roomId = TestData.room(token, "教室" + suffix);
        return new World(semesterId, classId, courseId, roomId, teacherA, teacherB,
                periodsOf(token, semesterId));
    }

    private Map<Integer, Long> periodsOf(String token, long semesterId) {
        List<Map<String, Object>> rows = as(token)
                .when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<Integer, Long> bySlot = new LinkedHashMap<>();
        rows.forEach(row -> bySlot.put(
                ((Number) row.get("dayOfWeek")).intValue() * 100 + ((Number) row.get("periodNo")).intValue(),
                ((Number) row.get("id")).longValue()));
        return bySlot;
    }

    /** Four lessons for one teacher on one weekday, one per period. */
    private List<Lesson> fourLessons(World world, long teacherId, int dayOfWeek) {
        List<Lesson> lessons = new ArrayList<>();
        for (int periodNo = 1; periodNo <= 4; periodNo++) {
            lessons.add(new Lesson(dayOfWeek, periodNo, world.courseId(), teacherId));
        }
        return lessons;
    }

    /** Two lessons of the same course for the same class on one weekday. */
    private List<Lesson> sameDay(World world, long courseId, int count) {
        List<Lesson> lessons = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            lessons.add(new Lesson(1, index + 1, courseId, world.teacherA()));
        }
        return lessons;
    }

    private record Lesson(int dayOfWeek, int periodNo, long courseId, long teacherId) {}

    @SafeVarargs
    private long draft(World world, List<Lesson>... groups) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(world.semesterId());
        draft.setName("阈值草稿" + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = drafts.save(draft).getId();
        List<Lesson> all = new ArrayList<>();
        for (List<Lesson> group : groups) {
            all.addAll(group);
        }
        int index = 1;
        for (Lesson lesson : all) {
            place(draftId, world, lesson, index++);
        }
        return draftId;
    }

    private void place(long draftId, World world, Lesson lesson, int index) {
        Long periodId = world.periods().get(lesson.dayOfWeek() * 100 + lesson.periodNo());
        DraftLesson row = new DraftLesson();
        row.setDraftId(draftId);
        row.setAssignmentId(0L);
        row.setLessonIndex(index);
        row.setCourseId(lesson.courseId());
        row.setTeacherId(lesson.teacherId());
        row.setAdminClassId(world.classId());
        row.setStudentCount(45);
        row.setPeriodId(periodId);
        row.setRoomId(world.roomId());
        draftLessons.save(row);
    }

    private List<String> conflicts(String token, long draftId, String type) {
        Response validation = as(token)
                .when().get("/api/timetable-drafts/" + draftId + "/validation")
                .then().statusCode(200).extract().response();
        List<Map<String, Object>> rows = validation.jsonPath().getList("conflicts");
        List<String> messages = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (type.equals(row.get("type"))) {
                messages.add(String.valueOf(row.get("message")));
            }
        }
        return messages;
    }

    private Response save(String token, World world, List<Map<String, Object>> configs) {
        return as(token).queryParam("semesterId", world.semesterId())
                .body(Map.of("configs", configs))
                .when().put("/api/constraints");
    }

    private Map<String, Object> threshold(
            String type, boolean enabled, int weight, List<Map<String, Object>> entries) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", type);
        config.put("weight", weight);
        config.put("hard", true);
        config.put("enabled", enabled);
        config.put("entries", entries);
        return config;
    }

    private Map<String, Object> params(Map<String, Object> params) {
        return Map.of("params", params);
    }
}
