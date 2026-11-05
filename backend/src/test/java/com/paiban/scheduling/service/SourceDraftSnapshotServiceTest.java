package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The snapshot is the only input a continuation solver may read: it must reject every
 * source whose lesson identity does not map exactly onto the current teaching tasks,
 * because a silent mismatch would drop a lock.
 */
class SourceDraftSnapshotServiceTest {

    private final TimetableDraftRepository drafts = mock(TimetableDraftRepository.class);
    private final DraftLessonRepository draftLessons = mock(DraftLessonRepository.class);
    private final AssignmentRepository assignments = mock(AssignmentRepository.class);
    private final TeacherRepository teachers = mock(TeacherRepository.class);
    private final PeriodRepository periods = mock(PeriodRepository.class);

    private final SourceDraftSnapshotService service = new SourceDraftSnapshotService(
            drafts, draftLessons, assignments, teachers, periods, new ObjectMapper());

    @Test
    void capturesRowsIndexesCurrentTeacherLocksAndFingerprint() {
        TimetableDraft draft = draft(5L, 1L, "DRAFT", 3L, "导入草稿");
        when(drafts.findWithLockById(5L)).thenReturn(Optional.of(draft));
        List<Assignment> semesterAssignments = List.of(
                assignment(10L, 1L, 1L, 100L, 1L, 2),
                assignment(11L, 1L, 2L, 200L, 2L, 1));
        when(assignments.findBySemesterIdAndDeletedAtIsNull(1L)).thenReturn(semesterAssignments);
        when(draftLessons.findByDraftIdOrderByIdAsc(5L)).thenReturn(List.of(
                row(10L, 0, 1L, 100L, 1L, 11L, true),
                row(10L, 1, 1L, 100L, 99L, 12L, false),
                row(11L, 0, 2L, 200L, 2L, null, false)));
        when(teachers.findAllById(any())).thenReturn(List.of(teacher(1L), teacher(99L), teacher(2L)));
        when(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L))
                .thenReturn(List.of(period(11L, 1), period(12L, 2)));

        SourceDraftSnapshot snapshot = service.capture(5L, 1L);

        assertThat(snapshot.draftId()).isEqualTo(5L);
        assertThat(snapshot.draftName()).isEqualTo("导入草稿");
        assertThat(snapshot.draftVersion()).isEqualTo(3L);
        assertThat(snapshot.lessons()).hasSize(3);
        assertThat(snapshot.lessons()).extracting(SourceDraftSnapshot.SourceLesson::assignmentId)
                .containsExactly(10L, 10L, 11L);
        assertThat(snapshot.lessons()).extracting(SourceDraftSnapshot.SourceLesson::lessonIndex)
                .containsExactly(0, 1, 0);
        // The row keeps its current (manually exchanged) teacher, not the assignment's.
        assertThat(snapshot.lessons().get(1).teacherId()).isEqualTo(99L);
        assertThat(snapshot.lessons().get(0).locked()).isTrue();
        assertThat(snapshot.lessons().get(1).locked()).isFalse();
        assertThat(service.lockedCount(snapshot)).isEqualTo(1);
        assertThat(snapshot.assignmentFingerprint()).hasSize(64);
        assertThat(snapshot.assignmentFingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void jsonRoundTripKeepsTheFrozenSnapshot() {
        TimetableDraft draft = draft(5L, 1L, "DRAFT", 0L, "草稿");
        when(drafts.findWithLockById(5L)).thenReturn(Optional.of(draft));
        when(assignments.findBySemesterIdAndDeletedAtIsNull(1L))
                .thenReturn(List.of(assignment(10L, 1L, 1L, 100L, 1L, 1)));
        when(draftLessons.findByDraftIdOrderByIdAsc(5L))
                .thenReturn(List.of(row(10L, 0, 1L, 100L, 1L, null, false)));
        when(teachers.findAllById(any())).thenReturn(List.of(teacher(1L)));
        when(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L)).thenReturn(List.of());

        SourceDraftSnapshot snapshot = service.capture(5L, 1L);
        SourceDraftSnapshot restored = service.fromJson(service.toJson(snapshot));

        assertThat(restored).isEqualTo(snapshot);
    }

    @Test
    void missingDraftIsNotFound() {
        when(drafts.findWithLockById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
    }

    @Test
    void draftFromAnotherSemesterIsRejected() {
        when(drafts.findWithLockById(5L)).thenReturn(Optional.of(draft(5L, 2L, "DRAFT", 0L, "别的学期")));

        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("不属于当前学期");
                });
    }

    @Test
    void publishedDraftIsRejected() {
        when(drafts.findWithLockById(5L)).thenReturn(Optional.of(draft(5L, 1L, "PUBLISHED", 0L, "已发布")));

        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("只能基于未发布的草稿");
                });
    }

    @Test
    void emptyDraftIsRejected() {
        when(drafts.findWithLockById(5L)).thenReturn(Optional.of(draft(5L, 1L, "DRAFT", 0L, "空")));
        when(draftLessons.findByDraftIdOrderByIdAsc(5L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("没有课节");
                });
    }

    @Test
    void unknownAssignmentIsRejected() {
        when(drafts.findWithLockById(5L)).thenReturn(Optional.of(draft(5L, 1L, "DRAFT", 0L, "草稿")));
        when(assignments.findBySemesterIdAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(draftLessons.findByDraftIdOrderByIdAsc(5L))
                .thenReturn(List.of(row(10L, 0, 1L, 100L, 1L, null, false)));

        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("不存在的授课任务: 10");
                });
    }

    @Test
    void weeklyLessonCountMismatchIsRejected() {
        prepare(List.of(assignment(10L, 1L, 1L, 100L, 1L, 3)), List.of(
                row(10L, 0, 1L, 100L, 1L, null, false),
                row(10L, 1, 1L, 100L, 1L, null, false)));

        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("需要 3 节，实际 2 节");
                });
    }

    @Test
    void missingOrDuplicateLessonIndexIsRejected() {
        prepare(List.of(assignment(10L, 1L, 1L, 100L, 1L, 2)), List.of(
                row(10L, 0, 1L, 100L, 1L, null, false),
                row(10L, 2, 1L, 100L, 1L, null, false)));
        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("课节序号不完整"));

        prepare(List.of(assignment(10L, 1L, 1L, 100L, 1L, 2)), List.of(
                row(10L, 0, 1L, 100L, 1L, null, false),
                row(10L, 0, 1L, 100L, 1L, null, false)));
        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("课节序号不完整"));
    }

    @Test
    void classAndCourseMustMatchTheAssignment() {
        prepare(List.of(assignment(10L, 1L, 1L, 100L, 1L, 1)),
                List.of(row(10L, 0, 2L, 1L, 100L, null, false)));
        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("班级与授课任务不符"));

        prepare(List.of(assignment(10L, 1L, 1L, 100L, 1L, 1)),
                List.of(row(10L, 0, 1L, 9L, 100L, null, false)));
        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("课程与授课任务不符"));
    }

    @Test
    void unknownTeacherIsRejected() {
        prepare(List.of(assignment(10L, 1L, 1L, 1L, 100L, 1)),
                List.of(row(10L, 0, 1L, 1L, 100L, null, false)));
        when(teachers.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("不存在的教师: 100");
                });
    }

    @Test
    void lockedLessonWithoutPeriodIsRejected() {
        prepare(List.of(assignment(10L, 1L, 1L, 1L, 100L, 2)), List.of(
                row(10L, 0, 1L, 1L, 100L, null, true),
                row(10L, 1, 1L, 1L, 100L, null, false)));

        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("锁定课节没有时间");
                });
    }

    @Test
    void allLockedIsAConflict() {
        prepare(List.of(assignment(10L, 1L, 1L, 1L, 100L, 2)), List.of(
                row(10L, 0, 1L, 1L, 100L, 11L, true),
                row(10L, 1, 1L, 1L, 100L, 12L, true)));
        when(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L))
                .thenReturn(List.of(period(11L, 1), period(12L, 2)));

        assertThatThrownBy(() -> service.capture(5L, 1L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getMessage()).contains("全部课节已锁定");
                });
    }

    @Test
    void requireAssignmentsUnchangedDetectsEveryEdit() {
        TimetableDraft draft = draft(5L, 1L, "DRAFT", 0L, "草稿");
        when(drafts.findWithLockById(5L)).thenReturn(Optional.of(draft));
        when(draftLessons.findByDraftIdOrderByIdAsc(5L))
                .thenReturn(List.of(row(10L, 0, 1L, 1L, 100L, null, false)));
        when(teachers.findAllById(any())).thenReturn(List.of(teacher(100L)));
        when(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L)).thenReturn(List.of());
        when(assignments.findBySemesterIdAndDeletedAtIsNull(1L))
                .thenReturn(List.of(assignment(10L, 1L, 1L, 1L, 100L, 1)));

        SourceDraftSnapshot snapshot = service.capture(5L, 1L);
        service.requireAssignmentsUnchanged(snapshot, 1L);

        List<List<Assignment>> edits = List.of(
                List.of(assignment(10L, 1L, 1L, 1L, 100L, 2)),   // weekly lessons
                List.of(assignment(10L, 1L, 1L, 1L, 101L, 1)),   // teacher
                List.of(assignment(10L, 1L, 2L, 1L, 100L, 1)),   // class
                List.of(assignment(10L, 1L, 1L, 2L, 100L, 1)));  // course
        for (List<Assignment> edited : edits) {
            when(assignments.findBySemesterIdAndDeletedAtIsNull(1L)).thenReturn(edited);
            assertThatThrownBy(() -> service.requireAssignmentsUnchanged(snapshot, 1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("授课任务在优化开始后发生变化");
        }
        when(assignments.findBySemesterIdAndDeletedAtIsNull(1L))
                .thenReturn(List.of(assignment(10L, 1L, 1L, 1L, 100L, 1)));
        service.requireAssignmentsUnchanged(snapshot, 1L);
    }

    private void prepare(List<Assignment> semesterAssignments, List<DraftLesson> rows) {
        when(drafts.findWithLockById(5L)).thenReturn(Optional.of(draft(5L, 1L, "DRAFT", 0L, "草稿")));
        when(assignments.findBySemesterIdAndDeletedAtIsNull(1L)).thenReturn(semesterAssignments);
        when(draftLessons.findByDraftIdOrderByIdAsc(5L)).thenReturn(rows);
        List<Teacher> known = new ArrayList<>();
        rows.forEach(row -> known.add(teacher(row.getTeacherId())));
        when(teachers.findAllById(any())).thenReturn(known);
        when(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(anyLong())).thenReturn(List.of());
    }

    private static TimetableDraft draft(
            long id, long semesterId, String status, long version, String name) {
        TimetableDraft draft = new TimetableDraft();
        draft.setId(id);
        draft.setSemesterId(semesterId);
        draft.setStatus(status);
        draft.setVersion(version);
        draft.setName(name);
        return draft;
    }

    private static Assignment assignment(
            long id, long semesterId, long classId, long courseId, long teacherId, int weeklyLessons) {
        Assignment assignment = new Assignment();
        assignment.setId(id);
        assignment.setSemesterId(semesterId);
        assignment.setAdminClassId(classId);
        assignment.setTeachingClassId(classId);
        assignment.setCourseId(courseId);
        assignment.setTeacherId(teacherId);
        assignment.setWeeklyLessons(weeklyLessons);
        return assignment;
    }

    private static DraftLesson row(
            long assignmentId, int index, long classId, long courseId, long teacherId,
            Long periodId, boolean locked) {
        DraftLesson row = new DraftLesson();
        row.setDraftId(5L);
        row.setAssignmentId(assignmentId);
        row.setLessonIndex(index);
        row.setAdminClassId(classId);
        row.setCourseId(courseId);
        row.setTeacherId(teacherId);
        row.setStudentCount(45);
        row.setPeriodId(periodId);
        row.setLocked(locked);
        return row;
    }

    private static Teacher teacher(long id) {
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setName("教师" + id);
        return teacher;
    }

    private static Period period(long id, int periodNo) {
        Period period = new Period();
        period.setId(id);
        period.setSemesterId(1L);
        period.setDayOfWeek(1);
        period.setPeriodNo(periodNo);
        period.setLabel("第" + periodNo + "节");
        return period;
    }
}
