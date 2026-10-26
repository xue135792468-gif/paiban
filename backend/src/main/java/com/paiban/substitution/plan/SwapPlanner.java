package com.paiban.substitution.plan;

import com.paiban.foundation.domain.PeriodSection;
import com.paiban.scheduling.constraint.ConstraintChecker;
import com.paiban.scheduling.constraint.ConstraintContext.CourseView;
import com.paiban.scheduling.constraint.ConstraintContext.PeriodView;
import com.paiban.scheduling.constraint.ConstraintContext.RoomView;
import com.paiban.scheduling.constraint.ConstraintViolation;
import com.paiban.scheduling.constraint.LessonView;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.substitution.plan.PlanSnapshot.DaySchedule;
import com.paiban.substitution.plan.PlanSnapshot.ScheduledLesson;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic swap-cycle search. No Spring, no database, no AI: everything is
 * derived from the in-memory {@link PlanSnapshot}, which is what lets the CLI and
 * the unit tests run the identical algorithm.
 *
 * <p>The requested lesson has to move from {@code O} to {@code T}. The rest of the
 * problem is a shortest simple cycle from {@code T} back to {@code O} through the
 * same class's occupied slots. A cycle of {@code n} nodes produces {@code n} moves
 * ({@code O -> T} plus one edge per node on the path).
 *
 * <p>Day-first is expressed by the {@code min-impact} comparator (impact days come
 * first); the other presets deliberately trade days for fewer teachers or a more
 * stable soft score.
 */
public final class SwapPlanner {

    public record Request(long classId, long entryId, LocalDate date, long targetPeriodId) {}

    public record Options(int searchWindowDays, int maxTeachers, RankingPreset preset,
                          int nodeBudget, int timeBudgetMs) {

        public static final int DEFAULT_WINDOW_DAYS = 7;
        public static final int DEFAULT_MAX_TEACHERS = 6;

        public Options {
            searchWindowDays = Math.max(1, Math.min(30, searchWindowDays));
            maxTeachers = Math.max(2, Math.min(10, maxTeachers));
            preset = preset == null ? RankingPreset.MIN_IMPACT : preset;
            nodeBudget = nodeBudget <= 0 ? 50_000 : nodeBudget;
            timeBudgetMs = timeBudgetMs <= 0 ? 200 : timeBudgetMs;
        }

        public static Options defaults() {
            return new Options(DEFAULT_WINDOW_DAYS, DEFAULT_MAX_TEACHERS, RankingPreset.MIN_IMPACT, 50_000, 200);
        }

        public static Options of(int windowDays, int maxTeachers, RankingPreset preset) {
            return new Options(windowDays, maxTeachers, preset, 50_000, 200);
        }
    }

    private static final int MAX_CANDIDATES_KEPT = 40;

    private SwapPlanner() {}

    public static PlanningResult plan(PlanSnapshot snapshot, Request request, Options options) {
        DaySchedule sourceDay = snapshot.scheduleOn(request.date());
        ScheduledLesson source = sourceDay.lessons().stream()
                .filter(lesson -> lesson.entryId() == request.entryId()
                        && lesson.classId() == request.classId())
                .findFirst()
                .orElse(null);
        if (source == null) {
            return PlanningResult.none(List.of(new PlanDiagnosis(
                    "SOURCE_NOT_FOUND",
                    "所选课节在该日期不属于这个班级，可能已有生效调代",
                    request.entryId(), null)), 0, false);
        }
        PeriodView targetPeriod = snapshot.period(request.targetPeriodId());
        if (targetPeriod == null) {
            return PlanningResult.none(List.of(new PlanDiagnosis(
                    "TARGET_NOT_FOUND", "目标节次不存在", request.entryId(), request.targetPeriodId())), 0, false);
        }
        if (request.targetPeriodId() == source.periodId()) {
            return PlanningResult.none(List.of(new PlanDiagnosis(
                    "TARGET_SAME", "目标节次就是原节次", request.entryId(), request.targetPeriodId())), 0, false);
        }
        LocalDate windowEnd = request.date().plusDays(options.searchWindowDays() - 1L);
        int delta = Math.floorMod(targetPeriod.dayOfWeek() - request.date().getDayOfWeek().getValue(), 7);
        LocalDate targetDate = request.date().plusDays(delta);
        if (targetDate.isAfter(windowEnd)) {
            return PlanningResult.none(List.of(new PlanDiagnosis(
                    "TARGET_OUT_OF_WINDOW",
                    "目标节次落在搜索窗口之外（窗口 " + options.searchWindowDays() + " 天）",
                    request.entryId(), request.targetPeriodId())), 0, false);
        }
        if (targetDate.isAfter(snapshot.semesterEnd())) {
            return PlanningResult.none(List.of(new PlanDiagnosis(
                    "TARGET_OUT_OF_SEMESTER", "目标日期超出学期范围", request.entryId(),
                    request.targetPeriodId())), 0, false);
        }
        DaySchedule targetDay = snapshot.scheduleOn(targetDate);
        ScheduledLesson targetLesson = targetDay.lessonAt(request.classId(), request.targetPeriodId());
        if (targetLesson == null) {
            return PlanningResult.none(List.of(new PlanDiagnosis(
                    "TARGET_EMPTY",
                    "目标节次在本班是空堂；空堂只挪不补不在调课范围内",
                    request.entryId(), request.targetPeriodId())), 0, false);
        }
        if (targetLesson.entryId() == source.entryId()) {
            return PlanningResult.none(List.of(new PlanDiagnosis(
                    "TARGET_SAME", "目标节次就是原课节", request.entryId(), request.targetPeriodId())), 0, false);
        }

        Search search = new Search(snapshot, request, options);
        List<PlanDiagnosis> startReasons = new ArrayList<>();
        if (!placeable(search, source, targetDate, targetPeriod, targetLesson.entryId(), startReasons)) {
            return PlanningResult.none(startReasons, search.visited, false);
        }

        Node start = new Node(targetDate, targetPeriod.id(), targetLesson);
        Node goal = new Node(request.date(), source.periodId(), source);
        List<Node> path = new ArrayList<>();
        path.add(start);
        Set<Long> usedEntries = new HashSet<>();
        usedEntries.add(source.entryId());
        usedEntries.add(targetLesson.entryId());
        search.deadline = System.nanoTime() + options.timeBudgetMs() * 1_000_000L;
        search.dfs(start, goal, path, usedEntries, source);
        return search.result();
    }

    private record Node(LocalDate date, long periodId, ScheduledLesson lesson) {}

    private static final class Search {
        private final PlanSnapshot snapshot;
        private final Request request;
        private final Options options;
        private final LocalDate windowEnd;
        private final Map<LocalDate, DaySchedule> days = new HashMap<>();
        private final List<CandidatePlan> found = new ArrayList<>();
        private final Set<String> keys = new HashSet<>();
        private final Map<String, PlanDiagnosis> blocked = new LinkedHashMap<>();
        private int visited;
        private long deadline;
        private boolean timedOut;

        Search(PlanSnapshot snapshot, Request request, Options options) {
            this.snapshot = snapshot;
            this.request = request;
            this.options = options;
            this.windowEnd = request.date().plusDays(options.searchWindowDays() - 1L);
        }

        DaySchedule schedule(LocalDate date) {
            return days.computeIfAbsent(date, snapshot::scheduleOn);
        }

        void dfs(Node current, Node goal, List<Node> path, Set<Long> usedEntries, ScheduledLesson source) {
            if (timedOut) {
                return;
            }
            visited++;
            if (visited > options.nodeBudget() || System.nanoTime() > deadline) {
                timedOut = true;
                return;
            }
            if (path.size() >= 1) {
                List<PlanDiagnosis> why = new ArrayList<>();
                PeriodView goalPeriod = snapshot.period(goal.periodId());
                if (placeable(this, current.lesson(), goal.date(), goalPeriod, source.entryId(), why)) {
                    List<Node> cycle = new ArrayList<>(path);
                    cycle.add(goal);
                    record(cycle, source);
                } else {
                    remember(why);
                }
            }
            if (path.size() >= options.maxTeachers() - 1) {
                return;
            }
            for (LocalDate date = request.date(); !date.isAfter(windowEnd); date = date.plusDays(1)) {
                final LocalDate currentDate = date;
                DaySchedule day = schedule(currentDate);
                for (ScheduledLesson candidate : day.ofClass(request.classId())) {
                    if (candidate.entryId() == source.entryId() || usedEntries.contains(candidate.entryId())) {
                        continue;
                    }
                    if (currentDate.equals(goal.date()) && candidate.periodId() == goal.periodId()) {
                        continue;
                    }
                    if (path.stream().anyMatch(node ->
                            node.date().equals(currentDate) && node.periodId() == candidate.periodId())) {
                        continue;
                    }
                    PeriodView period = snapshot.period(candidate.periodId());
                    if (period == null) {
                        continue;
                    }
                    List<PlanDiagnosis> why = new ArrayList<>();
                    if (!placeable(this, current.lesson(), currentDate, period, candidate.entryId(), why)) {
                        remember(why);
                        continue;
                    }
                    path.add(new Node(currentDate, candidate.periodId(), candidate));
                    usedEntries.add(candidate.entryId());
                    dfs(new Node(currentDate, candidate.periodId(), candidate), goal, path, usedEntries, source);
                    usedEntries.remove(candidate.entryId());
                    path.remove(path.size() - 1);
                    if (timedOut) {
                        return;
                    }
                }
            }
        }

        private void remember(List<PlanDiagnosis> why) {
            for (PlanDiagnosis diagnosis : why) {
                String key = diagnosis.code() + "|" + diagnosis.entryId() + "|" + diagnosis.periodId();
                blocked.putIfAbsent(key, diagnosis);
                if (blocked.size() >= 8) {
                    return;
                }
            }
        }

        private void record(List<Node> cycle, ScheduledLesson source) {
            List<PlanMove> moves = new ArrayList<>(cycle.size());
            int order = 0;
            // The requested lesson always moves first: O -> T.
            moves.add(move(source, request.date(), cycle.get(0), order++));
            for (int i = 0; i < cycle.size() - 1; i++) {
                moves.add(move(cycle.get(i).lesson(), cycle.get(i).date(), cycle.get(i + 1), order++));
            }
            CandidatePlan candidate = buildCandidate(cycle, moves, source);
            if (candidate == null || !keys.add(candidate.key())) {
                return;
            }
            found.add(candidate);
            if (found.size() > MAX_CANDIDATES_KEPT * 4) {
                // Keep the search bounded; the final ranking only needs the best few.
                found.sort(options.preset().comparator());
                found.subList(MAX_CANDIDATES_KEPT, found.size()).clear();
                keys.clear();
                found.forEach(plan -> keys.add(plan.key()));
            }
        }

        private PlanMove move(ScheduledLesson lesson, LocalDate fromDate, Node to, int order) {
            PeriodView fromPeriod = snapshot.period(lesson.periodId());
            return new PlanMove(
                    lesson.entryId(),
                    lesson.classId(),
                    lesson.courseId(),
                    lesson.teacherId(),
                    lesson.roomId(),
                    lesson.periodId(),
                    fromPeriod == null ? lesson.dayOfWeek() : fromPeriod.dayOfWeek(),
                    fromPeriod == null ? lesson.periodNo() : fromPeriod.periodNo(),
                    fromDate,
                    to.periodId(),
                    to.date().getDayOfWeek().getValue(),
                    snapshot.period(to.periodId()) == null ? lesson.periodNo()
                            : snapshot.period(to.periodId()).periodNo(),
                    to.date(),
                    order);
        }

        private CandidatePlan buildCandidate(List<Node> cycle, List<PlanMove> moves, ScheduledLesson source) {
            Set<LocalDate> dates = new LinkedHashSet<>();
            Set<Long> teachers = new LinkedHashSet<>();
            for (Node node : cycle) {
                dates.add(node.date());
                teachers.add(node.lesson().teacherId());
            }
            boolean sameWeek = dates.stream().allMatch(date -> isoWeek(date).equals(isoWeek(request.date())));
            Integer penalty = score(this, moves, dates);
            if (penalty == null) {
                return null;
            }
            return new CandidatePlan(moves, dates.size(), teachers.size(), penalty, cycle.size(), sameWeek);
        }

        private PlanningResult result() {
            found.sort(options.preset().comparator());
            List<CandidatePlan> top = found.stream().limit(3).toList();
            List<PlanDiagnosis> diagnoses;
            if (top.isEmpty()) {
                diagnoses = new ArrayList<>(blocked.values());
                diagnoses.add(new PlanDiagnosis(
                        "NO_SOLUTION",
                        "窗口内没有满足硬约束的置换环；可以换一个目标节次或扩大搜索窗口",
                        request.entryId(), request.targetPeriodId()));
                if (timedOut) {
                    diagnoses.add(new PlanDiagnosis("SEARCH_BUDGET", "搜索超出预算，结果可能不完整",
                            request.entryId(), null));
                }
            } else {
                diagnoses = List.of();
            }
            return new PlanningResult(top, diagnoses, visited, timedOut);
        }
    }

    // ------------------------------------------------------------------ scoring

    /** Returns the soft-score delta, or null when the final state adds a hard violation. */
    private static Integer score(Search search, List<PlanMove> moves, Set<LocalDate> dates) {
        Map<LocalDate, List<ScheduledLesson>> before = new LinkedHashMap<>();
        Map<LocalDate, List<ScheduledLesson>> after = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            List<ScheduledLesson> base = search.schedule(date).lessons();
            before.put(date, base);
            List<ScheduledLesson> changed = new ArrayList<>();
            for (ScheduledLesson lesson : base) {
                boolean movedOut = moves.stream().anyMatch(move ->
                        move.entryId() == lesson.entryId() && move.fromDate().equals(date));
                if (!movedOut) {
                    changed.add(lesson);
                }
            }
            for (PlanMove move : moves) {
                if (!move.toDate().equals(date)) {
                    continue;
                }
                changed.add(new ScheduledLesson(
                        move.entryId(), move.classId(), move.courseId(), move.teacherId(), move.roomId(),
                        move.toPeriodId(), move.toDayOfWeek(), move.toPeriodNo(),
                        search.snapshot.studentCount(move.classId())));
            }
            after.put(date, changed);
        }
        int beforePenalty = 0;
        int afterPenalty = 0;
        for (LocalDate date : dates) {
            List<LessonView> beforeViews = views(before.get(date));
            List<LessonView> afterViews = views(after.get(date));
            List<ConstraintViolation> beforeViolations =
                    ConstraintChecker.checkHardFast(search.snapshot.context(), beforeViews);
            List<ConstraintViolation> afterViolations =
                    ConstraintChecker.checkHardFast(search.snapshot.context(), afterViews);
            if (introducesViolation(beforeViolations, afterViolations)) {
                return null;
            }
            beforePenalty += ConstraintChecker.softPenalty(search.snapshot.context(), beforeViews);
            afterPenalty += ConstraintChecker.softPenalty(search.snapshot.context(), afterViews);
        }
        return afterPenalty - beforePenalty;
    }

    private static boolean introducesViolation(
            List<ConstraintViolation> before, List<ConstraintViolation> after) {
        Map<String, Integer> counts = new HashMap<>();
        for (ConstraintViolation violation : before) {
            counts.merge(key(violation), 1, Integer::sum);
        }
        for (ConstraintViolation violation : after) {
            String key = key(violation);
            int remaining = counts.getOrDefault(key, 0);
            if (remaining == 0) {
                return true;
            }
            counts.put(key, remaining - 1);
        }
        return false;
    }

    private static String key(ConstraintViolation violation) {
        return violation.code() + "|" + violation.lessonId() + "|" + violation.message();
    }

    private static List<LessonView> views(List<ScheduledLesson> lessons) {
        return lessons.stream()
                .map(lesson -> new LessonView(lesson.entryId(), lesson.classId(), lesson.courseId(),
                        lesson.teacherId(), lesson.roomId(), lesson.periodId(), lesson.studentCount()))
                .toList();
    }

    // -------------------------------------------------------------- edge pruning

    private static boolean placeable(Search search, ScheduledLesson lesson, LocalDate date,
                                     PeriodView target, long vacatingEntryId,
                                     List<PlanDiagnosis> reasons) {
        if (target == null) {
            return false;
        }
        PlanSnapshot snapshot = search.snapshot;
        ConstraintSettings settings = snapshot.settings();
        DaySchedule day = search.schedule(date);
        String label = label(target);

        if (blocked(settings, ConstraintType.TEACHER_CONFLICT)
                && day.lessons().stream().anyMatch(other -> other.teacherId() == lesson.teacherId()
                        && other.periodId() == target.id()
                        && other.entryId() != lesson.entryId()
                        && other.entryId() != vacatingEntryId)) {
            reasons.add(new PlanDiagnosis("TEACHER_BUSY",
                    teacherName(snapshot, lesson.teacherId()) + " 在 " + label + " 已有课",
                    lesson.entryId(), target.id()));
            return false;
        }
        RoomView room = snapshot.room(lesson.roomId());
        if (blocked(settings, ConstraintType.ROOM_CONFLICT)
                && lesson.roomId() != null
                && day.lessons().stream().anyMatch(other -> Objects.equals(other.roomId(), lesson.roomId())
                        && other.periodId() == target.id()
                        && other.entryId() != lesson.entryId()
                        && other.entryId() != vacatingEntryId)) {
            reasons.add(new PlanDiagnosis("ROOM_BUSY",
                    "教室「" + (room == null ? lesson.roomId() : room.name()) + "」在 " + label + " 已被占用",
                    lesson.entryId(), target.id()));
            return false;
        }
        if (blocked(settings, ConstraintType.ROOM_CAPACITY_FIT)
                && room != null && room.capacity() < lesson.studentCount()) {
            reasons.add(new PlanDiagnosis("ROOM_CAPACITY",
                    "教室「" + room.name() + "」容量不足", lesson.entryId(), target.id()));
            return false;
        }
        CourseView course = snapshot.course(lesson.courseId());
        String requiredType = course == null ? null : course.requiredRoomType();
        if (blocked(settings, ConstraintType.COURSE_REQUIRED_ROOM_TYPE)) {
            Long configuredCourse = settings.longParam(ConstraintType.COURSE_REQUIRED_ROOM_TYPE, "courseId");
            if (Objects.equals(configuredCourse, lesson.courseId())) {
                requiredType = settings.textParam(ConstraintType.COURSE_REQUIRED_ROOM_TYPE, "roomType");
            }
        }
        if (blocked(settings, ConstraintType.COURSE_REQUIRED_ROOM_TYPE)
                && requiredType != null && !requiredType.isBlank()
                && (room == null || !requiredType.equals(room.type()))) {
            reasons.add(new PlanDiagnosis("ROOM_TYPE",
                    "课程需要「" + requiredType + "」类型的教室", lesson.entryId(), target.id()));
            return false;
        }
        if (blocked(settings, ConstraintType.TEACHER_UNAVAILABLE)
                && unavailableAt(settings, lesson.teacherId(), target)) {
            reasons.add(new PlanDiagnosis("TEACHER_UNAVAILABLE",
                    teacherName(snapshot, lesson.teacherId()) + " 在 " + label + " 不排课",
                    lesson.entryId(), target.id()));
            return false;
        }
        if (blocked(settings, ConstraintType.TEACHER_MEETING_BLOCKED)) {
            String subject = settings.textParam(ConstraintType.TEACHER_MEETING_BLOCKED, "subject");
            var teacher = snapshot.teacher(lesson.teacherId());
            boolean subjectMatches = subject == null || subject.isBlank()
                    || (teacher != null && subject.equalsIgnoreCase(teacher.subject()));
            if (subjectMatches
                    && Objects.equals(settings.intParam(ConstraintType.TEACHER_MEETING_BLOCKED, "dayOfWeek"),
                            target.dayOfWeek())
                    && Objects.equals(settings.intParam(ConstraintType.TEACHER_MEETING_BLOCKED, "periodNo"),
                            target.periodNo())) {
                reasons.add(new PlanDiagnosis("TEACHER_MEETING",
                        teacherName(snapshot, lesson.teacherId()) + " 在 " + label + " 参加教研",
                        lesson.entryId(), target.id()));
                return false;
            }
        }
        if (blocked(settings, ConstraintType.COURSE_NOT_IN_EVENING)
                && settings.matchesCourse(ConstraintType.COURSE_NOT_IN_EVENING, lesson.courseId())
                && target.section() == PeriodSection.EVENING) {
            reasons.add(new PlanDiagnosis("COURSE_NOT_IN_EVENING",
                    "该课程不能安排在晚自习", lesson.entryId(), target.id()));
            return false;
        }
        if (blocked(settings, ConstraintType.SAME_COURSE_ONCE_PER_DAY)
                && settings.matchesCourse(ConstraintType.SAME_COURSE_ONCE_PER_DAY, lesson.courseId())
                && day.lessons().stream().anyMatch(other -> other.classId() == lesson.classId()
                        && other.courseId() == lesson.courseId()
                        && other.periodId() != target.id()
                        && other.entryId() != lesson.entryId()
                        && other.entryId() != vacatingEntryId)) {
            reasons.add(new PlanDiagnosis("SAME_COURSE_PER_DAY",
                    "本班当天已有同一门课程", lesson.entryId(), target.id()));
            return false;
        }
        return true;
    }

    private static boolean blocked(ConstraintSettings settings, ConstraintType type) {
        return settings.enabled(type) && settings.hard(type);
    }

    /** Union of every teacher-unavailable entry, matching how the solver reads them. */
    private static boolean unavailableAt(
            ConstraintSettings settings, long teacherId, PeriodView target) {
        for (Map<String, Object> entry : settings.entries(ConstraintType.TEACHER_UNAVAILABLE)) {
            if (Objects.equals(ConstraintSettings.longValue(entry.get("teacherId")), teacherId)
                    && Objects.equals(ConstraintSettings.intValue(entry.get("dayOfWeek")),
                    target.dayOfWeek())
                    && Objects.equals(ConstraintSettings.intValue(entry.get("periodNo")),
                    target.periodNo())) {
                return true;
            }
        }
        return false;
    }

    private static String teacherName(PlanSnapshot snapshot, long teacherId) {
        var teacher = snapshot.teacher(teacherId);
        return teacher == null ? "该教师" : teacher.name();
    }

    private static String label(PeriodView period) {
        String[] days = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        String day = period.dayOfWeek() >= 1 && period.dayOfWeek() <= 7
                ? days[period.dayOfWeek()] : "周" + period.dayOfWeek();
        return day + "第" + period.periodNo() + "节";
    }

    private static String isoWeek(LocalDate date) {
        return date.get(IsoFields.WEEK_BASED_YEAR) + "-" + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}
