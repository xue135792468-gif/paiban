package com.paiban.scheduling.plan;

import com.paiban.common.policy.ExchangePlanMetrics;
import com.paiban.common.policy.ExchangeRankingPreset;
import com.paiban.scheduling.constraint.ConstraintContext.PeriodView;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings.Control;
import com.paiban.scheduling.service.DraftValidator;
import com.paiban.scheduling.service.DraftValidator.ChangeAssessment;
import com.paiban.scheduling.service.DraftValidator.LessonChange;
import com.paiban.scheduling.service.DraftValidator.PlanLesson;
import com.paiban.scheduling.service.DraftValidator.PlanningSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic search for a same-class, same-week cycle that puts the requested
 * lesson on its target slot when the plain two-lesson swap is not legal.
 *
 * <p>This class is pure: no Spring, no persistence, no clock beyond the elapsed-time
 * budget. Everything comes from the {@link PlanningSnapshot}. The final verdict is
 * never made here — every completed cycle is handed back to the whole-draft
 * {@link DraftValidator#assess} through a {@link Verifier}, so the search pruning can
 * only ever lose candidates, never bless a bad one.
 *
 * <p>The search state is a simple directed cycle. {@code source -> target} is the
 * first edge; the lesson currently sitting on the target slot is "displaced" and has
 * to be placed somewhere. It may take the slot the source just left (closing the
 * cycle) or push another unlocked lesson of the same class out of its slot. Slots
 * held by locked lessons or other classes are never pushed.
 */
public final class DraftCyclePlanner {

    /** One lesson's period movement; teacher, class and course stay with it. */
    public record Move(Long lessonId, Long fromPeriodId, Long toPeriodId) {}

    /** One independently verified plan. {@code cycleLength} is the number of moved lessons. */
    public record Candidate(List<Move> moves, int impactDays, int impactTeachers,
                            int softPenaltyAfter, int cycleLength, String stableKey) {}

    /** Honest result: {@code budgetExhausted} means "search was cut short", not "no plan exists". */
    public record Result(List<Candidate> candidates, boolean budgetExhausted, int nodesVisited,
                         int verifiedCandidates) {}

    /**
     * Whole-draft verifier. The production caller passes {@link DraftValidator#assess};
     * tests can pass the same real validator. The planner never inspects the internals
     * of the assessment beyond "did it add a conflict" and the after soft score.
     */
    @FunctionalInterface
    public interface Verifier {
        ChangeAssessment verify(PlanningSnapshot snapshot, List<LessonChange> changes);
    }

    public record Options(int maxMovedLessons, ExchangeRankingPreset preset,
                          int nodeBudget, int timeBudgetMs) {

        public static final int DEFAULT_NODE_BUDGET = 200_000;
        public static final int DEFAULT_TIME_BUDGET_MS = 1_500;
        /** A client may lower the budget for tests, but never raise it past these caps. */
        public static final int MAX_NODE_BUDGET = 1_000_000;
        public static final int MAX_TIME_BUDGET_MS = 5_000;

        public Options {
            maxMovedLessons = Math.max(2, Math.min(10, maxMovedLessons));
            preset = preset == null ? ExchangeRankingPreset.MIN_IMPACT : preset;
            nodeBudget = nodeBudget <= 0
                    ? DEFAULT_NODE_BUDGET
                    : Math.min(nodeBudget, MAX_NODE_BUDGET);
            timeBudgetMs = timeBudgetMs <= 0
                    ? DEFAULT_TIME_BUDGET_MS
                    : Math.min(timeBudgetMs, MAX_TIME_BUDGET_MS);
        }

        public static Options defaults(int maxMovedLessons, ExchangeRankingPreset preset) {
            return new Options(maxMovedLessons, preset, DEFAULT_NODE_BUDGET, DEFAULT_TIME_BUDGET_MS);
        }
    }

    private static final int MAX_RETURNED = 3;
    private static final int MAX_VERIFIED = 200;

    private DraftCyclePlanner() {}

    /**
     * Tries the direct swap first; only if it adds a conflict does it search longer
     * cycles. The service layer has already rejected missing, locked, cross-class and
     * ambiguous targets, so those are preconditions here rather than search outcomes.
     */
    public static Result plan(PlanningSnapshot snapshot, long sourceLessonId, long targetPeriodId,
                              Options options, Verifier verifier) {
        Map<Long, PlanLesson> byId = new LinkedHashMap<>();
        for (PlanLesson lesson : snapshot.lessons()) {
            byId.put(lesson.id(), lesson);
        }
        PlanLesson source = byId.get(sourceLessonId);
        if (source == null || source.periodId() == null) {
            return new Result(List.of(), false, 0, 0);
        }
        Long originalPeriodId = source.periodId();
        if (originalPeriodId.equals(targetPeriodId)) {
            return new Result(List.of(), false, 0, 0);
        }

        Map<Long, List<PlanLesson>> byPeriod = new HashMap<>();
        for (PlanLesson lesson : snapshot.lessons()) {
            if (lesson.periodId() != null) {
                byPeriod.computeIfAbsent(lesson.periodId(), key -> new ArrayList<>()).add(lesson);
            }
        }
        PlanLesson displaced = byPeriod.getOrDefault(targetPeriodId, List.of()).stream()
                .filter(lesson -> Objects.equals(lesson.classId(), source.classId()))
                .findFirst()
                .orElse(null);
        if (displaced == null) {
            return new Result(List.of(), false, 0, 0);
        }

        Search search = new Search(snapshot, source, byId, byPeriod, options, verifier);

        // 2.2 Direct swap: source -> target and the occupant -> source's old slot.
        List<Move> direct = List.of(
                new Move(source.id(), originalPeriodId, targetPeriodId),
                new Move(displaced.id(), targetPeriodId, originalPeriodId));
        if (search.verifyAndRecord(direct)) {
            List<Candidate> only = List.of(search.candidates.get(stableKey(direct)));
            return new Result(only, false, 1, 1);
        }

        // 2.3 Cycle search. The direct swap failed, so only cycles longer than two
        // can help; the DFS closes naturally when the displaced lesson returns home.
        search.findCycles(displaced, targetPeriodId);
        List<Candidate> ranked = search.ranked();
        return new Result(ranked, search.budgetExhausted, search.nodes, search.verified);
    }

    /**
     * True when a lesson of this class at the slot is not part of the moves, so landing
     * there would leave a class conflict (or a locked lesson that cannot be pushed).
     * Two same-class lessons that both move are not a reason to prune: the final
     * whole-table check decides whether the resulting table is legal.
     */
    static boolean hasStayingSameClass(List<PlanLesson> occupants, Long classId,
                                       Set<Long> moving) {
        return occupants.stream()
                .filter(occupant -> Objects.equals(occupant.classId(), classId))
                .anyMatch(occupant -> !moving.contains(occupant.id()));
    }

    private static String stableKey(List<Move> moves) {
        List<Move> sorted = new ArrayList<>(moves);
        sorted.sort(Comparator.comparing(Move::lessonId));
        StringBuilder key = new StringBuilder();
        for (Move move : sorted) {
            if (key.length() > 0) {
                key.append(';');
            }
            key.append(move.lessonId()).append(':').append(move.toPeriodId());
        }
        return key.toString();
    }

    private static final class Search {

        private final PlanningSnapshot snapshot;
        private final PlanLesson source;
        private final Map<Long, PlanLesson> byId;
        private final Map<Long, List<PlanLesson>> byPeriod;
        private final List<PlanLesson> classLessons;
        private final Options options;
        private final Verifier verifier;
        private final long startedNanos = System.nanoTime();

        private final List<Long> cycle = new ArrayList<>();
        private final Map<Long, Long> assignment = new HashMap<>();
        private final Map<String, Candidate> candidates = new LinkedHashMap<>();

        private int nodes;
        private int verified;
        private boolean budgetExhausted;

        private Search(PlanningSnapshot snapshot, PlanLesson source, Map<Long, PlanLesson> byId,
                       Map<Long, List<PlanLesson>> byPeriod, Options options, Verifier verifier) {
            this.snapshot = snapshot;
            this.source = source;
            this.byId = byId;
            this.byPeriod = byPeriod;
            this.options = options;
            this.verifier = verifier;
            this.classLessons = snapshot.lessons().stream()
                    .filter(lesson -> Objects.equals(lesson.classId(), source.classId())
                            && lesson.periodId() != null)
                    .sorted(Comparator.comparing(PlanLesson::id))
                    .toList();
        }

        /** Seeds {@code source -> target} and makes the target occupant the first displaced lesson. */
        private void findCycles(PlanLesson displaced, Long targetPeriodId) {
            assignment.put(source.id(), targetPeriodId);
            cycle.add(source.id());
            cycle.add(displaced.id());
            dfs(displaced);
        }

        private void dfs(PlanLesson displaced) {
            nodes++;
            if (stopped()) {
                budgetExhausted = true;
                return;
            }

            // Option 1: the displaced lesson takes the slot the source left.
            if (canLand(source.periodId(), displaced, new HashSet<>(cycle))) {
                assignment.put(displaced.id(), source.periodId());
                complete();
                assignment.remove(displaced.id());
            }

            // Option 2: push another unlocked, not-yet-moved lesson of this class.
            if (cycle.size() >= options.maxMovedLessons()) {
                return;
            }
            for (PlanLesson candidate : classLessons) {
                if (candidate.id().equals(source.id())
                        || candidate.locked()
                        || cycle.contains(candidate.id())
                        || candidate.periodId() == null) {
                    continue;
                }
                Set<Long> moving = new HashSet<>(cycle);
                moving.add(candidate.id());
                if (!canLand(candidate.periodId(), displaced, moving)) {
                    continue;
                }
                cycle.add(candidate.id());
                assignment.put(displaced.id(), candidate.periodId());
                dfs(candidate);
                assignment.remove(displaced.id());
                cycle.remove(cycle.size() - 1);
                if (stopped()) {
                    return;
                }
            }
        }

        /**
         * Necessary conditions only. A slot is rejected when a lesson of this class that
         * is not moving would stay behind, when the class cannot attend that slot at all,
         * or — only while the matching rule is hard — when a staying lesson would share
         * the displaced lesson's teacher. Soft conflicts are deliberately not
         * pruned: they belong to the final whole-table soft score. The independent
         * verifier still has the final word.
         */
        private boolean canLand(Long periodId, PlanLesson displaced, Set<Long> moving) {
            if (periodId == null) {
                return false;
            }
            PeriodView period = snapshot.context().periods().get(periodId);
            if (period == null) {
                return false;
            }
            if (snapshot.context().settings()
                    .classUnavailable(source.classId(), period.dayOfWeek(), period.periodNo())) {
                return false;
            }
            List<PlanLesson> occupants = byPeriod.getOrDefault(periodId, List.of());
            if (hasStayingSameClass(occupants, source.classId(), moving)) {
                return false;
            }
            boolean teacherHard = isHard(ConstraintType.TEACHER_CONFLICT);
            for (PlanLesson occupant : occupants) {
                if (moving.contains(occupant.id())) {
                    continue;
                }
                if (teacherHard && Objects.equals(occupant.teacherId(), displaced.teacherId())) {
                    return false;
                }
            }
            return true;
        }

        private boolean isHard(ConstraintType type) {
            Control control = snapshot.context().settings().control(type);
            return control != null && control.hard();
        }

        private void complete() {
            if (verified >= MAX_VERIFIED) {
                return;
            }
            List<Move> moves = new ArrayList<>(cycle.size());
            for (Long lessonId : cycle) {
                Long to = assignment.get(lessonId);
                PlanLesson lesson = byId.get(lessonId);
                if (to == null || lesson == null || lesson.periodId() == null) {
                    return;
                }
                moves.add(new Move(lessonId, lesson.periodId(), to));
            }
            verifyAndRecord(moves);
        }

        private boolean verifyAndRecord(List<Move> rawMoves) {
            List<Move> moves = new ArrayList<>(rawMoves);
            moves.sort(Comparator.comparing(Move::lessonId));
            String key = stableKey(moves);
            if (candidates.containsKey(key)) {
                return true;
            }
            List<LessonChange> changes = new ArrayList<>(moves.size());
            for (Move move : moves) {
                PlanLesson lesson = byId.get(move.lessonId());
                if (lesson == null) {
                    return false;
                }
                changes.add(new LessonChange(
                        move.lessonId(), move.toPeriodId(), lesson.teacherId()));
            }
            ChangeAssessment assessment = verifier.verify(snapshot, changes);
            if (!assessment.added().isEmpty()) {
                return false;
            }
            candidates.put(key, candidate(moves, key, assessment.softPenaltyAfter()));
            verified++;
            return true;
        }

        private Candidate candidate(List<Move> moves, String key, int softPenaltyAfter) {
            Set<Integer> days = new LinkedHashSet<>();
            Set<Long> teachers = new LinkedHashSet<>();
            for (Move move : moves) {
                addDay(days, move.fromPeriodId());
                addDay(days, move.toPeriodId());
                PlanLesson lesson = byId.get(move.lessonId());
                if (lesson != null) {
                    teachers.add(lesson.teacherId());
                }
            }
            return new Candidate(List.copyOf(moves), days.size(), teachers.size(),
                    softPenaltyAfter, moves.size(), key);
        }

        private void addDay(Set<Integer> days, Long periodId) {
            PeriodView period = periodId == null ? null : snapshot.context().periods().get(periodId);
            if (period != null) {
                days.add(period.dayOfWeek());
            }
        }

        private List<Candidate> ranked() {
            Comparator<Candidate> order = Comparator.comparing(
                    (Candidate candidate) -> new Metrics(candidate),
                    options.preset().comparator());
            return candidates.values().stream()
                    .sorted(order)
                    .limit(MAX_RETURNED)
                    .toList();
        }

        private boolean stopped() {
            return nodes > options.nodeBudget()
                    || (System.nanoTime() - startedNanos) / 1_000_000 > options.timeBudgetMs();
        }
    }

    /** Adapts a candidate to the shared ranking vocabulary without exposing it. */
    private record Metrics(Candidate candidate) implements ExchangePlanMetrics {

        @Override
        public int impactDays() {
            return candidate.impactDays();
        }

        @Override
        public boolean sameWeek() {
            return true;
        }

        @Override
        public int impactTeachers() {
            return candidate.impactTeachers();
        }

        @Override
        public int softPenalty() {
            return candidate.softPenaltyAfter();
        }

        @Override
        public int cycleLength() {
            return candidate.cycleLength();
        }

        @Override
        public String stableKey() {
            return candidate.stableKey();
        }
    }
}
