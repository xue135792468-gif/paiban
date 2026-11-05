package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.foundation.web.FoundationDtos.CourseDto;
import com.paiban.foundation.web.FoundationDtos.TeacherDto;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.solver.Lesson;
import com.paiban.scheduling.solver.PeriodSlot;
import com.paiban.scheduling.solver.TimetableSolution;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The problem builder turns assignments and the warm-start seed into solver lessons. */
class TimetableProblemBuilderTest {

    @Test
    void seedsLessonsFromTheWarmStart() {
        PeriodRepository periodRepository = mock(PeriodRepository.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);
        WarmStartSeedService warmStart = mock(WarmStartSeedService.class);
        FoundationService foundation = mock(FoundationService.class);


        Period first = period(11L, 1);
        Period second = period(12L, 2);
        when(periodRepository.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L))
                .thenReturn(List.of(first, second));


        Assignment assignment = new Assignment();
        assignment.setId(21L);
        assignment.setSemesterId(1L);
        assignment.setAdminClassId(1L);
        assignment.setCourseId(1L);
        assignment.setTeacherId(1L);
        assignment.setWeeklyLessons(2);
        when(assignmentRepository.findBySemesterIdAndDeletedAtIsNull(1L)).thenReturn(List.of(assignment));

        when(foundation.classesById(1L)).thenReturn(Map.of(1L,
                new ClassDto(1L, "八（1）班", 1L, "八年级", null, null, 45, null, true)));
        when(foundation.coursesById()).thenReturn(Map.of(1L,
                new CourseDto(1L, "语文", null, false, null, true)));
        when(foundation.teachersById()).thenReturn(Map.of(1L,
                new TeacherDto(1L, "张三", null, null, null, null, true, null, null)));

        when(warmStart.seed(eq(1L), anyMap(), anyList())).thenReturn(new WarmStartSeedService.WarmStartSeed(
                Map.of(new AssignmentKey(1L, 1L, 1L),
                        new ArrayDeque<>(List.of(slot(11L, 1), slot(12L, 2)))),
                "DRAFT"));

        TimetableProblemBuilder.BuiltProblem built = new TimetableProblemBuilder(
                periodRepository, assignmentRepository, warmStart, foundation, emptyBaselines())
                .buildWithWarmStart(1L, ConstraintSettings.defaults());
        TimetableSolution solution = built.solution();

        assertThat(built.warmStartSource()).isEqualTo("DRAFT");
        assertThat(solution.getLessons()).hasSize(2);
        assertThat(solution.getLessons()).extracting(lesson -> lesson.getPeriod().getId())
                .containsExactlyInAnyOrder(11L, 12L);
        assertThat(solution.getLessons()).extracting(Lesson::getId).containsExactly(1L, 2L);
    }


    @Test
    void passesTheBaselineSourceAndSeededPeriodThrough() {
        PeriodRepository periodRepository = mock(PeriodRepository.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);
        WarmStartSeedService warmStart = mock(WarmStartSeedService.class);
        FoundationService foundation = mock(FoundationService.class);


        Period first = period(11L, 1);
        Period second = period(12L, 2);
        when(periodRepository.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L))
                .thenReturn(List.of(first, second));


        Assignment assignment = new Assignment();
        assignment.setId(21L);
        assignment.setSemesterId(1L);
        assignment.setAdminClassId(1L);
        assignment.setCourseId(1L);
        assignment.setTeacherId(1L);
        assignment.setWeeklyLessons(1);
        when(assignmentRepository.findBySemesterIdAndDeletedAtIsNull(1L)).thenReturn(List.of(assignment));

        when(foundation.classesById(1L)).thenReturn(Map.of(1L,
                new ClassDto(1L, "八（1）班", 1L, "八年级", null, null, 45, null, true)));
        when(foundation.coursesById()).thenReturn(Map.of(1L,
                new CourseDto(1L, "语文", null, false, null, true)));
        when(foundation.teachersById()).thenReturn(Map.of(1L,
                new TeacherDto(1L, "张三", null, null, null, null, true, null, null)));

        when(warmStart.seed(eq(1L), anyMap(), anyList())).thenReturn(new WarmStartSeedService.WarmStartSeed(
                Map.of(new AssignmentKey(1L, 1L, 1L),
                        new ArrayDeque<>(List.of(slot(11L, 1)))),
                "BASELINE"));

        TimetableProblemBuilder.BuiltProblem built = new TimetableProblemBuilder(
                periodRepository, assignmentRepository, warmStart, foundation, emptyBaselines())
                .buildWithWarmStart(1L, ConstraintSettings.defaults());
        TimetableSolution solution = built.solution();

        assertThat(built.warmStartSource()).isEqualTo("BASELINE");
        assertThat(solution.getLessons()).hasSize(1);
        Lesson lesson = solution.getLessons().get(0);
        assertThat(lesson.getPeriod().getId()).isEqualTo(11L);
    }

    @Test
    void blankSemesterReportsNoneAndLeavesPeriodsUnassigned() {
        PeriodRepository periodRepository = mock(PeriodRepository.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);
        WarmStartSeedService warmStart = mock(WarmStartSeedService.class);
        FoundationService foundation = mock(FoundationService.class);


        when(periodRepository.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L))
                .thenReturn(List.of(period(11L, 1), period(12L, 2)));

        Assignment assignment = assignment(21L, 1L, 1L, 1L, 1);
        when(assignmentRepository.findBySemesterIdAndDeletedAtIsNull(1L)).thenReturn(List.of(assignment));

        when(foundation.classesById(1L)).thenReturn(Map.of(1L,
                new ClassDto(1L, "八（1）班", 1L, "八年级", null, null, 45, null, true)));
        when(foundation.coursesById()).thenReturn(Map.of(1L,
                new CourseDto(1L, "语文", null, false, null, true)));
        when(foundation.teachersById()).thenReturn(Map.of(1L,
                new TeacherDto(1L, "张三", null, null, null, null, true, null, null)));

        when(warmStart.seed(eq(1L), anyMap(), anyList()))
                .thenReturn(new WarmStartSeedService.WarmStartSeed(Map.of(), "NONE"));

        TimetableProblemBuilder.BuiltProblem built = new TimetableProblemBuilder(
                periodRepository, assignmentRepository, warmStart, foundation, emptyBaselines())
                .buildWithWarmStart(1L, ConstraintSettings.defaults());

        assertThat(built.warmStartSource()).isEqualTo("NONE");
        assertThat(built.solution().getLessons()).hasSize(1);
        assertThat(built.solution().getLessons().get(0).getPeriod()).isNull();
    }

    @Test
    void buildFromSourceSeedsTheFrozenArrangementAndPinsLocksWithTheSourceTeacher() {
        PeriodRepository periodRepository = mock(PeriodRepository.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);
        WarmStartSeedService warmStart = mock(WarmStartSeedService.class);
        FoundationService foundation = mock(FoundationService.class);

        when(periodRepository.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L))
                .thenReturn(List.of(period(11L, 1), period(12L, 2)));


        when(assignmentRepository.findBySemesterIdAndDeletedAtIsNull(1L))
                .thenReturn(List.of(assignment(21L, 1L, 1L, 1L, 2)));

        when(foundation.classesById(1L)).thenReturn(Map.of(1L,
                new ClassDto(1L, "八（1）班", 1L, "八年级", null, null, 45, null, true)));
        when(foundation.coursesById()).thenReturn(Map.of(1L,
                new CourseDto(1L, "语文", null, false, null, true)));
        when(foundation.teachersById()).thenReturn(Map.of(
                1L, new TeacherDto(1L, "张三", null, null, null, null, true, null, null),
                99L, new TeacherDto(99L, "李四", null, null, null, null, true, null, null)));

        SourceDraftSnapshot snapshot = new SourceDraftSnapshot(9L, "来源草稿", 4L, "fingerprint",
                List.of(
                        new SourceDraftSnapshot.SourceLesson(21L, 0, 1L, 1L, 99L, 45, 11L, true),
                        new SourceDraftSnapshot.SourceLesson(21L, 1, 1L, 1L, 1L, 45, 12L, false)));

        TimetableProblemBuilder.BuiltProblem built = new TimetableProblemBuilder(
                periodRepository, assignmentRepository, warmStart, foundation, emptyBaselines())
                .buildFromSource(1L, ConstraintSettings.defaults(), snapshot);

        assertThat(built.warmStartSource()).isEqualTo("SOURCE_DRAFT");
        assertThat(built.solution().getLessons()).hasSize(2);
        Lesson locked = built.solution().getLessons().get(0);
        assertThat(locked.isLocked()).isTrue();
        assertThat(locked.getLockedPeriodId()).isEqualTo(11L);
        assertThat(locked.getPeriod().getId()).isEqualTo(11L);
        // The current draft teacher wins over the assignment's teacher (manual exchange).
        assertThat(locked.getTeacherId()).isEqualTo(99L);

        Lesson free = built.solution().getLessons().get(1);
        assertThat(free.isLocked()).isFalse();
        assertThat(free.getLockedPeriodId()).isNull();
        assertThat(free.getPeriod().getId()).isEqualTo(12L);
        assertThat(free.getTeacherId()).isEqualTo(1L);
    }

    @Test
    void buildFromSourceKeepsLockedSlotEvenWhenBlockedButSkipsBlockedFreeSlot() {
        PeriodRepository periodRepository = mock(PeriodRepository.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);
        WarmStartSeedService warmStart = mock(WarmStartSeedService.class);
        FoundationService foundation = mock(FoundationService.class);

        when(periodRepository.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L))
                .thenReturn(List.of(period(11L, 1), period(12L, 2)));
        when(assignmentRepository.findBySemesterIdAndDeletedAtIsNull(1L))
                .thenReturn(List.of(assignment(21L, 1L, 1L, 1L, 2)));
        when(foundation.classesById(1L)).thenReturn(Map.of(1L,
                new ClassDto(1L, "八（1）班", 1L, "八年级", null, null, 45, null, true)));
        when(foundation.coursesById()).thenReturn(Map.of(1L,
                new CourseDto(1L, "语文", null, false, null, true)));
        when(foundation.teachersById()).thenReturn(Map.of(1L,
                new TeacherDto(1L, "张三", null, null, null, null, true, null, null)));

        SourceDraftSnapshot snapshot = new SourceDraftSnapshot(9L, "来源草稿", 0L, "fingerprint",
                List.of(
                        new SourceDraftSnapshot.SourceLesson(21L, 0, 1L, 1L, 1L, 45, 11L, false),
                        new SourceDraftSnapshot.SourceLesson(21L, 1, 1L, 1L, 1L, 45, 12L, true)));
        ConstraintSettings settings = classUnavailableSettings(1L, 1, 1, 2);

        TimetableProblemBuilder.BuiltProblem built = new TimetableProblemBuilder(
                periodRepository, assignmentRepository, warmStart, foundation, emptyBaselines())
                .buildFromSource(1L, settings, snapshot);

        assertThat(built.solution().getLessons().get(0).getPeriod()).isNull();
        Lesson locked = built.solution().getLessons().get(1);
        assertThat(locked.getPeriod().getId()).isEqualTo(12L);
        assertThat(locked.getLockedPeriodId()).isEqualTo(12L);
    }

    private static ConstraintSettings classUnavailableSettings(
            long classId, int dayOfWeek, int... periodNos) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(ConstraintType.CLASS_UNAVAILABLE.name());
        config.setEnabled(true);
        config.setHard(true);
        config.setWeight(100);
        config.setParamsJson("{}");
        List<ConstraintEntry> entries = new java.util.ArrayList<>();
        for (int periodNo : periodNos) {
            ConstraintEntry entry = new ConstraintEntry();
            entry.setId((long) periodNo);
            entry.setType(ConstraintType.CLASS_UNAVAILABLE.name());
            entry.setEnabled(true);
            entry.setGroupKey("test-unavailable");
            entry.setParamsJson("{\"dayOfWeek\":" + dayOfWeek + ",\"periodNo\":" + periodNo
                    + ",\"classId\":" + classId + "}");
            entries.add(entry);
        }
        return ConstraintSettings.from(List.of(config), entries, new ObjectMapper());
    }

    private static SemesterBaselineQuery emptyBaselines() {
        SemesterBaselineQuery baselines = mock(SemesterBaselineQuery.class);
        when(baselines.publishedLessons(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());
        return baselines;
    }

    private static Assignment assignment(
            long id, long classId, long courseId, long teacherId, int weeklyLessons) {
        Assignment assignment = new Assignment();
        assignment.setId(id);
        assignment.setSemesterId(1L);
        assignment.setAdminClassId(classId);
        assignment.setCourseId(courseId);
        assignment.setTeacherId(teacherId);
        assignment.setWeeklyLessons(weeklyLessons);
        return assignment;
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

    private static PeriodSlot slot(long id, int periodNo) {
        return new PeriodSlot(id, 1, periodNo, periodNo == 1, false);
    }
}
