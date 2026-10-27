package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.foundation.domain.PeriodSection;
import com.paiban.scheduling.constraint.ConstraintChecker;
import com.paiban.scheduling.constraint.ConstraintContext;
import com.paiban.scheduling.constraint.ConstraintContext.ClassView;
import com.paiban.scheduling.constraint.ConstraintContext.CourseView;
import com.paiban.scheduling.constraint.ConstraintContext.PeriodView;
import com.paiban.scheduling.constraint.ConstraintContext.RoomView;
import com.paiban.scheduling.constraint.ConstraintContext.TeacherView;
import com.paiban.scheduling.constraint.LessonView;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.ConstraintService;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 10/06, part A: the whole 24-type catalogue proven to <em>take effect</em>.
 *
 * <p>For every type this test saves a representative configuration through the public
 * REST API, builds a minimal draft that breaks exactly that rule, and asserts the
 * violation code on {@code GET /api/timetable-drafts/{id}/validation}. A hard rule must
 * also make publishing return 409, so "listed in the editor" is never mistaken for
 * "enforced"; a soft rule must instead raise the independent penalty and must not
 * appear as a conflict. The six single-switch types are additionally checked with the
 * switch off, because a switch that only ever reports a violation proves nothing.
 *
 * <p>This exists because on 2026-10-26 the day-level hard rules silently did nothing in
 * the draft and publish paths while 689 tests stayed green — every per-type unit test
 * built its own context, so none of them exercised the wiring from saved configuration
 * to the verdict a user sees.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintCatalogueAcceptanceTest extends ApiIntegrationTest {

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private ConstraintService constraintService;

    // ------------------------------------------------------------------ the table

    /** Which fixture object a lesson names. Resolved against {@link World} when placed. */
    enum Course { CHINESE, MATH, SPORT }

    enum Teacher { MATH, OTHER, HEAD }

    enum Class { A, B }

    enum Room { NORMAL, LAB, SMALL }

    record Lesson(int day, int periodNo, Course course, Teacher teacher, Class adminClass, Room room) {}

    /**
     * One catalogue row: the configuration a school would save, the smallest timetable
     * that breaks it, and the assertion that must hold.
     *
     * @param soft true for the fifteen rules that calculate a penalty when not hard
     */
    record Case(String label, String type, boolean switchType, boolean hard, int weight,
                List<Map<String, Object>> entries, List<Lesson> lessons, String code,
                boolean soft) {}

    static Stream<Arguments> catalogue() {
        return Stream.of(
                Arguments.of(new Case("教师不冲突", "TEACHER_CONFLICT", true, true, 100, null,
                        List.of(lesson(1, 1, Course.MATH, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 1, Course.CHINESE, Teacher.MATH, Class.B, Room.LAB)),
                        "TEACHER_CONFLICT", false)),
                Arguments.of(new Case("班级不冲突", "CLASS_CONFLICT", true, true, 100, null,
                        List.of(lesson(1, 1, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 1, Course.MATH, Teacher.OTHER, Class.A, Room.LAB)),
                        "CLASS_CONFLICT", false)),
                Arguments.of(new Case("教室不冲突", "ROOM_CONFLICT", true, true, 100, null,
                        List.of(lesson(1, 1, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 1, Course.MATH, Teacher.OTHER, Class.B, Room.NORMAL)),
                        "ROOM_CONFLICT", false)),
                Arguments.of(new Case("教室容量", "ROOM_CAPACITY_FIT", true, true, 100, null,
                        List.of(lesson(1, 1, Course.CHINESE, Teacher.MATH, Class.A, Room.SMALL)),
                        "ROOM_CAPACITY", false)),
                Arguments.of(new Case("班级空堂", "CLASS_GAP_MINIMIZED", true, true, 30, null,
                        List.of(lesson(1, 1, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 3, Course.MATH, Teacher.OTHER, Class.A, Room.NORMAL)),
                        "CLASS_GAP_MINIMIZED", true)),
                Arguments.of(new Case("班主任第一节", "HEAD_TEACHER_FIRST_PERIOD", true, true, 20, null,
                        List.of(lesson(1, 2, Course.CHINESE, Teacher.HEAD, Class.A, Room.NORMAL)),
                        "HEAD_TEACHER_FIRST_PERIOD", true)),
                Arguments.of(new Case("教师禁排", "TEACHER_UNAVAILABLE", false, true, 100,
                        List.of(params("teacherId", Teacher.MATH, "dayOfWeek", 1, "periodNo", 2)),
                        List.of(lesson(1, 2, Course.MATH, Teacher.MATH, Class.A, Room.NORMAL)),
                        "TEACHER_UNAVAILABLE", false)),
                Arguments.of(new Case("教研禁排", "TEACHER_MEETING_BLOCKED", false, true, 100,
                        List.of(params("subject", "数学", "dayOfWeek", 1, "periodNo", 2)),
                        List.of(lesson(1, 2, Course.MATH, Teacher.MATH, Class.A, Room.NORMAL)),
                        "TEACHER_MEETING", false)),
                Arguments.of(new Case("课程教室类型", "COURSE_REQUIRED_ROOM_TYPE", false, true, 100,
                        List.of(params("courseId", Course.CHINESE, "roomType", "LAB")),
                        List.of(lesson(1, 1, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL)),
                        "ROOM_TYPE", false)),
                Arguments.of(new Case("课程不排晚自习", "COURSE_NOT_IN_EVENING", false, true, 100,
                        List.of(params("courseId", Course.CHINESE)),
                        List.of(lesson(1, 9, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL)),
                        "COURSE_NOT_IN_EVENING", false)),
                Arguments.of(new Case("课程禁排时段", "COURSE_UNAVAILABLE", false, true, 100,
                        List.of(params("courseId", Course.CHINESE, "dayOfWeek", 1, "periodNo", 2)),
                        List.of(lesson(1, 2, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL)),
                        "COURSE_UNAVAILABLE", false)),
                Arguments.of(new Case("同课程一天一节", "SAME_COURSE_ONCE_PER_DAY", false, true, 100,
                        List.of(params("courseId", Course.CHINESE)),
                        List.of(lesson(1, 1, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 2, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL)),
                        "SAME_COURSE_PER_DAY", false)),
                Arguments.of(new Case("体育不在下午第一节", "SPORT_NOT_FIRST_AFTERNOON", false, true, 70,
                        List.of(params("courseId", Course.SPORT)),
                        List.of(lesson(1, 5, Course.SPORT, Teacher.OTHER, Class.A, Room.NORMAL)),
                        "SPORT_NOT_FIRST_AFTERNOON", true)),
                Arguments.of(new Case("连堂课", "CONSECUTIVE_LESSONS", false, true, 60,
                        List.of(params("courseId", Course.CHINESE, "minConsecutive", 2)),
                        List.of(lesson(1, 1, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 3, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL)),
                        "CONSECUTIVE_LESSONS", true)),
                Arguments.of(new Case("课程分散", "COURSE_SPREAD", false, true, 50,
                        List.of(params()),
                        List.of(lesson(1, 1, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 2, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL)),
                        "COURSE_SPREAD", true)),
                Arguments.of(new Case("主科上午", "MAIN_SUBJECT_MORNING", false, true, 55,
                        List.of(params("courseId", Course.CHINESE, "beforePeriodNo", 4)),
                        List.of(lesson(1, 6, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL)),
                        "MAIN_SUBJECT_MORNING", true)),
                Arguments.of(new Case("教师偏好时段", "TEACHER_PREFERRED_SLOT", false, true, 40,
                        List.of(params("teacherId", Teacher.MATH, "dayOfWeek", 1, "periodNo", 1)),
                        List.of(lesson(1, 2, Course.MATH, Teacher.MATH, Class.A, Room.NORMAL)),
                        "TEACHER_PREFERRED_SLOT", true)),
                Arguments.of(new Case("教师不排第一节", "TEACHER_NO_FIRST_PERIOD", false, true, 35,
                        List.of(params("teacherId", Teacher.MATH)),
                        List.of(lesson(1, 1, Course.MATH, Teacher.MATH, Class.A, Room.NORMAL)),
                        "TEACHER_NO_FIRST_PERIOD", true)),
                Arguments.of(new Case("教师无课日", "TEACHER_DAY_OFF", false, true, 30,
                        List.of(params("teacherId", Teacher.MATH, "dayOfWeek", 1)),
                        List.of(lesson(1, 3, Course.MATH, Teacher.MATH, Class.A, Room.NORMAL)),
                        "TEACHER_DAY_OFF", true)),
                Arguments.of(new Case("体育不在最后一节", "PE_LAST_PERIOD_AVOID", false, true, 35,
                        List.of(params("courseId", Course.SPORT)),
                        List.of(lesson(1, 10, Course.SPORT, Teacher.OTHER, Class.A, Room.NORMAL)),
                        "PE_LAST_PERIOD_AVOID", true)),
                Arguments.of(new Case("教师单日上限", "TEACHER_MAX_PER_DAY", false, true, 60,
                        List.of(params("maxPerDay", 2), params("teacherId", Teacher.MATH, "maxPerDay", 1)),
                        List.of(lesson(1, 1, Course.MATH, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 2, Course.MATH, Teacher.MATH, Class.A, Room.NORMAL)),
                        "TEACHER_MAX_PER_DAY", true)),
                Arguments.of(new Case("班级单日上限", "CLASS_MAX_PER_DAY", false, true, 50,
                        List.of(params("maxPerDay", 2), params("classId", Class.A, "maxPerDay", 1)),
                        List.of(lesson(1, 1, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 2, Course.MATH, Teacher.OTHER, Class.A, Room.NORMAL)),
                        "CLASS_MAX_PER_DAY", true)),
                Arguments.of(new Case("课程单日上限", "COURSE_MAX_PER_DAY", false, true, 50,
                        List.of(params("maxPerDay", 2), params("courseId", Course.CHINESE, "maxPerDay", 1)),
                        List.of(lesson(1, 1, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 2, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL)),
                        "COURSE_MAX_PER_DAY", true)),
                Arguments.of(new Case("晚自习上限", "EVENING_MAX", false, true, 40,
                        List.of(params("maxPerDay", 2), params("classId", Class.A, "maxPerDay", 0)),
                        List.of(lesson(1, 9, Course.CHINESE, Teacher.MATH, Class.A, Room.NORMAL)),
                        "EVENING_MAX", true)),
                Arguments.of(new Case("教师连续上课上限", "TEACHER_MAX_CONSECUTIVE", false, true, 45,
                        List.of(params("maxConsecutive", 3),
                                params("teacherId", Teacher.MATH, "maxConsecutive", 2)),
                        List.of(lesson(1, 1, Course.MATH, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 2, Course.MATH, Teacher.MATH, Class.A, Room.NORMAL),
                                lesson(1, 3, Course.MATH, Teacher.MATH, Class.A, Room.NORMAL)),
                        "TEACHER_MAX_CONSECUTIVE", true)));
    }

    private static Lesson lesson(int day, int periodNo, Course course, Teacher teacher,
                                 Class adminClass, Room room) {
        return new Lesson(day, periodNo, course, teacher, adminClass, room);
    }

    /** An entry whose object is a fixture alias, resolved to a real id at save time. */
    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            params.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return params;
    }

    // ------------------------------------------------------------------ the test

    @ParameterizedTest(name = "{0}")
    @MethodSource("catalogue")
    void everyTypeIsEnforcedThroughTheDraftVerdict(Case spec) {
        String token = adminToken();
        World world = prepare(token);

        save(token, world, List.of(config(spec, world, spec.hard()))).then().statusCode(200);

        long draftId = draft(world, spec.lessons());
        assertThat(conflictCodes(token, draftId))
                .as("%s (%s) 的配置保存成功，但草稿校验没有报出 %s", spec.label(), spec.type(), spec.code())
                .contains(spec.code());

        // A hard rule must actually block the flow, not just decorate the page.
        assertThat(publishStatus(token, draftId))
                .as("%s 是硬规则，草稿却不拒绝发布", spec.type())
                .isEqualTo(409);

        // Hard rules are not scored: the same timetable costs nothing in soft penalty.
        assertThat(softPenalty(world, spec.lessons()))
                .as("%s 是硬规则，不应计入软约束扣分", spec.type())
                .isZero();

        if (spec.switchType()) {
            save(token, world, List.of(switchConfig(spec.type(), false, true))).then().statusCode(200);
            assertThat(conflictCodes(token, draftId))
                    .as("%s 关闭后仍然报出违反", spec.type())
                    .doesNotContain(spec.code());
        }

        if (spec.soft()) {
            // The same configuration as a soft rule: no conflict, but a real penalty.
            save(token, world, List.of(config(spec, world, false))).then().statusCode(200);
            assertThat(conflictCodes(token, draftId))
                    .as("%s 是软规则，不应作为硬冲突阻断", spec.type())
                    .doesNotContain(spec.code());
            assertThat(softPenalty(world, spec.lessons()))
                    .as("%s 是软规则，软约束扣分没有变化", spec.type())
                    .isPositive();
        }
    }

    /**
     * Ticket 14: for every entry-backed type the <em>entry</em> override decides the
     * verdict, in both directions. The type row carries the opposite choice, so a type
     * that silently kept using its own default would fail here.
     */
    @ParameterizedTest(name = "逐项控制：{0}")
    @MethodSource("catalogue")
    void perEntryControlDecidesEveryEntryBackedType(Case spec) {
        if (spec.switchType()) {
            return;
        }
        String token = adminToken();
        World world = prepare(token);
        long draftId = draft(world, spec.lessons());

        // Type says soft, entry says hard: the requirement must block.
        save(token, world, List.of(config(spec, world, false, true))).then().statusCode(200);
        assertThat(conflictCodes(token, draftId))
                .as("%s 的条目设为必须后应阻断", spec.type())
                .contains(spec.code());
        assertThat(publishStatus(token, draftId))
                .as("%s 的条目设为必须后应拒绝发布", spec.type())
                .isEqualTo(409);

        // Type says hard, entry says prefer: nothing blocks, but the fact is scored.
        save(token, world, List.of(config(spec, world, true, false))).then().statusCode(200);
        assertThat(conflictCodes(token, draftId))
                .as("%s 的条目设为尽量后不应阻断", spec.type())
                .doesNotContain(spec.code());
        assertThat(softPenalty(world, spec.lessons()))
                .as("%s 的条目设为尽量后应计入软约束扣分", spec.type())
                .isPositive();
    }

    /** A 25th type must not silently skip acceptance: the table and the enum move together. */
    @Test
    void theTableCoversTheWholeCatalogue() {
        Set<String> covered = catalogue()
                .map(arguments -> ((Case) arguments.get()[0]).type())
                .collect(Collectors.toSet());
        assertThat(covered).hasSize(25);
        assertThat(covered).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ConstraintType.values()).map(Enum::name).toList());
    }

    // ------------------------------------------------------------------ fixture

    /**
     * One semester with 4 morning, 4 afternoon and 2 evening periods, two classes (A has
     * a head teacher), three teachers with subjects, three courses including 体育, and
     * three rooms — one deliberately too small for the 45-student class.
     */
    private record World(long semesterId, long classA, long classB, long teacherMath, long teacherOther,
                         long teacherHead, long courseChinese, long courseMath, long courseSport,
                         long roomNormal, long roomLab, long roomSmall,
                         Map<Integer, Long> periodIdBySlot, Map<Long, PeriodView> periods,
                         Map<Long, RoomView> rooms, Map<Long, CourseView> courses,
                         Map<Long, TeacherView> teachers, Map<Long, ClassView> classes) {}

    private World prepare(String token) {
        String suffix = TestData.suffix();
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 2))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);

        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        long teacherHead = teacherWithSubject(token, "班主任" + suffix, "语文");
        long classA = classWithHeadTeacher(token, semesterId, gradeId, "高一1班" + suffix, teacherHead);
        long classB = TestData.adminClass(token, semesterId, gradeId, "高一2班" + suffix);

        long teacherMath = teacherWithSubject(token, "张老师" + suffix, "数学");
        long teacherOther = teacherWithSubject(token, "李老师" + suffix, "英语");
        long courseChinese = TestData.course(token, "语文" + suffix);
        long courseMath = TestData.course(token, "数学" + suffix);
        long courseSport = TestData.course(token, "体育" + suffix);

        long roomNormal = TestData.room(token, "普通教室" + suffix);
        long roomLab = roomWith(token, "实验室" + suffix, 50, "LAB");
        long roomSmall = roomWith(token, "小教室" + suffix, 10, "NORMAL");

        Map<Integer, Long> slotIds = new LinkedHashMap<>();
        Map<Long, PeriodView> periods = new LinkedHashMap<>();
        List<Map<String, Object>> rows = as(token)
                .when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            int day = ((Number) row.get("dayOfWeek")).intValue();
            int periodNo = ((Number) row.get("periodNo")).intValue();
            slotIds.put(day * 100 + periodNo, id);
            periods.put(id, new PeriodView(id, day, periodNo,
                    PeriodSection.valueOf(String.valueOf(row.get("section")))));
        }

        Map<Long, RoomView> rooms = new LinkedHashMap<>();
        rooms.put(roomNormal, new RoomView(roomNormal, "普通教室", "NORMAL", 50));
        rooms.put(roomLab, new RoomView(roomLab, "实验室", "LAB", 50));
        rooms.put(roomSmall, new RoomView(roomSmall, "小教室", "NORMAL", 10));

        Map<Long, CourseView> courses = new LinkedHashMap<>();
        courses.put(courseChinese, new CourseView(courseChinese, "语文", null));
        courses.put(courseMath, new CourseView(courseMath, "数学", null));
        courses.put(courseSport, new CourseView(courseSport, "体育", null));

        Map<Long, TeacherView> teachers = new LinkedHashMap<>();
        teachers.put(teacherMath, new TeacherView(teacherMath, "张老师", "数学"));
        teachers.put(teacherOther, new TeacherView(teacherOther, "李老师", "英语"));
        teachers.put(teacherHead, new TeacherView(teacherHead, "班主任", "语文"));

        Map<Long, ClassView> classes = new LinkedHashMap<>();
        classes.put(classA, new ClassView(classA, "高一1班", 45, teacherHead));
        classes.put(classB, new ClassView(classB, "高一2班", 45, null));

        return new World(semesterId, classA, classB, teacherMath, teacherOther, teacherHead,
                courseChinese, courseMath, courseSport, roomNormal, roomLab, roomSmall,
                slotIds, periods, rooms, courses, teachers, classes);
    }

    private long teacherWithSubject(String token, String name, String subject) {
        Response response = as(token)
                .body(Map.of("name", name, "employeeNo", "T" + TestData.suffix(), "subject", subject))
                .when().post("/api/teachers");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private long roomWith(String token, String name, int capacity, String type) {
        Response response = as(token)
                .body(Map.of("name", name, "capacity", capacity, "type", type))
                .when().post("/api/rooms");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private long classWithHeadTeacher(
            String token, long semesterId, long gradeId, String name, long headTeacherId) {
        Response response = as(token)
                .body(Map.of("gradeId", gradeId, "name", name, "studentCount", 45,
                        "headTeacherId", headTeacherId))
                .queryParam("semesterId", semesterId)
                .when().post("/api/classes");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    /** Resolves the fixture aliases of one entry into real ids before it is sent. */
    private Map<String, Object> resolve(World world, Map<String, Object> entry) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        entry.forEach((key, value) -> resolved.put(key, resolveValue(world, key, value)));
        return resolved;
    }

    private Object resolveValue(World world, String key, Object value) {
        if (value instanceof Teacher teacher) {
            return switch (teacher) {
                case MATH -> world.teacherMath();
                case OTHER -> world.teacherOther();
                case HEAD -> world.teacherHead();
            };
        }
        if (value instanceof Course course) {
            return switch (course) {
                case CHINESE -> world.courseChinese();
                case MATH -> world.courseMath();
                case SPORT -> world.courseSport();
            };
        }
        if (value instanceof Class adminClass) {
            return adminClass == Class.A ? world.classA() : world.classB();
        }
        return value;
    }

    /**
     * One save request row. The switch types keep their type-level row; every other
     * type sends its entries, resolved against the semester that is actually in use.
     */
    private Map<String, Object> config(Case spec, World world, boolean hard) {
        if (spec.switchType()) {
            return switchConfig(spec.type(), true, hard);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", spec.type());
        config.put("weight", spec.weight());
        config.put("hard", hard);
        config.put("enabled", true);
        config.put("entries", entryPayloads(spec, world));
        return config;
    }

    /** The same row with the hard/soft choice written on every entry instead. */
    private Map<String, Object> config(
            Case spec, World world, boolean typeHard, boolean entryHard) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", spec.type());
        config.put("weight", spec.weight());
        config.put("hard", typeHard);
        config.put("enabled", true);
        config.put("entries", entryPayloads(spec, world, entryHard));
        return config;
    }

    private Map<String, Object> switchConfig(String type, boolean enabled, boolean hard) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", type);
        config.put("params", Map.of());
        config.put("weight", ConstraintType.valueOf(type).getDefaultWeight());
        config.put("hard", hard);
        config.put("enabled", enabled);
        return config;
    }

    private List<Map<String, Object>> entryPayloads(Case spec, World world) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (Map<String, Object> entry : spec.entries()) {
            payloads.add(Map.of("params", resolve(world, entry)));
        }
        return payloads;
    }

    private List<Map<String, Object>> entryPayloads(
            Case spec, World world, boolean entryHard) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (Map<String, Object> entry : spec.entries()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("params", resolve(world, entry));
            payload.put("hard", entryHard);
            payloads.add(payload);
        }
        return payloads;
    }

    private Response save(String token, World world, List<Map<String, Object>> configs) {
        // The page always sends the version it loaded. Tests do the same so the entry
        // overrides written above can be replaced on the next save.
        long version = as(token).queryParam("semesterId", world.semesterId())
                .when().get("/api/constraints/state")
                .then().statusCode(200).extract().jsonPath().getLong("version");
        return as(token).queryParam("semesterId", world.semesterId())
                .queryParam("baseVersion", version)
                .body(Map.of("configs", configs))
                .when().put("/api/constraints");
    }

    private long draft(World world, List<Lesson> lessons) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(world.semesterId());
        draft.setName("24类验收" + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = drafts.save(draft).getId();
        int index = 1;
        for (Lesson lesson : lessons) {
            DraftLesson row = new DraftLesson();
            row.setDraftId(draftId);
            row.setAssignmentId(0L);
            row.setLessonIndex(index++);
            row.setCourseId(courseId(world, lesson.course()));
            row.setTeacherId(teacherId(world, lesson.teacher()));
            row.setAdminClassId(lesson.adminClass() == Class.A ? world.classA() : world.classB());
            row.setStudentCount(45);
            row.setPeriodId(world.periodIdBySlot().get(lesson.day() * 100 + lesson.periodNo()));
            row.setRoomId(roomId(world, lesson.room()));
            draftLessons.save(row);
        }
        return draftId;
    }

    private List<String> conflictCodes(String token, long draftId) {
        Response validation = as(token)
                .when().get("/api/timetable-drafts/" + draftId + "/validation")
                .then().statusCode(200).extract().response();
        List<Map<String, Object>> rows = validation.jsonPath().getList("conflicts");
        return rows.stream().map(row -> String.valueOf(row.get("type"))).toList();
    }

    private int publishStatus(String token, long draftId) {
        return as(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().extract().statusCode();
    }

    /** The independent soft score, recomputed through the shared rule catalogue. */
    private int softPenalty(World world, List<Lesson> lessons) {
        ConstraintContext context = new ConstraintContext(
                world.periods(), world.rooms(), world.courses(), world.teachers(), world.classes(),
                constraintService.settings(world.semesterId()));
        List<LessonView> views = new ArrayList<>();
        int id = 1;
        for (Lesson lesson : lessons) {
            views.add(new LessonView(id++, lesson.adminClass() == Class.A ? world.classA() : world.classB(),
                    courseId(world, lesson.course()), teacherId(world, lesson.teacher()),
                    roomId(world, lesson.room()),
                    world.periodIdBySlot().get(lesson.day() * 100 + lesson.periodNo()), 45));
        }
        return ConstraintChecker.softPenalty(context, views);
    }

    private long courseId(World world, Course course) {
        return switch (course) {
            case CHINESE -> world.courseChinese();
            case MATH -> world.courseMath();
            case SPORT -> world.courseSport();
        };
    }

    private long teacherId(World world, Teacher teacher) {
        return switch (teacher) {
            case MATH -> world.teacherMath();
            case OTHER -> world.teacherOther();
            case HEAD -> world.teacherHead();
        };
    }

    private long roomId(World world, Room room) {
        return switch (room) {
            case NORMAL -> world.roomNormal();
            case LAB -> world.roomLab();
            case SMALL -> world.roomSmall();
        };
    }
}
