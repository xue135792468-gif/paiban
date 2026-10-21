package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.DraftValidator.Conflict;
import com.paiban.scheduling.service.DraftValidator.ValidationResult;
import com.paiban.scheduling.web.SchedulingDtos.LessonOverride;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Batch override rules at the validator seam. Repositories are mocks, so the test
 * runs without Spring or a database.
 */
class DraftValidatorTest {

    private static final long DRAFT_ID = 1L;
    private static final long SEMESTER_ID = 10L;

    private static final long P1 = 101L;
    private static final long P2 = 102L;
    private static final long P3 = 103L;
    private static final long P4 = 104L;

    private static final long CLASS_1 = 201L;
    private static final long CLASS_2 = 202L;

    private static final long CHINESE = 301L;
    private static final long MATH = 302L;

    private static final long TEACHER_1 = 401L;
    private static final long TEACHER_2 = 402L;

    private static final long ROOM_1 = 501L;
    private static final long ROOM_2 = 502L;

    private static final long LESSON_1 = 601L;
    private static final long LESSON_2 = 602L;
    private static final long LESSON_3 = 603L;
    private static final long LESSON_4 = 604L;

    private DraftValidator validator;

    @BeforeEach
    void setUp() {
        TimetableDraftRepository drafts = mock(TimetableDraftRepository.class);
        DraftLessonRepository draftLessons = mock(DraftLessonRepository.class);
        PeriodRepository periods = mock(PeriodRepository.class);
        RoomRepository rooms = mock(RoomRepository.class);
        CourseRepository courses = mock(CourseRepository.class);
        TeacherRepository teachers = mock(TeacherRepository.class);
        AdminClassRepository adminClasses = mock(AdminClassRepository.class);
        ConstraintService constraints = mock(ConstraintService.class);

        TimetableDraft draft = new TimetableDraft();
        draft.setId(DRAFT_ID);
        draft.setSemesterId(SEMESTER_ID);
        when(drafts.findById(DRAFT_ID)).thenReturn(Optional.of(draft));

        when(draftLessons.findByDraftIdOrderByIdAsc(DRAFT_ID)).thenReturn(List.of(
                lesson(LESSON_1, CHINESE, TEACHER_1, CLASS_1, P1, ROOM_1),
                lesson(LESSON_2, MATH, TEACHER_2, CLASS_1, P2, ROOM_2),
                lesson(LESSON_3, CHINESE, TEACHER_1, CLASS_2, P3, ROOM_1),
                lesson(LESSON_4, MATH, TEACHER_2, CLASS_2, P4, ROOM_2)));
        when(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(SEMESTER_ID)).thenReturn(List.of(
                period(P1, 1, 1),
                period(P2, 1, 2),
                period(P3, 2, 1),
                period(P4, 2, 2)));
        when(rooms.findAll()).thenReturn(List.of(room(ROOM_1, "教室1"), room(ROOM_2, "教室2")));
        when(courses.findAll()).thenReturn(List.of(
                course(CHINESE, "语文", "语文"),
                course(MATH, "数学", "数学")));
        when(teachers.findAll()).thenReturn(List.of(
                teacher(TEACHER_1, "张老师", "语文"),
                teacher(TEACHER_2, "李老师", "数学")));
        when(adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(SEMESTER_ID)).thenReturn(List.of(
                adminClass(CLASS_1, "高一1班"),
                adminClass(CLASS_2, "高一2班")));

        validator = new DraftValidator(
                drafts, draftLessons, periods, rooms, courses, teachers, adminClasses, constraints, new ObjectMapper());
    }

    @Test
    void batchSwapWithinClassIsValidOnlyWhenBothOverridesAreAppliedTogether() {
        ValidationResult single = validator.validateMoves(
                DRAFT_ID, List.of(new LessonOverride(LESSON_1, P2, null, null)));
        assertThat(single.valid()).isFalse();
        assertThat(single.conflicts())
                .extracting(Conflict::type)
                .contains("CLASS_CONFLICT");

        ValidationResult swapped = validator.validateMoves(DRAFT_ID, List.of(
                new LessonOverride(LESSON_1, P2, null, null),
                new LessonOverride(LESSON_2, P1, null, null)));
        assertThat(swapped.valid()).isTrue();
        assertThat(swapped.hardConflicts()).isZero();
        assertThat(swapped.conflicts()).isEmpty();
    }

    @Test
    void conflictIsAttributedToTheMovedLesson() {
        ValidationResult result = validator.validateMoves(
                DRAFT_ID, List.of(new LessonOverride(LESSON_1, P2, null, null)));

        assertThat(result.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.type()).isEqualTo("CLASS_CONFLICT");
            assertThat(conflict.lessonId()).isEqualTo(LESSON_1);
        });
    }

    @Test
    void emptyOverridesReportNoConflicts() {
        ValidationResult result = validator.validateMoves(DRAFT_ID, List.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.hardConflicts()).isZero();
        assertThat(result.conflicts()).isEmpty();
    }

    @Test
    void duplicateLessonIdMergesAndUsesTheLastValue() {
        ValidationResult result = validator.validateMoves(DRAFT_ID, List.of(
                new LessonOverride(LESSON_1, P2, null, null),
                new LessonOverride(LESSON_1, P1, null, null)));

        assertThat(result.valid()).isTrue();
        assertThat(result.conflicts()).isEmpty();
    }

    @Test
    void duplicateLessonIdDoesNotDuplicateConflicts() {
        ValidationResult result = validator.validateMoves(DRAFT_ID, List.of(
                new LessonOverride(LESSON_1, P2, null, null),
                new LessonOverride(LESSON_1, P2, null, null)));

        assertThat(result.hardConflicts()).isEqualTo(1);
        assertThat(result.conflicts()).extracting(Conflict::type).containsExactly("CLASS_CONFLICT");
    }

    @Test
    void teacherOverrideParticipatesInConflictChecking() {
        ValidationResult result = validator.validateMoves(DRAFT_ID, List.of(
                new LessonOverride(LESSON_1, P2, null, TEACHER_2)));

        assertThat(result.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.type()).isEqualTo("TEACHER_CONFLICT");
            assertThat(conflict.lessonId()).isEqualTo(LESSON_1);
        });
    }

    private static DraftLesson lesson(
            long id, long courseId, long teacherId, long classId, long periodId, long roomId) {
        DraftLesson lesson = new DraftLesson();
        lesson.setId(id);
        lesson.setDraftId(DRAFT_ID);
        lesson.setAssignmentId(id);
        lesson.setLessonIndex(1);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periodId);
        lesson.setRoomId(roomId);
        return lesson;
    }

    private static Period period(long id, int dayOfWeek, int periodNo) {
        Period period = new Period();
        period.setId(id);
        period.setSemesterId(SEMESTER_ID);
        period.setDayOfWeek(dayOfWeek);
        period.setPeriodNo(periodNo);
        period.setLabel("第" + periodNo + "节");
        period.setSection(PeriodSection.MORNING);
        return period;
    }

    private static Room room(long id, String name) {
        Room room = new Room();
        room.setId(id);
        room.setName(name);
        room.setCapacity(50);
        room.setType("NORMAL");
        return room;
    }

    private static Course course(long id, String name, String subject) {
        Course course = new Course();
        course.setId(id);
        course.setName(name);
        course.setSubject(subject);
        return course;
    }

    private static Teacher teacher(long id, String name, String subject) {
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setName(name);
        teacher.setSubject(subject);
        return teacher;
    }

    private static AdminClass adminClass(long id, String name) {
        AdminClass adminClass = new AdminClass();
        adminClass.setId(id);
        adminClass.setSemesterId(SEMESTER_ID);
        adminClass.setGradeId(1L);
        adminClass.setName(name);
        adminClass.setStudentCount(45);
        return adminClass;
    }
}
