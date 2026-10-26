package com.paiban.scheduling.constraint;

import com.paiban.foundation.domain.PeriodSection;
import com.paiban.scheduling.constraint.ConstraintContext.ClassView;
import com.paiban.scheduling.constraint.ConstraintContext.CourseView;
import com.paiban.scheduling.constraint.ConstraintContext.PeriodView;
import com.paiban.scheduling.constraint.ConstraintContext.RoomView;
import com.paiban.scheduling.constraint.ConstraintContext.TeacherView;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The single implementation of the 24 preset rules. The draft validator and the
 * published-timetable verifier both call this class; neither owns a copy of the
 * rule definitions.
 */
public final class ConstraintChecker {

    private static final String[] DAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private ConstraintChecker() {}

    /**
     * Draft semantics: every enabled rule is reported, except day-level rules
     * which only block when marked hard. A null affected set means the whole
     * timetable; an empty set means nothing changed.
     */
    public static List<ConstraintViolation> check(
            ConstraintContext ctx, List<LessonView> lessons, Set<Long> affectedLessonIds) {
        ConstraintSettings settings = ctx.settings();
        return checkInternal(
                ctx,
                lessons,
                affectedLessonIds,
                settings::enabled,
                type -> settings.enabled(type) && settings.hard(type),
                false);
    }

    /** Published semantics: only rules marked hard can reject a change. */
    public static List<ConstraintViolation> checkHard(ConstraintContext ctx, List<LessonView> lessons) {
        ConstraintSettings settings = ctx.settings();
        Predicate<ConstraintType> hard = type -> settings.enabled(type) && settings.hard(type);
        return checkInternal(ctx, lessons, null, hard, hard, true);
    }

    /**
     * Same verdict as {@link #checkHard} but with a bucketed pair scan, so a
     * whole-semester snapshot (thousands of lessons) stays linear-ish. Used by
     * the plan verifier and the planner's final-state check, never by the draft
     * validator, whose per-scenario conflict order must not change.
     */
    public static List<ConstraintViolation> checkHardFast(ConstraintContext ctx, List<LessonView> lessons) {
        ConstraintSettings settings = ctx.settings();
        Predicate<ConstraintType> hard = type -> settings.enabled(type) && settings.hard(type);
        List<ConstraintViolation> out = new ArrayList<>();
        for (LessonView lesson : lessons) {
            checkLesson(ctx, lesson, hard, true, out);
        }
        Map<Long, List<LessonView>> byPeriod = new HashMap<>();
        Map<String, List<LessonView>> byCourseDay = new HashMap<>();
        for (LessonView lesson : lessons) {
            if (lesson.periodId() == null) {
                continue;
            }
            PeriodView period = ctx.periods().get(lesson.periodId());
            if (period == null) {
                continue;
            }
            byPeriod.computeIfAbsent(lesson.periodId(), key -> new ArrayList<>()).add(lesson);
            byCourseDay.computeIfAbsent(
                            lessonKey(lesson.classId(), lesson.courseId(), period.dayOfWeek()),
                            key -> new ArrayList<>())
                    .add(lesson);
        }
        for (List<LessonView> bucket : byPeriod.values()) {
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    checkPair(ctx, bucket.get(i), bucket.get(j), hard, true, out);
                }
            }
        }
        for (List<LessonView> bucket : byCourseDay.values()) {
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    if (!bucket.get(i).periodId().equals(bucket.get(j).periodId())) {
                        checkPair(ctx, bucket.get(i), bucket.get(j), hard, true, out);
                    }
                }
            }
        }
        if (groupRulesConfigured(settings, hard)) {
            checkGroups(ctx, lessons, out, null, hard, true);
        }
        checkOptionalHard(ctx, lessons, out);
        return out;
    }

    private static List<ConstraintViolation> checkInternal(
            ConstraintContext ctx,
            List<LessonView> lessons,
            Set<Long> affectedLessonIds,
            Predicate<ConstraintType> include,
            Predicate<ConstraintType> groupInclude,
            boolean hardResult) {
        boolean whole = affectedLessonIds == null;
        List<ConstraintViolation> out = new ArrayList<>();
        for (LessonView lesson : lessons) {
            if (whole || affectedLessonIds.contains(lesson.id())) {
                checkLesson(ctx, lesson, include, hardResult, out);
            }
        }
        if (whole) {
            for (int i = 0; i < lessons.size(); i++) {
                for (int j = i + 1; j < lessons.size(); j++) {
                    checkPair(ctx, lessons.get(i), lessons.get(j), include, hardResult, out);
                }
            }
        } else {
            for (int i = 0; i < lessons.size(); i++) {
                LessonView a = lessons.get(i);
                if (!affectedLessonIds.contains(a.id())) {
                    continue;
                }
                for (int j = i + 1; j < lessons.size(); j++) {
                    checkPair(ctx, a, lessons.get(j), include, hardResult, out);
                }
                for (int j = 0; j < i; j++) {
                    LessonView b = lessons.get(j);
                    if (!affectedLessonIds.contains(b.id())) {
                        checkPair(ctx, a, b, include, hardResult, out);
                    }
                }
            }
        }
        if (groupRulesConfigured(ctx.settings(), groupInclude)) {
            checkGroups(ctx, lessons, out, whole ? null : affectedLessonIds, groupInclude, hardResult);
        }
        // Day-level rules gate themselves on "hard" inside checkOptionalHard, so the
        // draft verdict needs them too: publishing reads this verdict, and a hard rule
        // that only appeared under checkHard could never refuse anything a user creates.
        int beforeOptional = out.size();
        checkOptionalHard(ctx, lessons, out);
        if (!whole) {
            // A local move still only answers for the lessons it touched; day-level rules
            // are evaluated over the whole draft, so keep just the rows that point at an
            // affected lesson (aggregated rows carry no lesson id and stay).
            for (int i = out.size() - 1; i >= beforeOptional; i--) {
                Long lessonId = out.get(i).lessonId();
                if (lessonId != null && !affectedLessonIds.contains(lessonId)) {
                    out.remove(i);
                }
            }
        }
        return out;
    }

    private static boolean groupRulesConfigured(
            ConstraintSettings settings, Predicate<ConstraintType> include) {
        return List.of(
                        ConstraintType.TEACHER_MAX_PER_DAY,
                        ConstraintType.CLASS_MAX_PER_DAY,
                        ConstraintType.COURSE_MAX_PER_DAY,
                        ConstraintType.EVENING_MAX,
                        ConstraintType.SPORT_NOT_FIRST_AFTERNOON)
                .stream()
                .anyMatch(include);
    }

    private static void checkLesson(
            ConstraintContext ctx,
            LessonView lesson,
            Predicate<ConstraintType> include,
            boolean hardResult,
            List<ConstraintViolation> out) {
        if (lesson.periodId() == null) {
            return;
        }
        PeriodView period = ctx.periods().get(lesson.periodId());
        if (period == null) {
            out.add(new ConstraintViolation("PERIOD", "所选课节不存在", lesson.id(), hardResult));
            return;
        }
        CourseView course = lesson.courseId() == null ? null : ctx.courses().get(lesson.courseId());
        RoomView room = lesson.roomId() == null ? null : ctx.rooms().get(lesson.roomId());
        String courseName = course == null ? "课程" : course.name();
        ConstraintSettings settings = ctx.settings();

        if (include.test(ConstraintType.ROOM_CAPACITY_FIT)
                && room != null && room.capacity() < lesson.studentCount()) {
            out.add(new ConstraintViolation("ROOM_CAPACITY",
                    "教室「" + room.name() + "」容量 " + room.capacity()
                            + " 小于班级人数 " + lesson.studentCount(),
                    lesson.id(), hardResult));
        }

        String requiredRoomType = course == null ? null : course.requiredRoomType();
        ConstraintType roomTypeConstraint = ConstraintType.COURSE_REQUIRED_ROOM_TYPE;
        if (include.test(roomTypeConstraint) && lesson.courseId() != null) {
            String configured = settings.courseText(roomTypeConstraint, lesson.courseId(), "roomType");
            if (configured != null) {
                requiredRoomType = configured;
            }
        }
        if (requiredRoomType != null && !requiredRoomType.isBlank()
                && (room == null || !requiredRoomType.equals(room.type()))) {
            out.add(new ConstraintViolation("ROOM_TYPE",
                    courseName + " 需要安排在「" + roomTypeLabel(requiredRoomType) + "」类型的教室",
                    lesson.id(), hardResult));
        }

        ConstraintType unavailable = ConstraintType.TEACHER_UNAVAILABLE;
        if (include.test(unavailable) && unavailableAt(settings, lesson.teacherId(), period)) {
            out.add(new ConstraintViolation("TEACHER_UNAVAILABLE",
                    teacherName(ctx, lesson.teacherId()) + " 在 " + periodLabel(period) + " 不排课",
                    lesson.id(), hardResult));
        }

        ConstraintType meeting = ConstraintType.TEACHER_MEETING_BLOCKED;
        if (include.test(meeting) && meetingBlocked(settings, lesson, ctx, period)) {
            out.add(new ConstraintViolation("TEACHER_MEETING",
                    teacherName(ctx, lesson.teacherId()) + " 在 " + periodLabel(period) + " 参加教研活动",
                    lesson.id(), hardResult));
        }

        ConstraintType notEvening = ConstraintType.COURSE_NOT_IN_EVENING;
        if (include.test(notEvening)
                && settings.matchesCourse(notEvening, lesson.courseId())
                && period.section() == PeriodSection.EVENING) {
            out.add(new ConstraintViolation("COURSE_NOT_IN_EVENING",
                    courseName + " 不能安排在晚自习", lesson.id(), hardResult));
        }
    }

    private static void checkPair(
            ConstraintContext ctx,
            LessonView a,
            LessonView b,
            Predicate<ConstraintType> include,
            boolean hardResult,
            List<ConstraintViolation> out) {
        if (a.periodId() == null || b.periodId() == null) {
            return;
        }
        PeriodView periodA = ctx.periods().get(a.periodId());
        PeriodView periodB = ctx.periods().get(b.periodId());
        if (periodA == null || periodB == null) {
            return;
        }
        ConstraintSettings settings = ctx.settings();
        if (a.periodId().equals(b.periodId())) {
            if (include.test(ConstraintType.TEACHER_CONFLICT)
                    && Objects.equals(a.teacherId(), b.teacherId())) {
                out.add(new ConstraintViolation("TEACHER_CONFLICT",
                        teacherName(ctx, a.teacherId()) + " 此时在 " + className(ctx, b.classId())
                                + " 有 " + courseName(ctx, b.courseId()) + "课（" + periodLabel(periodA) + "）",
                        a.id(), hardResult));
            }
            if (include.test(ConstraintType.CLASS_CONFLICT)
                    && Objects.equals(a.classId(), b.classId())) {
                out.add(new ConstraintViolation("CLASS_CONFLICT",
                        className(ctx, a.classId()) + " 在 " + periodLabel(periodA)
                                + " 已排 " + courseName(ctx, b.courseId()),
                        a.id(), hardResult));
            }
            if (include.test(ConstraintType.ROOM_CONFLICT)
                    && a.roomId() != null && Objects.equals(a.roomId(), b.roomId())) {
                out.add(new ConstraintViolation("ROOM_CONFLICT",
                        "教室「" + roomName(ctx, a.roomId()) + "」在 " + periodLabel(periodA)
                                + " 已被 " + className(ctx, b.classId()) + " 占用",
                        a.id(), hardResult));
            }
        }

        ConstraintType oncePerDay = ConstraintType.SAME_COURSE_ONCE_PER_DAY;
        if (include.test(oncePerDay)
                && Objects.equals(a.classId(), b.classId())
                && Objects.equals(a.courseId(), b.courseId())
                && settings.matchesCourse(oncePerDay, a.courseId())
                && periodA.dayOfWeek() == periodB.dayOfWeek()
                && periodA.periodNo() != periodB.periodNo()) {
            out.add(new ConstraintViolation("SAME_COURSE_PER_DAY",
                    className(ctx, a.classId()) + " 的 " + courseName(ctx, a.courseId())
                            + " 一天最多排一节（" + periodLabel(periodA) + " / " + periodLabel(periodB) + "）",
                    a.id(), hardResult));
        }
    }

    private static void checkGroups(
            ConstraintContext ctx,
            List<LessonView> lessons,
            List<ConstraintViolation> out,
            Set<Long> affectedLessonIds,
            Predicate<ConstraintType> include,
            boolean hardResult) {
        Map<String, Integer> teacherPerDay = new HashMap<>();
        Map<String, Integer> classPerDay = new HashMap<>();
        Map<String, Integer> coursePerDay = new HashMap<>();
        Map<String, Integer> eveningPerClass = new HashMap<>();
        Set<String> relevantKeys = new HashSet<>();
        for (LessonView lesson : lessons) {
            if (lesson.periodId() == null) {
                continue;
            }
            PeriodView period = ctx.periods().get(lesson.periodId());
            if (period == null) {
                continue;
            }
            String teacherKey = lesson.teacherId() + "-" + period.dayOfWeek();
            String classKey = lesson.classId() + "-" + period.dayOfWeek();
            String courseKey = lesson.classId() + "-" + lesson.courseId() + "-" + period.dayOfWeek();
            teacherPerDay.merge(teacherKey, 1, Integer::sum);
            classPerDay.merge(classKey, 1, Integer::sum);
            coursePerDay.merge(courseKey, 1, Integer::sum);
            if (period.section() == PeriodSection.EVENING) {
                eveningPerClass.merge(classKey, 1, Integer::sum);
            }
            if (affectedLessonIds != null && affectedLessonIds.contains(lesson.id())) {
                relevantKeys.add(teacherKey);
                relevantKeys.add(classKey);
                relevantKeys.add(courseKey);
            }
        }
        boolean filter = affectedLessonIds != null;
        ConstraintSettings settings = ctx.settings();

        ConstraintType teacherMax = ConstraintType.TEACHER_MAX_PER_DAY;
        if (include.test(teacherMax)) {
            for (Map.Entry<String, Integer> entry : teacherPerDay.entrySet()) {
                if (filter && !relevantKeys.contains(entry.getKey())) {
                    continue;
                }
                Long teacherId = objectIdOf(entry.getKey());
                Integer max = settings.threshold(teacherMax, teacherId);
                if (max == null || entry.getValue() <= max) {
                    continue;
                }
                out.add(new ConstraintViolation("TEACHER_MAX_PER_DAY",
                        teacherName(ctx, teacherId) + " 单日排了 " + entry.getValue()
                                + " 节，超过上限 " + max,
                        null, hardResult));
            }
        }
        ConstraintType classMax = ConstraintType.CLASS_MAX_PER_DAY;
        if (include.test(classMax)) {
            for (Map.Entry<String, Integer> entry : classPerDay.entrySet()) {
                if (filter && !relevantKeys.contains(entry.getKey())) {
                    continue;
                }
                Long classId = objectIdOf(entry.getKey());
                Integer max = settings.threshold(classMax, classId);
                if (max == null || entry.getValue() <= max) {
                    continue;
                }
                out.add(new ConstraintViolation("CLASS_MAX_PER_DAY",
                        className(ctx, classId) + " 单日排了 " + entry.getValue()
                                + " 节，超过上限 " + max,
                        null, hardResult));
            }
        }
        ConstraintType courseMax = ConstraintType.COURSE_MAX_PER_DAY;
        if (include.test(courseMax)) {
            // The bucket stays "class + course + day"; the entry that decides the limit
            // names the course only.
            for (Map.Entry<String, Integer> entry : coursePerDay.entrySet()) {
                if (filter && !relevantKeys.contains(entry.getKey())) {
                    continue;
                }
                String key = entry.getKey();
                Long classId = objectIdOf(key);
                Long courseId = courseIdOf(key);
                Integer max = settings.threshold(courseMax, courseId);
                if (max == null || entry.getValue() <= max) {
                    continue;
                }
                out.add(new ConstraintViolation("COURSE_MAX_PER_DAY",
                        className(ctx, classId) + " 的 " + courseName(ctx, courseId)
                                + " 单日排了 " + entry.getValue() + " 节，超过上限 " + max,
                        null, hardResult));
            }
        }
        ConstraintType eveningMax = ConstraintType.EVENING_MAX;
        if (include.test(eveningMax)) {
            for (Map.Entry<String, Integer> entry : eveningPerClass.entrySet()) {
                if (filter && !relevantKeys.contains(entry.getKey())) {
                    continue;
                }
                Long classId = objectIdOf(entry.getKey());
                Integer max = settings.threshold(eveningMax, classId);
                if (max == null || entry.getValue() <= max) {
                    continue;
                }
                out.add(new ConstraintViolation("EVENING_MAX",
                        className(ctx, classId) + " 晚自习排了 " + entry.getValue()
                                + " 节，超过上限 " + max,
                        null, hardResult));
            }
        }
        ConstraintType sport = ConstraintType.SPORT_NOT_FIRST_AFTERNOON;
        if (include.test(sport)) {
            for (LessonView lesson : lessons) {
                if (lesson.periodId() == null) {
                    continue;
                }
                if (filter && !affectedLessonIds.contains(lesson.id())) {
                    continue;
                }
                PeriodView period = ctx.periods().get(lesson.periodId());
                CourseView course = lesson.courseId() == null ? null : ctx.courses().get(lesson.courseId());
                if (targetsSport(settings, sport, lesson, course)
                        && period != null && period.section() == PeriodSection.AFTERNOON
                        && isFirstAfternoon(period, ctx)) {
                    out.add(new ConstraintViolation("SPORT_NOT_FIRST_AFTERNOON",
                            courseName(ctx, lesson.courseId()) + " 不应排在下午第一节",
                            lesson.id(), hardResult));
                }
            }
        }
    }

    /**
     * The object id a counting key starts with ({@code "{id}-{suffix}"}), or null when
     * the key has no numeric head — an unassigned lesson is simply not judged by a
     * per-object rule instead of failing the whole check.
     */
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

    private static boolean isFirstAfternoon(PeriodView period, ConstraintContext ctx) {
        return ctx.periods().values().stream()
                .filter(other -> other.dayOfWeek() == period.dayOfWeek()
                        && other.section() == PeriodSection.AFTERNOON)
                .mapToInt(PeriodView::periodNo)
                .min()
                .orElse(-1) == period.periodNo();
    }

    /**
     * The ten preference rules are soft by default but a school may mark any of
     * them hard. Hard mode must then reject a plan that breaks them, so the
     * published verifier can honour the whole 24-type catalogue.
     */
    private static void checkOptionalHard(ConstraintContext ctx, List<LessonView> lessons,
                                          List<ConstraintViolation> out) {
        ConstraintSettings s = ctx.settings();

        ConstraintType consecutive = ConstraintType.CONSECUTIVE_LESSONS;
        if (isHard(s, consecutive)) {
            // One entry per course (a second, different "consecutive" condition for
            // the same course is rejected at save time), so courses never interfere.
            for (Map<String, Object> entry : s.entries(consecutive)) {
                Long courseId = ConstraintSettings.longValue(entry.get("courseId"));
                if (courseId == null) {
                    continue;
                }
                Map<String, List<Integer>> slots = new HashMap<>();
                for (LessonView lesson : lessons) {
                    PeriodView period = periodOf(ctx, lesson);
                    if (period == null || !courseId.equals(lesson.courseId())) {
                        continue;
                    }
                    slots.computeIfAbsent(lessonKey(lesson.classId(), period.dayOfWeek()),
                            key -> new ArrayList<>()).add(period.periodNo());
                }
                for (List<Integer> periodNos : slots.values()) {
                    periodNos.sort(Integer::compareTo);
                    for (int i = 1; i < periodNos.size(); i++) {
                        if (periodNos.get(i) - periodNos.get(i - 1) > 1) {
                            out.add(new ConstraintViolation("CONSECUTIVE_LESSONS",
                                    "连堂课没有安排在相邻节次", null, true));
                        }
                    }
                }
            }
        }

        ConstraintType spread = ConstraintType.COURSE_SPREAD;
        if (isHard(s, spread)) {
            Map<String, Integer> counts = new HashMap<>();
            for (LessonView lesson : lessons) {
                PeriodView period = periodOf(ctx, lesson);
                if (period == null) {
                    continue;
                }
                counts.merge(lessonKey(lesson.classId(), lesson.courseId(), period.dayOfWeek()),
                        1, Integer::sum);
            }
            // Union of the entries: one violation per (class, course, day) fact, no
            // matter how many entries happen to match it.
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > 1
                        && s.matchesCourse(spread, courseIdOf(entry.getKey()))) {
                    out.add(new ConstraintViolation("COURSE_SPREAD",
                            "同一课程没有分散到不同天（当天 " + entry.getValue() + " 节）", null, true));
                }
            }
        }

        ConstraintType morning = ConstraintType.MAIN_SUBJECT_MORNING;
        if (isHard(s, morning)) {
            for (LessonView lesson : lessons) {
                PeriodView period = periodOf(ctx, lesson);
                if (period == null || lesson.courseId() == null) {
                    continue;
                }
                Integer before = s.courseScalar(morning, lesson.courseId(), "beforePeriodNo");
                if (before != null && period.periodNo() > before) {
                    out.add(new ConstraintViolation("MAIN_SUBJECT_MORNING",
                            courseName(ctx, lesson.courseId()) + " 没有排在上午", lesson.id(), true));
                }
            }
        }

        ConstraintType preferred = ConstraintType.TEACHER_PREFERRED_SLOT;
        if (isHard(s, preferred)) {
            for (LessonView lesson : lessons) {
                PeriodView period = periodOf(ctx, lesson);
                if (period != null && preferredSlotViolated(s, lesson.teacherId(), period)) {
                    out.add(new ConstraintViolation("TEACHER_PREFERRED_SLOT",
                            teacherName(ctx, lesson.teacherId()) + " 的课不在偏好时段",
                            lesson.id(), true));
                }
            }
        }

        ConstraintType maxConsecutive = ConstraintType.TEACHER_MAX_CONSECUTIVE;
        if (isHard(s, maxConsecutive)) {
            Map<String, List<Integer>> slots = new HashMap<>();
            for (LessonView lesson : lessons) {
                PeriodView period = periodOf(ctx, lesson);
                if (period != null) {
                    slots.computeIfAbsent(lessonKey(lesson.teacherId(), period.dayOfWeek()),
                            key -> new ArrayList<>()).add(period.periodNo());
                }
            }
            for (Map.Entry<String, List<Integer>> entry : slots.entrySet()) {
                // Each teacher has its own limit: an exception on one teacher never
                // changes what another teacher may do.
                Integer max = s.threshold(maxConsecutive, objectIdOf(entry.getKey()));
                if (max == null) {
                    continue;
                }
                List<Integer> periodNos = entry.getValue();
                periodNos.sort(Integer::compareTo);
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

        ConstraintType noFirst = ConstraintType.TEACHER_NO_FIRST_PERIOD;
        if (isHard(s, noFirst)) {
            for (LessonView lesson : lessons) {
                PeriodView period = periodOf(ctx, lesson);
                if (period != null && targetsTeacher(s, noFirst, lesson.teacherId())
                        && isFirstOfDay(period, ctx)) {
                    out.add(new ConstraintViolation("TEACHER_NO_FIRST_PERIOD",
                            teacherName(ctx, lesson.teacherId()) + " 被排在第一节", lesson.id(), true));
                }
            }
        }

        ConstraintType dayOff = ConstraintType.TEACHER_DAY_OFF;
        if (isHard(s, dayOff)) {
            for (LessonView lesson : lessons) {
                PeriodView period = periodOf(ctx, lesson);
                if (period != null && targetsTeacherDay(s, lesson.teacherId(), period.dayOfWeek())) {
                    out.add(new ConstraintViolation("TEACHER_DAY_OFF",
                            teacherName(ctx, lesson.teacherId()) + " 的无课日被排了课",
                            lesson.id(), true));
                }
            }
        }

        ConstraintType gap = ConstraintType.CLASS_GAP_MINIMIZED;
        if (isHard(s, gap)) {
            Map<String, Set<Integer>> slots = new HashMap<>();
            Map<String, Long> classByKey = new HashMap<>();
            for (LessonView lesson : lessons) {
                PeriodView period = periodOf(ctx, lesson);
                if (period != null && lesson.classId() != null) {
                    String key = lessonKey(lesson.classId(), period.dayOfWeek());
                    slots.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(period.periodNo());
                    classByKey.put(key, lesson.classId());
                }
            }
            for (Map.Entry<String, Set<Integer>> entry : slots.entrySet()) {
                int span = entry.getValue().stream().mapToInt(Integer::intValue).max().orElse(0)
                        - entry.getValue().stream().mapToInt(Integer::intValue).min().orElse(0) + 1;
                if (span > entry.getValue().size()) {
                    out.add(new ConstraintViolation("CLASS_GAP_MINIMIZED",
                            className(ctx, classByKey.get(entry.getKey())) + " 的课表存在空堂", null, true));
                }
            }
        }

        ConstraintType peLast = ConstraintType.PE_LAST_PERIOD_AVOID;
        if (isHard(s, peLast)) {
            for (LessonView lesson : lessons) {
                PeriodView period = periodOf(ctx, lesson);
                CourseView course = lesson.courseId() == null ? null : ctx.courses().get(lesson.courseId());
                if (period != null && targetsSport(s, peLast, lesson, course) && isLastOfDay(period, ctx)) {
                    out.add(new ConstraintViolation("PE_LAST_PERIOD_AVOID",
                            "体育被排在当天最后一节", lesson.id(), true));
                }
            }
        }

        ConstraintType headFirst = ConstraintType.HEAD_TEACHER_FIRST_PERIOD;
        if (isHard(s, headFirst)) {
            for (LessonView lesson : lessons) {
                PeriodView period = periodOf(ctx, lesson);
                ClassView adminClass = lesson.classId() == null ? null : ctx.classes().get(lesson.classId());
                if (period != null && adminClass != null
                        && Objects.equals(adminClass.headTeacherId(), lesson.teacherId())
                        && !isFirstOfDay(period, ctx)) {
                    out.add(new ConstraintViolation("HEAD_TEACHER_FIRST_PERIOD",
                            "班主任的课没有排在本班第一节", lesson.id(), true));
                }
            }
        }
    }

    private static boolean isHard(ConstraintSettings settings, ConstraintType type) {
        return settings.enabled(type) && settings.hard(type);
    }

    /**
     * Union of every {@code TEACHER_UNAVAILABLE} entry: the first entry matching
     * (teacher, weekday, period) blocks the lesson. Entries target one teacher each,
     * so two teachers never affect each other.
     */
    private static boolean unavailableAt(
            ConstraintSettings settings, Long teacherId, PeriodView period) {
        if (teacherId == null) {
            return false;
        }
        for (Map<String, Object> entry : settings.entries(ConstraintType.TEACHER_UNAVAILABLE)) {
            Long configuredTeacher = ConstraintSettings.longValue(entry.get("teacherId"));
            Integer day = ConstraintSettings.intValue(entry.get("dayOfWeek"));
            Integer periodNo = ConstraintSettings.intValue(entry.get("periodNo"));
            if (teacherId.equals(configuredTeacher)
                    && Objects.equals(day, period.dayOfWeek())
                    && Objects.equals(periodNo, period.periodNo())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Union of every {@code TEACHER_MEETING_BLOCKED} entry: an entry with a blank
     * subject covers every subject, an entry with a subject covers that subject's
     * teachers. Any single matching entry blocks the lesson, and the caller reports
     * at most one violation for it.
     */
    private static boolean meetingBlocked(
            ConstraintSettings settings, LessonView lesson, ConstraintContext ctx, PeriodView period) {
        TeacherView teacher = lesson.teacherId() == null ? null : ctx.teachers().get(lesson.teacherId());
        for (Map<String, Object> entry : settings.entries(ConstraintType.TEACHER_MEETING_BLOCKED)) {
            Integer day = ConstraintSettings.intValue(entry.get("dayOfWeek"));
            Integer periodNo = ConstraintSettings.intValue(entry.get("periodNo"));
            if (!Objects.equals(day, period.dayOfWeek()) || !Objects.equals(periodNo, period.periodNo())) {
                continue;
            }
            Object rawSubject = entry.get("subject");
            String subject = rawSubject == null ? null : rawSubject.toString();
            if (subject == null || subject.isBlank()
                    || (teacher != null && subject.equalsIgnoreCase(teacher.subject()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Union of the course targets of a sport-flavoured type ({@code SPORT_NOT_FIRST_AFTERNOON},
     * {@code PE_LAST_PERIOD_AVOID}): an entry with a course matches that course, an
     * entry without one matches the school's sport-class courses. Any entry matching
     * is enough, so an "all sport" entry overlapping a concrete one scores once.
     */
    private static boolean targetsSport(
            ConstraintSettings settings, ConstraintType type, LessonView lesson, CourseView course) {
        for (Map<String, Object> entry : settings.entries(type)) {
            Long configured = ConstraintSettings.longValue(entry.get("courseId"));
            if (configured != null) {
                if (configured.equals(lesson.courseId())) {
                    return true;
                }
            } else if (isSportClass(course)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSportClass(CourseView course) {
        return course != null && course.name() != null && course.name().contains("体育");
    }

    /** Union of the teachers named by {@code TEACHER_NO_FIRST_PERIOD} entries. */
    private static boolean targetsTeacher(
            ConstraintSettings settings, ConstraintType type, Long teacherId) {
        if (teacherId == null) {
            return false;
        }
        for (Map<String, Object> entry : settings.entries(type)) {
            if (teacherId.equals(ConstraintSettings.longValue(entry.get("teacherId")))) {
                return true;
            }
        }
        return false;
    }

    /** Union of the (teacher, weekday) pairs named by {@code TEACHER_DAY_OFF} entries. */
    private static boolean targetsTeacherDay(
            ConstraintSettings settings, Long teacherId, int dayOfWeek) {
        if (teacherId == null) {
            return false;
        }
        for (Map<String, Object> entry : settings.entries(ConstraintType.TEACHER_DAY_OFF)) {
            if (teacherId.equals(ConstraintSettings.longValue(entry.get("teacherId")))
                    && Objects.equals(ConstraintSettings.intValue(entry.get("dayOfWeek")), dayOfWeek)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the lesson's slot satisfies the teacher's preferred slots: the entries
     * of one teacher are an OR set, so any of them is enough. A teacher with no entry
     * is not judged at all, which also reads as "satisfied".
     */
    private static boolean preferredSlotSatisfied(
            ConstraintSettings settings, Long teacherId, PeriodView period) {
        boolean hasEntry = false;
        for (Map<String, Object> entry : settings.entries(ConstraintType.TEACHER_PREFERRED_SLOT)) {
            if (!Objects.equals(ConstraintSettings.longValue(entry.get("teacherId")), teacherId)) {
                continue;
            }
            hasEntry = true;
            if (Objects.equals(ConstraintSettings.intValue(entry.get("dayOfWeek")), period.dayOfWeek())
                    && Objects.equals(ConstraintSettings.intValue(entry.get("periodNo")), period.periodNo())) {
                return true;
            }
        }
        return !hasEntry;
    }

    /** True only when the teacher has preferred slots and this slot is outside all of them. */
    private static boolean preferredSlotViolated(
            ConstraintSettings settings, Long teacherId, PeriodView period) {
        if (teacherId == null) {
            return false;
        }
        return !preferredSlotSatisfied(settings, teacherId, period);
    }

    private static PeriodView periodOf(ConstraintContext ctx, LessonView lesson) {
        return lesson.periodId() == null ? null : ctx.periods().get(lesson.periodId());
    }

    // ---------------------------------------------------------------- soft score

    /**
     * Independent soft-constraint score. Each enabled, non-hard rule contributes
     * weight × number of violations. The planner reports the delta it computed
     * here and the verifier recomputes it from the final timetable, so a solver
     * bug cannot silently pass a mis-scored plan.
     */
    public static int softPenalty(ConstraintContext ctx, List<LessonView> lessons) {
        ConstraintSettings settings = ctx.settings();
        int total = 0;

        Map<String, Integer> teacherDay = new HashMap<>();
        Map<String, Integer> classDay = new HashMap<>();
        Map<String, Integer> classCourseDay = new HashMap<>();
        Map<String, Integer> classEvening = new HashMap<>();
        for (LessonView lesson : lessons) {
            if (lesson.periodId() == null) {
                continue;
            }
            PeriodView period = ctx.periods().get(lesson.periodId());
            if (period == null) {
                continue;
            }
            teacherDay.merge(lessonKey(lesson.teacherId(), period.dayOfWeek()), 1, Integer::sum);
            classDay.merge(lessonKey(lesson.classId(), period.dayOfWeek()), 1, Integer::sum);
            classCourseDay.merge(lessonKey(lesson.classId(), lesson.courseId(), period.dayOfWeek()),
                    1, Integer::sum);
            if (period.section() == PeriodSection.EVENING) {
                classEvening.merge(lessonKey(lesson.classId(), period.dayOfWeek()), 1, Integer::sum);
            }
        }

        total += weight(settings, ConstraintType.TEACHER_MAX_PER_DAY)
                * excess(teacherDay, key -> settings.threshold(ConstraintType.TEACHER_MAX_PER_DAY,
                        objectIdOf(key)));
        total += weight(settings, ConstraintType.CLASS_MAX_PER_DAY)
                * excess(classDay, key -> settings.threshold(ConstraintType.CLASS_MAX_PER_DAY,
                        objectIdOf(key)));
        // The course bucket is "class + course + day" while the entry names the course.
        total += weight(settings, ConstraintType.COURSE_MAX_PER_DAY)
                * excess(classCourseDay, key -> settings.threshold(ConstraintType.COURSE_MAX_PER_DAY,
                        courseIdOf(key)));
        total += weight(settings, ConstraintType.EVENING_MAX)
                * excess(classEvening, key -> settings.threshold(ConstraintType.EVENING_MAX,
                        objectIdOf(key)));

        for (LessonView lesson : lessons) {
            if (lesson.periodId() == null) {
                continue;
            }
            PeriodView period = ctx.periods().get(lesson.periodId());
            if (period == null || period.section() == null) {
                continue;
            }
            CourseView course = lesson.courseId() == null ? null : ctx.courses().get(lesson.courseId());

            ConstraintType sportFirst = ConstraintType.SPORT_NOT_FIRST_AFTERNOON;
            if (isSportSoft(settings, sportFirst, lesson, course)
                    && period.section() == PeriodSection.AFTERNOON
                    && isFirstAfternoon(period, ctx)) {
                total += weight(settings, sportFirst);
            }

            ConstraintType peLast = ConstraintType.PE_LAST_PERIOD_AVOID;
            if (isSportSoft(settings, peLast, lesson, course) && isLastOfDay(period, ctx)) {
                total += weight(settings, peLast);
            }

            ConstraintType morning = ConstraintType.MAIN_SUBJECT_MORNING;
            if (settings.enabled(morning) && !settings.hard(morning) && lesson.courseId() != null) {
                Integer before = settings.courseScalar(morning, lesson.courseId(), "beforePeriodNo");
                if (before != null && period.periodNo() > before) {
                    total += weight(settings, morning);
                }
            }

            ConstraintType preferred = ConstraintType.TEACHER_PREFERRED_SLOT;
            if (settings.enabled(preferred) && !settings.hard(preferred)
                    && preferredSlotViolated(settings, lesson.teacherId(), period)) {
                total += weight(settings, preferred);
            }

            ConstraintType noFirst = ConstraintType.TEACHER_NO_FIRST_PERIOD;
            if (settings.enabled(noFirst) && !settings.hard(noFirst)
                    && targetsTeacher(settings, noFirst, lesson.teacherId())
                    && isFirstOfDay(period, ctx)) {
                total += weight(settings, noFirst);
            }

            ConstraintType dayOff = ConstraintType.TEACHER_DAY_OFF;
            if (settings.enabled(dayOff) && !settings.hard(dayOff)
                    && targetsTeacherDay(settings, lesson.teacherId(), period.dayOfWeek())) {
                total += weight(settings, dayOff);
            }

            ConstraintType headFirst = ConstraintType.HEAD_TEACHER_FIRST_PERIOD;
            if (settings.enabled(headFirst) && !settings.hard(headFirst) && lesson.classId() != null) {
                ClassView adminClass = ctx.classes().get(lesson.classId());
                if (adminClass != null
                        && Objects.equals(adminClass.headTeacherId(), lesson.teacherId())
                        && !isFirstOfDay(period, ctx)) {
                    total += weight(settings, headFirst);
                }
            }
        }

        ConstraintType consecutive = ConstraintType.CONSECUTIVE_LESSONS;
        if (settings.enabled(consecutive) && !settings.hard(consecutive)) {
            for (Map<String, Object> entry : settings.entries(consecutive)) {
                Long courseId = ConstraintSettings.longValue(entry.get("courseId"));
                if (courseId == null) {
                    continue;
                }
                Map<String, List<Integer>> slots = new HashMap<>();
                for (LessonView lesson : lessons) {
                    if (!courseId.equals(lesson.courseId()) || lesson.periodId() == null) {
                        continue;
                    }
                    PeriodView period = ctx.periods().get(lesson.periodId());
                    if (period != null) {
                        slots.computeIfAbsent(lessonKey(lesson.classId(), period.dayOfWeek()),
                                        key -> new ArrayList<>())
                                .add(period.periodNo());
                    }
                }
                for (List<Integer> periodNos : slots.values()) {
                    periodNos.sort(Integer::compareTo);
                    for (int i = 1; i < periodNos.size(); i++) {
                        if (periodNos.get(i) - periodNos.get(i - 1) > 1) {
                            total += weight(settings, consecutive);
                        }
                    }
                }
            }
        }

        ConstraintType spread = ConstraintType.COURSE_SPREAD;
        if (settings.enabled(spread) && !settings.hard(spread)) {
            for (Map.Entry<String, Integer> entry : classCourseDay.entrySet()) {
                Long courseId = courseIdOf(entry.getKey());
                if (courseId == null || !settings.matchesCourse(spread, courseId)) {
                    continue;
                }
                total += weight(settings, spread) * Math.max(0, entry.getValue() - 1);
            }
        }

        ConstraintType gap = ConstraintType.CLASS_GAP_MINIMIZED;
        if (settings.enabled(gap) && !settings.hard(gap)) {
            for (Map.Entry<String, Integer> entry : classDay.entrySet()) {
                String[] parts = entry.getKey().split("-");
                if (!parts[0].matches("\\d+")) {
                    continue;
                }
                long classId = Long.parseLong(parts[0]);
                int day = Integer.parseInt(parts[1]);
                Set<Integer> used = new LinkedHashSet<>();
                for (LessonView lesson : lessons) {
                    if (lesson.periodId() == null || !Objects.equals(lesson.classId(), classId)) {
                        continue;
                    }
                    PeriodView period = ctx.periods().get(lesson.periodId());
                    if (period != null && period.dayOfWeek() == day) {
                        used.add(period.periodNo());
                    }
                }
                if (!used.isEmpty()) {
                    int span = used.stream().mapToInt(Integer::intValue).max().orElse(0)
                            - used.stream().mapToInt(Integer::intValue).min().orElse(0) + 1;
                    total += weight(settings, gap) * Math.max(0, span - used.size());
                }
            }
        }

        ConstraintType maxConsecutive = ConstraintType.TEACHER_MAX_CONSECUTIVE;
        if (settings.enabled(maxConsecutive) && !settings.hard(maxConsecutive)) {
            Map<String, List<Integer>> slots = new HashMap<>();
            for (LessonView lesson : lessons) {
                if (lesson.periodId() == null) {
                    continue;
                }
                PeriodView period = ctx.periods().get(lesson.periodId());
                if (period != null) {
                    slots.computeIfAbsent(lessonKey(lesson.teacherId(), period.dayOfWeek()),
                                    key -> new ArrayList<>())
                            .add(period.periodNo());
                }
            }
            for (Map.Entry<String, List<Integer>> entry : slots.entrySet()) {
                Integer max = settings.threshold(maxConsecutive, objectIdOf(entry.getKey()));
                if (max == null) {
                    continue;
                }
                List<Integer> periodNos = entry.getValue();
                periodNos.sort(Integer::compareTo);
                int run = 1;
                for (int i = 1; i < periodNos.size(); i++) {
                    if (periodNos.get(i) - periodNos.get(i - 1) == 1) {
                        run++;
                    } else {
                        total += weight(settings, maxConsecutive) * Math.max(0, run - max);
                        run = 1;
                    }
                }
                total += weight(settings, maxConsecutive) * Math.max(0, run - max);
            }
        }
        return total;
    }

    private static boolean isSportSoft(
            ConstraintSettings settings, ConstraintType type, LessonView lesson, CourseView course) {
        if (!settings.enabled(type) || settings.hard(type)) {
            return false;
        }
        return targetsSport(settings, type, lesson, course);
    }

    /** The course component of a {@code class-course-day} counting key. */
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

    private static int weight(ConstraintSettings settings, ConstraintType type) {
        return settings.enabled(type) && !settings.hard(type) ? settings.weight(type) : 0;
    }

    /**
     * Sums how far each counting bucket exceeds its own limit. The limit is resolved
     * per bucket through the one scoped-threshold policy, so one bucket can never be
     * charged twice for having several entries and an unlimited bucket costs nothing.
     */
    private static int excess(Map<String, Integer> counts, java.util.function.Function<String, Integer> limitOf) {
        int total = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Integer max = limitOf.apply(entry.getKey());
            if (max != null) {
                total += Math.max(0, entry.getValue() - max);
            }
        }
        return total;
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

    // ---------------------------------------------------------------- labels

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
