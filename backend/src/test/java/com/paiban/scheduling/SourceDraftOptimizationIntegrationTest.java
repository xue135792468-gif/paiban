package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.SchedulingJobRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 07: "continue optimising from this draft". An explicit source must seed the new
 * draft from that source, carry its locks (by assignment + lesson index), keep the source
 * draft untouched, and report the real publish verdict instead of claiming success.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SourceDraftOptimizationIntegrationTest extends ApiIntegrationTest {

    private static final String JOBS = "/api/scheduling-jobs";
    private static final String DRAFT = "/api/timetable-drafts/%d";
    private static final String LOCKS = "/api/timetable-drafts/%d/lessons/locks";

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private SchedulingJobRepository jobs;

    private record World(long semesterId, List<Long> periods, long classA, long classB,
                         long chinese, long math, long teacherA, long teacherB, long teacherC,
                         long room1, long room2, long room3) {}

    @Test
    void explicitSourceKeepsTheLockAndLeavesTheOriginalDraftUntouched() {
        String token = adminToken();
        World world = world(token);
        long assignmentId = assignment(world.semesterId(), world.classA(), world.chinese(),
                world.teacherA(), 3);

        long draftA = draft(world.semesterId(), "草稿A");
        seedRows(draftA, assignmentId, world.classA(), world.chinese(), world.teacherA(),
                world.room1(), List.of(world.periods().get(0), world.periods().get(1),
                        world.periods().get(2)));
        long draftB = draft(world.semesterId(), "草稿B");
        seedRows(draftB, assignmentId, world.classA(), world.chinese(), world.teacherA(),
                world.room1(), List.of(world.periods().get(3), world.periods().get(4),
                        world.periods().get(5)));
        lock(token, draftB, List.of(rowId(draftB, assignmentId, 1)));

        long jobId = startJob(token, world.semesterId(), draftB, 5);
        Map<String, Object> job = awaitJob(token, jobId, 90);

        assertThat(job.get("status")).isEqualTo("COMPLETED");
        assertThat(job.get("warmStartSource")).isEqualTo("SOURCE_DRAFT");
        assertThat(number(job.get("sourceDraftId"))).isEqualTo(draftB);
        assertThat(number(job.get("sourceDraftVersion"))).isEqualTo(1L);
        assertThat(number(job.get("inheritedLockCount"))).isEqualTo(1L);
        assertThat(job.get("resultPublishable")).isEqualTo(true);

        long newDraftId = number(job.get("draftId"));
        assertThat(newDraftId).isNotEqualTo(draftB).isNotEqualTo(draftA);

        DraftLesson inherited = row(newDraftId, assignmentId, 1);
        assertThat(inherited.getPeriodId()).isEqualTo(world.periods().get(4));
        assertThat(inherited.getTeacherId()).isEqualTo(world.teacherA());
        assertThat(inherited.isLocked()).isTrue();
        assertThat(row(newDraftId, assignmentId, 0).isLocked()).isFalse();
        assertThat(row(newDraftId, assignmentId, 2).isLocked()).isFalse();
        assertThat(draftLessons.findByDraftIdOrderByIdAsc(newDraftId))
                .allSatisfy(lesson -> assertThat(lesson.getPeriodId()).isNotNull());

        // The source draft keeps its arrangement, its lock and its version.
        assertThat(drafts.findById(draftB).orElseThrow().getVersion()).isEqualTo(1L);
        List<DraftLesson> sourceRows = draftLessons.findByDraftIdOrderByIdAsc(draftB);
        assertThat(sourceRows).extracting(DraftLesson::getPeriodId).containsExactly(
                world.periods().get(3), world.periods().get(4), world.periods().get(5));
        assertThat(sourceRows).extracting(DraftLesson::isLocked)
                .containsExactly(false, true, false);
    }

    @Test
    void onlyTheSelectedLessonIndexInheritsTheLock() {
        String token = adminToken();
        World world = world(token);
        long assignmentId = assignment(world.semesterId(), world.classA(), world.chinese(),
                world.teacherA(), 3);
        long source = draft(world.semesterId(), "来源");
        seedRows(source, assignmentId, world.classA(), world.chinese(), world.teacherA(),
                world.room1(), List.of(world.periods().get(0), world.periods().get(1),
                        world.periods().get(2)));
        lock(token, source, List.of(rowId(source, assignmentId, 2)));

        long jobId = startJob(token, world.semesterId(), source, 5);
        Map<String, Object> job = awaitJob(token, jobId, 90);
        long newDraftId = number(job.get("draftId"));

        assertThat(job.get("status")).isEqualTo("COMPLETED");
        assertThat(number(job.get("inheritedLockCount"))).isEqualTo(1L);
        assertThat(row(newDraftId, assignmentId, 2).isLocked()).isTrue();
        assertThat(row(newDraftId, assignmentId, 2).getPeriodId())
                .isEqualTo(world.periods().get(2));
        assertThat(row(newDraftId, assignmentId, 0).isLocked()).isFalse();
        assertThat(row(newDraftId, assignmentId, 1).isLocked()).isFalse();
    }

    @Test
    void omittingTheSourceKeepsTheLegacyDefaultPolicy() {
        String token = adminToken();
        World world = world(token);
        long assignmentId = assignment(world.semesterId(), world.classA(), world.chinese(),
                world.teacherA(), 3);
        long completeDraft = draft(world.semesterId(), "完整草稿");
        seedRows(completeDraft, assignmentId, world.classA(), world.chinese(), world.teacherA(),
                world.room1(), List.of(world.periods().get(0), world.periods().get(1),
                        world.periods().get(2)));

        long jobId = startJob(token, world.semesterId(), null, 5);
        Map<String, Object> job = awaitJob(token, jobId, 90);

        assertThat(job.get("status")).isEqualTo("COMPLETED");
        assertThat(job.get("warmStartSource")).isEqualTo("DRAFT");
        assertThat(job.get("sourceDraftId")).isNull();
        assertThat(job.get("sourceDraftVersion")).isNull();
        assertThat(job.get("inheritedLockCount")).isNull();
    }

    @Test
    void aManuallyExchangedTeacherIsKeptInsteadOfTheAssignmentTeacher() {
        String token = adminToken();
        World world = world(token);
        long assignmentId = assignment(world.semesterId(), world.classA(), world.chinese(),
                world.teacherA(), 3);
        long source = draft(world.semesterId(), "换过老师");
        seedRows(source, assignmentId, world.classA(), world.chinese(), world.teacherC(),
                world.room1(), List.of(world.periods().get(0), world.periods().get(1),
                        world.periods().get(2)));
        lock(token, source, List.of(rowId(source, assignmentId, 0)));

        long jobId = startJob(token, world.semesterId(), source, 5);
        Map<String, Object> job = awaitJob(token, jobId, 90);
        long newDraftId = number(job.get("draftId"));

        assertThat(draftLessons.findByDraftIdOrderByIdAsc(newDraftId))
                .allSatisfy(lesson -> assertThat(lesson.getTeacherId()).isEqualTo(world.teacherC()));
        assertThat(row(newDraftId, assignmentId, 0).getPeriodId())
                .isEqualTo(world.periods().get(0));
    }

    @Test
    void invalidSourcesAreRejectedBeforeAJobExists() {
        String token = adminToken();
        World world = world(token);
        long assignmentId = assignment(world.semesterId(), world.classA(), world.chinese(),
                world.teacherA(), 3);

        long otherSemester = otherSemester(token);
        long otherSemesterDraft = draft(otherSemester, "别的学期");
        expectStartFailure(token, world.semesterId(), otherSemesterDraft, 400, "不属于当前学期");

        long published = draft(world.semesterId(), "已发布");
        TimetableDraft publishedDraft = drafts.findById(published).orElseThrow();
        publishedDraft.setStatus("PUBLISHED");
        drafts.save(publishedDraft);
        expectStartFailure(token, world.semesterId(), published, 400, "只能基于未发布的草稿");

        long empty = draft(world.semesterId(), "空草稿");
        expectStartFailure(token, world.semesterId(), empty, 400, "没有课节");

        long partial = draft(world.semesterId(), "只有两节");
        seedRows(partial, assignmentId, world.classA(), world.chinese(), world.teacherA(),
                world.room1(), List.of(world.periods().get(0), world.periods().get(1)));
        expectStartFailure(token, world.semesterId(), partial, 400, "来源草稿与授课任务不一致");

        assertThat(jobs.count()).isZero();
    }

    @Test
    void aFullyLockedSourceIsRefusedWithoutCreatingAJob() {
        String token = adminToken();
        World world = world(token);
        long assignmentId = assignment(world.semesterId(), world.classA(), world.chinese(),
                world.teacherA(), 3);
        long source = draft(world.semesterId(), "全锁");
        seedRows(source, assignmentId, world.classA(), world.chinese(), world.teacherA(),
                world.room1(), List.of(world.periods().get(0), world.periods().get(1),
                        world.periods().get(2)));
        lock(token, source, List.of(
                rowId(source, assignmentId, 0),
                rowId(source, assignmentId, 1),
                rowId(source, assignmentId, 2)));

        as(token).queryParam("semesterId", world.semesterId())
                .body(Map.of("maxSeconds", 5, "sourceDraftId", source))
                .when().post(JOBS)
                .then().statusCode(409)
                .body("message", containsString("全部课节已锁定"));

        assertThat(jobs.count()).isZero();
    }

    @Test
    void aLockedSlotThatConflictsWithARuleIsReportedAsNotPublishable() {
        String token = adminToken();
        World world = world(token);
        long lockedA = assignment(world.semesterId(), world.classA(), world.chinese(),
                world.teacherA(), 1);
        long lockedB = assignment(world.semesterId(), world.classB(), world.math(),
                world.teacherA(), 1);
        // The third lesson stays unlocked so the source is not "everything locked"; the
        // solver may move it but cannot untangle two locked lessons of the same teacher.
        long free = assignment(world.semesterId(), world.classA(), world.math(),
                world.teacherB(), 1);

        long source = draft(world.semesterId(), "锁死的冲突");
        seedRows(source, lockedA, world.classA(), world.chinese(), world.teacherA(),
                world.room1(), List.of(world.periods().get(0)));
        seedRows(source, lockedB, world.classB(), world.math(), world.teacherA(),
                world.room2(), List.of(world.periods().get(0)));
        seedRows(source, free, world.classA(), world.math(), world.teacherB(),
                world.room3(), List.of(world.periods().get(1)));
        lock(token, source, List.of(
                rowId(source, lockedA, 0), rowId(source, lockedB, 0)));

        long jobId = startJob(token, world.semesterId(), source, 5);
        Map<String, Object> job = awaitJob(token, jobId, 90);

        assertThat(job.get("status")).isEqualTo("COMPLETED");
        assertThat(job.get("resultPublishable")).isEqualTo(false);
        assertThat((List<?>) job.get("resultBlockers")).isNotEmpty();
        assertThat(String.valueOf(job.get("resultBlockers"))).contains("冲突");

        long newDraftId = number(job.get("draftId"));
        assertThat(row(newDraftId, lockedA, 0).getPeriodId()).isEqualTo(world.periods().get(0));
        assertThat(row(newDraftId, lockedB, 0).getPeriodId()).isEqualTo(world.periods().get(0));
        assertThat(row(newDraftId, lockedA, 0).isLocked()).isTrue();
        assertThat(row(newDraftId, lockedB, 0).isLocked()).isTrue();
    }

    @Test
    void cancellingASourceJobLeavesNoNewDraftAndTheSourceUnchanged() {
        String token = adminToken();
        World world = world(token);
        long assignmentId = assignment(world.semesterId(), world.classA(), world.chinese(),
                world.teacherA(), 3);
        long source = draft(world.semesterId(), "取消来源");
        seedRows(source, assignmentId, world.classA(), world.chinese(), world.teacherA(),
                world.room1(), List.of(world.periods().get(0), world.periods().get(1),
                        world.periods().get(2)));

        long jobId = startJob(token, world.semesterId(), source, 60);
        // One solver thread per semester: the second submission is refused.
        as(token).queryParam("semesterId", world.semesterId())
                .body(Map.of("maxSeconds", 60, "sourceDraftId", source))
                .when().post(JOBS)
                .then().statusCode(409);

        as(token).when().delete(JOBS + "/" + jobId)
                .then().statusCode(200)
                .body("status", equalTo("CANCELLED"));
        Map<String, Object> job = awaitJob(token, jobId, 30);

        assertThat(job.get("status")).isEqualTo("CANCELLED");
        assertThat(drafts.count()).isEqualTo(1L);
        assertThat(drafts.findById(source).orElseThrow().getVersion()).isZero();
    }

    // ---------- fixtures ----------

    private World world(String token) {
        long semesterId = TestData.semester(token);
        Response periodResponse = as(token)
                .body(Map.of("morningCount", 2, "afternoonCount", 0, "eveningCount", 0,
                        "days", List.of(1, 2, 3)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200).extract().response();
        List<Long> periods = periodResponse.jsonPath().getList("id", Long.class);

        String suffix = TestData.suffix();
        long gradeId = TestData.grade(token, semesterId, "来源年级" + suffix);
        long classA = TestData.adminClass(token, semesterId, gradeId, "来源一班" + suffix);
        long classB = TestData.adminClass(token, semesterId, gradeId, "来源二班" + suffix);
        long chinese = TestData.course(token, "来源语文" + suffix);
        long math = TestData.course(token, "来源数学" + suffix);
        long teacherA = TestData.teacher(token, "来源甲" + suffix);
        long teacherB = TestData.teacher(token, "来源乙" + suffix);
        long teacherC = TestData.teacher(token, "来源丙" + suffix);
        return new World(semesterId, periods, classA, classB, chinese, math,
                teacherA, teacherB, teacherC,
                TestData.room(token, "来源房1" + suffix),
                TestData.room(token, "来源房2" + suffix),
                TestData.room(token, "来源房3" + suffix));
    }

    private long assignment(long semesterId, long classId, long courseId, long teacherId, int weekly) {
        Assignment assignment = new Assignment();
        assignment.setSemesterId(semesterId);
        assignment.setAdminClassId(classId);
        assignment.setTeachingClassId(classId);
        assignment.setCourseId(courseId);
        assignment.setTeacherId(teacherId);
        assignment.setWeeklyLessons(weekly);
        return assignments.save(assignment).getId();
    }

    /** A second real semester that is not made current, so its draft passes the FK. */
    private long otherSemester(String token) {
        return as(token)
                .body(Map.of(
                        "name", "另一学期-" + TestData.suffix(),
                        "startDate", "2025-09-01",
                        "endDate", "2026-01-20"))
                .when().post("/api/semesters")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");
    }

    private long draft(long semesterId, String name) {        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName(name + " " + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("admin");
        return drafts.save(draft).getId();
    }

    private void seedRows(long draftId, long assignmentId, long classId, long courseId,
                          long teacherId, long roomId, List<Long> periodIds) {
        List<DraftLesson> rows = new ArrayList<>();
        for (int index = 0; index < periodIds.size(); index++) {
            DraftLesson row = new DraftLesson();
            row.setDraftId(draftId);
            row.setAssignmentId(assignmentId);
            row.setLessonIndex(index);
            row.setCourseId(courseId);
            row.setTeacherId(teacherId);
            row.setAdminClassId(classId);
            row.setStudentCount(45);
            row.setPeriodId(periodIds.get(index));
            row.setRoomId(roomId);
            rows.add(row);
        }
        draftLessons.saveAll(rows);
    }

    private long rowId(long draftId, long assignmentId, int index) {
        return row(draftId, assignmentId, index).getId();
    }

    private DraftLesson row(long draftId, long assignmentId, int index) {
        return draftLessons.findByDraftIdOrderByIdAsc(draftId).stream()
                .filter(lesson -> lesson.getAssignmentId().equals(assignmentId)
                        && lesson.getLessonIndex() == index)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "缺少课节 " + assignmentId + "/" + index + " in draft " + draftId));
    }

    private void lock(String token, long draftId, List<Long> lessonIds) {
        as(token).body(Map.of("lessonIds", lessonIds, "locked", true))
                .when().put(LOCKS.formatted(draftId))
                .then().statusCode(200);
    }

    private long startJob(String token, long semesterId, Long sourceDraftId, int maxSeconds) {
        Map<String, Object> body = new HashMap<>();
        body.put("maxSeconds", maxSeconds);
        if (sourceDraftId != null) {
            body.put("sourceDraftId", sourceDraftId);
        }
        return as(token).queryParam("semesterId", semesterId)
                .body(body)
                .when().post(JOBS)
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");
    }

    private void expectStartFailure(
            String token, long semesterId, long sourceDraftId, int status, String message) {
        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("maxSeconds", 5, "sourceDraftId", sourceDraftId))
                .when().post(JOBS)
                .then().statusCode(status)
                .body("message", containsString(message));
    }

    private Map<String, Object> awaitJob(String token, long jobId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> job = as(token).when().get(JOBS + "/" + jobId)
                    .then().statusCode(200).extract().jsonPath().getMap("$");
            String status = String.valueOf(job.get("status"));
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(status)) {
                return job;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("排课任务超时未结束: " + jobId);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
