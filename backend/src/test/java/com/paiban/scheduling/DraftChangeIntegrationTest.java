package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 02: the one atomic change endpoint. Everything is validated before the first
 * row is touched, a stale version/source/rules snapshot is refused, locks are honoured,
 * and the before/after comparison uses stable violation keys rather than a total count.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DraftChangeIntegrationTest extends ApiIntegrationTest {

    private static final String CHANGES = "/api/timetable-drafts/%d/changes";
    private static final String DRAFT = "/api/timetable-drafts/%d";
    private static final String LOCKS = "/api/timetable-drafts/%d/lessons/locks";
    private static final String MOVE = "/api/timetable-drafts/%d/lessons/%d";

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private ConstraintConfigRepository constraintConfigs;

    @Autowired
    private AuditLogRepository auditLogs;

    private record World(long semesterId, List<Long> periods, long classA, long classB, long classC,
                         long classD, long classE, long chinese, long math, long teacherA,
                         long teacherB, long room1, long room2, long room3, long room4,
                         long room5, long room6) {}

    @Test
    void legalIntraClassSwapSavesBothLessonsTogether() {
        String token = adminToken();
        World world = world(token);
        long draftId = draft(world);
        long a = lesson(world, draftId, 1L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        long b = lesson(world, draftId, 2L, world.classA(), world.math(),
                world.teacherB(), world.periods().get(1), world.room2());
        String fingerprint = as(token).when().get(DRAFT.formatted(draftId))
                .then().statusCode(200).extract().jsonPath().getString("rulesFingerprint");
        assertThat(fingerprint).hasSize(64);

        as(token).body(request(0L, fingerprint, true, List.of(
                        change(a, world.periods().get(1), null, null,
                                world.periods().get(0), world.teacherA(), world.room1()),
                        change(b, world.periods().get(0), null, null,
                                world.periods().get(1), world.teacherB(), world.room2()))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("valid", equalTo(true))
                .body("version", equalTo(1))
                .body("hardConflicts", equalTo(0))
                .body("addedConflicts", hasSize(0));

        assertThat(draftLessons.findById(a).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(1));
        assertThat(draftLessons.findById(b).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(0));
        assertThat(version(draftId)).isEqualTo(1);
        assertThat(auditLogs.findTop100ByOrderByCreatedAtDesc())
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo("DRAFT_CHANGE");
                    assertThat(log.getEntityType()).isEqualTo("DRAFT_LESSON");
                    assertThat(log.getSuccessRows()).isEqualTo(2);
                });
    }

    @Test
    void moveToEmptySlotSavesButMoveIntoTeacherConflictWritesNothing() {
        String token = adminToken();
        World world = world(token);
        long draftId = draft(world);
        long a = lesson(world, draftId, 1L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        lesson(world, draftId, 2L, world.classB(), world.math(),
                world.teacherA(), world.periods().get(1), world.room2());

        as(token).body(request(0L, null, false, List.of(
                        change(a, world.periods().get(2), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("version", equalTo(1));
        assertThat(draftLessons.findById(a).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(2));

        as(token).body(request(1L, null, false, List.of(
                        change(a, world.periods().get(1), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(200)
                .body("saved", equalTo(false))
                .body("valid", equalTo(false))
                .body("hardConflicts", greaterThan(0))
                .body("addedConflicts[0].code", equalTo("TEACHER_CONFLICT"));

        assertThat(draftLessons.findById(a).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(2));
        assertThat(version(draftId)).isEqualTo(1);
    }

    @Test
    void lockedSourceOrTargetRejectsButARoomOnlyChangeIsAllowed() {
        String token = adminToken();
        World world = world(token);
        long draftId = draft(world);
        long a = lesson(world, draftId, 1L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        long b = lesson(world, draftId, 2L, world.classB(), world.math(),
                world.teacherB(), world.periods().get(1), world.room2());

        as(token).body(Map.of("lessonIds", List.of(a, b), "locked", true, "expectedVersion", 0))
                .when().put(LOCKS.formatted(draftId))
                .then().statusCode(200)
                .body("version", equalTo(1));

        as(token).body(request(1L, null, false, List.of(
                        change(a, world.periods().get(3), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(409)
                .body("message", containsString("已锁定"));

        as(token).body(request(1L, null, false, List.of(
                        change(b, world.periods().get(3), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(409)
                .body("message", containsString("已锁定"));

        as(token).body(request(1L, null, true, List.of(
                        change(a, world.periods().get(0), null, world.room3(),
                                world.periods().get(0), world.teacherA(), world.room1()))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("version", equalTo(2));

        DraftLesson after = draftLessons.findById(a).orElseThrow();
        assertThat(after.getPeriodId()).isEqualTo(world.periods().get(0));
        assertThat(after.getRoomId()).isEqualTo(world.room3());
        assertThat(after.isLocked()).isTrue();
    }

    @Test
    void invalidBatchesAreRejectedWithoutAnyWrite() {
        String token = adminToken();
        World world = world(token);
        long draftId = draft(world);
        long a = lesson(world, draftId, 1L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        long otherDraftLesson = otherDraftLesson(world);
        long foreignSemester = TestData.semester(token);
        long foreignPeriod = firstPeriod(token, foreignSemester);

        as(token).body(request(0L, null, false, List.of(
                        change(a, world.periods().get(2), null, null, null, null, null),
                        change(999_999L, world.periods().get(2), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(400);

        as(token).body(request(0L, null, false, List.of(
                        change(a, foreignPeriod, null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(400)
                .body("message", containsString("不属于该学期"));

        as(token).body(request(0L, null, false, List.of(
                        change(a, world.periods().get(2), null, null, null, null, null),
                        change(a, world.periods().get(3), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(400)
                .body("message", containsString("重复"));

        as(token).body(request(0L, null, false, List.of(
                        change(otherDraftLesson, world.periods().get(2), null, null,
                                null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(400)
                .body("message", containsString("非法课节"));

        as(token).body(request(0L, null, false, List.of(
                        change(a, 999_999L, null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(400)
                .body("message", containsString("作息时段不存在"));

        as(token).body(request(0L, null, false, List.of()))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(400);

        assertThat(draftLessons.findById(a).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(0));
        assertThat(version(draftId)).isZero();
    }

    @Test
    void staleVersionSourceOrRulesAreRejectedAndWriteNothing() {
        String token = adminToken();
        World world = world(token);
        long draftId = draft(world);
        long a = lesson(world, draftId, 1L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());

        as(token).body(request(0L, null, false, List.of(
                        change(a, world.periods().get(2), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(200)
                .body("version", equalTo(1));

        as(token).body(request(0L, null, false, List.of(
                        change(a, world.periods().get(3), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(409)
                .body("message", containsString("请刷新"));

        as(token).body(request(1L, null, true, List.of(
                        change(a, world.periods().get(3), null, null,
                                world.periods().get(0), world.teacherA(), world.room1()))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(409)
                .body("message", containsString("已被其他人修改"));

        as(token).body(request(1L, "not-the-current-fingerprint", false, List.of(
                        change(a, world.periods().get(3), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(409)
                .body("message", containsString("规则已变化"));

        assertThat(draftLessons.findById(a).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(2));
        assertThat(version(draftId)).isEqualTo(1);
    }

    @Test
    void repairableOldConflictIsAcceptedButFixingAndAddingAnotherIsRefused() {
        String token = adminToken();
        World world = world(token);
        long draftId = draft(world);
        long a = lesson(world, draftId, 1L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        long b = lesson(world, draftId, 2L, world.classB(), world.math(),
                world.teacherA(), world.periods().get(2), world.room2());
        long c = lesson(world, draftId, 3L, world.classC(), world.chinese(),
                world.teacherB(), world.periods().get(3), world.room3());
        lesson(world, draftId, 4L, world.classD(), world.math(),
                world.teacherB(), world.periods().get(4), world.room4());
        long e = lesson(world, draftId, 5L, world.classE(), world.chinese(),
                world.teacherA(), world.periods().get(5), world.room5());

        // Force a pre-existing hard teacher conflict: a and b now share teacher a and slot 3.
        as(token).body(Map.of("periodId", world.periods().get(2), "roomId", world.room1(),
                        "force", true))
                .when().patch(MOVE.formatted(draftId, a))
                .then().statusCode(200)
                .body("saved", equalTo(true));
        assertThat(version(draftId)).isEqualTo(1);

        as(token).body(request(1L, null, true, List.of(
                        change(a, world.periods().get(0), null, null,
                                world.periods().get(2), world.teacherA(), world.room1()))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("addedConflicts", hasSize(0));
        assertThat(version(draftId)).isEqualTo(2);

        // Another pre-existing conflict: c is forced onto d's slot with the same teacher.
        as(token).body(Map.of("periodId", world.periods().get(4), "roomId", world.room3(),
                        "force", true))
                .when().patch(MOVE.formatted(draftId, c))
                .then().statusCode(200)
                .body("saved", equalTo(true));
        assertThat(version(draftId)).isEqualTo(3);

        // Fix c, but move e into b's slot: a brand new conflict key appears.
        as(token).body(request(3L, null, true, List.of(
                        change(c, world.periods().get(3), null, null,
                                world.periods().get(4), world.teacherB(), world.room3()),
                        change(e, world.periods().get(2), null, null,
                                world.periods().get(5), world.teacherA(), world.room5()))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(200)
                .body("saved", equalTo(false))
                .body("valid", equalTo(false))
                .body("addedConflicts[0].code", equalTo("TEACHER_CONFLICT"));

        assertThat(draftLessons.findById(c).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(4));
        assertThat(draftLessons.findById(e).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(5));
        assertThat(version(draftId)).isEqualTo(3);
    }

    @Test
    void publishedArchivedAndPermissionAreRefused() {
        String token = adminToken();
        World world = world(token);
        long draftId = draft(world);
        long a = lesson(world, draftId, 1L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());

        setStatus(draftId, "PUBLISHED");
        as(token).body(request(0L, null, false, List.of(
                        change(a, world.periods().get(2), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(400)
                .body("message", containsString("课表已发布"));

        setStatus(draftId, "ARCHIVED");
        as(token).body(request(0L, null, false, List.of(
                        change(a, world.periods().get(2), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(400)
                .body("message", containsString("课表已归档"));

        long editableDraft = draft(world);
        long editableLesson = lesson(world, editableDraft, 1L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        String username = "chg_" + TestData.suffix();
        createUser(token, username, "chg123", "teacher");
        String teacherToken = login(username, "chg123");
        as(teacherToken).body(request(0L, null, false, List.of(
                        change(editableLesson, world.periods().get(2), null, null,
                                null, null, null))))
                .when().post(CHANGES.formatted(editableDraft))
                .then().statusCode(403);

        assertThat(draftLessons.findById(a).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(0));
    }

    @Test
    void softPenaltyBeforeAfterAndDeltaAreReported() {
        String token = adminToken();
        World world = world(token);
        addSoftGapRule(world.semesterId());
        long draftId = draft(world);
        long a = lesson(world, draftId, 1L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        long b = lesson(world, draftId, 2L, world.classA(), world.math(),
                world.teacherB(), world.periods().get(1), world.room2());
        lesson(world, draftId, 3L, world.classB(), world.math(),
                world.teacherA(), world.periods().get(2), world.room3());

        Response saved = as(token).body(request(0L, null, false, List.of(
                        change(b, world.periods().get(3), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .extract().response();
        assertThat(saved.jsonPath().getInt("softPenaltyAfter"))
                .isGreaterThan(saved.jsonPath().getInt("softPenaltyBefore"));
        assertThat(saved.jsonPath().getInt("softPenaltyDelta"))
                .isEqualTo(saved.jsonPath().getInt("softPenaltyAfter")
                        - saved.jsonPath().getInt("softPenaltyBefore"));

        Response refused = as(token).body(request(1L, null, false, List.of(
                        change(a, world.periods().get(2), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(200)
                .body("saved", equalTo(false))
                .extract().response();
        assertThat(refused.jsonPath().getInt("softPenaltyBefore")).isGreaterThanOrEqualTo(0);
        assertThat(refused.jsonPath().getInt("softPenaltyAfter")).isGreaterThanOrEqualTo(0);

        assertThat(draftLessons.findById(a).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(0));
        assertThat(version(draftId)).isEqualTo(1);
    }

    @Test
    void aBadLastChangeLeavesEarlierOnesUnwrittenAndAnOldVersionIsRefused() {
        String token = adminToken();
        World world = world(token);
        long draftId = draft(world);
        long a = lesson(world, draftId, 1L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        long b = lesson(world, draftId, 2L, world.classB(), world.math(),
                world.teacherB(), world.periods().get(1), world.room2());

        as(token).body(request(0L, null, false, List.of(
                        change(a, world.periods().get(3), null, null, null, null, null),
                        change(b, 999_999L, null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(400);
        assertThat(draftLessons.findById(a).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(0));
        assertThat(draftLessons.findById(b).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(1));
        assertThat(version(draftId)).isZero();

        // Two clients both read version 0: the first writes, the second must be refused.
        as(token).body(request(0L, null, false, List.of(
                        change(a, world.periods().get(3), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(200)
                .body("version", equalTo(1));

        as(token).body(request(0L, null, false, List.of(
                        change(b, world.periods().get(4), null, null, null, null, null))))
                .when().post(CHANGES.formatted(draftId))
                .then().statusCode(409);

        assertThat(draftLessons.findById(a).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(3));
        assertThat(draftLessons.findById(b).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(1));
        assertThat(version(draftId)).isEqualTo(1);
    }

    // ---------- fixtures ----------

    private World world(String token) {
        long semesterId = TestData.semester(token);
        Response periodResponse = as(token)
                .body(Map.of("morningCount", 6, "afternoonCount", 0, "eveningCount", 0,
                        "days", List.of(1)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200).extract().response();
        List<Long> periods = periodResponse.jsonPath().getList("id", Long.class);

        String suffix = TestData.suffix();
        long gradeId = TestData.grade(token, semesterId, "变更年级" + suffix);
        long classA = TestData.adminClass(token, semesterId, gradeId, "变更一班" + suffix);
        long classB = TestData.adminClass(token, semesterId, gradeId, "变更二班" + suffix);
        long classC = TestData.adminClass(token, semesterId, gradeId, "变更三班" + suffix);
        long classD = TestData.adminClass(token, semesterId, gradeId, "变更四班" + suffix);
        long classE = TestData.adminClass(token, semesterId, gradeId, "变更五班" + suffix);
        return new World(semesterId, periods, classA, classB, classC, classD, classE,
                TestData.course(token, "变更语文" + suffix),
                TestData.course(token, "变更数学" + suffix),
                TestData.teacher(token, "变更语文老师" + suffix),
                TestData.teacher(token, "变更数学老师" + suffix),
                TestData.room(token, "变更房1" + suffix),
                TestData.room(token, "变更房2" + suffix),
                TestData.room(token, "变更房3" + suffix),
                TestData.room(token, "变更房4" + suffix),
                TestData.room(token, "变更房5" + suffix),
                TestData.room(token, "变更房6" + suffix));
    }

    private long firstPeriod(String token, long semesterId) {
        Response response = as(token)
                .body(Map.of("morningCount", 1, "afternoonCount", 0, "eveningCount", 0,
                        "days", List.of(1)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200).extract().response();
        return response.jsonPath().getList("id", Long.class).get(0);
    }

    private long draft(World world) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(world.semesterId());
        draft.setName("变更测试 " + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("admin");
        return drafts.save(draft).getId();
    }

    private long otherDraftLesson(World world) {
        long draftId = draft(world);
        return lesson(world, draftId, 900L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
    }

    private long lesson(World world, long draftId, long assignmentId, long classId, long courseId,
                        long teacherId, Long periodId, Long roomId) {
        DraftLesson lesson = new DraftLesson();
        lesson.setDraftId(draftId);
        lesson.setAssignmentId(assignmentId);
        lesson.setLessonIndex(1);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periodId);
        lesson.setRoomId(roomId);
        return draftLessons.save(lesson).getId();
    }

    private void setStatus(long draftId, String status) {
        TimetableDraft draft = drafts.findById(draftId).orElseThrow();
        draft.setStatus(status);
        drafts.save(draft);
    }

    private void addSoftGapRule(long semesterId) {
        ConstraintConfig config = new ConstraintConfig();
        config.setSemesterId(semesterId);
        config.setType(ConstraintType.CLASS_GAP_MINIMIZED.name());
        config.setParamsJson("{}");
        config.setWeight(30);
        config.setHard(false);
        config.setEnabled(true);
        constraintConfigs.save(config);
    }

    private long version(long draftId) {
        return drafts.findById(draftId).orElseThrow().getVersion();
    }

    private static Map<String, Object> change(
            long lessonId, Long toPeriodId, Long toTeacherId, Long toRoomId,
            Long expectedPeriodId, Long expectedTeacherId, Long expectedRoomId) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("lessonId", lessonId);
        change.put("toPeriodId", toPeriodId);
        change.put("toTeacherId", toTeacherId);
        change.put("toRoomId", toRoomId);
        change.put("expectedPeriodId", expectedPeriodId);
        change.put("expectedTeacherId", expectedTeacherId);
        change.put("expectedRoomId", expectedRoomId);
        return change;
    }

    private static Map<String, Object> request(
            Long expectedVersion, String rulesFingerprint, boolean verifySource,
            List<Map<String, Object>> changes) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("changes", changes);
        request.put("expectedVersion", expectedVersion);
        request.put("rulesFingerprint", rulesFingerprint);
        request.put("verifySource", verifySource);
        return request;
    }
}
