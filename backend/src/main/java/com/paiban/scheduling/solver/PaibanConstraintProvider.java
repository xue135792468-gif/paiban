package com.paiban.scheduling.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import com.paiban.scheduling.domain.ConstraintType;
import java.util.List;
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
                && s.longParam(ConstraintType.TEACHER_UNAVAILABLE, "teacherId") != null) {
            constraints.add(teacherUnavailable(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_MEETING_BLOCKED)
                && s.intParam(ConstraintType.TEACHER_MEETING_BLOCKED, "dayOfWeek") != null) {
            constraints.add(teacherMeetingBlocked(factory, s));
        }
        if (s.enabled(ConstraintType.COURSE_REQUIRED_ROOM_TYPE)
                && s.longParam(ConstraintType.COURSE_REQUIRED_ROOM_TYPE, "courseId") != null) {
            constraints.add(courseRequiredRoomType(factory, s));
        }
        if (s.enabled(ConstraintType.COURSE_NOT_IN_EVENING)
                && s.longParam(ConstraintType.COURSE_NOT_IN_EVENING, "courseId") != null) {
            constraints.add(courseNotInEvening(factory, s));
        }
        if (s.enabled(ConstraintType.SAME_COURSE_ONCE_PER_DAY)) {
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
        if (s.enabled(ConstraintType.SPORT_NOT_FIRST_AFTERNOON)) {
            constraints.add(sportNotFirstAfternoon(factory, s));
        }
        if (s.enabled(ConstraintType.CONSECUTIVE_LESSONS)) {
            constraints.add(consecutiveLessons(factory, s));
        }
        if (s.enabled(ConstraintType.COURSE_SPREAD)) {
            constraints.add(courseSpread(factory, s));
        }
        if (s.enabled(ConstraintType.MAIN_SUBJECT_MORNING)
                && s.longParam(ConstraintType.MAIN_SUBJECT_MORNING, "courseId") != null) {
            constraints.add(mainSubjectMorning(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_PREFERRED_SLOT)
                && s.longParam(ConstraintType.TEACHER_PREFERRED_SLOT, "teacherId") != null) {
            constraints.add(teacherPreferredSlot(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_MAX_CONSECUTIVE)) {
            constraints.add(teacherMaxConsecutive(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_NO_FIRST_PERIOD)
                && s.longParam(ConstraintType.TEACHER_NO_FIRST_PERIOD, "teacherId") != null) {
            constraints.add(teacherNoFirstPeriod(factory, s));
        }
        if (s.enabled(ConstraintType.TEACHER_DAY_OFF)
                && s.longParam(ConstraintType.TEACHER_DAY_OFF, "teacherId") != null) {
            constraints.add(teacherDayOff(factory, s));
        }
        if (s.enabled(ConstraintType.CLASS_GAP_MINIMIZED)) {
            constraints.add(classGapMinimized(factory, s));
        }
        if (s.enabled(ConstraintType.PE_LAST_PERIOD_AVOID)) {
            constraints.add(peLastPeriodAvoid(factory, s));
        }
        if (s.enabled(ConstraintType.HEAD_TEACHER_FIRST_PERIOD)) {
            constraints.add(headTeacherFirstPeriod(factory, s));
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
        Long teacherId = s.longParam(type, "teacherId");
        Integer day = s.intParam(type, "dayOfWeek");
        Integer periodNo = s.intParam(type, "periodNo");
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && Objects.equals(lesson.getTeacherId(), teacherId)
                        && lesson.getPeriod().getDayOfWeek() == day
                        && lesson.getPeriod().getPeriodNo() == periodNo)
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint teacherMeetingBlocked(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_MEETING_BLOCKED;
        String subject = s.textParam(type, "subject");
        Integer day = s.intParam(type, "dayOfWeek");
        Integer periodNo = s.intParam(type, "periodNo");
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && (subject == null || subject.isBlank()
                        || subject.equalsIgnoreCase(lesson.getTeacherSubject()))
                        && lesson.getPeriod().getDayOfWeek() == day
                        && lesson.getPeriod().getPeriodNo() == periodNo)
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint courseRequiredRoomType(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.COURSE_REQUIRED_ROOM_TYPE;
        Long courseId = s.longParam(type, "courseId");
        String roomType = s.textParam(type, "roomType");
        return f.forEach(Lesson.class)
                .filter(lesson -> Objects.equals(lesson.getCourseId(), courseId)
                        && lesson.getRoom() != null
                        && !Objects.equals(lesson.getRoom().getType(), roomType))
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint courseNotInEvening(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.COURSE_NOT_IN_EVENING;
        Long courseId = s.longParam(type, "courseId");
        return f.forEach(Lesson.class)
                .filter(lesson -> Objects.equals(lesson.getCourseId(), courseId)
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
        return f.forEachUniquePair(
                        Lesson.class,
                        Joiners.equal(Lesson::getAdminClassId),
                        Joiners.equal(Lesson::getCourseId),
                        Joiners.equal(lesson -> lesson.getPeriod() == null
                                ? -1
                                : lesson.getPeriod().getDayOfWeek()))
                .filter((a, b) -> a.getPeriod() != null
                        && a.isAllowConsecutive()
                        && s.matchesCourse(type, a.getCourseId())
                        && Math.abs(a.getPeriod().getPeriodNo() - b.getPeriod().getPeriodNo()) == 1)
                .reward(HardSoftScore.ofSoft(Math.max(1, s.weight(type))))
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
        Long courseId = s.longParam(type, "courseId");
        int before = s.intParam(type, "beforePeriodNo") == null ? 4 : s.intParam(type, "beforePeriodNo");
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && Objects.equals(lesson.getCourseId(), courseId)
                        && lesson.getPeriod().getPeriodNo() > before)
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint teacherPreferredSlot(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_PREFERRED_SLOT;
        Long teacherId = s.longParam(type, "teacherId");
        Integer day = s.intParam(type, "dayOfWeek");
        Integer periodNo = s.intParam(type, "periodNo");
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && Objects.equals(lesson.getTeacherId(), teacherId)
                        && lesson.getPeriod().getDayOfWeek() == day
                        && lesson.getPeriod().getPeriodNo() == periodNo)
                .reward(HardSoftScore.ofSoft(Math.max(1, s.weight(type))))
                .asConstraint(type.getDisplayName());
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
        Long teacherId = s.longParam(type, "teacherId");
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && Objects.equals(lesson.getTeacherId(), teacherId)
                        && lesson.getPeriod().isFirstOfDay())
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
    }

    private Constraint teacherDayOff(ConstraintFactory f, ConstraintSettings s) {
        ConstraintType type = ConstraintType.TEACHER_DAY_OFF;
        Long teacherId = s.longParam(type, "teacherId");
        Integer day = s.intParam(type, "dayOfWeek");
        return f.forEach(Lesson.class)
                .filter(lesson -> lesson.getPeriod() != null
                        && Objects.equals(lesson.getTeacherId(), teacherId)
                        && lesson.getPeriod().getDayOfWeek() == day)
                .penalize(penalty(s, type))
                .asConstraint(type.getDisplayName());
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

    private boolean isSport(Lesson lesson, ConstraintSettings settings, ConstraintType type) {
        Long configured = settings.longParam(type, "courseId");
        if (configured != null) {
            return configured.equals(lesson.getCourseId());
        }
        return lesson.getCourseName() != null && lesson.getCourseName().contains("体育");
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

    private int gapCount(List<Integer> periodNos) {
        List<Integer> sorted = periodNos.stream().distinct().sorted().toList();
        int gaps = 0;
        for (int i = 1; i < sorted.size(); i++) {
            gaps += Math.max(0, sorted.get(i) - sorted.get(i - 1) - 1);
        }
        return gaps;
    }
}
