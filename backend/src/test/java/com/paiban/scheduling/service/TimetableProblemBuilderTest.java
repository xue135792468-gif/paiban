package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.SemesterRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.foundation.web.FoundationDtos.CourseDto;
import com.paiban.foundation.web.FoundationDtos.TeacherDto;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.solver.Lesson;
import com.paiban.scheduling.solver.TimetableSolution;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The problem builder seeds a new semester from its complete import draft. */
class TimetableProblemBuilderTest {

    @Test
    void seedsPeriodsFromTheOldestCompleteDraftAndPinsTheClassRoom() {
        PeriodRepository periodRepository = mock(PeriodRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);
        SemesterRepository semesterRepository = mock(SemesterRepository.class);
        SemesterBaselineQuery baselines = mock(SemesterBaselineQuery.class);
        TimetableDraftRepository draftRepository = mock(TimetableDraftRepository.class);
        DraftLessonRepository draftLessonRepository = mock(DraftLessonRepository.class);
        FoundationService foundation = mock(FoundationService.class);

        Semester semester = new Semester();
        semester.setId(1L);
        semester.setName("2025-2026学年第一学期");
        when(semesterRepository.findById(1L)).thenReturn(Optional.of(semester));

        Period first = period(11L, 1);
        Period second = period(12L, 2);
        when(periodRepository.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L))
                .thenReturn(List.of(first, second));

        Room room = new Room();
        room.setId(7L);
        room.setName("2025-2026学年第一学期-八年级八（1）班-固定教室");
        room.setType("NORMAL");
        room.setCapacity(45);
        when(roomRepository.findAllByOrderByNameAsc()).thenReturn(List.of(room));

        Assignment assignment = new Assignment();
        assignment.setId(21L);
        assignment.setSemesterId(1L);
        assignment.setAdminClassId(1L);
        assignment.setCourseId(1L);
        assignment.setTeacherId(1L);
        assignment.setWeeklyLessons(2);
        when(assignmentRepository.findBySemesterId(1L)).thenReturn(List.of(assignment));

        when(foundation.classesById(1L)).thenReturn(Map.of(1L,
                new ClassDto(1L, "八（1）班", 1L, "八年级", null, null, 45, null)));
        when(foundation.coursesById()).thenReturn(Map.of(1L,
                new CourseDto(1L, "语文", null, false, false, null, null)));
        when(foundation.teachersById()).thenReturn(Map.of(1L,
                new TeacherDto(1L, "张三", null, null, null, null, true, null, null, null, null)));

        when(baselines.publishedLessons(1L)).thenReturn(List.of());

        TimetableDraft importDraft = new TimetableDraft();
        importDraft.setId(5L);
        importDraft.setSemesterId(1L);
        importDraft.setCreatedAt(Instant.now());
        when(draftRepository.findBySemesterIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(importDraft));
        when(draftLessonRepository.findByDraftIdOrderByIdAsc(5L)).thenReturn(List.of(
                row(1L, 11L), row(2L, 12L)));

        TimetableSolution solution = new TimetableProblemBuilder(
                periodRepository, roomRepository, assignmentRepository, semesterRepository,
                baselines, draftRepository, draftLessonRepository, foundation)
                .build(1L);

        assertThat(solution.getLessons()).hasSize(2);
        assertThat(solution.getLessons()).extracting(lesson -> lesson.getPeriod().getId())
                .containsExactlyInAnyOrder(11L, 12L);
        assertThat(solution.getLessons()).allSatisfy(lesson -> {
            assertThat(lesson.getFixedRoom()).isNotNull();
            assertThat(lesson.getFixedRoom().getId()).isEqualTo(7L);
            assertThat(lesson.getRoom().getId()).isEqualTo(7L);
        });
        assertThat(solution.getLessons()).extracting(Lesson::getId).containsExactly(1L, 2L);
    }

    @Test
    void normalRoomTypeStillPinsTheClassOwnRoom() {
        PeriodRepository periodRepository = mock(PeriodRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);
        SemesterRepository semesterRepository = mock(SemesterRepository.class);
        SemesterBaselineQuery baselines = mock(SemesterBaselineQuery.class);
        TimetableDraftRepository draftRepository = mock(TimetableDraftRepository.class);
        DraftLessonRepository draftLessonRepository = mock(DraftLessonRepository.class);
        FoundationService foundation = mock(FoundationService.class);

        Semester semester = new Semester();
        semester.setId(1L);
        semester.setName("2025-2026学年第一学期");
        when(semesterRepository.findById(1L)).thenReturn(Optional.of(semester));

        when(periodRepository.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L))
                .thenReturn(List.of());

        Room room = new Room();
        room.setId(7L);
        room.setName("2025-2026学年第一学期-八年级八（1）班-固定教室");
        room.setType("NORMAL");
        room.setCapacity(45);
        when(roomRepository.findAllByOrderByNameAsc()).thenReturn(List.of(room));

        Assignment assignment = new Assignment();
        assignment.setId(21L);
        assignment.setSemesterId(1L);
        assignment.setAdminClassId(1L);
        assignment.setCourseId(1L);
        assignment.setTeacherId(1L);
        assignment.setWeeklyLessons(1);
        when(assignmentRepository.findBySemesterId(1L)).thenReturn(List.of(assignment));

        when(foundation.classesById(1L)).thenReturn(Map.of(1L,
                new ClassDto(1L, "八（1）班", 1L, "八年级", null, null, 45, null)));
        // NORMAL is the ordinary classroom, so the class room stays pinned.
        when(foundation.coursesById()).thenReturn(Map.of(1L,
                new CourseDto(1L, "语文", null, false, false, "NORMAL", null)));
        when(foundation.teachersById()).thenReturn(Map.of(1L,
                new TeacherDto(1L, "张三", null, null, null, null, true, null, null, null, null)));

        when(baselines.publishedLessons(1L)).thenReturn(List.of());
        when(draftRepository.findBySemesterIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        TimetableSolution solution = new TimetableProblemBuilder(
                periodRepository, roomRepository, assignmentRepository, semesterRepository,
                baselines, draftRepository, draftLessonRepository, foundation)
                .build(1L);

        assertThat(solution.getLessons()).hasSize(1);
        Lesson lesson = solution.getLessons().get(0);
        assertThat(lesson.getFixedRoom().getId()).isEqualTo(7L);
        assertThat(lesson.getRoom().getId()).isEqualTo(7L);
    }

    @Test
    void publishedBaselineSeedsThePeriodAndKeepsTheClassRoom() {
        PeriodRepository periodRepository = mock(PeriodRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);
        SemesterRepository semesterRepository = mock(SemesterRepository.class);
        SemesterBaselineQuery baselines = mock(SemesterBaselineQuery.class);
        TimetableDraftRepository draftRepository = mock(TimetableDraftRepository.class);
        DraftLessonRepository draftLessonRepository = mock(DraftLessonRepository.class);
        FoundationService foundation = mock(FoundationService.class);

        Semester semester = new Semester();
        semester.setId(1L);
        semester.setName("2025-2026学年第一学期");
        when(semesterRepository.findById(1L)).thenReturn(Optional.of(semester));

        Period first = period(11L, 1);
        Period second = period(12L, 2);
        when(periodRepository.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(1L))
                .thenReturn(List.of(first, second));

        Room room = new Room();
        room.setId(7L);
        room.setName("2025-2026学年第一学期-八年级八（1）班-固定教室");
        room.setType("NORMAL");
        room.setCapacity(45);
        when(roomRepository.findAllByOrderByNameAsc()).thenReturn(List.of(room));

        Assignment assignment = new Assignment();
        assignment.setId(21L);
        assignment.setSemesterId(1L);
        assignment.setAdminClassId(1L);
        assignment.setCourseId(1L);
        assignment.setTeacherId(1L);
        assignment.setWeeklyLessons(1);
        when(assignmentRepository.findBySemesterId(1L)).thenReturn(List.of(assignment));

        when(foundation.classesById(1L)).thenReturn(Map.of(1L,
                new ClassDto(1L, "八（1）班", 1L, "八年级", null, null, 45, null)));
        when(foundation.coursesById()).thenReturn(Map.of(1L,
                new CourseDto(1L, "语文", null, false, false, "NORMAL", null)));
        when(foundation.teachersById()).thenReturn(Map.of(1L,
                new TeacherDto(1L, "张三", null, null, null, null, true, null, null, null, null)));

        when(baselines.publishedLessons(1L)).thenReturn(List.of(
                new SemesterBaselineQuery.PublishedLesson(1L, 1L, 1L, 11L)));
        when(draftRepository.findBySemesterIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        TimetableSolution solution = new TimetableProblemBuilder(
                periodRepository, roomRepository, assignmentRepository, semesterRepository,
                baselines, draftRepository, draftLessonRepository, foundation)
                .build(1L);

        assertThat(solution.getLessons()).hasSize(1);
        Lesson lesson = solution.getLessons().get(0);
        assertThat(lesson.getPeriod().getId()).isEqualTo(11L);
        assertThat(lesson.getFixedRoom().getId()).isEqualTo(7L);
    }

    private static Period period(long id, int periodNo) {
        Period period = new Period();
        period.setId(id);
        period.setSemesterId(1L);
        period.setDayOfWeek(1);
        period.setPeriodNo(periodNo);
        period.setLabel("第" + periodNo + "节");
        period.setSection(PeriodSection.MORNING);
        return period;
    }

    private static DraftLesson row(long id, long periodId) {
        DraftLesson row = new DraftLesson();
        row.setId(id);
        row.setDraftId(5L);
        row.setAdminClassId(1L);
        row.setCourseId(1L);
        row.setTeacherId(1L);
        row.setPeriodId(periodId);
        return row;
    }
}
