package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
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
 * Swap candidate and move target queries at the HTTP seam. Drafts are built
 * directly through repositories because only the solver creates them through the
 * API, and the solver's placement is not deterministic enough for these assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SwapCandidateIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private PeriodRepository periods;

    @Autowired
    private AdminClassRepository adminClasses;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private RoomRepository rooms;

    private record World(long semesterId, long gradeId, long classA, long classB, long classC,
                         long draftId, List<Long> periodIds, Map<String, Long> courses,
                         Map<String, Long> teachers, Map<String, Long> rooms) {

        long period(int index) {
            return periodIds.get(index);
        }

        long course(String name) {
            return courses.get(name);
        }

        long teacher(String name) {
            return teachers.get(name);
        }

        long room(String name) {
            return rooms.get(name);
        }
    }

    private World world(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 2, "afternoonCount", 2, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        List<Long> periodIds = new ArrayList<>();
        List<Map<String, Object>> periodRows = as(token)
                .when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        periodRows.forEach(row -> periodIds.add(((Number) row.get("id")).longValue()));

        long gradeId = TestData.grade(token, semesterId, "高一");
        long classA = TestData.adminClass(token, semesterId, gradeId, "高一1班");
        long classB = TestData.adminClass(token, semesterId, gradeId, "高一2班");
        long classC = TestData.adminClass(token, semesterId, gradeId, "高一3班");

        Map<String, Long> courses = new LinkedHashMap<>();
        courses.put("语文", course(token, "语文", "语文"));
        courses.put("数学", course(token, "数学", "数学"));
        courses.put("语文阅读", course(token, "语文阅读", "语文"));

        Map<String, Long> teachers = new LinkedHashMap<>();
        teachers.put("张老师", teacher(token, "张老师", "语文"));
        teachers.put("李老师", teacher(token, "李老师", "数学"));
        teachers.put("王老师", teacher(token, "王老师", "语文"));
        teachers.put("陈老师", teacher(token, "陈老师", "数学"));
        teachers.put("赵老师", teacher(token, "赵老师", "英语"));

        Map<String, Long> rooms = new LinkedHashMap<>();
        rooms.put("教室1", TestData.room(token, "教室1"));
        rooms.put("教室2", TestData.room(token, "教室2"));
        rooms.put("教室3", TestData.room(token, "教室3"));

        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("换课查询测试");
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = drafts.save(draft).getId();
        return new World(semesterId, gradeId, classA, classB, classC, draftId, periodIds, courses, teachers, rooms);
    }

    private long course(String token, String name, String subject) {
        return as(token).body(Map.of("name", name, "subject", subject, "allowConsecutive", false))
                .when().post("/api/courses")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    private long teacher(String token, String name, String subject) {
        return as(token).body(Map.of("name", name, "employeeNo", "T" + TestData.suffix(), "subject", subject))
                .when().post("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    private long place(World world, long classId, String course, String teacher, int periodIndex, String room) {
        return place(world, classId, world.course(course), world.teacher(teacher), periodIndex, room);
    }

    private long place(
            World world, long classId, long courseId, long teacherId, int periodIndex, String room) {
        DraftLesson lesson = new DraftLesson();
        lesson.setDraftId(world.draftId());
        lesson.setAssignmentId(0L);
        lesson.setLessonIndex(1);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(world.period(periodIndex));
        lesson.setRoomId(world.room(room));
        return draftLessons.save(lesson).getId();
    }

    private Response candidates(String token, World world, long lessonId) {
        return candidates(token, world, lessonId, false);
    }

    private Response candidates(String token, World world, long lessonId, boolean includeUnavailable) {
        return as(token).queryParam("includeUnavailable", includeUnavailable).when()
                .get("/api/timetable-drafts/" + world.draftId() + "/lessons/" + lessonId + "/swap-candidates")
                .then().statusCode(200).extract().response();
    }

    @Test
    void intraClassCandidatesListSameClassLessonsWithTheirDetails() {
        String token = adminToken();
        World world = world(token);
        long target = place(world, world.classA(), "语文", "张老师", 0, "教室1");
        long math = place(world, world.classA(), "数学", "李老师", 1, "教室2");
        place(world, world.classB(), "数学", "陈老师", 2, "教室1");

        Response response = candidates(token, world, target);

        List<Map<String, Object>> intra = response.jsonPath().getList("intraClass");
        assertThat(intra).hasSize(1);
        Map<String, Object> candidate = intra.get(0);
        assertThat(((Number) candidate.get("lessonId")).longValue()).isEqualTo(math);
        assertThat(candidate.get("teacherName")).isEqualTo("李老师");
        assertThat(candidate.get("className")).isEqualTo("高一1班");
        assertThat(candidate.get("gradeName")).isEqualTo("高一");
        assertThat(candidate.get("courseName")).isEqualTo("数学");
        assertThat(((Number) candidate.get("periodId")).longValue()).isEqualTo(world.period(1));
        assertThat(candidate.get("periodLabel")).isEqualTo("周1第2节");
        assertThat(candidate.get("roomName")).isEqualTo("教室2");
        assertThat(candidate.get("available")).isEqualTo(true);
        assertThat(candidate.get("roomSwapRequired")).isEqualTo(false);
        assertThat((List<?>) candidate.get("conflicts")).isEmpty();
        assertThat(response.jsonPath().getList("crossClass")).isEmpty();
    }

    @Test
    void intraClassCandidateRequiresRoomExchangeWhenKeepingRoomsConflicts() {
        String token = adminToken();
        World world = world(token);
        long target = place(world, world.classA(), "语文", "张老师", 0, "教室1");
        long candidate = place(world, world.classA(), "数学", "李老师", 1, "教室2");
        // Occupies 教室1 at the candidate's period, so keeping rooms blocks the swap.
        place(world, world.classB(), "语文阅读", "王老师", 1, "教室1");

        Response response = candidates(token, world, target);

        List<Map<String, Object>> intra = response.jsonPath().getList("intraClass");
        assertThat(intra).hasSize(1);
        Map<String, Object> row = intra.get(0);
        assertThat(((Number) row.get("lessonId")).longValue()).isEqualTo(candidate);
        assertThat(row.get("available")).isEqualTo(true);
        assertThat(row.get("roomSwapRequired")).isEqualTo(true);
        assertThat((List<?>) row.get("conflicts")).isEmpty();

        as(token).body(Map.of(
                        "lessonId", target,
                        "targetLessonId", candidate,
                        "swapRooms", true))
                .when().post("/api/timetable-drafts/" + world.draftId() + "/swap")
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("type", equalTo("INTRA_CLASS"));

        assertThat(draftLessons.findById(target).orElseThrow().getPeriodId())
                .isEqualTo(world.period(1));
        assertThat(draftLessons.findById(target).orElseThrow().getRoomId())
                .isEqualTo(world.room("教室2"));
        assertThat(draftLessons.findById(candidate).orElseThrow().getPeriodId())
                .isEqualTo(world.period(0));
        assertThat(draftLessons.findById(candidate).orElseThrow().getRoomId())
                .isEqualTo(world.room("教室1"));
    }

    @Test
    void crossClassCandidatesSkipTheSameTeacher() {
        String token = adminToken();
        World world = world(token);
        long target = place(world, world.classA(), "语文", "张老师", 0, "教室1");
        place(world, world.classB(), "语文", "张老师", 2, "教室2");

        Response response = candidates(token, world, target, true);

        assertThat(response.jsonPath().getList("crossClass")).isEmpty();
    }

    @Test
    void crossClassCandidateSubjectComparisonIgnoresCaseAndSpaces() {
        String token = adminToken();
        World world = world(token);
        long physicsUpper = course(token, "物理A", "Physics");
        long physicsLower = course(token, "物理B", "physics");
        long teacherUpper = teacher(token, "周老师", "PHYSICS");
        long teacherLower = teacher(token, "吴老师", " physics ");
        long target = place(world, world.classA(), physicsUpper, teacherUpper, 0, "教室1");
        long candidate = place(world, world.classB(), physicsLower, teacherLower, 2, "教室2");

        Response response = candidates(token, world, target);

        List<Map<String, Object>> cross = response.jsonPath().getList("crossClass");
        assertThat(cross).hasSize(1);
        Map<String, Object> row = cross.get(0);
        assertThat(((Number) row.get("lessonId")).longValue()).isEqualTo(candidate);
        assertThat(row.get("available")).isEqualTo(true);
        assertThat((List<?>) row.get("conflicts")).isEmpty();
        assertThat((List<?>) row.get("warnings")).isEmpty();
    }

    @Test
    void crossClassCandidatesRequireTheSameSubjectAndADifferentClass() {
        String token = adminToken();
        World world = world(token);
        long target = place(world, world.classA(), "语文", "张老师", 0, "教室1");
        place(world, world.classA(), "数学", "李老师", 1, "教室2");
        long reading = place(world, world.classB(), "语文阅读", "王老师", 2, "教室1");
        place(world, world.classC(), "数学", "陈老师", 3, "教室3");

        Response response = candidates(token, world, target);

        List<Map<String, Object>> cross = response.jsonPath().getList("crossClass");
        assertThat(cross).hasSize(1);
        Map<String, Object> candidate = cross.get(0);
        assertThat(((Number) candidate.get("lessonId")).longValue()).isEqualTo(reading);
        assertThat(candidate.get("teacherName")).isEqualTo("王老师");
        assertThat(candidate.get("className")).isEqualTo("高一2班");
        assertThat(candidate.get("gradeName")).isEqualTo("高一");
        assertThat(candidate.get("courseName")).isEqualTo("语文阅读");
        assertThat(candidate.get("subject")).isEqualTo("语文");
        assertThat(((Number) candidate.get("periodId")).longValue()).isEqualTo(world.period(2));
        assertThat(candidate.get("roomName")).isEqualTo("教室1");
        assertThat(candidate.get("available")).isEqualTo(true);
        assertThat((List<?>) candidate.get("conflicts")).isEmpty();
        assertThat((List<?>) candidate.get("warnings")).isEmpty();
        assertThat(response.jsonPath().getList("intraClass")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailableCandidatesAppearOnlyWhenRequestedWithReasons() {
        String token = adminToken();
        World world = world(token);
        long target = place(world, world.classA(), "语文", "张老师", 0, "教室1");
        long blocked = place(world, world.classA(), "数学", "李老师", 1, "教室2");
        place(world, world.classC(), "数学", "李老师", 0, "教室3");
        long mismatched = place(world, world.classB(), "语文", "赵老师", 2, "教室1");

        Response defaults = candidates(token, world, target);
        assertThat(defaults.jsonPath().getList("intraClass")).isEmpty();
        assertThat(defaults.jsonPath().getList("crossClass")).isEmpty();

        Response all = candidates(token, world, target, true);
        List<Map<String, Object>> intra = all.jsonPath().getList("intraClass");
        assertThat(intra).hasSize(1);
        assertThat(((Number) intra.get(0).get("lessonId")).longValue()).isEqualTo(blocked);
        assertThat(intra.get(0).get("available")).isEqualTo(false);
        List<Map<String, Object>> intraConflicts = (List<Map<String, Object>>) intra.get(0).get("conflicts");
        assertThat(intraConflicts).anySatisfy(conflict -> {
            assertThat(conflict.get("type")).isEqualTo("TEACHER_CONFLICT");
            assertThat(((Number) conflict.get("lessonId")).longValue()).isEqualTo(blocked);
        });

        List<Map<String, Object>> cross = all.jsonPath().getList("crossClass");
        assertThat(cross).hasSize(1);
        assertThat(((Number) cross.get(0).get("lessonId")).longValue()).isEqualTo(mismatched);
        assertThat(cross.get(0).get("available")).isEqualTo(false);
        List<Map<String, Object>> crossConflicts = (List<Map<String, Object>>) cross.get(0).get("conflicts");
        assertThat(crossConflicts).anySatisfy(conflict -> {
            assertThat(conflict.get("type")).isEqualTo("TEACHER_SUBJECT_MISMATCH");
            assertThat((String) conflict.get("message")).contains("赵老师").contains("英语");
        });
    }

    private List<Long> lessonIds(Response response, String group) {
        List<Map<String, Object>> rows = response.jsonPath().getList(group);
        return rows.stream().map(row -> ((Number) row.get("lessonId")).longValue()).toList();
    }

    @Test
    void candidatesCanBeFilteredByTypeCourseGradeClassAndTeacher() {
        String token = adminToken();
        World world = world(token);
        long target = place(world, world.classA(), "语文", "张老师", 0, "教室1");
        long math = place(world, world.classA(), "数学", "李老师", 1, "教室2");
        long reading = place(world, world.classA(), "语文阅读", "王老师", 2, "教室1");
        long otherClassReading = place(world, world.classB(), "语文阅读", "王老师", 3, "教室2");
        long otherClassChinese = place(world, world.classC(), "语文", "王老师", 4, "教室3");
        long otherGrade = TestData.grade(token, world.semesterId(), "高二");
        long otherGradeClass = TestData.adminClass(token, world.semesterId(), otherGrade, "高二1班");
        long otherGradeChinese = place(world, otherGradeClass, "语文", "王老师", 5, "教室1");

        String url = "/api/timetable-drafts/" + world.draftId() + "/lessons/" + target + "/swap-candidates";
        Response intraOnly = as(token).queryParam("type", "INTRA_CLASS").when().get(url)
                .then().statusCode(200).extract().response();
        assertThat(lessonIds(intraOnly, "intraClass")).containsExactly(math, reading);
        assertThat(intraOnly.jsonPath().getList("crossClass")).isEmpty();

        Response crossOnly = as(token).queryParam("type", "CROSS_CLASS").when().get(url)
                .then().statusCode(200).extract().response();
        assertThat(crossOnly.jsonPath().getList("intraClass")).isEmpty();
        assertThat(lessonIds(crossOnly, "crossClass"))
                .containsExactly(otherClassReading, otherClassChinese, otherGradeChinese);

        Response byCourse = as(token).queryParam("courseId", world.course("语文阅读")).when().get(url)
                .then().statusCode(200).extract().response();
        assertThat(lessonIds(byCourse, "intraClass")).containsExactly(reading);
        assertThat(lessonIds(byCourse, "crossClass")).containsExactly(otherClassReading);

        Response byClass = as(token).queryParam("classId", world.classB()).when().get(url)
                .then().statusCode(200).extract().response();
        assertThat(byClass.jsonPath().getList("intraClass")).isEmpty();
        assertThat(lessonIds(byClass, "crossClass")).containsExactly(otherClassReading);

        Response byTeacher = as(token).queryParam("teacherId", world.teacher("王老师")).when().get(url)
                .then().statusCode(200).extract().response();
        assertThat(lessonIds(byTeacher, "intraClass")).containsExactly(reading);
        assertThat(lessonIds(byTeacher, "crossClass"))
                .containsExactly(otherClassReading, otherClassChinese, otherGradeChinese);

        Response byGrade = as(token).queryParam("gradeId", otherGrade).when().get(url)
                .then().statusCode(200).extract().response();
        assertThat(byGrade.jsonPath().getList("intraClass")).isEmpty();
        assertThat(lessonIds(byGrade, "crossClass")).containsExactly(otherGradeChinese);
    }

    @Test
    @SuppressWarnings("unchecked")
    void moveTargetsValidateEveryPeriodIndependentlyKeepingTheRoom() {
        String token = adminToken();
        World world = world(token);
        long target = place(world, world.classA(), "语文", "张老师", 0, "教室1");
        place(world, world.classA(), "数学", "李老师", 1, "教室2");
        place(world, world.classB(), "数学", "陈老师", 2, "教室1");

        Response response = as(token).when()
                .get("/api/timetable-drafts/" + world.draftId() + "/lessons/" + target + "/move-targets")
                .then().statusCode(200).extract().response();

        assertThat(((Number) response.jsonPath().get("roomId")).longValue()).isEqualTo(world.room("教室1"));
        List<Map<String, Object>> targets = response.jsonPath().getList("targets");
        assertThat(targets).hasSize(world.periodIds().size());
        assertThat(targets.stream().map(row -> ((Number) row.get("periodId")).longValue()).toList())
                .containsExactlyElementsOf(world.periodIds());

        Map<Long, Map<String, Object>> byPeriod = new LinkedHashMap<>();
        targets.forEach(row -> byPeriod.put(((Number) row.get("periodId")).longValue(), row));

        assertThat(byPeriod.get(world.period(0)).get("available")).isEqualTo(true);

        Map<String, Object> occupied = byPeriod.get(world.period(1));
        assertThat(occupied.get("available")).isEqualTo(false);
        assertThat(((Number) occupied.get("hardConflicts")).intValue()).isEqualTo(1);
        assertThat((List<Map<String, Object>>) occupied.get("conflicts")).anySatisfy(conflict -> {
            assertThat(conflict.get("type")).isEqualTo("CLASS_CONFLICT");
            assertThat(((Number) conflict.get("lessonId")).longValue()).isEqualTo(target);
        });

        Map<String, Object> roomTaken = byPeriod.get(world.period(2));
        assertThat(roomTaken.get("available")).isEqualTo(false);
        assertThat((List<Map<String, Object>>) roomTaken.get("conflicts")).anySatisfy(conflict ->
                assertThat(conflict.get("type")).isEqualTo("ROOM_CONFLICT"));

        Map<String, Object> free = byPeriod.get(world.period(3));
        assertThat(free.get("available")).isEqualTo(true);
        assertThat(((Number) free.get("hardConflicts")).intValue()).isZero();
    }

    @Test
    void fortyByFortyDraftReturnsCandidatesWithinOneAndAHalfSeconds() {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        long gradeId = TestData.grade(token, semesterId, "高一");
        List<Period> periodList = periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId);
        assertThat(periodList).hasSize(40);

        List<AdminClass> classRows = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            AdminClass adminClass = new AdminClass();
            adminClass.setSemesterId(semesterId);
            adminClass.setGradeId(gradeId);
            adminClass.setName("高一" + (index + 1) + "班");
            adminClass.setStudentCount(45);
            classRows.add(adminClass);
        }
        classRows = adminClasses.saveAll(classRows);

        String[] subjects = {"语文", "数学", "英语", "物理", "化学", "生物", "政治", "历史", "地理", "体育"};
        List<Course> courseRows = new ArrayList<>();
        for (String subject : subjects) {
            Course course = new Course();
            course.setName(subject);
            course.setSubject(subject);
            courseRows.add(course);
        }
        courseRows = courses.saveAll(courseRows);

        List<Teacher> teacherRows = new ArrayList<>();
        for (int classIndex = 0; classIndex < 40; classIndex++) {
            for (String subject : subjects) {
                for (int repeat = 0; repeat < 4; repeat++) {
                    Teacher teacher = new Teacher();
                    teacher.setName(subject + "老师" + classIndex + "-" + repeat);
                    teacher.setEmployeeNo("T" + classIndex + "-" + subject + "-" + repeat);
                    teacher.setSubject(subject);
                    teacherRows.add(teacher);
                }
            }
        }
        teacherRows = teachers.saveAll(teacherRows);

        List<Room> roomRows = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            Room room = new Room();
            room.setName("教室" + index);
            room.setCapacity(50);
            room.setType("NORMAL");
            roomRows.add(room);
        }
        roomRows = rooms.saveAll(roomRows);

        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("40x40");
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = drafts.save(draft).getId();

        List<DraftLesson> rows = new ArrayList<>();
        for (int classIndex = 0; classIndex < 40; classIndex++) {
            for (int subjectIndex = 0; subjectIndex < subjects.length; subjectIndex++) {
                for (int repeat = 0; repeat < 4; repeat++) {
                    DraftLesson lesson = new DraftLesson();
                    lesson.setDraftId(draftId);
                    lesson.setAssignmentId(0L);
                    lesson.setLessonIndex(repeat + 1);
                    lesson.setCourseId(courseRows.get(subjectIndex).getId());
                    lesson.setTeacherId(teacherRows.get(
                            classIndex * subjects.length * 4 + subjectIndex * 4 + repeat).getId());
                    lesson.setAdminClassId(classRows.get(classIndex).getId());
                    lesson.setStudentCount(45);
                    lesson.setPeriodId(periodList.get(subjectIndex * 4 + repeat).getId());
                    lesson.setRoomId(roomRows.get(classIndex).getId());
                    rows.add(lesson);
                }
            }
        }
        List<DraftLesson> saved = draftLessons.saveAll(rows);
        assertThat(saved).hasSize(1600);
        long target = saved.get(0).getId();

        // Warm up JIT, connection and the persistence context before timing the request.
        as(token).when()
                .get("/api/timetable-drafts/" + draftId + "/lessons/" + target + "/swap-candidates")
                .then().statusCode(200);

        long startedAt = System.nanoTime();
        Response response = as(token).when()
                .get("/api/timetable-drafts/" + draftId + "/lessons/" + target + "/swap-candidates")
                .then().statusCode(200).extract().response();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(response.jsonPath().getList("intraClass")).hasSize(39);
        assertThat(response.jsonPath().getList("crossClass")).hasSize(156);
        // The strict SLA lives in the dedicated performance benchmark; this integration
        // test shares a loaded full suite, so allow a 1.5s gross-regression bound.
        assertThat(elapsedMs).isLessThan(1500L);
    }
}
