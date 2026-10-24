package com.paiban.substitution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.scheduling.service.ConstraintService;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.RankingPreset;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The adapter seam: published rows and effective substitutions become the pure
 * snapshot the planner, verifier and CLI consume.
 */
class PlanSnapshotFactoryTest {

    private static final long SEMESTER = 1L;
    private static final long CLASS = 2L;
    private static final long TEACHER = 3L;
    private static final long SUBSTITUTE = 4L;
    private static final long COURSE = 5L;
    private static final long ROOM = 6L;
    private static final long PERIOD_1 = 101L;
    private static final long PERIOD_2 = 102L;
    private static final long ENTRY = 7L;
    private static final LocalDate MONDAY = LocalDate.of(2025, 9, 1);

    @Test
    void mapsThePublishedRowsAndAppliesEffectiveSubstitutionsOnTheDate() {
        PlanSnapshot snapshot = newFactory().build(SEMESTER);

        assertThat(snapshot.baseline()).hasSize(1);
        assertThat(snapshot.substitutions()).hasSize(1);
        assertThat(snapshot.snapshotHash()).hasSize(64);

        PlanSnapshot.DaySchedule before = snapshot.scheduleOn(MONDAY);
        assertThat(before.lessons()).hasSize(1);
        assertThat(before.lessons().get(0).periodId()).isEqualTo(PERIOD_1);
        assertThat(before.lessons().get(0).teacherId()).isEqualTo(TEACHER);

        // One week later the substitution has taken effect: new slot and teacher.
        PlanSnapshot.DaySchedule after = snapshot.scheduleOn(MONDAY.plusDays(7));
        assertThat(after.lessons()).hasSize(1);
        assertThat(after.lessons().get(0).periodId()).isEqualTo(PERIOD_2);
        assertThat(after.lessons().get(0).teacherId()).isEqualTo(SUBSTITUTE);
        assertThat(snapshot.snapshotHash()).isEqualTo(newFactory().build(SEMESTER).snapshotHash());
    }

    @Test
    void exposesTheConfiguredSwapPolicy() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.substitutionSearchWindowDays()).thenReturn(9);
        when(settings.substitutionMaxPlanTeachers()).thenReturn(4);
        when(settings.substitutionRankingPreset()).thenReturn("min-teachers");

        PlanSnapshotFactory.SwapPlannerOptions options = factory(settings).swapOptions();

        assertThat(options.windowDays()).isEqualTo(9);
        assertThat(options.maxTeachers()).isEqualTo(4);
        assertThat(options.preset()).isEqualTo(RankingPreset.MIN_TEACHERS);
    }

    private PlanSnapshotFactory newFactory() {
        return factory(mock(SettingsService.class));
    }

    private PlanSnapshotFactory factory(SettingsService settings) {
        SemesterService semesters = mock(SemesterService.class);
        PeriodRepository periods = mock(PeriodRepository.class);
        RoomRepository rooms = mock(RoomRepository.class);
        CourseRepository courses = mock(CourseRepository.class);
        TeacherRepository teachers = mock(TeacherRepository.class);
        AdminClassRepository adminClasses = mock(AdminClassRepository.class);
        TimetableEntryRepository entries = mock(TimetableEntryRepository.class);
        SubstitutionRepository substitutions = mock(SubstitutionRepository.class);
        ConstraintService constraints = mock(ConstraintService.class);

        Semester semester = new Semester();
        semester.setId(SEMESTER);
        semester.setStartDate(MONDAY);
        semester.setEndDate(LocalDate.of(2026, 1, 20));
        when(semesters.require(SEMESTER)).thenReturn(semester);

        when(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(SEMESTER))
                .thenReturn(List.of(period(PERIOD_1, 1), period(PERIOD_2, 2)));
        Room room = new Room();
        room.setId(ROOM);
        room.setName("教室1");
        room.setType("NORMAL");
        room.setCapacity(50);
        when(rooms.findAll()).thenReturn(List.of(room));
        Course course = new Course();
        course.setId(COURSE);
        course.setName("语文");
        when(courses.findAll()).thenReturn(List.of(course));
        Teacher teacher = new Teacher();
        teacher.setId(TEACHER);
        teacher.setName("张老师");
        teacher.setSubject("语文");
        Teacher substitute = new Teacher();
        substitute.setId(SUBSTITUTE);
        substitute.setName("李老师");
        substitute.setSubject("数学");
        when(teachers.findAll()).thenReturn(List.of(teacher, substitute));
        AdminClass adminClass = new AdminClass();
        adminClass.setId(CLASS);
        adminClass.setSemesterId(SEMESTER);
        adminClass.setName("高一1班");
        adminClass.setStudentCount(45);
        when(adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(SEMESTER))
                .thenReturn(List.of(adminClass));

        TimetableEntry entry = new TimetableEntry();
        entry.setId(ENTRY);
        entry.setSemesterId(SEMESTER);
        entry.setAdminClassId(CLASS);
        entry.setCourseId(COURSE);
        entry.setTeacherId(TEACHER);
        entry.setRoomId(ROOM);
        entry.setPeriodId(PERIOD_1);
        entry.setDayOfWeek(1);
        entry.setPeriodNo(1);
        when(entries.findBySemesterId(SEMESTER)).thenReturn(List.of(entry));

        Substitution substitution = new Substitution();
        substitution.setId(1L);
        substitution.setEntryId(ENTRY);
        substitution.setEffectiveDate(MONDAY.plusDays(7));
        substitution.setCreatedAt(Instant.parse("2025-09-01T08:00:00Z"));
        substitution.setNewPeriodId(PERIOD_2);
        substitution.setNewDayOfWeek(1);
        substitution.setNewPeriodNo(2);
        substitution.setNewRoomId(ROOM);
        substitution.setSubstituteTeacherId(SUBSTITUTE);
        substitution.setStatus(Substitution.EFFECTIVE);
        substitution.setType(Substitution.TYPE_SWAP);
        when(substitutions.findBySemesterIdAndStatus(SEMESTER, Substitution.EFFECTIVE))
                .thenReturn(List.of(substitution));

        when(constraints.hasConfigs(SEMESTER)).thenReturn(false);

        return new PlanSnapshotFactory(semesters, periods, rooms, courses, teachers, adminClasses,
                entries, substitutions, constraints, settings, new ObjectMapper());
    }

    private static Period period(long id, int periodNo) {
        Period period = new Period();
        period.setId(id);
        period.setSemesterId(SEMESTER);
        period.setDayOfWeek(1);
        period.setPeriodNo(periodNo);
        period.setLabel("第" + periodNo + "节");
        period.setSection(PeriodSection.MORNING);
        return period;
    }
}
