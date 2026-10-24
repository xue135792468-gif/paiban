package com.paiban.substitution.plan;

import com.paiban.scheduling.constraint.ConstraintChecker;
import com.paiban.scheduling.constraint.ConstraintViolation;
import com.paiban.scheduling.constraint.LessonView;
import com.paiban.substitution.plan.PlanSnapshot.DaySchedule;
import com.paiban.substitution.plan.PlanSnapshot.ScheduledLesson;
import java.time.LocalDate;
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
 * Independent gate for a plan. It never calls planner helpers: it rebuilds the
 * before/after timetable from the snapshot and the moves, then brute-scans the
 * four core invariants and the configured hard rules. A solver bug cannot make a
 * bad plan effective.
 *
 * <p>The soft score is recomputed from the final timetable, not copied from the
 * planner. It intentionally goes through the one rule catalogue (ADR-0025: no
 * second copy of the 24 rules), so the comparison catches planner bookkeeping
 * errors rather than a shared arithmetic bug; the property test pins the two
 * computations together.
 */
public final class PlanVerifier {

    public enum Kind {
        SWAP, COVER
    }

    public record Issue(String code, String message, Long entryId, Long periodId) {}

    public record Result(boolean passed, List<Issue> issues, int softPenalty) {

        public Result {
            issues = List.copyOf(issues);
        }
    }

    private PlanVerifier() {}

    /**
     * Compares two weekly baselines (the CLI {@code verify} use case). Every changed
     * entry becomes a move, so the same invariant checks run, and any added or
     * removed entry is reported rather than silently accepted.
     */
    public static Result verifyDiff(PlanSnapshot before, PlanSnapshot after) {
        List<Issue> issues = new ArrayList<>();
        Map<Long, PlanSnapshot.BaselineLesson> afterByEntry = new HashMap<>();
        after.baseline().forEach(lesson -> afterByEntry.put(lesson.entryId(), lesson));
        Map<Long, PlanSnapshot.BaselineLesson> beforeByEntry = new HashMap<>();
        before.baseline().forEach(lesson -> beforeByEntry.put(lesson.entryId(), lesson));

        List<PlanMove> moves = new ArrayList<>();
        boolean teacherChanged = false;
        for (PlanSnapshot.BaselineLesson lesson : before.baseline()) {
            PlanSnapshot.BaselineLesson now = afterByEntry.get(lesson.entryId());
            if (now == null) {
                issues.add(new Issue("ENTRY_REMOVED", "课节在改动后消失", lesson.entryId(), lesson.periodId()));
                continue;
            }
            if (now.periodId() == lesson.periodId()
                    && Objects.equals(now.teacherId(), lesson.teacherId())
                    && Objects.equals(now.roomId(), lesson.roomId())
                    && now.courseId() == lesson.courseId()) {
                continue;
            }
            teacherChanged |= now.teacherId() != lesson.teacherId();
            LocalDate fromDate = dateFor(before, lesson.dayOfWeek());
            LocalDate toDate = dateFor(after, now.dayOfWeek());
            moves.add(new PlanMove(
                    lesson.entryId(), lesson.classId(), lesson.courseId(), now.teacherId(), now.roomId(),
                    lesson.periodId(), lesson.dayOfWeek(), lesson.periodNo(), fromDate,
                    now.periodId(), now.dayOfWeek(), now.periodNo(), toDate, moves.size()));
        }
        for (PlanSnapshot.BaselineLesson lesson : after.baseline()) {
            if (!beforeByEntry.containsKey(lesson.entryId())) {
                issues.add(new Issue("ENTRY_ADDED", "改动后出现了新课时", lesson.entryId(), lesson.periodId()));
            }
        }
        Kind kind = teacherChanged && moves.stream()
                .allMatch(move -> move.fromPeriodId() == move.toPeriodId())
                ? Kind.COVER : Kind.SWAP;
        Result structural = verify(before, moves, kind, -1);
        List<Issue> all = new ArrayList<>(issues);
        all.addAll(structural.issues());
        return new Result(all.isEmpty(), all, structural.softPenalty());
    }

    private static LocalDate dateFor(PlanSnapshot snapshot, int dayOfWeek) {
        int startDay = snapshot.semesterStart().getDayOfWeek().getValue();
        return snapshot.semesterStart().plusDays(Math.floorMod(dayOfWeek - startDay, 7));
    }

    public static Result verify(PlanSnapshot snapshot,
                                List<PlanMove> moves,
                                Kind kind,
                                int expectedSoftPenalty) {
        List<Issue> issues = new ArrayList<>();
        if (moves == null || moves.isEmpty()) {
            return new Result(false, List.of(new Issue("NO_MOVES", "方案没有任何课节移动", null, null)), 0);
        }

        Set<Long> entryIds = new HashSet<>();
        long classId = moves.get(0).classId();
        for (PlanMove move : moves) {
            if (!entryIds.add(move.entryId())) {
                issues.add(new Issue("DUPLICATE_ENTRY", "同一课节在方案中出现多次", move.entryId(),
                        move.toPeriodId()));
            }
            if (move.classId() != classId) {
                issues.add(new Issue("CROSS_CLASS",
                        "调课限定在同一行政班内，方案却涉及多个班级", move.entryId(), move.toPeriodId()));
            }
            if (snapshot.period(move.toPeriodId()) == null) {
                issues.add(new Issue("TARGET_NOT_FOUND", "目标节次不存在", move.entryId(), move.toPeriodId()));
            }
        }

        Set<LocalDate> dates = new LinkedHashSet<>();
        moves.forEach(move -> {
            dates.add(move.fromDate());
            dates.add(move.toDate());
        });

        Map<LocalDate, List<ScheduledLesson>> before = new LinkedHashMap<>();
        Map<LocalDate, List<ScheduledLesson>> after = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            List<ScheduledLesson> base = snapshot.scheduleOn(date).lessons();
            before.put(date, base);
            after.put(date, applyMoves(snapshot, base, moves, date));
        }

        for (LocalDate date : dates) {
            checkInvariants(date, before.get(date), after.get(date), issues);
            checkHardRules(snapshot, before.get(date), after.get(date), issues);
        }
        checkMovesPreserveLessonData(snapshot, moves, kind, before, issues);
        checkNothingElseChanged(before, after, moves, issues);

        int softPenalty = 0;
        for (LocalDate date : dates) {
            softPenalty += ConstraintChecker.softPenalty(snapshot.context(), views(after.get(date)))
                    - ConstraintChecker.softPenalty(snapshot.context(), views(before.get(date)));
        }
        if (expectedSoftPenalty >= 0 && softPenalty != expectedSoftPenalty) {
            issues.add(new Issue("SOFT_SCORE_MISMATCH",
                    "求解器报告软约束扣分 " + expectedSoftPenalty + "，校验器重算为 " + softPenalty,
                    null, null));
        }
        return new Result(issues.isEmpty(), issues, softPenalty);
    }

    private static List<ScheduledLesson> applyMoves(
            PlanSnapshot snapshot, List<ScheduledLesson> base, List<PlanMove> moves, LocalDate date) {
        List<ScheduledLesson> changed = new ArrayList<>();
        for (ScheduledLesson lesson : base) {
            boolean movedOut = moves.stream().anyMatch(move ->
                    move.entryId() == lesson.entryId() && move.fromDate().equals(date));
            if (!movedOut) {
                changed.add(lesson);
            }
        }
        for (PlanMove move : moves) {
            if (move.toDate().equals(date)) {
                changed.add(new ScheduledLesson(
                        move.entryId(), move.classId(), move.courseId(), move.teacherId(), move.roomId(),
                        move.toPeriodId(), move.toDayOfWeek(), move.toPeriodNo(),
                        snapshot.studentCount(move.classId())));
            }
        }
        return changed;
    }

    /**
     * The four invariants are implemented here directly, without the constraint
     * engine, so a bug in the shared rules cannot hide a broken plan.
     */
    private static void checkInvariants(LocalDate date,
                                        List<ScheduledLesson> before,
                                        List<ScheduledLesson> after,
                                        List<Issue> issues) {
        reportDuplicates(date, after, ScheduledLesson::teacherId, "TEACHER_DOUBLE_BOOKED",
                "同一教师在同一时刻出现多节课", issues);
        reportDuplicates(date, after, lesson -> lesson.classId(), "CLASS_DOUBLE_BOOKED",
                "同一班级在同一时刻出现多门课", issues);
        reportDuplicates(date, after, lesson -> lesson.roomId(), "ROOM_DOUBLE_BOOKED",
                "同一教室在同一时刻被多节课占用", issues);

        Map<Long, Integer> beforeCounts = new HashMap<>();
        Map<Long, Integer> afterCounts = new HashMap<>();
        before.forEach(lesson -> beforeCounts.merge(lesson.classId(), 1, Integer::sum));
        after.forEach(lesson -> afterCounts.merge(lesson.classId(), 1, Integer::sum));
        for (Map.Entry<Long, Integer> entry : beforeCounts.entrySet()) {
            int now = afterCounts.getOrDefault(entry.getKey(), 0);
            if (now != entry.getValue()) {
                issues.add(new Issue("CLASS_DAY_COUNT_CHANGED",
                        "班级 " + entry.getKey() + " 在 " + date + " 的节数从 "
                                + entry.getValue() + " 变成 " + now,
                        null, null));
            }
        }
        for (Map.Entry<Long, Integer> entry : afterCounts.entrySet()) {
            if (!beforeCounts.containsKey(entry.getKey())) {
                issues.add(new Issue("CLASS_DAY_COUNT_CHANGED",
                        "班级 " + entry.getKey() + " 在 " + date + " 新增了 " + entry.getValue() + " 节课",
                        null, null));
            }
        }
    }

    private interface KeyOf {
        Object apply(ScheduledLesson lesson);
    }

    private static void reportDuplicates(LocalDate date,
                                         List<ScheduledLesson> lessons,
                                         KeyOf keyOf,
                                         String code,
                                         String message,
                                         List<Issue> issues) {
        Map<String, ScheduledLesson> seen = new HashMap<>();
        for (ScheduledLesson lesson : lessons) {
            Object key = keyOf.apply(lesson);
            if (key == null) {
                continue;
            }
            String composite = lesson.periodId() + "|" + key;
            ScheduledLesson previous = seen.putIfAbsent(composite, lesson);
            if (previous != null) {
                issues.add(new Issue(code, message + "（" + date + " 第 " + lesson.periodNo() + " 节）",
                        lesson.entryId(), lesson.periodId()));
            }
        }
    }

    private static void checkHardRules(PlanSnapshot snapshot,
                                       List<ScheduledLesson> before,
                                       List<ScheduledLesson> after,
                                       List<Issue> issues) {
        List<ConstraintViolation> beforeViolations =
                ConstraintChecker.checkHardFast(snapshot.context(), views(before));
        List<ConstraintViolation> afterViolations =
                ConstraintChecker.checkHardFast(snapshot.context(), views(after));
        Map<String, Integer> counts = new HashMap<>();
        beforeViolations.forEach(violation -> counts.merge(violationKey(violation), 1, Integer::sum));
        for (ConstraintViolation violation : afterViolations) {
            String key = violationKey(violation);
            int remaining = counts.getOrDefault(key, 0);
            if (remaining > 0) {
                counts.put(key, remaining - 1);
            } else {
                issues.add(new Issue(violation.code(), violation.message(), violation.lessonId(), null));
            }
        }
    }

    private static String violationKey(ConstraintViolation violation) {
        return violation.code() + "|" + violation.lessonId() + "|" + violation.message();
    }

    private static void checkMovesPreserveLessonData(PlanSnapshot snapshot,
                                                     List<PlanMove> moves,
                                                     Kind kind,
                                                     Map<LocalDate, List<ScheduledLesson>> before,
                                                     List<Issue> issues) {
        for (PlanMove move : moves) {
            ScheduledLesson source = before.getOrDefault(move.fromDate(), List.of()).stream()
                    .filter(lesson -> lesson.entryId() == move.entryId())
                    .findFirst()
                    .orElse(null);
            if (source == null) {
                issues.add(new Issue("MOVE_SOURCE_MISSING",
                        "方案移动的课节在变更前的课表里不存在", move.entryId(), move.fromPeriodId()));
                continue;
            }
            if (source.periodId() != move.fromPeriodId()) {
                issues.add(new Issue("MOVE_SOURCE_MOVED",
                        "方案基于的课节时间与当前课表不一致，课表可能已变化",
                        move.entryId(), move.fromPeriodId()));
            }
            if (source.courseId() != move.courseId()) {
                issues.add(new Issue("COURSE_CHANGED", "调课不能改变课程", move.entryId(),
                        move.toPeriodId()));
            }
            if (kind == Kind.SWAP && source.teacherId() != move.teacherId()) {
                issues.add(new Issue("TEACHER_CHANGED", "调课不能改变任课教师", move.entryId(),
                        move.toPeriodId()));
            }
            if (kind == Kind.COVER && move.fromPeriodId() != move.toPeriodId()) {
                issues.add(new Issue("COVER_MOVED_SLOT",
                        "代课只换教师，不换节次；换时间请走调课", move.entryId(), move.toPeriodId()));
            }
        }
    }

    private static void checkNothingElseChanged(Map<LocalDate, List<ScheduledLesson>> before,
                                                Map<LocalDate, List<ScheduledLesson>> after,
                                                List<PlanMove> moves,
                                                List<Issue> issues) {
        Set<Long> planEntries = new HashSet<>();
        moves.forEach(move -> planEntries.add(move.entryId()));
        for (LocalDate date : before.keySet()) {
            Map<Long, ScheduledLesson> afterByEntry = new HashMap<>();
            after.get(date).forEach(lesson -> afterByEntry.put(lesson.entryId(), lesson));
            for (ScheduledLesson lesson : before.get(date)) {
                if (planEntries.contains(lesson.entryId())) {
                    continue;
                }
                ScheduledLesson now = afterByEntry.get(lesson.entryId());
                if (now == null
                        || now.periodId() != lesson.periodId()
                        || !Objects.equals(now.teacherId(), lesson.teacherId())
                        || !Objects.equals(now.roomId(), lesson.roomId())) {
                    issues.add(new Issue("UNPLANNED_CHANGE",
                            "方案之外的课节发生了变化", lesson.entryId(), lesson.periodId()));
                }
            }
        }
    }

    private static List<LessonView> views(List<ScheduledLesson> lessons) {
        return lessons.stream()
                .map(lesson -> new LessonView(lesson.entryId(), lesson.classId(), lesson.courseId(),
                        lesson.teacherId(), lesson.roomId(), lesson.periodId(), lesson.studentCount()))
                .toList();
    }
}
