package com.paiban.scheduling.solver;
import org.springframework.util.StringUtils;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings.Control;
import com.paiban.scheduling.solver.ConstraintSettings.Requirement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Implements the whole preset constraint catalogue with Constraint Streams.
 *
 * <p>A rule is registered once per <em>distinct effective control</em> (hard, or a soft
 * weight). Each fact belongs to exactly one of those buckets because the control is
 * resolved through {@link ConstraintSettings#match}, so overlapping requirements score
 * the same fact once — hard when any overlapping requirement is hard, otherwise at the
 * highest soft weight. That is the solver-side twin of the checker and keeps the two
 * verdicts from drifting.
 */
public class PaibanConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        ConstraintSettings s = ConstraintSettingsHolder.get();
        List<Constraint> constraints = new ArrayList<>();

        // Structural constraints that are always on: every lesson must be placed and
        // course metadata (required room type) is part of the school's data model.
        constraints.add(unassignedLesson(factory));
        constraints.add(courseRoomTypeMetadata(factory, s));

        addSwitch(constraints, s, ConstraintType.TEACHER_CONFLICT, teacherConflict(factory, s));
        addSwitch(constraints, s, ConstraintType.CLASS_CONFLICT, classConflict(factory, s));
        addSwitch(constraints, s, ConstraintType.ROOM_CONFLICT, roomConflict(factory, s));
        addSwitch(constraints, s, ConstraintType.ROOM_CAPACITY_FIT, roomCapacity(factory, s));

        addProhibition(factory, constraints, s, ConstraintType.TEACHER_UNAVAILABLE,
                lesson -> true,
                lesson -> s.match(ConstraintType.TEACHER_UNAVAILABLE,
                        entry -> ConstraintEntryMatcher.teacherSlot(
                                entry, lesson.getTeacherId(),
                                lesson.getPeriod().getDayOfWeek(),
                                lesson.getPeriod().getPeriodNo())));
        addProhibition(factory, constraints, s, ConstraintType.TEACHER_MEETING_BLOCKED,
                lesson -> true,
                lesson -> s.match(ConstraintType.TEACHER_MEETING_BLOCKED,
                        entry -> ConstraintEntryMatcher.slot(
                                entry, lesson.getPeriod().getDayOfWeek(),
                                lesson.getPeriod().getPeriodNo())
                                && ConstraintEntryMatcher.meetingSubject(
                                        entry, lesson.getTeacherSubject())));
        addProhibition(factory, constraints, s, ConstraintType.COURSE_REQUIRED_ROOM_TYPE,
                lesson -> roomTypeMismatch(s, lesson),
                lesson -> courseStrictControl(s, ConstraintType.COURSE_REQUIRED_ROOM_TYPE,
                        lesson.getCourseId()));
        addProhibition(factory, constraints, s, ConstraintType.COURSE_NOT_IN_EVENING,
                lesson -> lesson.getPeriod().getSection() == PeriodSection.EVENING,
                lesson -> courseLooseControl(s, ConstraintType.COURSE_NOT_IN_EVENING,
                        lesson.getCourseId()));
        addProhibition(factory, constraints, s, ConstraintType.COURSE_UNAVAILABLE,
                lesson -> true,
                lesson -> s.match(ConstraintType.COURSE_UNAVAILABLE,
                        entry -> ConstraintEntryMatcher.courseSlot(
                                entry, lesson.getCourseId(),
                                lesson.getPeriod().getDayOfWeek(),
                                lesson.getPeriod().getPeriodNo())));

        addOncePerDay(factory, constraints, s);
        addThreshold(factory, constraints, s, ConstraintType.TEACHER_MAX_PER_DAY, Lesson::getTeacherId);
        addThreshold(factory, constraints, s, ConstraintType.CLASS_MAX_PER_DAY, Lesson::getAdminClassId);
        addCourseThreshold(factory, constraints, s);
        addThreshold(factory, constraints, s, ConstraintType.EVENING_MAX, Lesson::getAdminClassId);
        addProhibition(factory, constraints, s, ConstraintType.SPORT_NOT_FIRST_AFTERNOON,
                lesson -> lesson.getPeriod().isFirstAfternoon(),
                lesson -> sportControl(s, ConstraintType.SPORT_NOT_FIRST_AFTERNOON, lesson));
        addConsecutive(factory, constraints, s);
        addSpread(factory, constraints, s);
        addProhibition(factory, constraints, s, ConstraintType.MAIN_SUBJECT_MORNING,
                lesson -> {
                    Integer before = s.courseScalar(
                            ConstraintType.MAIN_SUBJECT_MORNING, lesson.getCourseId(), "beforePeriodNo");
                    return lesson.getPeriod() != null && before != null
                            && lesson.getPeriod().getPeriodNo() > before;
                },
                lesson -> courseStrictControl(s, ConstraintType.MAIN_SUBJECT_MORNING, lesson.getCourseId()));
        addPreferredSlots(factory, constraints, s);
        addMaxConsecutive(factory, constraints, s);
        addProhibition(factory, constraints, s, ConstraintType.TEACHER_NO_FIRST_PERIOD,
                lesson -> lesson.getPeriod().isFirstOfDay(),
                lesson -> teacherControl(s, ConstraintType.TEACHER_NO_FIRST_PERIOD, lesson.getTeacherId()));
        addProhibition(factory, constraints, s, ConstraintType.TEACHER_DAY_OFF,
                lesson -> true,
                lesson -> s.match(ConstraintType.TEACHER_DAY_OFF,
                        entry -> ConstraintEntryMatcher.dayOff(
                                entry, lesson.getTeacherId(),
                                lesson.getPeriod().getDayOfWeek())));
        addSwitch(constraints, s, ConstraintType.CLASS_GAP_MINIMIZED,
                classGapMinimized(factory, s));
        addProhibition(factory, constraints, s, ConstraintType.PE_LAST_PERIOD_AVOID,
                lesson -> lesson.getPeriod().isLastOfDay(),
                lesson -> sportControl(s, ConstraintType.PE_LAST_PERIOD_AVOID, lesson));
        addHeadTeacherFirstPeriod(factory, constraints, s);

        try {
            for (com.paiban.scheduling.solver.plugin.CustomConstraint plugin
                    : com.paiban.scheduling.solver.plugin.PluginConstraintLoader.load()) {
                constraints.addAll(plugin.define(factory, s));
            }
        } catch (RuntimeException ex) {
            // A broken plugin must never prevent the core solve.
            org.slf4j.LoggerFactory.getLogger(PaibanConstraintProvider.class)
                    .warn("忽略加载失败的约束插件: {}", ex.getMessage());
        }
        return constraints.toArray(new Constraint[0]);
    }

    // ------------------------------------------------------------- registration

    private void addSwitch(
            List<Constraint> out, ConstraintSettings s,
            ConstraintType type, Constraint constraint) {
        if (s.control(type) != null) {
            out.add(constraint);
        }
    }

    /**
     * Registers one stream per distinct control. {@code fact} says whether the rule
     * applies at all; {@code controlOf} resolves the single effective control for that
     * lesson, and only the stream whose control equals it matches — so a fact that
     * several requirements cover is scored exactly once.
     */
    private void addProhibition(
            ConstraintFactory factory, List<Constraint> out, ConstraintSettings s,
            ConstraintType type, Predicate<Lesson> fact, Function<Lesson, Control> controlOf) {
        for (Control control : controlsOf(s, type)) {
            out.add(factory.forEach(Lesson.class)
                    .filter(lesson -> lesson.getPeriod() != null
                            && fact.test(lesson)
                            && control.equals(controlOf.apply(lesson)))
                    .penalize(score(control))
                    .asConstraint(constraintName(type, control)));
        }
    }

    private List<Control> controlsOf(ConstraintSettings settings, ConstraintType type) {
        LinkedHashSet<Control> controls = new LinkedHashSet<>();
        for (Requirement requirement : settings.requirements(type)) {
            controls.add(requirement.control());
        }
        if (!type.isEntryBacked()) {
            Control control = settings.control(type);
            if (control != null) {
                controls.add(control);
            }
        }
        return List.copyOf(controls);
    }

    private HardSoftScore score(Control control) {
        return control.hard()
                ? HardSoftScore.ONE_HARD
                : HardSoftScore.ofSoft(Math.max(1, control.weight()));
    }

    private String constraintName(ConstraintType type, Control control) {
        return control.hard()
                ? type.getDisplayName()
                : type.getDisplayName() + "（软 " + control.weight() + "）";
    }

    // ---------- hard constraints ----------

    private Constraint unassignedLesson(ConstraintFactory f) {
        return f.forEachIncludingUnassigned(Lesson.class)
                .filter(lesson -> lesson.getPeriod() == null || lesson.getRoom() == null)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("课节未排");
    }

    /**
     * The course's own required room type is a hard invariant only while no configured
     * requirement replaces it. A configured entry carries its own hard/soft control
     * (see the {@code COURSE_REQUIRED_ROOM_TYPE} prohibition), so the solver, checker and
     * substitution planner all read the same effective value.
     */
    private Constraint courseRoomTypeMetadata(ConstraintFactory f, ConstraintSettings s) {
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && lesson.getRequiredRoomType() != null
                        && StringUtils.hasText(lesson.getRequiredRoomType())
                        && s.courseText(ConstraintType.COURSE_REQUIRED_ROOM_TYPE,
                        lesson.getCourseId(), "roomType") == null
                        && (lesson.getRoom() == null
                        || !lesson.getRequiredRoomType().equals(lesson.getRoom().getType())))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("课程教室类型要求");
    }

    private Constraint teacherConflict(ConstraintFactory f, ConstraintSettings s) {
        return f.forEachUniquePair(
                        Lesson.class,
                        Joiners.equal(Lesson::getTeacherId),
                        Joiners.equal(Lesson::getPeriod))
                .filter((a, b) -> a.getPeriod() != null)
                .penalize(penalty(s, ConstraintType.TEACHER_CONFLICT))
                .asConstraint(ConstraintType.TEACHER_CONFLICT.getDisplayName());
    }

    private Constraint classConflict(ConstraintFactory f, ConstraintSettings s) {
        return f.forEachUniquePair(
                        Lesson.class,
                        Joiners.equal(Lesson::getAdminClassId),
                        Joiners.equal(Lesson::getPeriod))
                .filter((a, b) -> a.getPeriod() != null)
                .penalize(penalty(s, ConstraintType.CLASS_CONFLICT))
                .asConstraint(ConstraintType.CLASS_CONFLICT.getDisplayName());
    }

    private Constraint roomConflict(ConstraintFactory f, ConstraintSettings s) {
        return f.forEachUniquePair(
                        Lesson.class,
                        Joiners.equal(Lesson::getRoom),
                        Joiners.equal(Lesson::getPeriod))
                .filter((a, b) -> a.getPeriod() != null && a.getRoom() != null)
                .penalize(penalty(s, ConstraintType.ROOM_CONFLICT))
                .asConstraint(ConstraintType.ROOM_CONFLICT.getDisplayName());
    }

    private Constraint roomCapacity(ConstraintFactory f, ConstraintSettings s) {
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getRoom() != null
                        && lesson.getRoom().getCapacity() < lesson.getStudentCount())
                .penalize(penalty(s, ConstraintType.ROOM_CAPACITY_FIT))
                .asConstraint(ConstraintType.ROOM_CAPACITY_FIT.getDisplayName());
    }

    // ---------- entry-backed prohibitions ----------

    private void addOncePerDay(ConstraintFactory f, List<Constraint> out, ConstraintSettings s) {
        addDailyOverflow(f, out, s, ConstraintType.SAME_COURSE_ONCE_PER_DAY);
    }

    private void addSpread(ConstraintFactory f, List<Constraint> out, ConstraintSettings s) {
        addDailyOverflow(f, out, s, ConstraintType.COURSE_SPREAD);
    }

    /** Both rules count one (class, course, day) bucket and penalise counts above one. */
    private void addDailyOverflow(
            ConstraintFactory f, List<Constraint> out, ConstraintSettings s,
            ConstraintType type) {
        for (Control control : controlsOf(s, type)) {
            out.add(f.forEach(Lesson.class)
                    .filter(lesson -> lesson.getPeriod() != null
                            && control.equals(courseLooseControl(s, type, lesson.getCourseId())))
                    .groupBy(
                            Lesson::getAdminClassId,
                            Lesson::getCourseId,
                            lesson -> lesson.getPeriod().getDayOfWeek(),
                            ConstraintCollectors.count())
                    .filter((classId, courseId, day, count) -> count > 1)
                    .penalize(score(control))
                    .asConstraint(constraintName(type, control)));
        }
    }

    private void addConsecutive(ConstraintFactory f, List<Constraint> out, ConstraintSettings s) {
        ConstraintType type = ConstraintType.CONSECUTIVE_LESSONS;
        for (Control control : controlsOf(s, type)) {
            out.add(f.forEach(Lesson.class)
                    .filter(lesson -> lesson.getPeriod() != null
                            && lesson.isAllowConsecutive()
                            && control.equals(courseStrictControl(s, type, lesson.getCourseId())))
                    .groupBy(
                            Lesson::getAdminClassId,
                            lesson -> lesson.getPeriod().getDayOfWeek(),
                            Lesson::getCourseId,
                            ConstraintCollectors.toList())
                    .filter((classId, day, courseId, lessons) -> {
                        Integer min = s.courseScalar(type, courseId, "minConsecutive");
                        return min != null && longestRun(lessons) < min;
                    })
                    .penalize(score(control))
                    .asConstraint(constraintName(type, control)));
        }
    }

    // ---------- thresholds ----------

    private void addThreshold(
            ConstraintFactory f, List<Constraint> out, ConstraintSettings s,
            ConstraintType type, Function<Lesson, Long> objectOf) {
        for (Control control : controlsOf(s, type)) {
            out.add(f.forEach(Lesson.class)
                    .filter(lesson -> lesson.getPeriod() != null)
                    .groupBy(
                            objectOf,
                            lesson -> lesson.getPeriod().getDayOfWeek(),
                            ConstraintCollectors.count())
                    .filter((objectId, day, count) -> control.equals(s.thresholdControl(type, objectId))
                            && !s.withinLimit(type, objectId, count))
                    .penalize(score(control))
                    .asConstraint(constraintName(type, control)));
        }
    }

    private void addCourseThreshold(ConstraintFactory f, List<Constraint> out, ConstraintSettings s) {
        ConstraintType type = ConstraintType.COURSE_MAX_PER_DAY;
        for (Control control : controlsOf(s, type)) {
            out.add(f.forEach(Lesson.class)
                    .filter(lesson -> lesson.getPeriod() != null)
                    .groupBy(
                            Lesson::getAdminClassId,
                            Lesson::getCourseId,
                            lesson -> lesson.getPeriod().getDayOfWeek(),
                            ConstraintCollectors.count())
                    .filter((classId, courseId, day, count) ->
                            control.equals(s.thresholdControl(type, courseId))
                                    && !s.withinLimit(type, courseId, count))
                    .penalize(score(control))
                    .asConstraint(constraintName(type, control)));
        }
    }

    private void addMaxConsecutive(ConstraintFactory f, List<Constraint> out, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_MAX_CONSECUTIVE;
        for (Control control : controlsOf(s, type)) {
            out.add(f.forEach(Lesson.class)
                    .filter(lesson -> lesson.getPeriod() != null)
                    .groupBy(
                            Lesson::getTeacherId,
                            lesson -> lesson.getPeriod().getDayOfWeek(),
                            ConstraintCollectors.toList())
                    .filter((teacherId, day, lessons) ->
                            control.equals(s.thresholdControl(type, teacherId))
                                    && !s.withinLimit(type, teacherId, maxConsecutive(lessons)))
                    .penalize(score(control))
                    .asConstraint(constraintName(type, control)));
        }
    }

    // ---------- preferred slots ----------

    /**
     * A teacher's active preference rows form one optional set: a lesson in any of them
     * satisfies the whole set. With a hard requirement anywhere in that set the miss is
     * hard; an all-soft set rewards each hit at its highest weight (the checker scores
     * the misses, which is the same ordering).
     */
    private void addPreferredSlots(ConstraintFactory f, List<Constraint> out, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_PREFERRED_SLOT;
        Map<Long, Control> controlByTeacher = new HashMap<>();
        Map<Long, LinkedHashSet<String>> slotsByTeacher = new HashMap<>();
        for (Requirement requirement : s.requirements(type)) {
            for (Map<String, Object> entry : requirement.entries()) {
                Long teacherId = ConstraintParams.teacherId(entry);
                if (teacherId == null) {
                    continue;
                }
                Control current = controlByTeacher.get(teacherId);
                if (requirement.hard()) {
                    controlByTeacher.put(teacherId, new Control(true, 0));
                } else if (current == null || (!current.hard() && requirement.weight() > current.weight())) {
                    controlByTeacher.put(teacherId, new Control(false, requirement.weight()));
                }
                Integer day = ConstraintParams.dayOfWeek(entry);
                Integer periodNo = ConstraintParams.periodNo(entry);
                slotsByTeacher.computeIfAbsent(teacherId, ignored -> new LinkedHashSet<>())
                        .add(day + "-" + periodNo);
            }
        }
        for (Control control : new LinkedHashSet<>(controlByTeacher.values())) {
            if (control.hard()) {
                out.add(f.forEach(Lesson.class)
                        .filter(lesson -> lesson.getPeriod() != null
                                && control.equals(controlByTeacher.get(lesson.getTeacherId()))
                                && !preferredSlot(slotsByTeacher, lesson))
                        .penalize(HardSoftScore.ONE_HARD)
                        .asConstraint(constraintName(type, control)));
            } else {
                out.add(f.forEach(Lesson.class)
                        .filter(lesson -> lesson.getPeriod() != null
                                && control.equals(controlByTeacher.get(lesson.getTeacherId()))
                                && preferredSlot(slotsByTeacher, lesson))
                        .reward(HardSoftScore.ofSoft(Math.max(1, control.weight())))
                        .asConstraint(constraintName(type, control)));
            }
        }
    }

    private boolean preferredSlot(Map<Long, LinkedHashSet<String>> slotsByTeacher, Lesson lesson) {
        LinkedHashSet<String> slots = slotsByTeacher.get(lesson.getTeacherId());
        if (slots == null) {
            return false;
        }
        return slots.contains(lesson.getPeriod().getDayOfWeek() + "-" + lesson.getPeriod().getPeriodNo());
    }

    // ---------- remaining switches ----------

    private Constraint classGapMinimized(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.CLASS_GAP_MINIMIZED;
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null)
                .groupBy(
                        Lesson::getAdminClassId,
                        lesson -> lesson.getPeriod().getDayOfWeek(),
                        ConstraintCollectors.toList(lesson -> lesson.getPeriod().getPeriodNo()))
                .filter((classId, day, periodNos) -> gapCount(periodNos) > 0)
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private void addHeadTeacherFirstPeriod(
            ConstraintFactory f, List<Constraint> out, ConstraintSettings s) {
        ConstraintType type = ConstraintType.HEAD_TEACHER_FIRST_PERIOD;
        Control control = s.control(type);
        if (control == null) {
            return;
        }
        Predicate<Lesson> headTeacher = lesson -> lesson.getTeacherId() != null
                && lesson.getTeacherId().equals(lesson.getHeadTeacherId());
        if (control.hard()) {
            out.add(f.forEach(Lesson.class)
                    .filter(lesson -> lesson.getPeriod() != null
                            && headTeacher.test(lesson)
                            && !lesson.getPeriod().isFirstOfDay())
                    .penalize(HardSoftScore.ONE_HARD)
                    .asConstraint(type.getDisplayName()));
        } else {
            out.add(f.forEach(Lesson.class)
                    .filter(lesson -> lesson.getPeriod() != null
                            && headTeacher.test(lesson)
                            && lesson.getPeriod().isFirstOfDay())
                    .reward(HardSoftScore.ofSoft(Math.max(1, control.weight())))
                    .asConstraint(type.getDisplayName()));
        }
    }

    // ---------- helpers ----------

    private HardSoftScore penalty(ConstraintSettings settings, ConstraintType type) {
        Control control = settings.control(type);
        return control == null ? HardSoftScore.ZERO : score(control);
    }

    private boolean roomTypeMismatch(ConstraintSettings s, Lesson lesson) {
        String configured = s.courseText(
                ConstraintType.COURSE_REQUIRED_ROOM_TYPE, lesson.getCourseId(), "roomType");
        if (configured == null || !StringUtils.hasText(configured)) {
            return false;
        }
        return lesson.getRoom() != null && !configured.equals(lesson.getRoom().getType());
    }

    private Control courseLooseControl(
            ConstraintSettings settings, ConstraintType type, Long courseId) {
        return settings.match(type, entry -> ConstraintEntryMatcher.courseOrAll(entry, courseId));
    }

    private Control courseStrictControl(
            ConstraintSettings settings, ConstraintType type, Long courseId) {
        return settings.match(type, entry -> ConstraintEntryMatcher.course(entry, courseId));
    }

    private Control teacherControl(
            ConstraintSettings settings, ConstraintType type, Long teacherId) {
        if (teacherId == null) {
            return null;
        }
        return settings.match(type, entry -> ConstraintEntryMatcher.teacher(entry, teacherId));
    }

    private Control sportControl(
            ConstraintSettings settings, ConstraintType type, Lesson lesson) {
        return settings.match(type, entry -> ConstraintEntryMatcher.courseOrSport(
                entry, lesson.getCourseId(),
                lesson.getCourseName() != null && lesson.getCourseName().contains("体育")));
    }

    private int maxConsecutive(List<Lesson> lessons) {
        List<Integer> periodNos = lessons.stream()
                .map(lesson -> lesson.getPeriod().getPeriodNo())
                .sorted()
                .distinct()
                .toList();
        int best = 0;
        int run = 1;
        for (int i = 0; i < periodNos.size(); i++) {
            if (i > 0 && periodNos.get(i) == periodNos.get(i - 1) + 1) {
                run++;
            } else {
                run = 1;
            }
            best = Math.max(best, run);
        }
        return best;
    }

    private int longestRun(List<Lesson> lessons) {
        List<Integer> periodNos = lessons.stream()
                .map(lesson -> lesson.getPeriod().getPeriodNo())
                .sorted()
                .distinct()
                .toList();
        int best = 0;
        int run = 1;
        for (int i = 0; i < periodNos.size(); i++) {
            if (i > 0 && periodNos.get(i) == periodNos.get(i - 1) + 1) {
                run++;
            } else {
                run = 1;
            }
            best = Math.max(best, run);
        }
        return best;
    }

    private int gapCount(List<Integer> periodNos) {
        List<Integer> sorted = periodNos.stream().distinct().sorted().toList();
        int gaps = 0;
        for (int i = 1; i < sorted.size(); i++) {
            gaps += Math.max(0, sorted.get(i) - sorted.get(i - 1) - 1);
        }
        return gaps;
    }
}
