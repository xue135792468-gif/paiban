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
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.DraftValidator.ChangeAssessment;
import com.paiban.scheduling.service.DraftValidator.LessonChange;
import com.paiban.scheduling.solver.ConstraintSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Whole-draft before/after comparison at the validator seam: a change may repair an
 * old conflict while others remain, but it may never add a new stable key or make an
 * existing key appear more often. Several lessons changing together are judged only
 * after every change has been applied to the in-memory copy.
 */
class DraftValidatorChangeAssessmentTest {

    private static final long DRAFT_ID = 1L;
    private static final long SEMESTER_ID = 10L;

    private static final long P1 = 101L;
    private static final long P2 = 102L;
    private static final long P3 = 103L;
    private static final long P4 = 104L;
    private static final long P5 = 105L;
    private static final long P6 = 106L;

    private static final long CLASS_1 = 201L;
    private static final long CLASS_2 = 202L;
    private static final long CLASS_3 = 203L;
    private static final long CLASS_5 = 205L;

    private static final long CHINESE = 301L;
    private static final long MATH = 302L;

    private static final long TEACHER_1 = 401L;
    private static final long TEACHER_2 = 402L;
    private static final long TEACHER_4 = 404L;

    private static final long ROOM_1 = 501L;
    private static final long ROOM_2 = 502L;
    private static final long ROOM_3 = 503L;
    private static final long ROOM_4 = 504L;
    private static final long ROOM_5 = 505L;

    private static final long LESSON_1 = 601L;
    private static final long LESSON_2 = 602L;
    private static final long LESSON_3 = 603L;
    private static final long LESSON_4 = 604L;
    private static final long LESSON_5 = 605L;

    private final ObjectMapper mapper = new ObjectMapper();

    private DraftValidator validator;
    private DraftLessonRepository draftLessons;
    private ConstraintService constraintService;
    private TimetableDraft draft;

    @BeforeEach
    void setUp() {
        TimetableDraftRepository drafts = mock(TimetableDraftRepository.class);
        draftLessons = mock(DraftLessonRepository.class);
        PeriodRepository periods = mock(PeriodRepository.class);
        RoomRepository rooms = mock(RoomRepository.class);
        CourseRepository courses = mock(CourseRepository.class);
        TeacherRepository teachers = mock(TeacherRepository.class);
        AdminClassRepository adminClasses = mock(AdminClassRepository.class);
        constraintService = mock(ConstraintService.class);
        SemesterBaselineQuery baselines = mock(SemesterBaselineQuery.class);
        when(constraintService.settings(SEMESTER_ID)).thenReturn(ConstraintSettings.defaults());

        draft = new TimetableDraft();
        draft.setId(DRAFT_ID);
        draft.setSemesterId(SEMESTER_ID);
        when(drafts.findById(DRAFT_ID)).thenReturn(Optional.of(draft));

        when(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(SEMESTER_ID)).thenReturn(List.of(
                period(P1, 1), period(P2, 2), period(P3, 3), period(P4, 4),
                period(P5, 5), period(P6, 6)));
        when(rooms.findAll()).thenReturn(List.of(
                room(ROOM_1, "教室1"), room(ROOM_2, "教室2"), room(ROOM_3, "教室3"),
                room(ROOM_4, "教室4"), room(ROOM_5, "教室5")));
        when(courses.findAll()).thenReturn(List.of(
                course(CHINESE, "语文"), course(MATH, "数学")));
        when(teachers.findAll()).thenReturn(List.of(
                teacher(TEACHER_1, "张老师"), teacher(TEACHER_2, "李老师"),
                teacher(TEACHER_4, "王老师")));
        when(adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(SEMESTER_ID)).thenReturn(List.of(
                adminClass(CLASS_1, "高一1班"), adminClass(CLASS_2, "高一2班"),
                adminClass(CLASS_3, "高一3班"), adminClass(CLASS_5, "高一5班")));

        validator = new DraftValidator(
                drafts, draftLessons, periods, rooms, courses, teachers, adminClasses,
                constraintService, baselines);
    }

    @Test
    void repairingOneOfThreeOldConflictsIsAllowedWithoutNewOnes() {
        useLessons(
                lesson(LESSON_1, CHINESE, TEACHER_1, CLASS_1, P1, ROOM_1),
                lesson(LESSON_2, MATH, TEACHER_2, CLASS_1, P1, ROOM_2),
                lesson(LESSON_3, CHINESE, TEACHER_1, CLASS_2, P1, ROOM_3),
                lesson(LESSON_4, MATH, TEACHER_4, CLASS_3, P1, ROOM_1));

        ChangeAssessment assessment = validator.assessChanges(DRAFT_ID, List.of(
                new LessonChange(LESSON_2, P2, ROOM_2, TEACHER_2)));

        assertThat(assessment.before()).hasSize(3);
        assertThat(assessment.allowed()).isTrue();
        assertThat(assessment.added()).isEmpty();
        assertThat(assessment.remaining()).hasSize(2);
    }

    @Test
    void repairingOneConflictWhileIntroducingAnotherIsRefused() {
        useLessons(
                lesson(LESSON_1, CHINESE, TEACHER_1, CLASS_1, P1, ROOM_1),
                lesson(LESSON_2, MATH, TEACHER_2, CLASS_1, P1, ROOM_2),
                lesson(LESSON_3, CHINESE, TEACHER_1, CLASS_2, P1, ROOM_3),
                lesson(LESSON_4, MATH, TEACHER_4, CLASS_3, P1, ROOM_1),
                lesson(LESSON_5, MATH, TEACHER_1, CLASS_5, P4, ROOM_5));

        ChangeAssessment assessment = validator.assessChanges(DRAFT_ID, List.of(
                new LessonChange(LESSON_2, P2, ROOM_2, TEACHER_2),
                new LessonChange(LESSON_3, P4, ROOM_3, TEACHER_1)));

        assertThat(assessment.allowed()).isFalse();
        assertThat(assessment.added())
                .extracting(DraftValidator.Violation::key)
                .containsExactly("TEACHER_CONFLICT|L|" + LESSON_3);
    }

    @Test
    void oneAggregateHitBecomingTwoIsAnAggravationAndTwoBecomingOneIsAllowed() {
        useConsecutiveSettings();
        useLessons(
                lesson(LESSON_1, CHINESE, TEACHER_1, CLASS_1, P1, ROOM_1),
                lesson(LESSON_2, CHINESE, TEACHER_1, CLASS_1, P2, ROOM_1),
                lesson(LESSON_3, CHINESE, TEACHER_1, CLASS_1, P5, ROOM_1));

        ChangeAssessment worsened = validator.assessChanges(DRAFT_ID, List.of(
                new LessonChange(LESSON_2, P3, ROOM_1, TEACHER_1)));
        assertThat(worsened.before()).hasSize(1);
        assertThat(worsened.after()).hasSize(2);
        assertThat(worsened.added()).hasSize(1);
        assertThat(worsened.allowed()).isFalse();
        assertThat(worsened.added().get(0).key())
                .isEqualTo("CONSECUTIVE_LESSONS|" + CLASS_1 + "-" + CHINESE + "-1");

        useLessons(
                lesson(LESSON_1, CHINESE, TEACHER_1, CLASS_1, P1, ROOM_1),
                lesson(LESSON_2, CHINESE, TEACHER_1, CLASS_1, P3, ROOM_1),
                lesson(LESSON_3, CHINESE, TEACHER_1, CLASS_1, P5, ROOM_1));
        ChangeAssessment improved = validator.assessChanges(DRAFT_ID, List.of(
                new LessonChange(LESSON_2, P2, ROOM_1, TEACHER_1)));
        assertThat(improved.before()).hasSize(2);
        assertThat(improved.after()).hasSize(1);
        assertThat(improved.allowed()).isTrue();
        assertThat(improved.added()).isEmpty();
    }

    @Test
    void swapIsJudgedAfterBothLessonsHaveMoved() {
        useLessons(
                lesson(LESSON_1, CHINESE, TEACHER_1, CLASS_1, P1, ROOM_1),
                lesson(LESSON_2, MATH, TEACHER_2, CLASS_1, P2, ROOM_2));

        ChangeAssessment assessment = validator.assessChanges(DRAFT_ID, List.of(
                new LessonChange(LESSON_1, P2, ROOM_1, TEACHER_1),
                new LessonChange(LESSON_2, P1, ROOM_2, TEACHER_2)));

        assertThat(assessment.before()).isEmpty();
        assertThat(assessment.after()).isEmpty();
        assertThat(assessment.added()).isEmpty();
        assertThat(assessment.allowed()).isTrue();
    }

    @Test
    void messageAndCountingTextDoNotChangeTheStableKey() {
        useSameCourseSettings();
        useLessons(
                lesson(LESSON_1, CHINESE, TEACHER_1, CLASS_1, P1, ROOM_1),
                lesson(LESSON_2, CHINESE, TEACHER_1, CLASS_1, P2, ROOM_1));

        ChangeAssessment assessment = validator.assessChanges(DRAFT_ID, List.of(
                new LessonChange(LESSON_2, P3, ROOM_1, TEACHER_1)));

        assertThat(assessment.before()).hasSize(1);
        assertThat(assessment.remaining()).hasSize(1);
        assertThat(assessment.before().get(0).key())
                .isEqualTo(assessment.remaining().get(0).key());
        assertThat(assessment.before().get(0).message())
                .isNotEqualTo(assessment.remaining().get(0).message());
        assertThat(assessment.added()).isEmpty();
        assertThat(assessment.allowed()).isTrue();
    }

    private void useLessons(DraftLesson... lessons) {
        when(draftLessons.findByDraftIdOrderByIdAsc(DRAFT_ID)).thenReturn(List.of(lessons));
    }

    private void useConsecutiveSettings() {
        when(constraintService.settings(SEMESTER_ID)).thenReturn(settingsWithEntries(
                ConstraintType.CONSECUTIVE_LESSONS, true, 60,
                params("courseId", CHINESE, "minConsecutive", 2)));
    }

    private void useSameCourseSettings() {
        when(constraintService.settings(SEMESTER_ID)).thenReturn(settingsWithEntries(
                ConstraintType.SAME_COURSE_ONCE_PER_DAY, true, 100,
                params("courseId", CHINESE)));
    }

    private ConstraintSettings settingsWithEntries(
            ConstraintType type, boolean hard, int weight, Map<String, Object> entryParams) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(type.name());
        config.setHard(hard);
        config.setWeight(weight);
        config.setEnabled(true);
        config.setParamsJson("{}");
        ConstraintEntry entry = new ConstraintEntry();
        entry.setId(1L);
        entry.setType(type.name());
        entry.setEnabled(true);
        entry.setGroupKey(null);
        try {
            entry.setParamsJson(mapper.writeValueAsString(entryParams));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return ConstraintSettings.from(List.of(config), List.of(entry), mapper);
    }

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            params.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return params;
    }

    private static DraftLesson lesson(
            long id, long courseId, long teacherId, long classId, Long periodId, Long roomId) {
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

    private static Period period(long id, int periodNo) {
        Period period = new Period();
        period.setId(id);
        period.setSemesterId(SEMESTER_ID);
        period.setDayOfWeek(1);
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

    private static Course course(long id, String name) {
        Course course = new Course();
        course.setId(id);
        course.setName(name);
        course.setSubject(name);
        return course;
    }

    private static Teacher teacher(long id, String name) {
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setName(name);
        teacher.setSubject(name);
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
