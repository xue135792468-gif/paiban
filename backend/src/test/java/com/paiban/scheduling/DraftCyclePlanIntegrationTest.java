package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftCyclePlanToken;
import com.paiban.scheduling.domain.DraftCyclePlanTokenRepository;
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
 * Ticket 04 at the HTTP boundary. The important properties are the ones a UI cannot
 * enforce: search never mutates the timetable, a token applies all moves or none, and a
 * second apply (retry or a stale concurrent request) can never swap twice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DraftCyclePlanIntegrationTest extends ApiIntegrationTest {

    private static final String SEARCH = "/api/timetable-drafts/%d/cycle-plans/search";
    private static final String APPLY = "/api/timetable-drafts/%d/cycle-plans/apply";
    private static final String DRAFT = "/api/timetable-drafts/%d";
    private static final String LOCKS = "/api/timetable-drafts/%d/lessons/locks";
    private static final String MOVE = "/api/timetable-drafts/%d/lessons/%d";
    private static final String VALIDATION = "/api/timetable-drafts/%d/validation";

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private ConstraintConfigRepository constraintConfigs;

    @Autowired
    private DraftCyclePlanTokenRepository cycleTokens;

    @Autowired
    private AssignmentRepository assignments;

    private record World(long semesterId, List<Long> periods, long classA, long classB, long classC,
                         long chinese, long math, long english, long teacherA, long teacherB,
                         long teacherC, long room1, long room2, long room3, long room4,
                         long room5, long room6) {}

    private record Scenario(long draftId, long source, long target, long intermediate,
                            long blocker) {}

    @Test
    void searchReturnsVerifiedPlansWithoutTouchingTheTimetable() {
        String token = adminToken();
        World world = world(token);
        Scenario scenario = threeLessonCycle(world);

        Response response = search(token, scenario.draftId(), scenario.source(),
                world.periods().get(1), scenario.target(), 0L);
        response.then().statusCode(200)
                .body("status", equalTo("FOUND"))
                .body("version", equalTo(0))
                .body("fingerprint", notNullValue())
                .body("plans", hasSize(1))
                .body("plans[0].token", notNullValue())
                .body("plans[0].kind", equalTo("CYCLE"))
                .body("plans[0].moves", hasSize(3))
                .body("plans[0].movedLessons", equalTo(3))
                .body("plans[0].affectedTeachers", equalTo(3))
                .body("plans[0].expiresAt", notNullValue());
        assertThat(response.jsonPath().getString("fingerprint")).hasSize(64);
        assertThat(response.jsonPath().getString("plans[0].moves[0].teacherId")).isNotBlank();

        assertThat(version(scenario.draftId())).isZero();
        assertThat(lessonPeriod(scenario.source())).isEqualTo(world.periods().get(0));
        assertThat(lessonPeriod(scenario.target())).isEqualTo(world.periods().get(1));
        assertThat(lessonPeriod(scenario.intermediate())).isEqualTo(world.periods().get(2));
    }

    @Test
    void applyMovesAllLessonsAtomicallyAndReplayIsRefused() {
        String token = adminToken();
        World world = world(token);
        Scenario scenario = threeLessonCycle(world);

        String planToken = searchToken(token, scenario.draftId(), scenario.source(),
                world.periods().get(1), scenario.target(), 0L);
        as(token).body(Map.of("token", planToken, "expectedVersion", 0))
                .when().post(APPLY.formatted(scenario.draftId()))
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("version", equalTo(1))
                .body("tokenUsed", equalTo(true))
                .body("addedConflicts", hasSize(0));

        assertThat(lessonPeriod(scenario.source())).isEqualTo(world.periods().get(1));
        assertThat(lessonPeriod(scenario.target())).isEqualTo(world.periods().get(2));
        assertThat(lessonPeriod(scenario.intermediate())).isEqualTo(world.periods().get(0));
        assertThat(version(scenario.draftId())).isEqualTo(1);

        as(token).body(Map.of("token", planToken, "expectedVersion", 1))
                .when().post(APPLY.formatted(scenario.draftId()))
                .then().statusCode(409)
                .body("message", containsString("已应用"));

        assertThat(lessonPeriod(scenario.source())).isEqualTo(world.periods().get(1));
        assertThat(lessonPeriod(scenario.target())).isEqualTo(world.periods().get(2));
        assertThat(lessonPeriod(scenario.intermediate())).isEqualTo(world.periods().get(0));
        assertThat(version(scenario.draftId())).isEqualTo(1);
    }

    @Test
    void expiredForgedAndForeignTokensAreRefusedWithoutWrites() {
        String token = adminToken();
        World world = world(token);
        Scenario scenario = threeLessonCycle(world);

        String planToken = searchToken(token, scenario.draftId(), scenario.source(),
                world.periods().get(1), scenario.target(), 0L);
        DraftCyclePlanToken row = cycleTokens.findById(planToken).orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(60));
        cycleTokens.save(row);

        as(token).body(Map.of("token", planToken, "expectedVersion", 0))
                .when().post(APPLY.formatted(scenario.draftId()))
                .then().statusCode(409)
                .body("message", containsString("已过期"));

        as(token).body(Map.of("token", "no-such-token", "expectedVersion", 0))
                .when().post(APPLY.formatted(scenario.draftId()))
                .then().statusCode(404);

        assertThat(version(scenario.draftId())).isZero();
        assertThat(lessonPeriod(scenario.source())).isEqualTo(world.periods().get(0));
    }

    @Test
    void anyDraftRulesLocksOrSettingsChangeInvalidatesAPreview() {
        String token = adminToken();
        World world = world(token);

        // Someone edits the draft (a legal move elsewhere) after the preview.
        Scenario moved = threeLessonCycle(world);
        String movedToken = searchToken(token, moved.draftId(), moved.source(),
                world.periods().get(1), moved.target(), 0L);
        as(token).body(Map.of("periodId", world.periods().get(5), "roomId", world.room6()))
                .when().patch(MOVE.formatted(moved.draftId(), moved.blocker()))
                .then().statusCode(200);
        as(token).body(Map.of("token", movedToken, "expectedVersion", 0))
                .when().post(APPLY.formatted(moved.draftId()))
                .then().statusCode(409);
        assertThat(lessonPeriod(moved.source())).isEqualTo(world.periods().get(0));

        // Someone edits the rules after the preview; the version did not change.
        Scenario ruled = threeLessonCycle(world);
        String ruleToken = searchToken(token, ruled.draftId(), ruled.source(),
                world.periods().get(1), ruled.target(), 0L);
        addSoftRule(world.semesterId());
        as(token).body(Map.of("token", ruleToken, "expectedVersion", 0))
                .when().post(APPLY.formatted(ruled.draftId()))
                .then().statusCode(409)
                .body("message", containsString("课表已变化"));
        assertThat(lessonPeriod(ruled.source())).isEqualTo(world.periods().get(0));
        assertThat(version(ruled.draftId())).isZero();

        // Someone changes the shared exchange settings after the preview.
        Scenario policy = threeLessonCycle(world);
        String policyToken = searchToken(token, policy.draftId(), policy.source(),
                world.periods().get(1), policy.target(), 0L);
        settingsService.put("substitution.max-plan-teachers", "5");
        as(token).body(Map.of("token", policyToken, "expectedVersion", 0))
                .when().post(APPLY.formatted(policy.draftId()))
                .then().statusCode(409)
                .body("message", containsString("课表已变化"));
        assertThat(lessonPeriod(policy.source())).isEqualTo(world.periods().get(0));
        assertThat(version(policy.draftId())).isZero();
    }

    @Test
    void lockingAnIntermediateLessonAfterPreviewInvalidatesTheToken() {
        String token = adminToken();
        World world = world(token);
        Scenario scenario = threeLessonCycle(world);
        String planToken = searchToken(token, scenario.draftId(), scenario.source(),
                world.periods().get(1), scenario.target(), 0L);

        as(token).body(Map.of("lessonIds", List.of(scenario.intermediate()), "locked", true,
                        "expectedVersion", version(scenario.draftId())))
                .when().put(LOCKS.formatted(scenario.draftId()))
                .then().statusCode(200);

        as(token).body(Map.of("token", planToken, "expectedVersion", 1))
                .when().post(APPLY.formatted(scenario.draftId()))
                .then().statusCode(409)
                .body("message", containsString("课表已变化"));
        assertThat(version(scenario.draftId())).isEqualTo(1);
        assertThat(lessonPeriod(scenario.source())).isEqualTo(world.periods().get(0));
    }

    @Test
    void assignmentChangeAfterPreviewInvalidatesTheToken() {
        String token = adminToken();
        World world = world(token);
        Scenario scenario = threeLessonCycle(world);
        String planToken = searchToken(token, scenario.draftId(), scenario.source(),
                world.periods().get(1), scenario.target(), 0L);

        Assignment assignment = new Assignment();
        assignment.setSemesterId(world.semesterId());
        assignment.setAdminClassId(world.classA());
        assignment.setTeachingClassId(world.classA());
        assignment.setCourseId(world.chinese());
        assignment.setTeacherId(world.teacherA());
        assignment.setWeeklyLessons(2);
        assignments.save(assignment);

        as(token).body(Map.of("token", planToken, "expectedVersion", 0))
                .when().post(APPLY.formatted(scenario.draftId()))
                .then().statusCode(409)
                .body("message", containsString("课表已变化"));
        assertThat(version(scenario.draftId())).isZero();
        assertThat(lessonPeriod(scenario.source())).isEqualTo(world.periods().get(0));
    }

    @Test
    void lockedCrossClassAmbiguousAndEmptyTargetsAreRefusedOrReported() {
        String token = adminToken();
        World world = world(token);

        Scenario lockedSource = threeLessonCycle(world);
        as(token).body(Map.of("lessonIds", List.of(lockedSource.source()), "locked", true,
                        "expectedVersion", version(lockedSource.draftId())))
                .when().put(LOCKS.formatted(lockedSource.draftId()))
                .then().statusCode(200);
        as(token).body(searchRequest(lockedSource.source(), world.periods().get(1),
                        lockedSource.target(), version(lockedSource.draftId()), null))
                .when().post(SEARCH.formatted(lockedSource.draftId()))
                .then().statusCode(409)
                .body("message", containsString("来源课节已锁定"));

        Scenario lockedTarget = threeLessonCycle(world);
        as(token).body(Map.of("lessonIds", List.of(lockedTarget.target()), "locked", true,
                        "expectedVersion", version(lockedTarget.draftId())))
                .when().put(LOCKS.formatted(lockedTarget.draftId()))
                .then().statusCode(200);
        as(token).body(searchRequest(lockedTarget.source(), world.periods().get(1),
                        lockedTarget.target(), version(lockedTarget.draftId()), null))
                .when().post(SEARCH.formatted(lockedTarget.draftId()))
                .then().statusCode(409)
                .body("message", containsString("落点课节已锁定"));

        // Locking the intermediate lesson leaves no cycle, but is not an input error.
        Scenario lockedMiddle = threeLessonCycle(world);
        as(token).body(Map.of("lessonIds", List.of(lockedMiddle.intermediate()), "locked", true,
                        "expectedVersion", version(lockedMiddle.draftId())))
                .when().put(LOCKS.formatted(lockedMiddle.draftId()))
                .then().statusCode(200);
        as(token).body(searchRequest(lockedMiddle.source(), world.periods().get(1),
                        lockedMiddle.target(), version(lockedMiddle.draftId()), null))
                .when().post(SEARCH.formatted(lockedMiddle.draftId()))
                .then().statusCode(200)
                .body("status", equalTo("NONE"))
                .body("plans", hasSize(0));

        // Landing on another class's lesson is never reinterpreted as an exchange.
        long crossDraft = draft(world);
        long crossSource = lesson(world, crossDraft, 10L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        lesson(world, crossDraft, 11L, world.classB(), world.math(),
                world.teacherB(), world.periods().get(1), world.room2());
        as(token).body(searchRequest(crossSource, world.periods().get(1), null, 0L, null))
                .when().post(SEARCH.formatted(crossDraft))
                .then().statusCode(409)
                .body("message", containsString("只能在本班范围内换位"));

        // Two of this class's lessons on one slot: ambiguous, do not guess.
        long ambiguousDraft = draft(world);
        long ambiguousSource = lesson(world, ambiguousDraft, 20L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        lesson(world, ambiguousDraft, 21L, world.classA(), world.math(),
                world.teacherB(), world.periods().get(1), world.room2());
        lesson(world, ambiguousDraft, 22L, world.classA(), world.english(),
                world.teacherC(), world.periods().get(1), world.room3());
        as(token).body(searchRequest(ambiguousSource, world.periods().get(1), null, 0L, null))
                .when().post(SEARCH.formatted(ambiguousDraft))
                .then().statusCode(409)
                .body("message", containsString("多节课"));

        // Wrong targetLessonId means the slot changed under the preview.
        Scenario changedTarget = threeLessonCycle(world);
        as(token).body(searchRequest(changedTarget.source(), world.periods().get(1),
                        changedTarget.intermediate(), 0L, null))
                .when().post(SEARCH.formatted(changedTarget.draftId()))
                .then().statusCode(409)
                .body("message", containsString("落点课节已变化"));

        // Empty landing slot is an instruction for the move endpoint, not a search.
        long emptyDraft = draft(world);
        long emptySource = lesson(world, emptyDraft, 30L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        as(token).body(searchRequest(emptySource, world.periods().get(2), null, 0L, null))
                .when().post(SEARCH.formatted(emptyDraft))
                .then().statusCode(200)
                .body("status", equalTo("NONE"))
                .body("message", containsString("落点为空"));
    }

    @Test
    void permissionAndPublishedDraftAreRefused() {
        String token = adminToken();
        World world = world(token);
        Scenario scenario = threeLessonCycle(world);

        String username = "cyc_" + TestData.suffix();
        createUser(token, username, "cyc123", "teacher");
        String teacherToken = login(username, "cyc123");
        as(teacherToken).body(searchRequest(scenario.source(), world.periods().get(1),
                        scenario.target(), 0L, null))
                .when().post(SEARCH.formatted(scenario.draftId()))
                .then().statusCode(403);

        setStatus(scenario.draftId(), "PUBLISHED");
        as(token).body(searchRequest(scenario.source(), world.periods().get(1),
                        scenario.target(), 0L, null))
                .when().post(SEARCH.formatted(scenario.draftId()))
                .then().statusCode(400)
                .body("message", containsString("课表已发布"));
    }

    @Test
    void twoAppliesHoldingTheSameOldVersionChangeTheTimetableOnce() {
        String token = adminToken();
        World world = world(token);
        Scenario scenario = threeLessonCycle(world);
        String planToken = searchToken(token, scenario.draftId(), scenario.source(),
                world.periods().get(1), scenario.target(), 0L);

        as(token).body(Map.of("token", planToken, "expectedVersion", 0))
                .when().post(APPLY.formatted(scenario.draftId()))
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("version", equalTo(1));

        // A second request that also read version 0 arrives late: it must lose.
        as(token).body(Map.of("token", planToken, "expectedVersion", 0))
                .when().post(APPLY.formatted(scenario.draftId()))
                .then().statusCode(409);

        assertThat(version(scenario.draftId())).isEqualTo(1);
        assertThat(lessonPeriod(scenario.source())).isEqualTo(world.periods().get(1));
    }

    @Test
    void threeLessonCycleRunsEndToEndAndKeepsTheTableValid() {
        String token = adminToken();
        World world = world(token);
        Scenario scenario = threeLessonCycle(world);

        search(token, scenario.draftId(), scenario.source(), world.periods().get(1),
                scenario.target(), 0L)
                .then().statusCode(200).body("status", equalTo("FOUND"));
        String planToken = searchToken(token, scenario.draftId(), scenario.source(),
                world.periods().get(1), scenario.target(), 0L);
        as(token).body(Map.of("token", planToken, "expectedVersion", 0))
                .when().post(APPLY.formatted(scenario.draftId()))
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("valid", equalTo(true))
                .body("hardConflicts", equalTo(0))
                .body("addedConflicts", hasSize(0));

        assertThat(lessonPeriod(scenario.source())).isEqualTo(world.periods().get(1));
        assertThat(lessonPeriod(scenario.target())).isEqualTo(world.periods().get(2));
        assertThat(lessonPeriod(scenario.intermediate())).isEqualTo(world.periods().get(0));

        as(token).when().get(VALIDATION.formatted(scenario.draftId()))
                .then().statusCode(200)
                .body("hardConflicts", equalTo(0));
    }

    // ---------------------------------------------------------------- fixtures

    private Response search(String token, long draftId, long source, long targetPeriod,
                            Long targetLessonId, long expectedVersion) {
        return as(token).body(searchRequest(source, targetPeriod, targetLessonId,
                        expectedVersion, null))
                .when().post(SEARCH.formatted(draftId))
                .then().statusCode(200)
                .extract().response();
    }

    private String searchToken(String token, long draftId, long source, long targetPeriod,
                               long targetLessonId, long expectedVersion) {
        Response response = search(token, draftId, source, targetPeriod, targetLessonId,
                expectedVersion);
        return response.jsonPath().getString("plans[0].token");
    }

    private static Map<String, Object> searchRequest(long sourceLessonId, long targetPeriodId,
                                                      Long targetLessonId, Long expectedVersion,
                                                      String rulesFingerprint) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sourceLessonId", sourceLessonId);
        request.put("targetPeriodId", targetPeriodId);
        request.put("targetLessonId", targetLessonId);
        request.put("expectedVersion", expectedVersion);
        request.put("rulesFingerprint", rulesFingerprint);
        return request;
    }

    private World world(String token) {
        long semesterId = TestData.semester(token);
        Response periodResponse = as(token)
                .body(Map.of("morningCount", 6, "afternoonCount", 0, "eveningCount", 0,
                        "days", List.of(1)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200).extract().response();
        List<Long> periods = periodResponse.jsonPath().getList("id", Long.class);

        String suffix = TestData.suffix();
        long gradeId = TestData.grade(token, semesterId, "换位年级" + suffix);
        long classA = TestData.adminClass(token, semesterId, gradeId, "换位一班" + suffix);
        long classB = TestData.adminClass(token, semesterId, gradeId, "换位二班" + suffix);
        long classC = TestData.adminClass(token, semesterId, gradeId, "换位三班" + suffix);
        return new World(semesterId, periods, classA, classB, classC,
                TestData.course(token, "换位语文" + suffix),
                TestData.course(token, "换位数学" + suffix),
                TestData.course(token, "换位英语" + suffix),
                TestData.teacher(token, "换位甲老师" + suffix),
                TestData.teacher(token, "换位乙老师" + suffix),
                TestData.teacher(token, "换位丙老师" + suffix),
                TestData.room(token, "换位房1" + suffix),
                TestData.room(token, "换位房2" + suffix),
                TestData.room(token, "换位房3" + suffix),
                TestData.room(token, "换位房4" + suffix),
                TestData.room(token, "换位房5" + suffix),
                TestData.room(token, "换位房6" + suffix));
    }

    private Scenario threeLessonCycle(World world) {
        long draftId = draft(world);
        long source = lesson(world, draftId, 1L, world.classA(), world.chinese(),
                world.teacherA(), world.periods().get(0), world.room1());
        long target = lesson(world, draftId, 2L, world.classA(), world.math(),
                world.teacherB(), world.periods().get(1), world.room2());
        long intermediate = lesson(world, draftId, 3L, world.classA(), world.english(),
                world.teacherC(), world.periods().get(2), world.room3());
        // Another class shares the source slot with the target lesson's teacher, so the
        // direct swap is illegal while the three-lesson cycle is legal.
        long blocker = lesson(world, draftId, 4L, world.classB(), world.math(),
                world.teacherB(), world.periods().get(0), world.room4());
        return new Scenario(draftId, source, target, intermediate, blocker);
    }

    private long draft(World world) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(world.semesterId());
        draft.setName("换位测试 " + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("admin");
        return drafts.save(draft).getId();
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

    private void addSoftRule(long semesterId) {
        ConstraintConfig config = new ConstraintConfig();
        config.setSemesterId(semesterId);
        config.setType(ConstraintType.CLASS_GAP_MINIMIZED.name());
        config.setParamsJson("{}");
        config.setWeight(30);
        config.setHard(false);
        config.setEnabled(true);
        constraintConfigs.save(config);
    }

    private void setStatus(long draftId, String status) {
        TimetableDraft draft = drafts.findById(draftId).orElseThrow();
        draft.setStatus(status);
        drafts.save(draft);
    }

    private Long lessonPeriod(long lessonId) {
        return draftLessons.findById(lessonId).orElseThrow().getPeriodId();
    }

    private long version(long draftId) {
        return drafts.findById(draftId).orElseThrow().getVersion();
    }
}
