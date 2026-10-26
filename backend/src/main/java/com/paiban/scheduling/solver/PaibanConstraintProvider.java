package com.paiban.scheduling.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import com.paiban.scheduling.domain.ConstraintType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implements the whole preset constraint catalogue with Constraint Streams.
 * Enabled state, hard/soft mode and weight come from {@link ConstraintSettings}.
 */
public class PaibanConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        ConstraintSettings s = ConstraintSettingsHolder.get();
        java.util.List<Constraint> constraints = new java.util.ArrayList<>();

        // Structural constraints that are always on: every lesson must be placed and
        // course metadata (required room type) is part of the school's data model.
        constraints.add(unassignedLesson(factory));
        constraints.add(courseRoomTypeMetadata(factory));

        if (s.enabled(ConstraintType.TEACHER_CONFLICT)) {
            constraints.add(teacherConflict(factory, s));
        }
        if (s.enabled(ConstraintType.CLASS_CONFLICT)) {
            constraints.add(classConflict(factory, s));
        }
        if (s.enabled(ConstraintType.ROOM_CONFLICT)) {
            constraints.add(roomConflict(factory, s));
        }
        if (s.enabled(ConstraintType.ROOM_CAPACITY_FIT)) {
            constraints.add(roomCapacity(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_UNAVAILABLE)
                && !s.entries(ConstraintType.TEACHER_UNAVAILABLE).isEmpty()) {
            constraints.add(teacherUnavailable(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_MEETING_BLOCKED)
                && !s.entries(ConstraintType.TEACHER_MEETING_BLOCKED).isEmpty()) {
            constraints.add(teacherMeetingBlocked(factory, s));
        }
        if (s.enabled(ConstraintType.COURSE_REQUIRED_ROOM_TYPE)
                && !s.entries(ConstraintType.COURSE_REQUIRED_ROOM_TYPE).isEmpty()) {
            constraints.add(courseRequiredRoomType(factory, s));
        }
        if (s.enabled(ConstraintType.COURSE_NOT_IN_EVENING)
                && !s.entries(ConstraintType.COURSE_NOT_IN_EVENING).isEmpty()) {
            constraints.add(courseNotInEvening(factory, s));
        }
        if (s.enabled(ConstraintType.SAME_COURSE_ONCE_PER_DAY)
                && !s.entries(ConstraintType.SAME_COURSE_ONCE_PER_DAY).isEmpty()) {
            constraints.add(sameCourseOncePerDay(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_MAX_PER_DAY)) {
            constraints.add(teacherMaxPerDay(factory, s));
        }
        if (s.enabled(ConstraintType.CLASS_MAX_PER_DAY)) {
            constraints.add(classMaxPerDay(factory, s));
        }
        if (s.enabled(ConstraintType.COURSE_MAX_PER_DAY)) {
            constraints.add(courseMaxPerDay(factory, s));
        }
        if (s.enabled(ConstraintType.EVENING_MAX)) {
            constraints.add(eveningMax(factory, s));
        }
        if (s.enabled(ConstraintType.SPORT_NOT_FIRST_AFTERNOON)
                && !s.entries(ConstraintType.SPORT_NOT_FIRST_AFTERNOON).isEmpty()) {
            constraints.add(sportNotFirstAfternoon(factory, s));
        }
        if (s.enabled(ConstraintType.CONSECUTIVE_LESSONS)
                && !s.entries(ConstraintType.CONSECUTIVE_LESSONS).isEmpty()) {
            constraints.add(consecutiveLessons(factory, s));
        }
        if (s.enabled(ConstraintType.COURSE_SPREAD)
                && !s.entries(ConstraintType.COURSE_SPREAD).isEmpty()) {
            constraints.add(courseSpread(factory, s));
        }
        if (s.enabled(ConstraintType.MAIN_SUBJECT_MORNING)
                && !s.entries(ConstraintType.MAIN_SUBJECT_MORNING).isEmpty()) {
            constraints.add(mainSubjectMorning(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_PREFERRED_SLOT)
                && !s.entries(ConstraintType.TEACHER_PREFERRED_SLOT).isEmpty()) {
            constraints.add(teacherPreferredSlot(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_MAX_CONSECUTIVE)) {
            constraints.add(teacherMaxConsecutive(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_NO_FIRST_PERIOD)
                && !s.entries(ConstraintType.TEACHER_NO_FIRST_PERIOD).isEmpty()) {
            constraints.add(teacherNoFirstPeriod(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_DAY_OFF)
                && !s.entries(ConstraintType.TEACHER_DAY_OFF).isEmpty()) {
            constraints.add(teacherDayOff(factory, s));
        }
        if (s.enabled(ConstraintType.CLASS_GAP_MINIMIZED)) {
            constraints.add(classGapMinimized(factory, s));
        }
        if (s.enabled(ConstraintType.PE_LAST_PERIOD_AVOID)
                && !s.entries(ConstraintType.PE_LAST_PERIOD_AVOID).isEmpty()) {
            constraints.add(peLastPeriodAvoid(factory, s));
        }
        if (s.enabled(ConstraintType.HEAD_TEACHER_FIRST_PERIOD)) {
            constraints.add(headTeacherFirstPeriod(factory, s));
        }
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

    // ---------- hard constraints ----------

    private Constraint unassignedLesson(ConstraintFactory f) {
        return f.forEachIncludingUnassigned(Lesson.class)
                .filter(lesson -> lesson.getPeriod() == null || lesson.getRoom() == null)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("课节未排");
    }

    private Constraint courseRoomTypeMetadata(ConstraintFactory f) {
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && lesson.getRequiredRoomType() != null
                        && !lesson.getRequiredRoomType().isBlank()
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

    private Constraint teacherUnavailable(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_UNAVAILABLE;
        Map<Long, List<int[]>> blockedSlots = new HashMap<>();
        for (Map<String, Object> entry : s.entries(type)) {
            Long teacherId = ConstraintSettings.longValue(entry.get("teacherId"));
            Integer day = ConstraintSettings.intValue(entry.get("dayOfWeek"));
            Integer periodNo = ConstraintSettings.intValue(entry.get("periodNo"));
            if (teacherId == null || day == null || periodNo == null) {
                continue;
            }
            blockedSlots.computeIfAbsent(teacherId, key -> new ArrayList<>())
                    .add(new int[] {day, periodNo});
        }
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && isBlocked(blockedSlots, lesson.getTeacherId(),
                        lesson.getPeriod().getDayOfWeek(), lesson.getPeriod().getPeriodNo()))
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    /** True when any entry of this teacher forbids the slot: the union of all entries. */
    private boolean isBlocked(Map<Long, List<int[]>> blockedSlots, Long teacherId,
                              int dayOfWeek, int periodNo) {
        List<int[]> slots = teacherId == null ? null : blockedSlots.get(teacherId);
        if (slots == null) {
            return false;
        }
        for (int[] slot : slots) {
            if (slot[0] == dayOfWeek && slot[1] == periodNo) {
                return true;
            }
        }
        return false;
    }

    private Constraint teacherMeetingBlocked(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_MEETING_BLOCKED;
        // key "day-period" -> subjects blocked there; "" means every subject.
        Map<String, List<String>> bySlot = new HashMap<>();
        for (Map<String, Object> entry : s.entries(type)) {
            Integer day = ConstraintSettings.intValue(entry.get("dayOfWeek"));
            Integer periodNo = ConstraintSettings.intValue(entry.get("periodNo"));
            if (day == null || periodNo == null) {
                continue;
            }
            Object rawSubject = entry.get("subject");
            String subject = rawSubject == null || rawSubject.toString().isBlank()
                    ? "" : rawSubject.toString();
            bySlot.computeIfAbsent(day + "-" + periodNo, key -> new ArrayList<>()).add(subject);
        }
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && blockedByMeeting(bySlot, lesson.getPeriod().getDayOfWeek(),
                        lesson.getPeriod().getPeriodNo(), lesson.getTeacherSubject()))
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    /** Union of the meeting entries: any entry whose subject matches (or is blank) blocks. */
    private boolean blockedByMeeting(Map<String, List<String>> bySlot, int day, int periodNo,
                                     String teacherSubject) {
        List<String> subjects = bySlot.get(day + "-" + periodNo);
        if (subjects == null) {
            return false;
        }
        for (String subject : subjects) {
            if (subject.isEmpty() || subject.equalsIgnoreCase(teacherSubject)) {
                return true;
            }
        }
        return false;
    }

    private Constraint courseRequiredRoomType(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.COURSE_REQUIRED_ROOM_TYPE;
        // Different courses never interfere; one entry per course (a second, different
        // room type for the same course is rejected at save time).
        Map<Long, String> byCourse = new HashMap<>();
        for (Map<String, Object> entry : s.entries(type)) {
            Long courseId = ConstraintSettings.longValue(entry.get("courseId"));
            Object roomType = entry.get("roomType");
            if (courseId != null && roomType != null) {
                byCourse.putIfAbsent(courseId, roomType.toString());
            }
        }
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getRoom() != null
                        && byCourse.containsKey(lesson.getCourseId())
                        && !Objects.equals(lesson.getRoom().getType(),
                        byCourse.get(lesson.getCourseId())))
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint courseNotInEvening(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.COURSE_NOT_IN_EVENING;
        List<Long> courses = new ArrayList<>();
        for (Map<String, Object> entry : s.entries(type)) {
            Long courseId = ConstraintSettings.longValue(entry.get("courseId"));
            if (courseId != null) {
                courses.add(courseId);
            }
        }
        return f.forEach(Lesson.class)
                .filter(lesson -> courses.contains(lesson.getCourseId())
                        && lesson.getPeriod() != null
                        && lesson.getPeriod().getSection() == com.paiban.foundation.domain.PeriodSection.EVENING)
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint sameCourseOncePerDay(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.SAME_COURSE_ONCE_PER_DAY;
        return f.forEachUniquePair(
                        Lesson.class,
                        Joiners.equal(Lesson::getAdminClassId),
                        Joiners.equal(Lesson::getCourseId),
                        Joiners.equal(lesson -> lesson.getPeriod() == null
                                ? -1
                                : lesson.getPeriod().getDayOfWeek()))
                .filter((a, b) -> a.getPeriod() != null && s.matchesCourse(type, a.getCourseId()))
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    // ---------- soft constraints ----------

    private Constraint teacherMaxPerDay(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_MAX_PER_DAY;
        int max = s.intParam(type, "maxPerDay") == null ? 4 : s.intParam(type, "maxPerDay");
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null)
                .groupBy(
                        Lesson::getTeacherId,
                        lesson -> lesson.getPeriod().getDayOfWeek(),
                        ConstraintCollectors.count())
                .filter((teacherId, day, count) -> count > max)
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint classMaxPerDay(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.CLASS_MAX_PER_DAY;
        int max = s.intParam(type, "maxPerDay") == null ? 8 : s.intParam(type, "maxPerDay");
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null)
                .groupBy(
                        Lesson::getAdminClassId,
                        lesson -> lesson.getPeriod().getDayOfWeek(),
                        ConstraintCollectors.count())
                .filter((classId, day, count) -> count > max)
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint courseMaxPerDay(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.COURSE_MAX_PER_DAY;
        int max = s.intParam(type, "maxPerDay") == null ? 2 : s.intParam(type, "maxPerDay");
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null)
                .groupBy(
                        Lesson::getAdminClassId,
                        Lesson::getCourseId,
                        lesson -> lesson.getPeriod().getDayOfWeek(),
                        ConstraintCollectors.count())
                .filter((classId, courseId, day, count) -> count > max)
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint eveningMax(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.EVENING_MAX;
        int max = s.intParam(type, "maxPerDay") == null ? 2 : s.intParam(type, "maxPerDay");
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && lesson.getPeriod().getSection()
                        == com.paiban.foundation.domain.PeriodSection.EVENING)
                .groupBy(
                        Lesson::getAdminClassId,
                        lesson -> lesson.getPeriod().getDayOfWeek(),
                        ConstraintCollectors.count())
                .filter((classId, day, count) -> count > max)
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint sportNotFirstAfternoon(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.SPORT_NOT_FIRST_AFTERNOON;
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && lesson.getPeriod().isFirstAfternoon()
                        && isSport(lesson, s, type))
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint consecutiveLessons(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.CONSECUTIVE_LESSONS;
        // One entry per course, so each course is judged by its own minimum and
        // courses never interfere.
        Map<Long, Integer> minByCourse = new HashMap<>();
        for (Map<String, Object> entry : s.entries(type)) {
            Long courseId = ConstraintSettings.longValue(entry.get("courseId"));
            Integer min = ConstraintSettings.intValue(entry.get("minConsecutive"));
            if (courseId != null) {
                minByCourse.putIfAbsent(courseId, min == null ? 2 : min);
            }
        }
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && lesson.isAllowConsecutive()
                        && minByCourse.containsKey(lesson.getCourseId()))
                .groupBy(
                        Lesson::getAdminClassId,
                        lesson -> lesson.getPeriod().getDayOfWeek(),
                        Lesson::getCourseId,
                        ConstraintCollectors.toList())
                .filter((classId, day, courseId, lessons) -> {
                    Integer min = minByCourse.get(courseId);
                    return min != null && longestRun(lessons) < min;
                })
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint courseSpread(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.COURSE_SPREAD;
        return f.forEachUniquePair(
                        Lesson.class,
                        Joiners.equal(Lesson::getAdminClassId),
                        Joiners.equal(Lesson::getCourseId),
                        Joiners.equal(lesson -> lesson.getPeriod() == null
                                ? -1
                                : lesson.getPeriod().getDayOfWeek()))
                .filter((a, b) -> a.getPeriod() != null
                        && s.matchesCourse(type, a.getCourseId())
                        && a.getPeriod().getPeriodNo() != b.getPeriod().getPeriodNo())
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint mainSubjectMorning(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.MAIN_SUBJECT_MORNING;
        // Each course carries its own cut-off; the union of entries decides which
        // courses are constrained at all.
        Map<Long, Integer> beforeByCourse = new HashMap<>();
        for (Map<String, Object> entry : s.entries(type)) {
            Long courseId = ConstraintSettings.longValue(entry.get("courseId"));
            Integer before = ConstraintSettings.intValue(entry.get("beforePeriodNo"));
            if (courseId != null) {
                beforeByCourse.putIfAbsent(courseId, before == null ? 4 : before);
            }
        }
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && beforeByCourse.containsKey(lesson.getCourseId())
                        && lesson.getPeriod().getPeriodNo() > beforeByCourse.get(lesson.getCourseId()))
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint teacherPreferredSlot(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_PREFERRED_SLOT;
        // One teacher may list several slots: they are an OR set, a lesson in any one
        // of them satisfies the preference.
        Map<Long, List<int[]>> slotsByTeacher = new HashMap<>();
        for (Map<String, Object> entry : s.entries(type)) {
            Long teacherId = ConstraintSettings.longValue(entry.get("teacherId"));
            Integer day = ConstraintSettings.intValue(entry.get("dayOfWeek"));
            Integer periodNo = ConstraintSettings.intValue(entry.get("periodNo"));
            if (teacherId == null || day == null || periodNo == null) {
                continue;
            }
            slotsByTeacher.computeIfAbsent(teacherId, key -> new ArrayList<>())
                    .add(new int[] {day, periodNo});
        }
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && isPreferred(slotsByTeacher, lesson.getTeacherId(),
                        lesson.getPeriod().getDayOfWeek(), lesson.getPeriod().getPeriodNo()))
                .reward(HardSoftScore.ofSoft(Math.max(1, s.weight(type))))
                .asConstraint(type.getDisplayName());
    }

    /** True when the slot is one of the teacher's preferred slots (any entry). */
    private boolean isPreferred(Map<Long, List<int[]>> slotsByTeacher, Long teacherId,
                                int dayOfWeek, int periodNo) {
        List<int[]> slots = teacherId == null ? null : slotsByTeacher.get(teacherId);
        if (slots == null) {
            return false;
        }
        for (int[] slot : slots) {
            if (slot[0] == dayOfWeek && slot[1] == periodNo) {
                return true;
            }
        }
        return false;
    }

    private Constraint teacherMaxConsecutive(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_MAX_CONSECUTIVE;
        int max = s.intParam(type, "maxConsecutive") == null ? 3 : s.intParam(type, "maxConsecutive");
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null)
                .groupBy(
                        Lesson::getTeacherId,
                        lesson -> lesson.getPeriod().getDayOfWeek(),
                        ConstraintCollectors.toList())
                .filter((teacherId, day, lessons) -> maxConsecutive(lessons) > max)
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint teacherNoFirstPeriod(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_NO_FIRST_PERIOD;
        List<Long> teacherIds = new ArrayList<>();
        for (Map<String, Object> entry : s.entries(type)) {
            Long teacherId = ConstraintSettings.longValue(entry.get("teacherId"));
            if (teacherId != null) {
                teacherIds.add(teacherId);
            }
        }
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && teacherIds.contains(lesson.getTeacherId())
                        && lesson.getPeriod().isFirstOfDay())
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint teacherDayOff(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_DAY_OFF;
        // Union of the (teacher, day) pairs: a teacher may have several days off.
        Map<Long, List<Integer>> daysByTeacher = new HashMap<>();
        for (Map<String, Object> entry : s.entries(type)) {
            Long teacherId = ConstraintSettings.longValue(entry.get("teacherId"));
            Integer day = ConstraintSettings.intValue(entry.get("dayOfWeek"));
            if (teacherId == null || day == null) {
                continue;
            }
            daysByTeacher.computeIfAbsent(teacherId, key -> new ArrayList<>()).add(day);
        }
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && isDayOff(daysByTeacher, lesson.getTeacherId(),
                        lesson.getPeriod().getDayOfWeek()))
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private boolean isDayOff(Map<Long, List<Integer>> daysByTeacher, Long teacherId, int dayOfWeek) {
        List<Integer> days = teacherId == null ? null : daysByTeacher.get(teacherId);
        return days != null && days.contains(dayOfWeek);
    }

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

    private Constraint peLastPeriodAvoid(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.PE_LAST_PERIOD_AVOID;
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && lesson.getPeriod().isLastOfDay()
                        && isSport(lesson, s, type))
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint headTeacherFirstPeriod(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.HEAD_TEACHER_FIRST_PERIOD;
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && lesson.getTeacherId() != null
                        && lesson.getTeacherId().equals(lesson.getHeadTeacherId())
                        && lesson.getPeriod().isFirstOfDay())
                .reward(HardSoftScore.ofSoft(Math.max(1, s.weight(type))))
                .asConstraint(type.getDisplayName());
    }

    // ---------- helpers ----------

    private HardSoftScore penalty(ConstraintSettings settings, ConstraintType type) {
        return settings.hard(type)
                ? HardSoftScore.ONE_HARD
                : HardSoftScore.ofSoft(Math.max(1, settings.weight(type)));
    }

    /**
     * Union of the sport-flavoured entries: an entry with a course matches that
     * course, an entry without one matches the school's sport-class courses. A
     * lesson that both an "all sport" entry and a concrete one match is still a
     * single violation, because this returns one boolean per lesson.
     */
    private boolean isSport(Lesson lesson, ConstraintSettings settings, ConstraintType type) {
        for (Map<String, Object> entry : settings.entries(type)) {
            Long configured = ConstraintSettings.longValue(entry.get("courseId"));
            if (configured != null) {
                if (configured.equals(lesson.getCourseId())) {
                    return true;
                }
            } else if (lesson.getCourseName() != null && lesson.getCourseName().contains("体育")) {
                return true;
            }
        }
        return false;
    }

    private int maxConsecutive(List<Lesson> lessons) {        List<Integer> periodNos = lessons.stream()
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
