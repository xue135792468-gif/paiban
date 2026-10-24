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
        if (include.test(roomTypeConstraint)) {
            Long configuredCourse = settings.longParam(roomTypeConstraint, "courseId");
            if (Objects.equals(configuredCourse, lesson.courseId())) {
                requiredRoomType = settings.textParam(roomTypeConstraint, "roomType");
            }
        }
        if (requiredRoomType != null && !requiredRoomType.isBlank()
                && (room == null || !requiredRoomType.equals(room.type()))) {
            out.add(new ConstraintViolation("ROOM_TYPE",
                    courseName + " 需要安排在「" + roomTypeLabel(requiredRoomType) + "」类型的教室",
                    lesson.id(), hardResult));
        }

        ConstraintType unavailable = ConstraintType.TEACHER_UNAVAILABLE;
        if (include.test(unavailable)) {
            Long teacherId = settings.longParam(unavailable, "teacherId");
            Integer day = settings.intParam(unavailable, "dayOfWeek");
            Integer periodNo = settings.intParam(unavailable, "periodNo");
            if (Objects.equals(teacherId, lesson.teacherId())
                    && Objects.equals(day, period.dayOfWeek())
                    && Objects.equals(periodNo, period.periodNo())) {
                out.add(new ConstraintViolation("TEACHER_UNAVAILABLE",
                        teacherName(ctx, lesson.teacherId()) + " 在 " + periodLabel(period) + " 不排课",
                        lesson.id(), hardResult));
            }
        }

        ConstraintType meeting = ConstraintType.TEACHER_MEETING_BLOCKED;
        if (include.test(meeting)) {
            String subject = settings.textParam(meeting, "subject");
            Integer day = settings.intParam(meeting, "dayOfWeek");
            Integer periodNo = settings.intParam(meeting, "periodNo");
            TeacherView teacher = lesson.teacherId() == null ? null : ctx.teachers().get(lesson.teacherId());
            boolean subjectMatches = subject == null || subject.isBlank()
                    || (teacher != null && subject.equalsIgnoreCase(teacher.subject()));
            if (subjectMatches
                    && Objects.equals(day, period.dayOfWeek())
                    && Objects.equals(periodNo, period.periodNo())) {
                out.add(new ConstraintViolation("TEACHER_MEETING",
                        teacherName(ctx, lesson.teacherId()) + " 在 " + periodLabel(period) + " 参加教研活动",
                        lesson.id(), hardResult));
            }
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
            int max = settings.intParam(teacherMax, "maxPerDay") == null
                    ? 4 : settings.intParam(teacherMax, "maxPerDay");
            for (Map.Entry<String, Integer> entry : teacherPerDay.entrySet()) {
                if (entry.getValue() > max && (!filter || relevantKeys.contains(entry.getKey()))) {
                    Long teacherId = Long.parseLong(cutSuffix(entry.getKey()));
                    out.add(new ConstraintViolation("TEACHER_MAX_PER_DAY",
                            teacherName(ctx, teacherId) + " 单日排了 " + entry.getValue()
                                    + " 节，超过上限 " + max,
                            null, hardResult));
                }
            }
        }
        ConstraintType classMax = ConstraintType.CLASS_MAX_PER_DAY;
        if (include.test(classMax)) {
            int max = settings.intParam(classMax, "maxPerDay") == null
                    ? 8 : settings.intParam(classMax, "maxPerDay");
            for (Map.Entry<String, Integer> entry : classPerDay.entrySet()) {
                if (entry.getValue() > max && (!filter || relevantKeys.contains(entry.getKey()))) {
                    Long classId = Long.parseLong(cutSuffix(entry.getKey()));
                    out.add(new ConstraintViolation("CLASS_MAX_PER_DAY",
                            className(ctx, classId) + " 单日排了 " + entry.getValue()
                                    + " 节，超过上限 " + max,
                            null, hardResult));
                }
            }
        }
        ConstraintType courseMax = ConstraintType.COURSE_MAX_PER_DAY;
        if (include.test(courseMax)) {
            int max = settings.intParam(courseMax, "maxPerDay") == null
                    ? 2 : settings.intParam(courseMax, "maxPerDay");
            for (Map.Entry<String, Integer> entry : coursePerDay.entrySet()) {
                if (entry.getValue() > max && (!filter || relevantKeys.contains(entry.getKey()))) {
                    String key = entry.getKey();
                    int second = key.indexOf('-', key.indexOf('-') + 1);
                    Long classId = Long.parseLong(key.substring(0, key.indexOf('-')));
                    Long courseId = Long.parseLong(key.substring(key.indexOf('-') + 1, second));
                    out.add(new ConstraintViolation("COURSE_MAX_PER_DAY",
                            className(ctx, classId) + " 的 " + courseName(ctx, courseId)
                                    + " 单日排了 " + entry.getValue() + " 节，超过上限 " + max,
                            null, hardResult));
                }
            }
        }
        ConstraintType eveningMax = ConstraintType.EVENING_MAX;
        if (include.test(eveningMax)) {
            int max = settings.intParam(eveningMax, "maxPerDay") == null
                    ? 2 : settings.intParam(eveningMax, "maxPerDay");
            for (Map.Entry<String, Integer> entry : eveningPerClass.entrySet()) {
                if (entry.getValue() > max && (!filter || relevantKeys.contains(entry.getKey()))) {
                    Long classId = Long.parseLong(cutSuffix(entry.getKey()));
                    out.add(new ConstraintViolation("EVENING_MAX",
                            className(ctx, classId) + " 晚自习排了 " + entry.getValue()
                                    + " 节，超过上限 " + max,
                            null, hardResult));
                }
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
                Long configured = settings.longParam(sport, "courseId");
                boolean isSport = configured != null
                        ? Objects.equals(configured, lesson.courseId())
                        : course != null && course.name() != null && course.name().contains("体育");
                if (isSport && period != null && period.section() == PeriodSection.AFTERNOON
                        && isFirstAfternoon(period, ctx)) {
                    out.add(new ConstraintViolation("SPORT_NOT_FIRST_AFTERNOON",
                            courseName(ctx, lesson.courseId()) + " 不应排在下午第一节",
                            lesson.id(), hardResult));
                }
            }
        }
    }

    /** Key format "{id}-{suffix}"; the id is everything before the first dash. */
    private static String cutSuffix(String key) {
        return key.substring(0, key.indexOf('-'));
    }

    private static boolean isFirstAfternoon(PeriodView period, ConstraintContext ctx) {
        return ctx.periods().values().stream()
                .filter(other -> other.dayOfWeek() == period.dayOfWeek()
                        && other.section() == PeriodSection.AFTERNOON)
                .mapToInt(PeriodView::periodNo)
                .min()
                .orElse(-1) == period.periodNo();
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
                * excess(teacherDay, settings.intParam(ConstraintType.TEACHER_MAX_PER_DAY, "maxPerDay"), 4);
        total += weight(settings, ConstraintType.CLASS_MAX_PER_DAY)
                * excess(classDay, settings.intParam(ConstraintType.CLASS_MAX_PER_DAY, "maxPerDay"), 8);
        total += weight(settings, ConstraintType.COURSE_MAX_PER_DAY)
                * excess(classCourseDay, settings.intParam(ConstraintType.COURSE_MAX_PER_DAY, "maxPerDay"), 2);
        total += weight(settings, ConstraintType.EVENING_MAX)
                * excess(classEvening, settings.intParam(ConstraintType.EVENING_MAX, "maxPerDay"), 2);

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
            if (isSport(settings, sportFirst, lesson, course)
                    && period.section() == PeriodSection.AFTERNOON
                    && isFirstAfternoon(period, ctx)) {
                total += weight(settings, sportFirst);
            }

            ConstraintType peLast = ConstraintType.PE_LAST_PERIOD_AVOID;
            if (isSport(settings, peLast, lesson, course) && isLastOfDay(period, ctx)) {
                total += weight(settings, peLast);
            }

            ConstraintType morning = ConstraintType.MAIN_SUBJECT_MORNING;
            if (settings.enabled(morning) && !settings.hard(morning)
                    && settings.matchesCourse(morning, lesson.courseId())) {
                int before = settings.intParam(morning, "beforePeriodNo") == null
                        ? 4 : settings.intParam(morning, "beforePeriodNo");
                if (period.periodNo() > before) {
                    total += weight(settings, morning);
                }
            }

            ConstraintType preferred = ConstraintType.TEACHER_PREFERRED_SLOT;
            if (settings.enabled(preferred) && !settings.hard(preferred)
                    && Objects.equals(settings.longParam(preferred, "teacherId"), lesson.teacherId())) {
                boolean matches = Objects.equals(settings.intParam(preferred, "dayOfWeek"), period.dayOfWeek())
                        && Objects.equals(settings.intParam(preferred, "periodNo"), period.periodNo());
                if (!matches) {
                    total += weight(settings, preferred);
                }
            }

            ConstraintType noFirst = ConstraintType.TEACHER_NO_FIRST_PERIOD;
            if (settings.enabled(noFirst) && !settings.hard(noFirst)
                    && Objects.equals(settings.longParam(noFirst, "teacherId"), lesson.teacherId())
                    && isFirstOfDay(period, ctx)) {
                total += weight(settings, noFirst);
            }

            ConstraintType dayOff = ConstraintType.TEACHER_DAY_OFF;
            if (settings.enabled(dayOff) && !settings.hard(dayOff)
                    && Objects.equals(settings.longParam(dayOff, "teacherId"), lesson.teacherId())
                    && Objects.equals(settings.intParam(dayOff, "dayOfWeek"), period.dayOfWeek())) {
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
            Long courseId = settings.longParam(consecutive, "courseId");
            Map<String, List<Integer>> slots = new HashMap<>();
            for (LessonView lesson : lessons) {
                if (courseId == null || !courseId.equals(lesson.courseId()) || lesson.periodId() == null) {
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

        ConstraintType spread = ConstraintType.COURSE_SPREAD;
        if (settings.enabled(spread) && !settings.hard(spread)) {
            Long spreadCourse = settings.longParam(spread, "courseId");
            for (Map.Entry<String, Integer> entry : classCourseDay.entrySet()) {
                String[] parts = entry.getKey().split("-");
                if (spreadCourse != null
                        && !spreadCourse.equals(Long.parseLong(parts[1]))) {
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
            int max = settings.intParam(maxConsecutive, "maxConsecutive") == null
                    ? 3 : settings.intParam(maxConsecutive, "maxConsecutive");
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
            for (List<Integer> periodNos : slots.values()) {
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

    private static boolean isSport(
            ConstraintSettings settings, ConstraintType type, LessonView lesson, CourseView course) {
        if (!settings.enabled(type) || settings.hard(type)) {
            return false;
        }
        Long configured = settings.longParam(type, "courseId");
        return configured != null
                ? configured.equals(lesson.courseId())
                : course != null && course.name() != null && course.name().contains("体育");
    }

    private static int weight(ConstraintSettings settings, ConstraintType type) {
        return settings.enabled(type) && !settings.hard(type) ? settings.weight(type) : 0;
    }

    private static int excess(Map<String, Integer> counts, Integer configuredMax, int fallback) {
        int max = configuredMax == null ? fallback : configuredMax;
        int total = 0;
        for (int count : counts.values()) {
            total += Math.max(0, count - max);
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
