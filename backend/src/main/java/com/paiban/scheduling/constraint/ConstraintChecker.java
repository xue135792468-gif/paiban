package com.paiban.scheduling.constraint;
import org.springframework.util.StringUtils;

import com.paiban.foundation.domain.PeriodSection;
import com.paiban.scheduling.constraint.ConstraintContext.ClassView;
import com.paiban.scheduling.constraint.ConstraintContext.CourseView;
import com.paiban.scheduling.constraint.ConstraintContext.PeriodView;
import com.paiban.scheduling.constraint.ConstraintContext.RoomView;
import com.paiban.scheduling.constraint.ConstraintContext.TeacherView;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintEntryMatcher;
import com.paiban.scheduling.solver.ConstraintParams;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.solver.ConstraintSettings.Control;
import com.paiban.scheduling.solver.ConstraintSettings.Requirement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single implementation of the 25 preset rules. The draft validator and the
 * published-timetable verifier both call this class; neither owns a copy of the rule
 * definitions.
 *
 * <p>Since the ticket-14 revision every rule asks the settings snapshot for the
 * <em>effective control</em> of the fact it found. A hard control makes the fact a
 * blocker; a soft control only costs its weight in {@link #softPenalty}. Overlapping
 * requirements for the same fact are resolved once (hard wins, otherwise the highest
 * weight), so a duplicated or overlapping configuration can never count twice.
 */
public final class ConstraintChecker {

    private static final String[] DAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private ConstraintChecker() {}

    /**
     * Draft semantics: every rule whose effective requirement is hard is reported.
     * Soft requirements are scored by {@link #softPenalty} instead of blocking, which
     * is what makes "必须/尽量" mean the same thing in the editor, the solver and
     * publishing. A null affected set means the whole timetable; an empty set means
     * nothing changed and therefore nothing to report.
     */
    public static List<ConstraintViolation> check(
            ConstraintContext ctx, List<LessonView> lessons, Set<Long> affectedLessonIds) {
        if (affectedLessonIds != null && affectedLessonIds.isEmpty()) {
            return List.of();
        }
        return checkInternal(ctx, lessons, affectedLessonIds, false);
    }

    /** Published semantics: the whole timetable, hard requirements only. */
    public static List<ConstraintViolation> checkHard(ConstraintContext ctx, List<LessonView> lessons) {
        return checkInternal(ctx, lessons, null, false);
    }

    /**
     * Same verdict as {@link #checkHard} but with a bucketed pair scan, so a
     * whole-semester snapshot (thousands of lessons) stays linear-ish. Used by
     * the plan verifier and the planner's final-state check.
     */
    public static List<ConstraintViolation> checkHardFast(ConstraintContext ctx, List<LessonView> lessons) {
        return checkInternal(ctx, lessons, null, true);
    }

    private static List<ConstraintViolation> checkInternal(
            ConstraintContext ctx,
            List<LessonView> lessons,
            Set<Long> affectedLessonIds,
            boolean bucketedPairs) {
        boolean whole = affectedLessonIds == null;
        List<ConstraintViolation> out = new ArrayList<>();
        for (LessonView lesson : lessons) {
            if (whole || affectedLessonIds.contains(lesson.id())) {
                checkLesson(ctx, lesson, out);
            }
        }
        if (bucketedPairs) {
            bucketedPairs(ctx, lessons, out);
        } else {
            for (int i = 0; i < lessons.size(); i++) {
                LessonView a = lessons.get(i);
                if (!whole && !affectedLessonIds.contains(a.id())) {
                    continue;
                }
                for (int j = i + 1; j < lessons.size(); j++) {
                    checkPair(ctx, a, lessons.get(j), out);
                }
                if (!whole) {
                    for (int j = 0; j < i; j++) {
                        LessonView b = lessons.get(j);
                        if (!affectedLessonIds.contains(b.id())) {
                            checkPair(ctx, a, b, out);
                        }
                    }
                }
            }
        }
        checkAggregates(ctx, lessons, whole ? null : touched(lessons, ctx, affectedLessonIds), out);
        return out;
    }

    /** One lesson-scoped conflict, added only when the matched requirement is hard. */
    private static void addIfHard(
            List<ConstraintViolation> out, String code, String message, Long lessonId, Control control) {
        if (control != null && control.hard()) {
            out.add(new ConstraintViolation(code, message, lessonId, true));
        }
    }

    // ------------------------------------------------------------------ per lesson

    private static void checkLesson(
            ConstraintContext ctx, LessonView lesson, List<ConstraintViolation> out) {
        if (lesson.periodId() == null) {
            return;
        }
        PeriodView period = ctx.periods().get(lesson.periodId());
        if (period == null) {
            out.add(new ConstraintViolation("PERIOD", "所选课节不存在", lesson.id(), true));
            return;
        }
        CourseView course = lesson.courseId() == null ? null : ctx.courses().get(lesson.courseId());
        RoomView room = lesson.roomId() == null ? null : ctx.rooms().get(lesson.roomId());
        String courseName = course == null ? "课程" : course.name();
        ConstraintSettings settings = ctx.settings();

        Control capacity = settings.control(ConstraintType.ROOM_CAPACITY_FIT);
        if (capacity != null && capacity.hard()
                && room != null && room.capacity() < lesson.studentCount()) {
            out.add(new ConstraintViolation("ROOM_CAPACITY",
                    "教室「" + room.name() + "」容量 " + room.capacity()
                            + " 小于班级人数 " + lesson.studentCount(),
                    lesson.id(), true));
        }

        // The course's own required room type is master data and stays a hard invariant;
        // a configured requirement replaces that value and brings its own control.
        String requiredRoomType = course == null ? null : course.requiredRoomType();
        Control roomTypeControl = new Control(true, 100);
        if (lesson.courseId() != null) {
            Control configured = settings.match(ConstraintType.COURSE_REQUIRED_ROOM_TYPE,
                    entry -> ConstraintEntryMatcher.courseWithRoomType(entry, lesson.courseId()));
            String configuredType = settings.courseText(
                    ConstraintType.COURSE_REQUIRED_ROOM_TYPE, lesson.courseId(), "roomType");
            if (configured != null && configuredType != null) {
                requiredRoomType = configuredType;
                roomTypeControl = configured;
            }
        }
        if (requiredRoomType != null && StringUtils.hasText(requiredRoomType)
                && (room == null || !requiredRoomType.equals(room.type()))) {
            addIfHard(out, "ROOM_TYPE",
                    courseName + " 需要安排在「" + roomTypeLabel(requiredRoomType) + "」类型的教室",
                    lesson.id(), roomTypeControl);
        }

        Control unavailable = settings.match(ConstraintType.TEACHER_UNAVAILABLE,
                entry -> ConstraintEntryMatcher.teacherSlot(
                        entry, lesson.teacherId(), period.dayOfWeek(), period.periodNo()));
        addIfHard(out, "TEACHER_UNAVAILABLE",
                teacherName(ctx, lesson.teacherId()) + " 在 " + periodLabel(period) + " 不排课",
                lesson.id(), unavailable);

        Control meeting = settings.match(ConstraintType.TEACHER_MEETING_BLOCKED,
                entry -> ConstraintEntryMatcher.slot(entry, period.dayOfWeek(), period.periodNo())
                        && ConstraintEntryMatcher.meetingSubject(
                                entry, teacherSubject(ctx, lesson.teacherId())));
        addIfHard(out, "TEACHER_MEETING",
                teacherName(ctx, lesson.teacherId()) + " 在 " + periodLabel(period) + " 参加教研活动",
                lesson.id(), meeting);

        Control notEvening = settings.match(ConstraintType.COURSE_NOT_IN_EVENING,
                entry -> ConstraintEntryMatcher.courseOrAll(entry, lesson.courseId()));
        if (notEvening != null && notEvening.hard() && period.section() == PeriodSection.EVENING) {
            out.add(new ConstraintViolation("COURSE_NOT_IN_EVENING",
                    courseName + " 不能安排在晚自习", lesson.id(), true));
        }

        Control courseUnavailable = settings.match(ConstraintType.COURSE_UNAVAILABLE,
                entry -> ConstraintEntryMatcher.courseSlot(
                        entry, lesson.courseId(), period.dayOfWeek(), period.periodNo()));
        addIfHard(out, "COURSE_UNAVAILABLE",
                courseName + " 在 " + periodLabel(period) + " 不排课", lesson.id(), courseUnavailable);
    }

    private static String teacherSubject(ConstraintContext ctx, Long teacherId) {
        TeacherView teacher = teacherId == null ? null : ctx.teachers().get(teacherId);
        return teacher == null ? null : teacher.subject();
    }

    // ------------------------------------------------------------------ pairs

    private static void checkPair(
            ConstraintContext ctx, LessonView a, LessonView b, List<ConstraintViolation> out) {
        if (a.periodId() == null || b.periodId() == null) {
            return;
        }
        PeriodView periodA = ctx.periods().get(a.periodId());
        PeriodView periodB = ctx.periods().get(b.periodId());
        if (periodA == null || periodB == null || !a.periodId().equals(b.periodId())) {
            return;
        }
        ConstraintSettings settings = ctx.settings();
        Control teacherConflict = settings.control(ConstraintType.TEACHER_CONFLICT);
        if (teacherConflict != null && teacherConflict.hard()
                && Objects.equals(a.teacherId(), b.teacherId())) {
            out.add(new ConstraintViolation("TEACHER_CONFLICT",
                    teacherName(ctx, a.teacherId()) + " 此时在 " + className(ctx, b.classId())
                            + " 有 " + courseName(ctx, b.courseId()) + "课（" + periodLabel(periodA) + "）",
                    a.id(), true));
        }
        Control classConflict = settings.control(ConstraintType.CLASS_CONFLICT);
        if (classConflict != null && classConflict.hard()
                && Objects.equals(a.classId(), b.classId())) {
            out.add(new ConstraintViolation("CLASS_CONFLICT",
                    className(ctx, a.classId()) + " 在 " + periodLabel(periodA)
                            + " 已排 " + courseName(ctx, b.courseId()),
                    a.id(), true));
        }
        Control roomConflict = settings.control(ConstraintType.ROOM_CONFLICT);
        if (roomConflict != null && roomConflict.hard()
                && a.roomId() != null && Objects.equals(a.roomId(), b.roomId())) {
            out.add(new ConstraintViolation("ROOM_CONFLICT",
                    "教室「" + roomName(ctx, a.roomId()) + "」在 " + periodLabel(periodA)
                            + " 已被 " + className(ctx, b.classId()) + " 占用",
                    a.id(), true));
        }
    }

    private static void bucketedPairs(
            ConstraintContext ctx, List<LessonView> lessons, List<ConstraintViolation> out) {
        Map<Long, List<LessonView>> byPeriod = new HashMap<>();
        for (LessonView lesson : lessons) {
            if (lesson.periodId() == null || !ctx.periods().containsKey(lesson.periodId())) {
                continue;
            }
            byPeriod.computeIfAbsent(lesson.periodId(), key -> new ArrayList<>()).add(lesson);
        }
        for (List<LessonView> bucket : byPeriod.values()) {
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    checkPair(ctx, bucket.get(i), bucket.get(j), out);
                }
            }
        }
    }

    // ------------------------------------------------------------------ aggregates

    /**
     * The facts a local change touches. A rule that counts per day, per course or per
     * teacher must answer only for the buckets the change is part of; otherwise a move
     * in one class would report a conflict that already existed elsewhere.
     */
    private record Touched(Set<Long> lessons, Set<String> teacherDay, Set<String> classDay,
                           Set<String> classCourseDay, Set<String> teacherPeriods) {

        boolean any() {
            return !lessons.isEmpty();
        }
    }

    private static Touched touched(
            List<LessonView> lessons, ConstraintContext ctx, Set<Long> affectedLessonIds) {
        Set<String> teacherDay = new HashSet<>();
        Set<String> classDay = new HashSet<>();
        Set<String> classCourseDay = new HashSet<>();
        Set<String> teacherPeriods = new HashSet<>();
        for (LessonView lesson : lessons) {
            if (!affectedLessonIds.contains(lesson.id())) {
                continue;
            }
            PeriodView period = periodOf(ctx, lesson);
            if (period == null) {
                continue;
            }
            teacherDay.add(lessonKey(lesson.teacherId(), period.dayOfWeek()));
            classDay.add(lessonKey(lesson.classId(), period.dayOfWeek()));
            classCourseDay.add(lessonKey(lesson.classId(), lesson.courseId(), period.dayOfWeek()));
            teacherPeriods.add(lessonKey(lesson.teacherId(), period.dayOfWeek()));
        }
        return new Touched(new HashSet<>(affectedLessonIds), teacherDay, classDay,
                classCourseDay, teacherPeriods);
    }

    private static void checkAggregates(
            ConstraintContext ctx, List<LessonView> lessons, Touched touched,
            List<ConstraintViolation> out) {
        ConstraintSettings s = ctx.settings();

        Map<String, List<LessonView>> teacherDay = new HashMap<>();
        Map<String, List<LessonView>> classDay = new HashMap<>();
        Map<String, List<LessonView>> classCourseDay = new HashMap<>();
        Map<String, List<LessonView>> classEvening = new HashMap<>();
        Map<String, List<LessonView>> teacherPeriods = new HashMap<>();
        for (LessonView lesson : lessons) {
            PeriodView period = periodOf(ctx, lesson);
            if (period == null) {
                continue;
            }
            teacherDay.computeIfAbsent(
                    lessonKey(lesson.teacherId(), period.dayOfWeek()), key -> new ArrayList<>())
                    .add(lesson);
            classDay.computeIfAbsent(
                    lessonKey(lesson.classId(), period.dayOfWeek()), key -> new ArrayList<>())
                    .add(lesson);
            classCourseDay.computeIfAbsent(
                    lessonKey(lesson.classId(), lesson.courseId(), period.dayOfWeek()),
                    key -> new ArrayList<>()).add(lesson);
            teacherPeriods.computeIfAbsent(
                    lessonKey(lesson.teacherId(), period.dayOfWeek()), key -> new ArrayList<>())
                    .add(lesson);
            if (period.section() == PeriodSection.EVENING) {
                classEvening.computeIfAbsent(
                        lessonKey(lesson.classId(), period.dayOfWeek()), key -> new ArrayList<>())
                        .add(lesson);
            }
        }

        checkThreshold(ctx, s, ConstraintType.TEACHER_MAX_PER_DAY, teacherDay, touched,
                (key, count) -> {
                    Long teacherId = objectIdOf(key);
                    Integer limit = s.limitOrNull(ConstraintType.TEACHER_MAX_PER_DAY, teacherId);
                    return limit != null && count > limit
                            ? new ThresholdMessage("TEACHER_MAX_PER_DAY",
                            teacherName(ctx, teacherId) + " 单日排了 " + count + " 节，超过上限 " + limit)
                            : null;
                }, out);
        checkThreshold(ctx, s, ConstraintType.CLASS_MAX_PER_DAY, classDay, touched,
                (key, count) -> {
                    Long classId = objectIdOf(key);
                    Integer limit = s.limitOrNull(ConstraintType.CLASS_MAX_PER_DAY, classId);
                    return limit != null && count > limit
                            ? new ThresholdMessage("CLASS_MAX_PER_DAY",
                            className(ctx, classId) + " 单日排了 " + count + " 节，超过上限 " + limit)
                            : null;
                }, out);
        checkThreshold(ctx, s, ConstraintType.COURSE_MAX_PER_DAY, classCourseDay, touched,
                (key, count) -> {
                    Long classId = objectIdOf(key);
                    Long courseId = courseIdOf(key);
                    Integer limit = s.limitOrNull(ConstraintType.COURSE_MAX_PER_DAY, courseId);
                    return limit != null && count > limit
                            ? new ThresholdMessage("COURSE_MAX_PER_DAY",
                            className(ctx, classId) + " 的 " + courseName(ctx, courseId)
                                    + " 单日排了 " + count + " 节，超过上限 " + limit)
                            : null;
                }, out);
        checkThreshold(ctx, s, ConstraintType.EVENING_MAX, classEvening, touched,
                (key, count) -> {
                    Long classId = objectIdOf(key);
                    Integer limit = s.limitOrNull(ConstraintType.EVENING_MAX, classId);
                    return limit != null && count > limit
                            ? new ThresholdMessage("EVENING_MAX",
                            className(ctx, classId) + " 晚自习排了 " + count + " 节，超过上限 " + limit)
                            : null;
                }, out);

        for (LessonView lesson : lessons) {
            PeriodView period = periodOf(ctx, lesson);
            if (period == null) {
                continue;
            }
            boolean relevant = touched == null || touched.lessons().contains(lesson.id());
            CourseView course = lesson.courseId() == null ? null : ctx.courses().get(lesson.courseId());

            if (relevant && period.section() == PeriodSection.AFTERNOON
                    && isFirstAfternoon(period, ctx)) {
                addIfHard(out, "SPORT_NOT_FIRST_AFTERNOON",
                        courseName(ctx, lesson.courseId()) + " 不应排在下午第一节", lesson.id(),
                        sportMatch(s, ConstraintType.SPORT_NOT_FIRST_AFTERNOON, lesson, course));
            }
            if (relevant && isLastOfDay(period, ctx)) {
                addIfHard(out, "PE_LAST_PERIOD_AVOID",
                        "体育被排在当天最后一节", lesson.id(),
                        sportMatch(s, ConstraintType.PE_LAST_PERIOD_AVOID, lesson, course));
            }
            if (relevant && lesson.courseId() != null) {
                Integer before = s.courseScalar(
                        ConstraintType.MAIN_SUBJECT_MORNING, lesson.courseId(), "beforePeriodNo");
                if (before != null && period.periodNo() > before) {
                    addIfHard(out, "MAIN_SUBJECT_MORNING",
                            courseName(ctx, lesson.courseId()) + " 没有排在上午", lesson.id(),
                            courseStrictMatch(s, ConstraintType.MAIN_SUBJECT_MORNING, lesson.courseId()));
                }
            }
            if (relevant) {
                addIfHard(out, "TEACHER_PREFERRED_SLOT",
                        teacherName(ctx, lesson.teacherId()) + " 的课不在偏好时段", lesson.id(),
                        preferredMiss(s, lesson.teacherId(), period));
                addIfHard(out, "TEACHER_NO_FIRST_PERIOD",
                        teacherName(ctx, lesson.teacherId()) + " 被排在第一节", lesson.id(),
                        isFirstOfDay(period, ctx)
                                ? teacherMatch(s, ConstraintType.TEACHER_NO_FIRST_PERIOD, lesson.teacherId())
                                : null);
                addIfHard(out, "TEACHER_DAY_OFF",
                        teacherName(ctx, lesson.teacherId()) + " 的无课日被排了课", lesson.id(),
                        teacherDayMatch(s, lesson.teacherId(), period.dayOfWeek()));
                ClassView adminClass = lesson.classId() == null ? null : ctx.classes().get(lesson.classId());
                if (adminClass != null
                        && Objects.equals(adminClass.headTeacherId(), lesson.teacherId())
                        && !isFirstOfDay(period, ctx)) {
                    addIfHard(out, "HEAD_TEACHER_FIRST_PERIOD",
                            "班主任的课没有排在本班第一节", lesson.id(),
                            s.control(ConstraintType.HEAD_TEACHER_FIRST_PERIOD));
                }
            }
        }

        // Same course once per day: one violation per (class, course, day) fact, not one
        // per pair, so a third lesson at the same day cannot count the same fact twice.
        for (Map.Entry<String, List<LessonView>> entry : classCourseDay.entrySet()) {
            if (touched != null && !touched.classCourseDay().contains(entry.getKey())) {
                continue;
            }
            String key = entry.getKey();
            List<LessonView> rows = entry.getValue();
            Long courseId = courseIdOf(key);
            if (rows.size() < 2) {
                continue;
            }
            Control control = courseLooseMatch(s, ConstraintType.SAME_COURSE_ONCE_PER_DAY, courseId);
            if (control == null || !control.hard()) {
                continue;
            }
            List<String> labels = rows.stream()
                    .map(lesson -> periodLabel(ctx.periods().get(lesson.periodId())))
                    .distinct()
                    .toList();
            out.add(new ConstraintViolation("SAME_COURSE_PER_DAY",
                    className(ctx, objectIdOf(key)) + " 的 " + courseName(ctx, courseId)
                            + " 一天最多排一节（" + String.join(" / ", labels) + "）",
                    null, true));
        }

        for (Map.Entry<String, List<LessonView>> entry : classCourseDay.entrySet()) {
            if (touched != null && !touched.classCourseDay().contains(entry.getKey())) {
                continue;
            }
            Long courseId = courseIdOf(entry.getKey());
            Control control = courseLooseMatch(s, ConstraintType.COURSE_SPREAD, courseId);
            if (control != null && control.hard() && entry.getValue().size() > 1) {
                out.add(new ConstraintViolation("COURSE_SPREAD",
                        "同一课程没有分散到不同天（当天 " + entry.getValue().size() + " 节）",
                        null, true));
            }
        }

        checkConsecutive(ctx, s, classCourseDay, touched, out);
        checkClassGap(ctx, s, classDay, touched, out);
        checkTeacherMaxConsecutive(ctx, s, teacherPeriods, touched, out);
    }

    private record ThresholdMessage(String code, String message) {}

    private interface ThresholdFact {
        ThresholdMessage message(String key, int count);
    }

    /**
     * One threshold rule over one bucket map. The limit and the control both come from
     * the single selected scope (exception replaces global), so an object exception
     * never inherits the global's hardness on top of its own value.
     */
    private static void checkThreshold(
            ConstraintContext ctx, ConstraintSettings s, ConstraintType type,
            Map<String, List<LessonView>> buckets, Touched touched, ThresholdFact fact,
            List<ConstraintViolation> out) {
        Set<String> relevant = switch (type) {
            case TEACHER_MAX_PER_DAY, TEACHER_MAX_CONSECUTIVE -> touched == null ? null : touched.teacherDay();
            case CLASS_MAX_PER_DAY, EVENING_MAX -> touched == null ? null : touched.classDay();
            case COURSE_MAX_PER_DAY -> touched == null ? null : touched.classCourseDay();
            default -> null;
        };
        for (Map.Entry<String, List<LessonView>> entry : buckets.entrySet()) {
            if (relevant != null && !relevant.contains(entry.getKey())) {
                continue;
            }
            int count = entry.getValue().size();
            Control control = thresholdControl(s, type, objectIdOf(entry.getKey()), entry.getKey());
            if (control == null || !control.hard()) {
                continue;
            }
            ThresholdMessage message = fact.message(entry.getKey(), count);
            if (message != null) {
                out.add(new ConstraintViolation(message.code(), message.message(), null, true));
            }
        }
    }

    /** The control of the threshold selected for this counting bucket's object. */
    private static Control thresholdControl(
            ConstraintSettings s, ConstraintType type, Long objectId, String key) {
        Long scopeId = type == ConstraintType.COURSE_MAX_PER_DAY ? courseIdOf(key) : objectId;
        return s.thresholdControl(type, scopeId);
    }

    private static void checkConsecutive(
            ConstraintContext ctx, ConstraintSettings s, Map<String, List<LessonView>> classCourseDay,
            Touched touched, List<ConstraintViolation> out) {
        for (Map.Entry<String, List<LessonView>> entry : classCourseDay.entrySet()) {
            if (touched != null && !touched.classCourseDay().contains(entry.getKey())) {
                continue;
            }
            Long courseId = courseIdOf(entry.getKey());
            Integer min = s.courseScalar(ConstraintType.CONSECUTIVE_LESSONS, courseId, "minConsecutive");
            if (min == null) {
                continue;
            }
            Control control = courseStrictMatch(s, ConstraintType.CONSECUTIVE_LESSONS, courseId);
            if (control == null || !control.hard()) {
                continue;
            }
            List<Integer> periodNos = entry.getValue().stream()
                    .map(lesson -> ctx.periods().get(lesson.periodId()).periodNo())
                    .sorted()
                    .toList();
            for (int i = 1; i < periodNos.size(); i++) {
                if (periodNos.get(i) - periodNos.get(i - 1) > 1) {
                    out.add(new ConstraintViolation("CONSECUTIVE_LESSONS",
                            "连堂课没有安排在相邻节次", null, true));
                }
            }
        }
    }

    private static void checkClassGap(
            ConstraintContext ctx, ConstraintSettings s, Map<String, List<LessonView>> classDay,
            Touched touched, List<ConstraintViolation> out) {
        Control control = s.control(ConstraintType.CLASS_GAP_MINIMIZED);
        if (control == null || !control.hard()) {
            return;
        }
        for (Map.Entry<String, List<LessonView>> entry : classDay.entrySet()) {
            if (touched != null && !touched.classDay().contains(entry.getKey())) {
                continue;
            }
            Set<Integer> used = new LinkedHashSet<>();
            entry.getValue().forEach(lesson -> used.add(ctx.periods().get(lesson.periodId()).periodNo()));
            if (used.isEmpty()) {
                continue;
            }
            int span = used.stream().mapToInt(Integer::intValue).max().orElse(0)
                    - used.stream().mapToInt(Integer::intValue).min().orElse(0) + 1;
            if (span > used.size()) {
                out.add(new ConstraintViolation("CLASS_GAP_MINIMIZED",
                        className(ctx, objectIdOf(entry.getKey())) + " 的课表存在空堂", null, true));
            }
        }
    }

    private static void checkTeacherMaxConsecutive(
            ConstraintContext ctx, ConstraintSettings s, Map<String, List<LessonView>> teacherPeriods,
            Touched touched, List<ConstraintViolation> out) {
        for (Map.Entry<String, List<LessonView>> entry : teacherPeriods.entrySet()) {
            if (touched != null && !touched.teacherPeriods().contains(entry.getKey())) {
                continue;
            }
            Long teacherId = objectIdOf(entry.getKey());
            Integer max = s.limitOrNull(ConstraintType.TEACHER_MAX_CONSECUTIVE, teacherId);
            Control control = s.thresholdControl(ConstraintType.TEACHER_MAX_CONSECUTIVE, teacherId);
            if (max == null || control == null || !control.hard()) {
                continue;
            }
            List<Integer> periodNos = entry.getValue().stream()
                    .map(lesson -> ctx.periods().get(lesson.periodId()).periodNo())
                    .distinct()
                    .sorted()
                    .toList();
            int run = 1;
            for (int i = 1; i <= periodNos.size(); i++) {
                if (i < periodNos.size() && periodNos.get(i) - periodNos.get(i - 1) == 1) {
                    run++;
                } else {
                    if (run > max) {
                        out.add(new ConstraintViolation("TEACHER_MAX_CONSECUTIVE",
                                "教师连续上课 " + run + " 节，超过上限 " + max, null, true));
                    }
                    run = 1;
                }
            }
        }
    }

    // ---------------------------------------------------------------- soft score

    /**
     * Independent soft-constraint score. Every enabled soft requirement contributes its
     * own weight per violation fact; overlapping requirements for one fact are resolved
     * to the highest weight once (ticket-14 spec: 全软取最高重要程度).
     */
    public static int softPenalty(ConstraintContext ctx, List<LessonView> lessons) {
        ConstraintSettings settings = ctx.settings();
        int total = 0;

        Map<String, List<LessonView>> teacherDay = new HashMap<>();
        Map<String, List<LessonView>> classDay = new HashMap<>();
        Map<String, List<LessonView>> classCourseDay = new HashMap<>();
        Map<String, List<LessonView>> classEvening = new HashMap<>();
        for (LessonView lesson : lessons) {
            PeriodView period = periodOf(ctx, lesson);
            if (period == null) {
                continue;
            }
            teacherDay.computeIfAbsent(
                    lessonKey(lesson.teacherId(), period.dayOfWeek()), key -> new ArrayList<>())
                    .add(lesson);
            classDay.computeIfAbsent(
                    lessonKey(lesson.classId(), period.dayOfWeek()), key -> new ArrayList<>())
                    .add(lesson);
            classCourseDay.computeIfAbsent(
                    lessonKey(lesson.classId(), lesson.courseId(), period.dayOfWeek()),
                    key -> new ArrayList<>()).add(lesson);
            if (period.section() == PeriodSection.EVENING) {
                classEvening.computeIfAbsent(
                        lessonKey(lesson.classId(), period.dayOfWeek()), key -> new ArrayList<>())
                        .add(lesson);
            }
        }

        total += softThreshold(settings, ConstraintType.TEACHER_MAX_PER_DAY, teacherDay, ConstraintChecker::objectIdOf);
        total += softThreshold(settings, ConstraintType.CLASS_MAX_PER_DAY, classDay, ConstraintChecker::objectIdOf);
        total += softThreshold(settings, ConstraintType.EVENING_MAX, classEvening, ConstraintChecker::objectIdOf);
        total += softCourseThreshold(settings, classCourseDay);
        total += softConflicts(ctx, lessons, settings);

        for (LessonView lesson : lessons) {
            PeriodView period = periodOf(ctx, lesson);
            if (period == null) {
                continue;
            }
            CourseView course = lesson.courseId() == null ? null : ctx.courses().get(lesson.courseId());
            RoomView room = lesson.roomId() == null ? null : ctx.rooms().get(lesson.roomId());
            Control capacity = settings.control(ConstraintType.ROOM_CAPACITY_FIT);
            if (room != null && room.capacity() < lesson.studentCount()) {
                total += softHit(capacity, true);
            }
            String configuredType = lesson.courseId() == null ? null : settings.courseText(
                    ConstraintType.COURSE_REQUIRED_ROOM_TYPE, lesson.courseId(), "roomType");
            if (configuredType != null && StringUtils.hasText(configuredType)
                    && (room == null || !configuredType.equals(room.type()))) {
                total += softHit(settings.match(ConstraintType.COURSE_REQUIRED_ROOM_TYPE,
                        entry -> ConstraintEntryMatcher.courseWithRoomType(entry, lesson.courseId())), true);
            }
            total += softHit(settings.match(ConstraintType.TEACHER_UNAVAILABLE,
                            entry -> ConstraintEntryMatcher.teacherSlot(
                                    entry, lesson.teacherId(),
                                    period.dayOfWeek(), period.periodNo())),
                    true);
            total += softHit(settings.match(ConstraintType.TEACHER_MEETING_BLOCKED,
                            entry -> ConstraintEntryMatcher.slot(
                                    entry, period.dayOfWeek(), period.periodNo())
                                    && ConstraintEntryMatcher.meetingSubject(
                                            entry, teacherSubject(ctx, lesson.teacherId()))),
                    true);
            total += softHit(settings.match(ConstraintType.COURSE_NOT_IN_EVENING,
                            entry -> ConstraintEntryMatcher.courseOrAll(entry, lesson.courseId())),
                    period.section() == PeriodSection.EVENING);
            total += softHit(settings.match(ConstraintType.COURSE_UNAVAILABLE,
                            entry -> ConstraintEntryMatcher.courseSlot(
                                    entry, lesson.courseId(),
                                    period.dayOfWeek(), period.periodNo())),
                    true);
            total += softHit(sportMatch(settings, ConstraintType.SPORT_NOT_FIRST_AFTERNOON, lesson, course),
                    period.section() == PeriodSection.AFTERNOON && isFirstAfternoon(period, ctx));
            total += softHit(sportMatch(settings, ConstraintType.PE_LAST_PERIOD_AVOID, lesson, course),
                    isLastOfDay(period, ctx));
            if (lesson.courseId() != null) {
                Integer before = settings.courseScalar(
                        ConstraintType.MAIN_SUBJECT_MORNING, lesson.courseId(), "beforePeriodNo");
                total += softHit(courseStrictMatch(
                                settings, ConstraintType.MAIN_SUBJECT_MORNING, lesson.courseId()),
                        before != null && period.periodNo() > before);
            }
            total += softHit(preferredMiss(settings, lesson.teacherId(), period), true);
            total += softHit(teacherMatch(
                            settings, ConstraintType.TEACHER_NO_FIRST_PERIOD, lesson.teacherId()),
                    isFirstOfDay(period, ctx));
            total += softHit(teacherDayMatch(settings, lesson.teacherId(), period.dayOfWeek()), true);
            ClassView adminClass = lesson.classId() == null ? null : ctx.classes().get(lesson.classId());
            total += softHit(settings.control(ConstraintType.HEAD_TEACHER_FIRST_PERIOD),
                    adminClass != null
                            && Objects.equals(adminClass.headTeacherId(), lesson.teacherId())
                            && !isFirstOfDay(period, ctx));
        }

        for (Map.Entry<String, List<LessonView>> entry : classCourseDay.entrySet()) {
            Long courseId = courseIdOf(entry.getKey());
            List<LessonView> rows = entry.getValue();
            Control spread = courseLooseMatch(settings, ConstraintType.COURSE_SPREAD, courseId);
            if (spread != null && !spread.hard() && rows.size() > 1) {
                total += spread.weight() * (rows.size() - 1);
            }
            Control once = courseLooseMatch(settings, ConstraintType.SAME_COURSE_ONCE_PER_DAY, courseId);
            if (once != null && !once.hard() && rows.size() > 1) {
                total += once.weight() * (rows.size() - 1);
            }
            Integer min = settings.courseScalar(
                    ConstraintType.CONSECUTIVE_LESSONS, courseId, "minConsecutive");
            Control consecutive = courseStrictMatch(
                    settings, ConstraintType.CONSECUTIVE_LESSONS, courseId);
            if (min != null && consecutive != null && !consecutive.hard()) {
                List<Integer> periodNos = rows.stream()
                        .map(lesson -> ctx.periods().get(lesson.periodId()).periodNo())
                        .sorted()
                        .toList();
                for (int i = 1; i < periodNos.size(); i++) {
                    if (periodNos.get(i) - periodNos.get(i - 1) > 1) {
                        total += consecutive.weight();
                    }
                }
            }
        }

        Control gap = settings.control(ConstraintType.CLASS_GAP_MINIMIZED);
        if (gap != null && !gap.hard()) {
            for (List<LessonView> rows : classDay.values()) {
                Set<Integer> used = new LinkedHashSet<>();
                rows.forEach(lesson -> used.add(ctx.periods().get(lesson.periodId()).periodNo()));
                if (used.isEmpty()) {
                    continue;
                }
                int span = used.stream().mapToInt(Integer::intValue).max().orElse(0)
                        - used.stream().mapToInt(Integer::intValue).min().orElse(0) + 1;
                total += gap.weight() * Math.max(0, span - used.size());
            }
        }

        Map<String, List<Integer>> teacherPeriods = new HashMap<>();
        for (LessonView lesson : lessons) {
            PeriodView period = periodOf(ctx, lesson);
            if (period != null) {
                teacherPeriods.computeIfAbsent(
                        lessonKey(lesson.teacherId(), period.dayOfWeek()),
                        key -> new ArrayList<>()).add(period.periodNo());
            }
        }
        for (Map.Entry<String, List<Integer>> entry : teacherPeriods.entrySet()) {
            Long teacherId = objectIdOf(entry.getKey());
            Integer max = settings.limitOrNull(ConstraintType.TEACHER_MAX_CONSECUTIVE, teacherId);
            Control control = settings.thresholdControl(
                    ConstraintType.TEACHER_MAX_CONSECUTIVE, teacherId);
            if (max == null || control == null || control.hard()) {
                continue;
            }
            List<Integer> periodNos = entry.getValue().stream().sorted().toList();
            int run = 1;
            for (int i = 1; i <= periodNos.size(); i++) {
                if (i < periodNos.size() && periodNos.get(i) - periodNos.get(i - 1) == 1) {
                    run++;
                } else {
                    total += control.weight() * Math.max(0, run - max);
                    run = 1;
                }
            }
        }
        return total;
    }

    private static int softHit(Control control, boolean fact) {
        return fact && control != null && !control.hard() ? control.weight() : 0;
    }

    /**
     * Soft side of the three structural conflicts. The solver already scores them via
     * the same type-level control; without this, a school that marks one of them soft
     * would get a zero penalty from the independent checker while the solver still
     * avoided it, and the two verdicts would disagree.
     */
    private static int softConflicts(
            ConstraintContext ctx, List<LessonView> lessons, ConstraintSettings settings) {
        Control teacher = settings.control(ConstraintType.TEACHER_CONFLICT);
        Control adminClass = settings.control(ConstraintType.CLASS_CONFLICT);
        Control room = settings.control(ConstraintType.ROOM_CONFLICT);
        boolean teacherSoft = teacher != null && !teacher.hard();
        boolean classSoft = adminClass != null && !adminClass.hard();
        boolean roomSoft = room != null && !room.hard();
        if (!teacherSoft && !classSoft && !roomSoft) {
            return 0;
        }
        Map<Long, List<LessonView>> byPeriod = new HashMap<>();
        for (LessonView lesson : lessons) {
            if (lesson.periodId() != null && ctx.periods().containsKey(lesson.periodId())) {
                byPeriod.computeIfAbsent(lesson.periodId(), key -> new ArrayList<>()).add(lesson);
            }
        }
        int total = 0;
        for (List<LessonView> bucket : byPeriod.values()) {
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    LessonView a = bucket.get(i);
                    LessonView b = bucket.get(j);
                    if (teacherSoft && Objects.equals(a.teacherId(), b.teacherId())) {
                        total += teacher.weight();
                    }
                    if (classSoft && Objects.equals(a.classId(), b.classId())) {
                        total += adminClass.weight();
                    }
                    if (roomSoft && a.roomId() != null && Objects.equals(a.roomId(), b.roomId())) {
                        total += room.weight();
                    }
                }
            }
        }
        return total;
    }

    private static int softThreshold(
            ConstraintSettings settings, ConstraintType type,
            Map<String, List<LessonView>> buckets,
            java.util.function.Function<String, Long> objectOfKey) {
        int total = 0;
        for (Map.Entry<String, List<LessonView>> entry : buckets.entrySet()) {
            Long objectId = objectOfKey.apply(entry.getKey());
            Integer limit = settings.limitOrNull(type, objectId);
            Control control = settings.thresholdControl(type, objectId);
            if (limit == null || control == null || control.hard()) {
                continue;
            }
            total += control.weight() * Math.max(0, entry.getValue().size() - limit);
        }
        return total;
    }

    private static int softCourseThreshold(
            ConstraintSettings settings, Map<String, List<LessonView>> buckets) {
        int total = 0;
        for (Map.Entry<String, List<LessonView>> entry : buckets.entrySet()) {
            Long courseId = courseIdOf(entry.getKey());
            Integer limit = settings.limitOrNull(ConstraintType.COURSE_MAX_PER_DAY, courseId);
            Control control = settings.thresholdControl(ConstraintType.COURSE_MAX_PER_DAY, courseId);
            if (limit == null || control == null || control.hard()) {
                continue;
            }
            total += control.weight() * Math.max(0, entry.getValue().size() - limit);
        }
        return total;
    }

    // ------------------------------------------------------------------ matching

    /** The control of an entry-backed type whose target course is required (no "all"). */
    private static Control courseStrictMatch(
            ConstraintSettings settings, ConstraintType type, Long courseId) {
        return settings.match(type, entry -> ConstraintEntryMatcher.course(entry, courseId));
    }

    /** The control of an entry-backed type whose blank course means "every course". */
    private static Control courseLooseMatch(
            ConstraintSettings settings, ConstraintType type, Long courseId) {
        return settings.match(type, entry -> ConstraintEntryMatcher.courseOrAll(entry, courseId));
    }

    private static Control teacherMatch(
            ConstraintSettings settings, ConstraintType type, Long teacherId) {
        if (teacherId == null) {
            return null;
        }
        return settings.match(type, entry -> ConstraintEntryMatcher.teacher(entry, teacherId));
    }

    private static Control teacherDayMatch(
            ConstraintSettings settings, Long teacherId, int dayOfWeek) {
        if (teacherId == null) {
            return null;
        }
        return settings.match(ConstraintType.TEACHER_DAY_OFF,
                entry -> ConstraintEntryMatcher.dayOff(entry, teacherId, dayOfWeek));
    }

    private static Control sportMatch(
            ConstraintSettings settings, ConstraintType type, LessonView lesson, CourseView course) {
        return settings.match(type, entry -> ConstraintEntryMatcher.courseOrSport(
                entry, lesson.courseId(), isSportClass(course)));
    }

    /**
     * The preferred-slot rule is an optional set per teacher: every active slot of that
     * teacher is an alternative, so a hit on any one satisfies the whole set. Only when
     * none matches does the fact cost something — hard if any of that teacher's
     * requirements is hard, otherwise the highest soft weight. Returns null when the
     * teacher has no active preference at all (not judged) and when the slot matches.
     */
    private static Control preferredMiss(
            ConstraintSettings settings, Long teacherId, PeriodView period) {
        if (teacherId == null) {
            return null;
        }
        boolean has = false;
        boolean hard = false;
        boolean satisfied = false;
        int weight = -1;
        for (Requirement requirement : settings.requirements(ConstraintType.TEACHER_PREFERRED_SLOT)) {
            boolean owned = false;
            for (Map<String, Object> entry : requirement.entries()) {
                if (!Objects.equals(
                        ConstraintParams.teacherId(entry), teacherId)) {
                    continue;
                }
                owned = true;
                if (Objects.equals(ConstraintParams.dayOfWeek(entry), period.dayOfWeek())
                        && Objects.equals(
                        ConstraintParams.periodNo(entry), period.periodNo())) {
                    satisfied = true;
                }
            }
            if (!owned) {
                continue;
            }
            has = true;
            if (requirement.hard()) {
                hard = true;
            } else {
                weight = Math.max(weight, requirement.weight());
            }
        }
        if (!has || satisfied) {
            return null;
        }
        return hard ? new Control(true, 0) : new Control(false, weight);
    }

    private static PeriodView periodOf(ConstraintContext ctx, LessonView lesson) {
        return lesson.periodId() == null ? null : ctx.periods().get(lesson.periodId());
    }

    private static Long objectIdOf(String key) {
        int dash = key.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            return Long.parseLong(key.substring(0, dash));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long courseIdOf(String key) {
        String[] parts = key.split("-");
        if (parts.length < 2) {
            return null;
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isSportClass(CourseView course) {
        return course != null && course.name() != null && course.name().contains("体育");
    }

    private static boolean isFirstAfternoon(PeriodView period, ConstraintContext ctx) {
        return ctx.periods().values().stream()
                .filter(other -> other.dayOfWeek() == period.dayOfWeek()
                        && other.section() == PeriodSection.AFTERNOON)
                .mapToInt(PeriodView::periodNo)
                .min()
                .orElse(-1) == period.periodNo();
    }

    private static boolean isLastOfDay(PeriodView period, ConstraintContext ctx) {
        return ctx.periods().values().stream()
                .filter(other -> other.dayOfWeek() == period.dayOfWeek())
                .mapToInt(PeriodView::periodNo)
                .max()
                .orElse(-1) == period.periodNo();
    }

    private static boolean isFirstOfDay(PeriodView period, ConstraintContext ctx) {
        return ctx.periods().values().stream()
                .filter(other -> other.dayOfWeek() == period.dayOfWeek())
                .mapToInt(PeriodView::periodNo)
                .min()
                .orElse(-1) == period.periodNo();
    }

    private static String lessonKey(Object... parts) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append('-');
            }
            builder.append(parts[i] == null ? "null" : parts[i]);
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ labels

    private static String teacherName(ConstraintContext ctx, Long id) {
        TeacherView teacher = id == null ? null : ctx.teachers().get(id);
        return teacher == null ? "该教师" : teacher.name();
    }

    private static String className(ConstraintContext ctx, Long id) {
        ClassView adminClass = id == null ? null : ctx.classes().get(id);
        return adminClass == null ? "该班级" : adminClass.name();
    }

    private static String courseName(ConstraintContext ctx, Long id) {
        CourseView course = id == null ? null : ctx.courses().get(id);
        return course == null ? "该课程" : course.name();
    }

    private static String roomName(ConstraintContext ctx, Long id) {
        RoomView room = id == null ? null : ctx.rooms().get(id);
        return room == null ? "该教室" : room.name();
    }

    private static String periodLabel(PeriodView period) {
        String day = period.dayOfWeek() >= 1 && period.dayOfWeek() <= 7
                ? DAY_NAMES[period.dayOfWeek()]
                : "周" + period.dayOfWeek();
        return day + "第" + period.periodNo() + "节";
    }

    private static String roomTypeLabel(String type) {
        return switch (type) {
            case "NORMAL" -> "普通教室";
            case "LAB" -> "实验室";
            case "PLAYGROUND" -> "操场";
            case "MULTIMEDIA" -> "多媒体";
            default -> "其他";
        };
    }
}
