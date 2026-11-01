package com.paiban.scheduling.service;

import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.solver.PeriodSlot;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chooses the solver's warm start and seeds one period queue per assignment.
 *
 * <p>The published baseline, when it exists, is already complete and conflict-free, so
 * it is a far better starting point than an empty grid. Timefold's construction phase
 * only fills uninitialized lessons; the ones seeded here keep their baseline period and
 * local search starts from a publishable solution. Assignments that were edited after
 * publishing (or have no baseline entry) keep their unassigned periods and are filled
 * by the construction phase as before.
 *
 * <p>A new semester has no baseline yet, but the imported workbook draft is already a
 * complete schedule. If the baseline is empty, the oldest complete draft (normally the
 * import) seeds the same way.
 */
@Service
public class WarmStartSeedService {

    private final SemesterBaselineQuery baselines;
    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;

    public WarmStartSeedService(
            SemesterBaselineQuery baselines,
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons) {
        this.baselines = baselines;
        this.drafts = drafts;
        this.draftLessons = draftLessons;
    }

    /** Seeded periods plus the label of where they came from: BASELINE, DRAFT or NONE. */
    public record WarmStartSeed(Map<AssignmentKey, Deque<PeriodSlot>> periods, String source) {}

    @Transactional(readOnly = true)
    public WarmStartSeed seed(
            Long semesterId, Map<Long, PeriodSlot> periodById, List<Assignment> assignments) {
        Map<AssignmentKey, Deque<PeriodSlot>> baseline = new HashMap<>();
        baselines.publishedLessons(semesterId).stream()
                .sorted(Comparator.comparing(SemesterBaselineQuery.PublishedLesson::periodId))
                .forEach(lesson -> addPeriodIfKnown(
                        baseline, lesson.adminClassId(), lesson.courseId(), lesson.teacherId(),
                        lesson.periodId(), periodById));
        if (!baseline.isEmpty()) {
            return new WarmStartSeed(baseline, "BASELINE");
        }

        Map<AssignmentKey, Integer> required = new HashMap<>();
        assignments.forEach(assignment -> required.merge(
                AssignmentKey.of(assignment), assignment.getWeeklyLessons(), Integer::sum));

        List<TimetableDraft> existing = drafts.findBySemesterIdOrderByCreatedAtDesc(semesterId);
        if (existing.isEmpty()) {
            return new WarmStartSeed(Map.of(), "NONE");
        }
        // One query for every candidate draft's rows; the grouping keeps the id ordering
        // the per-draft query used to provide.
        Map<Long, List<DraftLesson>> rowsByDraft = draftLessons
                .findByDraftIdInOrderByDraftIdAscIdAsc(
                        existing.stream().map(TimetableDraft::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        DraftLesson::getDraftId, LinkedHashMap::new, Collectors.toList()));
        for (int i = existing.size() - 1; i >= 0; i--) {
            TimetableDraft draft = existing.get(i);
            List<DraftLesson> rows = rowsByDraft.getOrDefault(draft.getId(), List.of());
            if (rows.isEmpty() || rows.stream().anyMatch(row -> row.getPeriodId() == null)) {
                continue;
            }
            Map<AssignmentKey, Deque<PeriodSlot>> byAssignment = new HashMap<>();
            Map<Long, Set<Long>> periodsByClass = new HashMap<>();
            Map<Long, Set<Long>> periodsByTeacher = new HashMap<>();
            boolean usable = true;
            for (DraftLesson row : rows) {
                // A complete draft that already double-books a class or a teacher is not a
                // publishable starting point; skip it and keep looking for a clean one.
                if (!firstTime(periodsByClass, row.getAdminClassId(), row.getPeriodId())
                        || !firstTime(periodsByTeacher, row.getTeacherId(), row.getPeriodId())) {
                    usable = false;
                    break;
                }
                if (!addPeriodIfKnown(byAssignment, row.getAdminClassId(), row.getCourseId(),
                        row.getTeacherId(), row.getPeriodId(), periodById)) {
                    usable = false;
                    break;
                }
            }
            if (usable && isComplete(byAssignment, required)) {
                return new WarmStartSeed(byAssignment, "DRAFT");
            }
        }
        return new WarmStartSeed(Map.of(), "NONE");
    }

    /** True when this (owner, period) pair has not been seen before in the draft. */
    private static boolean firstTime(Map<Long, Set<Long>> seen, Long ownerId, Long periodId) {
        return seen.computeIfAbsent(ownerId, key -> new HashSet<>()).add(periodId);
    }

    /** A draft is complete when every assignment has all of its weekly lessons seeded. */
    private static boolean isComplete(
            Map<AssignmentKey, Deque<PeriodSlot>> byAssignment,
            Map<AssignmentKey, Integer> required) {
        return required.entrySet().stream().allMatch(entry ->
                byAssignment.getOrDefault(entry.getKey(), new ArrayDeque<>()).size()
                        >= entry.getValue());
    }

    /**
     * Adds one period to the assignment's queue; false when the period is unknown, i.e.
     * the row points at a period id that is not part of this semester.
     */
    private static boolean addPeriodIfKnown(
            Map<AssignmentKey, Deque<PeriodSlot>> byAssignment, Long classId, Long courseId,
            Long teacherId, Long periodId, Map<Long, PeriodSlot> periodById) {
        PeriodSlot slot = periodId == null ? null : periodById.get(periodId);
        if (slot == null) {
            return false;
        }
        byAssignment.computeIfAbsent(
                new AssignmentKey(classId, courseId, teacherId), key -> new ArrayDeque<>())
                .add(slot);
        return true;
    }
}
