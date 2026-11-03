package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.DraftLockService;
import com.paiban.scheduling.service.DraftWriteGuard;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ticket 01: locks are persisted, survive a reload, and every draft write entry point
 * honours them. The version on the draft is the shared optimistic protocol used by
 * moving, swapping, room filling and deleting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DraftLockIntegrationTest extends ApiIntegrationTest {

    private static final String LOCKS = "/api/timetable-drafts/%d/lessons/locks";
    private static final String MOVE = "/api/timetable-drafts/%d/lessons/%d";
    private static final String SWAP = "/api/timetable-drafts/%d/swap";
    private static final String DRAFT = "/api/timetable-drafts/%d";

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private DraftWriteGuard guard;

    @Autowired
    private DraftLockService lockService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private record World(long semesterId, List<Long> periods, long classA, long classB,
                         long chinese, long math, long english,
                         long teacherA, long teacherB, long mathTeacherA, long mathTeacherB,
                         long englishTeacherA, long englishTeacherB,
                         long room1, long room2, long room3, long room4, long room5, long room6) {}

    private record Seeded(long draftId, long chineseA, long mathA, long englishA,
                          long chineseB, long mathB, long englishB) {}

    @Test
    void newDraftReadsUnlockedAndLockSurvivesReload() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);

        Response detail = as(token).when().get(DRAFT.formatted(seeded.draftId()))
                .then().statusCode(200).extract().response();
        assertThat(detail.jsonPath().getLong("draft.version")).isZero();
        List<Map<String, Object>> lessons = detail.jsonPath().getList("lessons");
        assertThat(lessons).hasSize(6).allSatisfy(lesson ->
                assertThat(lesson.get("locked")).isEqualTo(false));

        as(token).body(Map.of("lessonIds", List.of(seeded.chineseA()), "locked", true, "expectedVersion", 0))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(200)
                .body("requested", equalTo(1))
                .body("updated", equalTo(1))
                .body("locked", equalTo(true))
                .body("version", equalTo(1));

        Response reloaded = as(token).when().get(DRAFT.formatted(seeded.draftId()))
                .then().statusCode(200).extract().response();
        assertThat(reloaded.jsonPath().getLong("draft.version")).isEqualTo(1);
        List<Map<String, Object>> after = reloaded.jsonPath().getList("lessons");
        assertThat(lesson(after, seeded.chineseA()).get("locked")).isEqualTo(true);
        assertThat(lesson(after, seeded.mathA()).get("locked")).isEqualTo(false);
        assertThat(lesson(after, seeded.chineseB()).get("locked")).isEqualTo(false);
    }

    @Test
    void onlyTheSelectedLessonOfTheSameCourseIsLocked() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);

        as(token).body(Map.of("lessonIds", List.of(seeded.mathA()), "locked", true))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(200)
                .body("requested", equalTo(1))
                .body("version", equalTo(1));

        List<Map<String, Object>> lessons = lessonsOf(token, seeded.draftId());
        assertThat(lesson(lessons, seeded.mathA()).get("locked")).isEqualTo(true);
        assertThat(lesson(lessons, seeded.chineseA()).get("locked")).isEqualTo(false);
        assertThat(lesson(lessons, seeded.englishA()).get("locked")).isEqualTo(false);
        assertThat(lesson(lessons, seeded.mathB()).get("locked")).isEqualTo(false);
    }

    @Test
    void invalidBatchesAreRejectedWithoutPartialWrites() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);
        long unassigned = lesson(world, seeded.draftId(), 101L, world.classA(),
                world.chinese(), world.teacherA(), null, world.room1());
        long otherDraftLesson = seedOtherDraft(world);

        String username = "lock_" + TestData.suffix();
        createUser(token, username, "lock123", "teacher");
        String teacherToken = login(username, "lock123");
        as(teacherToken).body(Map.of("lessonIds", List.of(seeded.chineseA()), "locked", true))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(403);

        as(token).body(Map.of("lessonIds", List.of(), "locked", true))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(400)
                .body("message", containsString("请选择要锁定的课节"));

        as(token).body(Map.of("lessonIds", List.of(unassigned), "locked", true))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(400)
                .body("message", containsString("未排课的课节不能锁定"));

        as(token).body(Map.of("lessonIds", List.of(seeded.chineseA(), unassigned), "locked", true))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(400)
                .body("message", containsString("未排课的课节不能锁定"));

        as(token).body(Map.of("lessonIds", List.of(seeded.chineseA(), otherDraftLesson), "locked", true))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(400)
                .body("message", containsString("非法课节 id"));

        List<Map<String, Object>> lessons = lessonsOf(token, seeded.draftId());
        assertThat(lesson(lessons, seeded.chineseA()).get("locked")).isEqualTo(false);
        assertThat(lesson(lessons, unassigned).get("locked")).isEqualTo(false);

        // Duplicates are de-duplicated before counting, and an idempotent call does not bump.
        as(token).body(Map.of(
                        "lessonIds", List.of(seeded.chineseA(), seeded.chineseA()), "locked", true))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(200)
                .body("requested", equalTo(1))
                .body("updated", equalTo(1))
                .body("version", equalTo(1));
        as(token).body(Map.of("lessonIds", List.of(seeded.chineseA()), "locked", true))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(200)
                .body("requested", equalTo(1))
                .body("updated", equalTo(0))
                .body("version", equalTo(1));
    }

    @Test
    void publishedDraftRejectsLocking() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);
        as(token).when().post(DRAFT.formatted(seeded.draftId()) + "/publish").then().statusCode(200);

        as(token).body(Map.of("lessonIds", List.of(seeded.chineseA()), "locked", true))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(400)
                .body("message", containsString("课表已发布"));

        assertThat(draftLessons.findById(seeded.chineseA()).orElseThrow().isLocked()).isFalse();
    }

    @Test
    void lockedLessonBlocksMoveSwapAndDelete() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);
        as(token).body(Map.of("lessonIds", List.of(seeded.chineseA()), "locked", true, "expectedVersion", 0))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(200);

        long freeForClassA = world.periods().get(3);

        as(token).body(Map.of("periodId", freeForClassA, "roomId", world.room1()))
                .when().patch(MOVE.formatted(seeded.draftId(), seeded.chineseA()))
                .then().statusCode(409)
                .body("message", containsString("已锁定"));
        assertThat(draftLessons.findById(seeded.chineseA()).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(0));

        as(token).body(Map.of("lessonId", seeded.chineseA(), "targetLessonId", seeded.mathA()))
                .when().post(SWAP.formatted(seeded.draftId()))
                .then().statusCode(409)
                .body("message", containsString("已锁定"));

        as(token).body(Map.of("lessonId", seeded.chineseA(), "targetLessonId", seeded.chineseB()))
                .when().post(SWAP.formatted(seeded.draftId()))
                .then().statusCode(409)
                .body("message", containsString("已锁定"));

        as(token).when().delete(DRAFT.formatted(seeded.draftId()))
                .then().statusCode(409)
                .body("message", containsString("草稿含 1 节锁定课节"));
        assertThat(drafts.findById(seeded.draftId())).isPresent();

        as(token).body(Map.of("lessonIds", List.of(seeded.chineseA()), "locked", false, "expectedVersion", 1))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(200)
                .body("version", equalTo(2));

        as(token).body(Map.of("periodId", freeForClassA, "roomId", world.room1()))
                .when().patch(MOVE.formatted(seeded.draftId(), seeded.chineseA()))
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("version", equalTo(3));
        assertThat(draftLessons.findById(seeded.chineseA()).orElseThrow().getPeriodId())
                .isEqualTo(freeForClassA);

        as(token).body(Map.of("lessonId", seeded.chineseA(), "targetLessonId", seeded.mathA()))
                .when().post(SWAP.formatted(seeded.draftId()))
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("version", equalTo(4));

        as(token).when().delete(DRAFT.formatted(seeded.draftId())).then().statusCode(200);
    }

    @Test
    void deletingALockedDraftWithoutConfirmationKeepsTheDraftAndItsLesson() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);
        lockOne(token, seeded);

        as(token).when().delete(DRAFT.formatted(seeded.draftId()))
                .then().statusCode(409)
                .body("message", containsString("草稿含 1 节锁定课节"));

        assertThat(drafts.findById(seeded.draftId())).isPresent();
        assertThat(draftLessons.findById(seeded.chineseA())).isPresent();
    }

    @Test
    void confirmedDeleteRejectsAStaleLockedCount() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);
        lockOne(token, seeded);

        as(token).when().delete(DRAFT.formatted(seeded.draftId()) + "?unlockLocked=true&expectedLockedCount=0")
                .then().statusCode(409)
                .body("message", containsString("锁定状态已变化"));

        assertThat(drafts.findById(seeded.draftId())).isPresent();
        assertThat(draftLessons.findById(seeded.chineseA()).orElseThrow().isLocked()).isTrue();
    }

    @Test
    void confirmedDeleteRequiresTheExpectedLockedCount() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);
        lockOne(token, seeded);

        as(token).when().delete(DRAFT.formatted(seeded.draftId()) + "?unlockLocked=true")
                .then().statusCode(400)
                .body("message", containsString("删除含锁草稿需要确认锁定数量"));

        assertThat(drafts.findById(seeded.draftId())).isPresent();
        assertThat(draftLessons.findById(seeded.chineseA()).orElseThrow().isLocked()).isTrue();
    }

    @Test
    void confirmedDeleteRejectsAStaleCountEvenWhenNothingIsLocked() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);

        as(token).when().delete(DRAFT.formatted(seeded.draftId()) + "?unlockLocked=true&expectedLockedCount=1")
                .then().statusCode(409)
                .body("message", containsString("锁定状态已变化"));

        assertThat(drafts.findById(seeded.draftId())).isPresent();
    }

    @Test
    void confirmedDeleteRemovesTheDraftAndItsLockedLessons() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);
        lockOne(token, seeded);

        as(token).when().delete(DRAFT.formatted(seeded.draftId()) + "?unlockLocked=true&expectedLockedCount=1")
                .then().statusCode(200);

        assertThat(drafts.findById(seeded.draftId())).isEmpty();
        assertThat(draftLessons.findById(seeded.chineseA())).isEmpty();
        assertThat(auditLogs.findTop100ByOrderByCreatedAtDesc())
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo("DRAFT_DELETE");
                    assertThat(log.getEntityType()).isEqualTo("TIMETABLE_DRAFT");
                    assertThat(log.getDetail())
                            .contains("draftId=" + seeded.draftId())
                            .contains("locked=1");
                });
    }

    @Test
    void unlockedDraftStillDeletesWithAPlainRequest() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);

        as(token).when().delete(DRAFT.formatted(seeded.draftId())).then().statusCode(200);

        assertThat(drafts.findById(seeded.draftId())).isEmpty();
    }

    @Test
    void listAndDetailExposeTheLockedCountForTheDeleteConfirmation() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);
        lockOne(token, seeded);

        assertThat(listedLockedCount(token, world.semesterId(), seeded.draftId())).isEqualTo(1);
        assertThat(detailLockedCount(token, seeded.draftId())).isEqualTo(1);

        as(token).body(Map.of("lessonIds", List.of(seeded.chineseA()), "locked", false, "expectedVersion", 1))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(200);

        assertThat(listedLockedCount(token, world.semesterId(), seeded.draftId())).isZero();
        assertThat(detailLockedCount(token, seeded.draftId())).isZero();
    }

    private long listedLockedCount(String token, long semesterId, long draftId) {
        List<Map<String, Object>> rows = as(token)
                .when().get("/api/timetable-drafts?semesterId=" + semesterId)
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<String, Object> row = rows.stream()
                .filter(item -> ((Number) item.get("id")).longValue() == draftId)
                .findFirst()
                .orElseThrow();
        return ((Number) row.get("lockedCount")).longValue();
    }

    private long detailLockedCount(String token, long draftId) {
        return as(token).when().get(DRAFT.formatted(draftId))
                .then().statusCode(200).extract().jsonPath().getLong("draft.lockedCount");
    }

    private void lockOne(String token, Seeded seeded) {
        as(token).body(Map.of("lessonIds", List.of(seeded.chineseA()), "locked", true, "expectedVersion", 0))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(200);
    }

    @Test
    void patchRejectsPublishedDraftWithoutWriting() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);
        as(token).when().post(DRAFT.formatted(seeded.draftId()) + "/publish").then().statusCode(200);

        as(token).body(Map.of("periodId", world.periods().get(3), "roomId", world.room1()))
                .when().patch(MOVE.formatted(seeded.draftId(), seeded.chineseA()))
                .then().statusCode(400)
                .body("message", containsString("课表已发布"));

        assertThat(draftLessons.findById(seeded.chineseA()).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(0));
    }

    @Test
    void roomFillStillFillsARoomOnALockedLesson() {
        String token = adminToken();
        World world = world(token);
        long draftId = draft(world);
        long first = lesson(world, draftId, 201L, world.classA(),
                world.chinese(), world.teacherA(), world.periods().get(0), null);
        long second = lesson(world, draftId, 202L, world.classA(),
                world.math(), world.mathTeacherA(), world.periods().get(1), null);

        as(token).body(Map.of("lessonIds", List.of(first), "locked", true, "expectedVersion", 0))
                .when().put(LOCKS.formatted(draftId))
                .then().statusCode(200)
                .body("version", equalTo(1));

        as(token).when().post(DRAFT.formatted(draftId) + "/room-fill")
                .then().statusCode(200)
                .body("applied", equalTo(true))
                .body("assignedLessons", equalTo(2));

        List<Map<String, Object>> lessons = lessonsOf(token, draftId);
        assertThat(lesson(lessons, first).get("roomId")).isNotNull();
        assertThat(lesson(lessons, first).get("locked")).isEqualTo(true);
        assertThat(lesson(lessons, second).get("roomId")).isNotNull();
        assertThat(as(token).when().get(DRAFT.formatted(draftId))
                .then().statusCode(200).extract().jsonPath().getLong("draft.version")).isEqualTo(2);
    }

    @Test
    void staleVersionIsRejectedAndVersionOnlyMovesOnRealWrites() {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);
        long locked = seeded.chineseA();

        // A refused move writes nothing and does not bump the version.
        as(token).body(Map.of("periodId", world.periods().get(0)))
                .when().patch(MOVE.formatted(seeded.draftId(), seeded.mathA()))
                .then().statusCode(200)
                .body("saved", equalTo(false))
                .body("version", equalTo(0));

        as(token).body(Map.of("lessonIds", List.of(locked), "locked", true, "expectedVersion", 0))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(200)
                .body("version", equalTo(1));

        long freeForClassA = world.periods().get(3);
        // Stale version: the request is refused before anything is read or written.
        as(token).body(Map.of("periodId", freeForClassA, "expectedVersion", 0))
                .when().patch(MOVE.formatted(seeded.draftId(), locked))
                .then().statusCode(409)
                .body("message", containsString("请刷新"));
        assertThat(draftLessons.findById(locked).orElseThrow().getPeriodId())
                .isEqualTo(world.periods().get(0));

        // Current version, but the lesson is still locked.
        as(token).body(Map.of("periodId", freeForClassA, "expectedVersion", 1))
                .when().patch(MOVE.formatted(seeded.draftId(), locked))
                .then().statusCode(409)
                .body("message", containsString("已锁定"));

        as(token).body(Map.of("lessonIds", List.of(locked), "locked", false, "expectedVersion", 1))
                .when().put(LOCKS.formatted(seeded.draftId()))
                .then().statusCode(200)
                .body("version", equalTo(2));

        as(token).body(Map.of("periodId", freeForClassA, "expectedVersion", 2))
                .when().patch(MOVE.formatted(seeded.draftId(), locked))
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("version", equalTo(3));
        assertThat(draftLessons.findById(locked).orElseThrow().getPeriodId()).isEqualTo(freeForClassA);
    }

    // ---------- fixtures ----------

    @Test
    void aLockWriteWaitsForTheDraftRowLockInsteadOfRacingIt() throws Exception {
        String token = adminToken();
        World world = world(token);
        Seeded seeded = seedMainDraft(world);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        guard.lockEditable(seeded.draftId(), null);
                        held.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> writer = pool.submit(() -> lockService.setLocked(
                    seeded.draftId(), List.of(seeded.chineseA()), true, null, "test"));
            try {
                writer.get(300, TimeUnit.MILLISECONDS);
                throw new AssertionError("the lock write should wait for the draft row lock");
            } catch (TimeoutException expected) {
                // Still waiting, exactly as the shared write protocol requires.
            }

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            writer.get(5, TimeUnit.SECONDS);
            DraftLesson locked = draftLessons.findById(seeded.chineseA()).orElseThrow();
            assertThat(locked.isLocked()).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private World world(String token) {
        long semesterId = TestData.semester(token);
        Response periodResponse = as(token)
                .body(Map.of("morningCount", 2, "afternoonCount", 0, "eveningCount", 0,
                        "days", List.of(1, 2, 3)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200).extract().response();
        List<Long> periods = periodResponse.jsonPath().getList("id", Long.class);

        String suffix = TestData.suffix();
        long gradeId = TestData.grade(token, semesterId, "锁测年级" + suffix);
        long classA = TestData.adminClass(token, semesterId, gradeId, "锁测一班" + suffix);
        long classB = TestData.adminClass(token, semesterId, gradeId, "锁测二班" + suffix);
        long chinese = TestData.course(token, "锁测语文" + suffix);
        long math = TestData.course(token, "锁测数学" + suffix);
        long english = TestData.course(token, "锁测英语" + suffix);
        long teacherA = TestData.teacher(token, "锁测语文甲" + suffix);
        long teacherB = TestData.teacher(token, "锁测语文乙" + suffix);
        long mathTeacherA = TestData.teacher(token, "锁测数学甲" + suffix);
        long mathTeacherB = TestData.teacher(token, "锁测数学乙" + suffix);
        long englishTeacherA = TestData.teacher(token, "锁测英语甲" + suffix);
        long englishTeacherB = TestData.teacher(token, "锁测英语乙" + suffix);
        return new World(semesterId, periods, classA, classB, chinese, math, english,
                teacherA, teacherB, mathTeacherA, mathTeacherB, englishTeacherA, englishTeacherB,
                TestData.room(token, "锁测房1" + suffix), TestData.room(token, "锁测房2" + suffix),
                TestData.room(token, "锁测房3" + suffix), TestData.room(token, "锁测房4" + suffix),
                TestData.room(token, "锁测房5" + suffix), TestData.room(token, "锁测房6" + suffix));
    }

    private long draft(World world) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(world.semesterId());
        draft.setName("锁定测试 " + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("admin");
        return drafts.save(draft).getId();
    }

    private Seeded seedMainDraft(World world) {
        long draftId = draft(world);
        return new Seeded(
                draftId,
                lesson(world, draftId, 1L, world.classA(), world.chinese(), world.teacherA(),
                        world.periods().get(0), world.room1()),
                lesson(world, draftId, 2L, world.classA(), world.math(), world.mathTeacherA(),
                        world.periods().get(1), world.room2()),
                lesson(world, draftId, 3L, world.classA(), world.english(), world.englishTeacherA(),
                        world.periods().get(2), world.room3()),
                lesson(world, draftId, 4L, world.classB(), world.chinese(), world.teacherB(),
                        world.periods().get(3), world.room4()),
                lesson(world, draftId, 5L, world.classB(), world.math(), world.mathTeacherB(),
                        world.periods().get(4), world.room5()),
                lesson(world, draftId, 6L, world.classB(), world.english(), world.englishTeacherB(),
                        world.periods().get(5), world.room6()));
    }

    private long seedOtherDraft(World world) {
        long draftId = draft(world);
        return lesson(world, draftId, 301L, world.classA(), world.chinese(), world.teacherA(),
                world.periods().get(0), world.room1());
    }

    private long lesson(World world, long draftId, long assignmentId, long classId,
                        long courseId, long teacherId, Long periodId, Long roomId) {
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

    private List<Map<String, Object>> lessonsOf(String token, long draftId) {
        return as(token).when().get(DRAFT.formatted(draftId))
                .then().statusCode(200).extract().jsonPath().getList("lessons");
    }

    private static Map<String, Object> lesson(List<Map<String, Object>> lessons, long lessonId) {
        return lessons.stream()
                .filter(lesson -> ((Number) lesson.get("id")).longValue() == lessonId)
                .findFirst()
                .orElseThrow();
    }
}
