package com.paiban.substitution.service;

import com.paiban.common.policy.ExchangePolicyService;
import com.paiban.common.policy.ExchangePolicySnapshot;
import com.paiban.common.policy.ExchangeRankingPreset;
import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.scheduling.constraint.ConstraintContext.ClassView;
import com.paiban.scheduling.constraint.ConstraintContext.CourseView;
import com.paiban.scheduling.constraint.ConstraintContext.PeriodView;
import com.paiban.scheduling.constraint.ConstraintContext.TeacherView;
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
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final AdminClassRepository adminClasses;
    private final TimetableEntryRepository entries;
    private final SubstitutionRepository substitutions;
    private final ConstraintService constraints;
    private final SettingsService settings;
    private final ExchangePolicyService exchangePolicy;

    public PlanSnapshotFactory(
            SemesterService semesters,
            PeriodRepository periods,
            CourseRepository courses,
            TeacherRepository teachers,
            AdminClassRepository adminClasses,
            TimetableEntryRepository entries,
            SubstitutionRepository substitutions,
            ConstraintService constraints,
            SettingsService settings,
            ExchangePolicyService exchangePolicy) {
        this.semesters = semesters;
        this.periods = periods;
        this.courses = courses;
        this.teachers = teachers;
        this.adminClasses = adminClasses;
        this.entries = entries;
        this.substitutions = substitutions;
        this.constraints = constraints;
        this.settings = settings;
        this.exchangePolicy = exchangePolicy;
    }

    @Transactional(readOnly = true)
    public PlanSnapshot build(Long semesterId) {
        Semester semester = semesters.require(semesterId);
        Map<Long, PeriodView> periodMap = new HashMap<>();
        periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)
                .forEach(period -> periodMap.put(period.getId(), new PeriodView(
                        period.getId(), period.getDayOfWeek(), period.getPeriodNo())));
        Map<Long, CourseView> courseMap = new HashMap<>();
        courses.findAll().forEach(course -> courseMap.put(course.getId(), new CourseView(
                course.getId(), course.getName())));
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
                        entry.getPeriodId(), entry.getDayOfWeek(), entry.getPeriodNo()))
                .toList();
        List<AppliedSubstitution> applied = substitutions
                .findBySemesterIdAndStatus(semesterId, Substitution.EFFECTIVE).stream()
                .map(item -> new AppliedSubstitution(
                        item.getId(), item.getEntryId(), item.getEffectiveDate(),
                        item.getLessonDate(), item.getTargetLessonDate(), item.getCreatedAt(),
                        item.getNewPeriodId(), item.getNewDayOfWeek(), item.getNewPeriodNo(),
                        item.getSubstituteTeacherId(), item.getType()))
                .toList();
        // The same settings snapshot the solver and the draft check use, so the
        // planner, the verifier and the CLI judge a plan by one rule set.
        ConstraintSettings settingsData = constraints.settings(semesterId);
        return new PlanSnapshot(
                semesterId, semester.getStartDate(), semester.getEndDate(),
                periodMap, courseMap, teacherMap, classMap,
                baseline, applied, settingsData);
    }

    @Transactional(readOnly = true)
    public PlanSnapshot buildCurrent() {
        return build(semesters.requireCurrent().getId());
    }

    public SwapPlannerOptions swapOptions() {
        // The shared policy is the one place that reads the moved-lesson cap and
        // ranking preset, so the draft search and the published planner cannot
        // drift apart. The search window is not part of the shared policy.
        ExchangePolicySnapshot policy = exchangePolicy.current();
        return new SwapPlannerOptions(
                settings.substitutionSearchWindowDays(),
                policy.maxMovedLessons(),
                policy.rankingPreset(),
                policy.fingerprint());
    }

    /** Settings resolved to planner options; kept next to the snapshot adapter. */
    public record SwapPlannerOptions(int windowDays, int maxTeachers,
                                     ExchangeRankingPreset preset, String fingerprint) {

        /** Alias with the setting's real name: it caps the moved lessons. */
        public int maxMovedLessons() {
            return maxTeachers;
        }
    }
}
