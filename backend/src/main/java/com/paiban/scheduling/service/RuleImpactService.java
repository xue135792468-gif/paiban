package com.paiban.scheduling.service;

import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.constraint.ConstraintChecker;
import com.paiban.scheduling.constraint.ConstraintContext;
import com.paiban.scheduling.constraint.ConstraintViolation;
import com.paiban.scheduling.constraint.LessonView;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintEntryRequest;
import com.paiban.scheduling.web.ConstraintDtos.ImpactConflictDto;
import com.paiban.scheduling.web.ConstraintDtos.ImpactObjectDto;
import com.paiban.scheduling.web.ConstraintDtos.ImpactSlotDto;
import com.paiban.scheduling.web.ConstraintDtos.RuleImpactDto;
import com.paiban.scheduling.web.ConstraintDtos.TemplateReferenceIssue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The impact preview of ticket 10: what classes, teachers and slots a rule card
 * touches, how many lessons of the newest draft fall inside that scope, and which
 * hard conflicts the one shared {@link ConstraintChecker} reports for them.
 *
 * <p>This service never writes and never owns a second evaluator. It derives the
 * scope from the request, merges the request onto the saved configuration through
 * {@link ConstraintConfigService#previewSnapshot}, and then asks the same checker the
 * draft editor uses. A rule that cannot be evaluated (invalid configuration) reports
 * its errors instead of guessing.
 */
@Service
public class RuleImpactService {

    private static final int CONFLICT_TIMEOUT_SECONDS = 5;
    private static final String DRAFT = "DRAFT";

    /** Types whose one entry may name no teacher, meaning every teacher. */
    private static final Set<ConstraintType> TEACHER_TARGETS = EnumSet.of(
            ConstraintType.TEACHER_UNAVAILABLE,
            ConstraintType.TEACHER_NO_FIRST_PERIOD,
            ConstraintType.TEACHER_DAY_OFF,
            ConstraintType.TEACHER_PREFERRED_SLOT,
            ConstraintType.TEACHER_MAX_PER_DAY,
            ConstraintType.TEACHER_MAX_CONSECUTIVE);

    /** Types whose one entry may name no class, meaning every class. */
    private static final Set<ConstraintType> CLASS_TARGETS = EnumSet.of(
            ConstraintType.CLASS_UNAVAILABLE,
            ConstraintType.CLASS_MAX_PER_DAY,
            ConstraintType.CLASS_GAP_MINIMIZED);

    /** Course types whose one entry may name no course, meaning every course. */
    private static final Set<ConstraintType> COURSE_TARGETS = EnumSet.of(
            ConstraintType.COURSE_UNAVAILABLE,
            ConstraintType.COURSE_MAX_PER_DAY,
            ConstraintType.SAME_COURSE_ONCE_PER_DAY,
            ConstraintType.COURSE_SPREAD,
            ConstraintType.PE_LAST_PERIOD_AVOID,
            ConstraintType.CONSECUTIVE_LESSONS,
            ConstraintType.MAIN_SUBJECT_MORNING);

    private enum Mode { ALL, CLASS, TEACHER, COURSE }

    /** What one requirement entry matches; an empty slot set means "every slot". */
    private record EntryScope(Mode mode, Set<Long> ids, Set<String> slots) {

        boolean matches(DraftLesson lesson, Period period) {
            if (period == null || !slotMatches(slots, period.getDayOfWeek(), period.getPeriodNo())) {
                return false;
            }
            return switch (mode) {
                case ALL -> true;
                case CLASS -> ids.contains(lesson.getAdminClassId());
                case TEACHER -> ids.contains(lesson.getTeacherId());
                case COURSE -> ids.isEmpty() || ids.contains(lesson.getCourseId());
            };
        }
    }

    /** Display scope of one entry, before the configs are unioned. */
    private record EntryImpact(EntryScope scope, Set<Long> classes, Set<Long> teachers) {}

    private final ConstraintConfigService configs;
    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final PeriodRepository periods;
    private final AdminClassRepository adminClasses;
    private final TeacherRepository teachers;
    private final CourseRepository courses;
    private final AssignmentRepository assignments;

    public RuleImpactService(
            ConstraintConfigService configs,
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            PeriodRepository periods,
            AdminClassRepository adminClasses,
            TeacherRepository teachers,
            CourseRepository courses,
            AssignmentRepository assignments) {
        this.configs = configs;
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.periods = periods;
        this.adminClasses = adminClasses;
        this.teachers = teachers;
        this.courses = courses;
        this.assignments = assignments;
    }

    @Transactional(readOnly = true)
    public RuleImpactDto impact(Long semesterId, List<ConstraintConfigRequest> requests) {
        Long resolved = configs.resolveSemester(semesterId);
        List<ConstraintConfigRequest> rules = requests == null ? List.of() : requests;
        ConstraintConfigService.PreviewSnapshot preview = configs.previewSnapshot(resolved, rules);
        List<String> errors = new ArrayList<>(preview.preview().errors());
        for (TemplateReferenceIssue issue : preview.preview().invalidReferences()) {
            errors.add(describeReference(issue));
        }
        List<String> warnings = new ArrayList<>(preview.preview().warnings());

        List<Period> periodRows = periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(resolved);
        Map<Long, Period> periodById = new HashMap<>();
        periodRows.forEach(period -> periodById.put(period.getId(), period));
        List<AdminClass> classRows =
                adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(resolved);
        Map<Long, String> classNames = new LinkedHashMap<>();
        classRows.forEach(item -> classNames.put(item.getId(), item.getName()));
        List<Teacher> teacherRows = teachers.findAll();
        Map<Long, String> teacherNames = new LinkedHashMap<>();
        teacherRows.forEach(item -> teacherNames.put(item.getId(), item.getName()));
        List<Course> courseRows = courses.findAll();
        Map<Long, String> courseNames = new LinkedHashMap<>();
        courseRows.forEach(item -> courseNames.put(item.getId(), item.getName()));

        List<Assignment> activeAssignments = assignments.findBySemesterIdAndDeletedAtIsNull(resolved);
        Map<Long, Set<Long>> classesByTeacher = new LinkedHashMap<>();
        Map<Long, Set<Long>> classesByCourse = new LinkedHashMap<>();
        Map<Long, Set<Long>> teachersByCourse = new LinkedHashMap<>();
        for (Assignment assignment : activeAssignments) {
            classesByTeacher.computeIfAbsent(assignment.getTeacherId(), key -> new LinkedHashSet<>())
                    .add(assignment.getAdminClassId());
            classesByCourse.computeIfAbsent(assignment.getCourseId(), key -> new LinkedHashSet<>())
                    .add(assignment.getAdminClassId());
            teachersByCourse.computeIfAbsent(assignment.getCourseId(), key -> new LinkedHashSet<>())
                    .add(assignment.getTeacherId());
        }

        Scope scope = scopeOf(rules, classNames.keySet(), teacherNames.keySet(), courseNames.keySet(),
                classesByTeacher, classesByCourse, teachersByCourse);
        List<ImpactSlotDto> slotDtos = slotDtos(scope.slots(), periodRows);

        TimetableDraft draft = latestDraft(resolved);
        if (draft == null) {
            return new RuleImpactDto(
                    objectDtos(scope.classes(), classNames),
                    objectDtos(scope.teachers(), teacherNames),
                    slotDtos, 0, List.of(), errors, warnings);
        }

        List<DraftLesson> lessons = draftLessons.findByDraftIdOrderByIdAsc(draft.getId());
        Set<Long> affected = new LinkedHashSet<>();
        int lessonCount = 0;
        for (DraftLesson lesson : lessons) {
            Period period = lesson.getPeriodId() == null ? null : periodById.get(lesson.getPeriodId());
            if (period == null) {
                continue;
            }
            if (scope.matches(lesson, period)) {
                affected.add(lesson.getId());
                lessonCount++;
            }
        }

        List<ImpactConflictDto> conflicts = List.of();
        if (!errors.isEmpty()) {
            warnings.add("规则配置有错误，未计算冲突");
        } else if (!affected.isEmpty()) {
            conflicts = conflictsOf(preview.settings(), lessons, periodRows, classRows,
                    teacherRows, courseRows, affected, periodById, warnings);
        }
        return new RuleImpactDto(
                objectDtos(scope.classes(), classNames),
                objectDtos(scope.teachers(), teacherNames),
                slotDtos, lessonCount, conflicts, errors, warnings);
    }

    // ------------------------------------------------------------------ scope

    private record Scope(List<Long> classes, List<Long> teachers, Set<String> slots,
                         List<EntryScope> entryScopes) {

        boolean matches(DraftLesson lesson, Period period) {
            return entryScopes.stream().anyMatch(scope -> scope.matches(lesson, period));
        }
    }

    private Scope scopeOf(
            List<ConstraintConfigRequest> rules,
            Set<Long> allClassIds,
            Set<Long> allTeacherIds,
            Set<Long> allCourseIds,
            Map<Long, Set<Long>> classesByTeacher,
            Map<Long, Set<Long>> classesByCourse,
            Map<Long, Set<Long>> teachersByCourse) {
        Set<Long> classes = new TreeSet<>();
        Set<Long> teachers = new TreeSet<>();
        Set<String> slots = new LinkedHashSet<>();
        List<EntryScope> scopes = new ArrayList<>();
        for (ConstraintConfigRequest rule : rules) {
            ConstraintType type = typeOf(rule.type());
            if (type == null || type.isRetired()) {
                continue;
            }
            for (Map<String, Object> params : paramsOf(rule)) {
                EntryImpact impact = entryImpact(type, params, allClassIds, allTeacherIds,
                        allCourseIds, classesByTeacher, classesByCourse, teachersByCourse);
                if (impact == null) {
                    continue;
                }
                scopes.add(impact.scope());
                classes.addAll(impact.classes());
                teachers.addAll(impact.teachers());
                slots.addAll(impact.scope().slots());
            }
        }
        return new Scope(List.copyOf(classes), List.copyOf(teachers), slots, scopes);
    }

    private EntryImpact entryImpact(
            ConstraintType type,
            Map<String, Object> params,
            Set<Long> allClassIds,
            Set<Long> allTeacherIds,
            Set<Long> allCourseIds,
            Map<Long, Set<Long>> classesByTeacher,
            Map<Long, Set<Long>> classesByCourse,
            Map<Long, Set<Long>> teachersByCourse) {
        Long teacherId = asLong(params.get("teacherId"));
        Long classId = asLong(params.get("classId"));
        Long courseId = asLong(params.get("courseId"));
        Set<String> slots = slotsOf(params);
        Set<Long> classes = new LinkedHashSet<>();
        Set<Long> teachers = new LinkedHashSet<>();

        if (teacherId != null) {
            teachers.add(teacherId);
            classes.addAll(classesByTeacher.getOrDefault(teacherId, Set.of()));
            return new EntryImpact(new EntryScope(Mode.TEACHER, Set.of(teacherId), slots),
                    classes, teachers);
        }
        if (classId != null) {
            classes.add(classId);
            return new EntryImpact(new EntryScope(Mode.CLASS, Set.of(classId), slots),
                    classes, teachers);
        }
        if (courseId != null) {
            classes.addAll(classesByCourse.getOrDefault(courseId, Set.of()));
            teachers.addAll(teachersByCourse.getOrDefault(courseId, Set.of()));
            return new EntryImpact(new EntryScope(Mode.COURSE, Set.of(courseId), slots),
                    classes, teachers);
        }

        if (TEACHER_TARGETS.contains(type)) {
            teachers.addAll(allTeacherIds);
            allTeacherIds.forEach(id ->
                    classes.addAll(classesByTeacher.getOrDefault(id, Set.of())));
            return new EntryImpact(new EntryScope(Mode.TEACHER, Set.copyOf(allTeacherIds), slots),
                    classes, teachers);
        }
        if (COURSE_TARGETS.contains(type)) {
            if (type == ConstraintType.COURSE_UNAVAILABLE
                    || type == ConstraintType.CONSECUTIVE_LESSONS
                    || type == ConstraintType.MAIN_SUBJECT_MORNING) {
                return null;
            }
            classes.addAll(allClassIds);
            teachers.addAll(allTeacherIds);
            return new EntryImpact(new EntryScope(Mode.COURSE, Set.copyOf(allCourseIds), slots),
                    classes, teachers);
        }
        if (CLASS_TARGETS.contains(type)) {
            classes.addAll(allClassIds);
            if (type == ConstraintType.CLASS_GAP_MINIMIZED) {
                return new EntryImpact(new EntryScope(Mode.ALL, Set.of(), Set.of()),
                        classes, teachers);
            }
            teachers.addAll(allTeacherIds);
            return new EntryImpact(new EntryScope(Mode.ALL, Set.of(), slots), classes, teachers);
        }
        return null;
    }

    /** The slots one entry names: exact, a whole weekday, or every slot. */
    private static Set<String> slotsOf(Map<String, Object> params) {
        Integer day = asInteger(params.get("dayOfWeek"));
        Integer periodNo = asInteger(params.get("periodNo"));
        if (day == null) {
            return Set.of();
        }
        if (periodNo == null) {
            return Set.of(day + "-*");
        }
        return Set.of(day + "-" + periodNo);
    }

    private static boolean slotMatches(Set<String> slots, int day, int periodNo) {
        return slots.isEmpty()
                || slots.contains(day + "-" + periodNo)
                || slots.contains(day + "-*");
    }

    private List<ImpactSlotDto> slotDtos(Set<String> slots, List<Period> periodRows) {
        Set<String> expanded = new TreeSet<>();
        if (slots.isEmpty()) {
            periodRows.forEach(period ->
                    expanded.add(period.getDayOfWeek() + "-" + period.getPeriodNo()));
        } else {
            for (String slot : slots) {
                String[] parts = slot.split("-");
                int day = Integer.parseInt(parts[0]);
                if ("*".equals(parts[1])) {
                    periodRows.stream()
                            .filter(period -> period.getDayOfWeek() == day)
                            .forEach(period -> expanded.add(
                                    day + "-" + period.getPeriodNo()));
                } else {
                    expanded.add(slot);
                }
            }
        }
        return expanded.stream()
                .map(slot -> {
                    String[] parts = slot.split("-");
                    return new ImpactSlotDto(Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1]));
                })
                .toList();
    }

    // -------------------------------------------------------------- conflicts

    private List<ImpactConflictDto> conflictsOf(
            com.paiban.scheduling.solver.ConstraintSettings settings,
            List<DraftLesson> lessons,
            List<Period> periodRows,
            List<AdminClass> classRows,
            List<Teacher> teacherRows,
            List<Course> courseRows,
            Set<Long> affectedLessonIds,
            Map<Long, Period> periodById,
            List<String> warnings) {
        Map<Long, ConstraintContext.PeriodView> periodMap = new HashMap<>();
        periodRows.forEach(period -> periodMap.put(period.getId(),
                new ConstraintContext.PeriodView(
                        period.getId(), period.getDayOfWeek(), period.getPeriodNo())));
        Map<Long, ConstraintContext.CourseView> courseMap = new HashMap<>();
        courseRows.forEach(course -> courseMap.put(course.getId(),
                new ConstraintContext.CourseView(
                        course.getId(), course.getName())));
        Map<Long, ConstraintContext.TeacherView> teacherMap = new HashMap<>();
        teacherRows.forEach(teacher -> teacherMap.put(teacher.getId(),
                new ConstraintContext.TeacherView(
                        teacher.getId(), teacher.getName(), teacher.getSubject())));
        Map<Long, ConstraintContext.ClassView> classMap = new HashMap<>();
        classRows.forEach(item -> classMap.put(item.getId(), new ConstraintContext.ClassView(
                item.getId(), item.getName(), item.getStudentCount(), item.getHeadTeacherId())));
        ConstraintContext context = new ConstraintContext(
                periodMap, courseMap, teacherMap, classMap, settings);
        List<LessonView> views = lessons.stream()
                .map(lesson -> new LessonView(
                        lesson.getId(), lesson.getAdminClassId(), lesson.getCourseId(),
                        lesson.getTeacherId(), lesson.getPeriodId(),
                        lesson.getStudentCount()))
                .toList();

        FutureTask<List<ConstraintViolation>> task =
                new FutureTask<>(() -> ConstraintChecker.check(context, views, affectedLessonIds));
        Thread worker = new Thread(task, "rule-impact-check");
        worker.setDaemon(true);
        worker.start();
        List<ConstraintViolation> violations;
        try {
            violations = task.get(CONFLICT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            warnings.add("冲突计算超过 5 秒，已跳过冲突检查");
            return List.of();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            warnings.add("冲突计算被中断，已跳过冲突检查");
            return List.of();
        } catch (ExecutionException ex) {
            warnings.add("冲突计算失败，已跳过冲突检查");
            return List.of();
        }
        return violations.stream()
                .filter(violation -> violation.lessonId() == null
                        || affectedLessonIds.contains(violation.lessonId()))
                .map(violation -> new ImpactConflictDto(
                        violation.lessonId(), violation.code(), violation.message()))
                .toList();
    }

    // ----------------------------------------------------------------- helpers

    private TimetableDraft latestDraft(Long semesterId) {
        return drafts.findBySemesterIdOrderByCreatedAtDesc(semesterId).stream()
                .filter(draft -> DRAFT.equals(draft.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private static List<Map<String, Object>> paramsOf(ConstraintConfigRequest request) {
        if (request.entries() != null && !request.entries().isEmpty()) {
            return request.entries().stream()
                    .map(entry -> entry.params() == null ? Map.<String, Object>of() : entry.params())
                    .toList();
        }
        if (request.params() != null && !request.params().isEmpty()) {
            return List.of(request.params());
        }
        return List.of(Map.of());
    }

    private static ConstraintType typeOf(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        try {
            return ConstraintType.valueOf(code);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<ImpactObjectDto> objectDtos(
            List<Long> ids, Map<Long, String> names) {
        return ids.stream()
                .sorted(Comparator.comparing(
                        (Long id) -> names.getOrDefault(id, ""),
                        Comparator.naturalOrder()))
                .map(id -> new ImpactObjectDto(id, names.getOrDefault(id, "#" + id)))
                .toList();
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static Integer asInteger(Object value) {
        Long longValue = asLong(value);
        return longValue == null ? null : longValue.intValue();
    }

    private static String describeReference(TemplateReferenceIssue issue) {
        String where = issue.position() > 0
                ? issue.typeName() + "(" + issue.type() + ") 第" + issue.position() + " 条"
                : issue.typeName() + "(" + issue.type() + ")";
        return where + ": 参数「" + issue.fieldLabel() + "」"
                + (issue.objectId() == null ? "" : " #" + issue.objectId())
                + " " + issue.reason();
    }
}
