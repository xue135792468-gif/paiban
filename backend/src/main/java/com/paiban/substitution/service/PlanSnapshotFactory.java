package com.paiban.substitution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.scheduling.constraint.ConstraintContext.ClassView;
import com.paiban.scheduling.constraint.ConstraintContext.CourseView;
import com.paiban.scheduling.constraint.ConstraintContext.PeriodView;
import com.paiban.scheduling.constraint.ConstraintContext.RoomView;
import com.paiban.scheduling.constraint.ConstraintContext.TeacherView;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.service.ConstraintService;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.PlanSnapshot.AppliedSubstitution;
import com.paiban.substitution.plan.PlanSnapshot.BaselineLesson;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter from the published-timetable tables to the pure {@link PlanSnapshot}.
 * This is the seam that lets the planner, the verifier and the CLI share one
 * in-memory model while the web app keeps owning persistence.
 */
@Service
public class PlanSnapshotFactory {

    private final SemesterService semesters;
    private final PeriodRepository periods;
    private final RoomRepository rooms;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final AdminClassRepository adminClasses;
    private final TimetableEntryRepository entries;
    private final SubstitutionRepository substitutions;
    private final ConstraintService constraints;
    private final SettingsService settings;
    private final ObjectMapper objectMapper;

    public PlanSnapshotFactory(
            SemesterService semesters,
            PeriodRepository periods,
            RoomRepository rooms,
            CourseRepository courses,
            TeacherRepository teachers,
            AdminClassRepository adminClasses,
            TimetableEntryRepository entries,
            SubstitutionRepository substitutions,
            ConstraintService constraints,
            SettingsService settings,
            ObjectMapper objectMapper) {
        this.semesters = semesters;
        this.periods = periods;
        this.rooms = rooms;
        this.courses = courses;
        this.teachers = teachers;
        this.adminClasses = adminClasses;
        this.entries = entries;
        this.substitutions = substitutions;
        this.constraints = constraints;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PlanSnapshot build(Long semesterId) {
        Semester semester = semesters.require(semesterId);
        Map<Long, PeriodView> periodMap = new HashMap<>();
        periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)
                .forEach(period -> periodMap.put(period.getId(), new PeriodView(
                        period.getId(), period.getDayOfWeek(), period.getPeriodNo(), period.getSection())));
        Map<Long, RoomView> roomMap = new HashMap<>();
        rooms.findAll().forEach(room -> roomMap.put(room.getId(), new RoomView(
                room.getId(), room.getName(), room.getType(), room.getCapacity())));
        Map<Long, CourseView> courseMap = new HashMap<>();
        courses.findAll().forEach(course -> courseMap.put(course.getId(), new CourseView(
                course.getId(), course.getName(), course.getRequiredRoomType())));
        Map<Long, TeacherView> teacherMap = new HashMap<>();
        teachers.findAll().forEach(teacher -> teacherMap.put(teacher.getId(), new TeacherView(
                teacher.getId(), teacher.getName(), teacher.getSubject())));
        Map<Long, ClassView> classMap = new HashMap<>();
        adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId)
                .forEach(adminClass -> classMap.put(adminClass.getId(), new ClassView(
                        adminClass.getId(), adminClass.getName(), adminClass.getStudentCount(),
                        adminClass.getHeadTeacherId())));

        List<BaselineLesson> baseline = entries.findBySemesterId(semesterId).stream()
                .map(entry -> new BaselineLesson(
                        entry.getId(), entry.getAdminClassId(), entry.getCourseId(), entry.getTeacherId(),
                        entry.getRoomId(), entry.getPeriodId(), entry.getDayOfWeek(), entry.getPeriodNo()))
                .toList();
        List<AppliedSubstitution> applied = substitutions
                .findBySemesterIdAndStatus(semesterId, Substitution.EFFECTIVE).stream()
                .map(item -> new AppliedSubstitution(
                        item.getId(), item.getEntryId(), item.getEffectiveDate(), item.getCreatedAt(),
                        item.getNewPeriodId(), item.getNewDayOfWeek(), item.getNewPeriodNo(),
                        item.getNewRoomId(), item.getSubstituteTeacherId(), item.getType()))
                .toList();
        List<ConstraintConfig> active = constraints.active(semesterId);
        ConstraintSettings settingsData = constraints.hasConfigs(semesterId)
                ? ConstraintSettings.from(active, objectMapper)
                : ConstraintSettings.defaults();
        return new PlanSnapshot(
                semesterId, semester.getStartDate(), semester.getEndDate(),
                periodMap, roomMap, courseMap, teacherMap, classMap,
                baseline, applied, settingsData);
    }

    @Transactional(readOnly = true)
    public PlanSnapshot buildCurrent() {
        return build(semesters.requireCurrent().getId());
    }

    public SwapPlannerOptions swapOptions() {
        return new SwapPlannerOptions(
                settings.substitutionSearchWindowDays(),
                settings.substitutionMaxPlanTeachers(),
                com.paiban.substitution.plan.RankingPreset.fromCode(
                        settings.substitutionRankingPreset()));
    }

    /** Settings resolved to planner options; kept next to the snapshot adapter. */
    public record SwapPlannerOptions(int windowDays, int maxTeachers,
                                     com.paiban.substitution.plan.RankingPreset preset) {}
}
