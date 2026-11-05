package com.paiban.scheduling.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.constraint.ConstraintChecker;
import com.paiban.scheduling.constraint.ConstraintContext;
import com.paiban.scheduling.constraint.ConstraintViolation;
import com.paiban.scheduling.constraint.LessonView;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.web.SchedulingDtos.LessonOverride;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Millisecond conflict checking for manual adjustments. Returns human readable
 * reasons ("张老师此时在 3 班有数学课") instead of a bare score. The rule
 * definitions live in {@link ConstraintChecker} so the published-timetable
 * verifier uses exactly the same 24 rules.
 *
 * <p>{@link #validateDraft} goes one step further than a local check: it also reports
 * the {@link DraftPublishReadiness} blockers, which is the same judgement
 * {@code TimetablePublishService} refuses on. The page therefore cannot say "passed"
 * for a draft publishing will reject.
 */
@Service
public class DraftValidator {

    public record Conflict(String type, String message, Long lessonId) {}

    /**
     * The shape every validation endpoint returns. {@code valid}, {@code hardConflicts} and
     * {@code conflicts} keep their original meaning for existing consumers;
     * {@code publishable} and {@code blockers} are additive.
     *
     * <p>{@code valid} answers "is this draft complete enough to be a timetable?" — no
     * scheduling conflict, no unscheduled lesson, no dangling period and no lesson without
     * a room. It deliberately says nothing about the already-published state
     * ({@code DRAFT_PUBLISHED}, {@code SEMESTER_ALREADY_PUBLISHED}): those are terminal
     * states, not defects of the content, so they only show up in {@code publishable} and
     * {@code blockers}. Otherwise the very draft that was just published would read back as
     * "invalid", which is the response change ticket 13 promised not to make.
     *
     * <p>A local check ({@code validate-move}, {@code validate-moves}, swap scenarios) only
     * looks at the lessons it was asked about, so it reports no blockers and
     * {@code publishable} mirrors {@code valid} there. Only the whole-draft check
     * ({@link #validateDraft}) answers the publishing question.
     */
    public record ValidationResult(
            boolean valid,
            int hardConflicts,
            List<Conflict> conflicts,
            boolean publishable,
            List<DraftPublishReadiness.Blocker> blockers,
            String rulesFingerprint) {}

    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final PeriodRepository periods;
    private final RoomRepository rooms;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final AdminClassRepository adminClasses;
    private final ConstraintService constraints;
    private final SemesterBaselineQuery baselines;

    public DraftValidator(
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            PeriodRepository periods,
            RoomRepository rooms,
            CourseRepository courses,
            TeacherRepository teachers,
            AdminClassRepository adminClasses,
            ConstraintService constraints,
            SemesterBaselineQuery baselines) {
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.periods = periods;
        this.rooms = rooms;
        this.courses = courses;
        this.teachers = teachers;
        this.adminClasses = adminClasses;
        this.constraints = constraints;
        this.baselines = baselines;
    }

    /**
     * Single-lesson special case of {@link #validateMoves}. Period and room are
     * assigned unconditionally, including nulls, so the existing endpoint keeps its
     * exact behaviour.
     */
    @Transactional(readOnly = true)
    public ValidationResult validateMove(Long draftId, Long lessonId, Long periodId, Long roomId) {
        Snapshot snapshot = snapshot(draftId);
        L moved = snapshot.lessons.stream()
                .filter(lesson -> lesson.id.equals(lessonId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + lessonId));
        moved.periodId = periodId;
        moved.roomId = roomId;
        return resultOf(snapshot, checkAffected(snapshot, Set.of(moved.id)));
    }

    @Transactional(readOnly = true)
    public ValidationResult validateMoves(Long draftId, List<LessonOverride> overrides) {
        Snapshot snapshot = snapshot(draftId);
        Set<Long> affected = applyOverrides(snapshot, overrides == null ? List.of() : overrides);
        return resultOf(snapshot, checkAffected(snapshot, affected));
    }

    /**
     * A change that assigns every mutable attribute unconditionally, nulls included.
     * Unlike {@link LessonOverride}, an explicit null clears the field, which is what
     * an exact swap (including a room being handed over as null) needs.
     */
    public record LessonChange(Long lessonId, Long periodId, Long roomId, Long teacherId) {}

    @Transactional(readOnly = true)
    public ValidationResult validateChanges(Long draftId, List<LessonChange> changes) {
        Snapshot snapshot = snapshot(draftId);
        return resultOf(snapshot, checkAffected(snapshot, applyChanges(snapshot, changes)));
    }

    /** The fingerprint of the rules in force for this draft's semester. */
    @Transactional(readOnly = true)
    public String rulesFingerprint(Long draftId) {
        TimetableDraft draft = drafts.findByIdAndDeletedAtIsNull(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        return constraints.settings(draft.getSemesterId()).fingerprint();
    }

    /**
     * One rule hit with a stable key, a hard/soft severity and the weight of the fact
     * (one pair conflict is 1; a two-lesson same-course day is 2). The comparison uses
     * the key for identity and the weight for "did it get worse", so a repair may keep
     * a key while reducing its weight, but may never raise it.
     */
    public record Violation(String key, String code, String message, Long lessonId,
                            int severity, int weight) {}

    /**
     * Entire-draft verdict for one atomic change list. {@code allowed} is true only when
     * the change introduces no new stable key and does not make an existing key appear
     * more often; pre-existing problems stay in {@code remaining} so the UI can keep
     * showing them. The comparison is made on whole-draft snapshots after every change
     * has been applied, never on a partially moved timetable.
     */
    public record ChangeAssessment(
            boolean allowed,
            List<Violation> before,
            List<Violation> after,
            List<Violation> added,
            List<Violation> remaining,
            int softPenaltyBefore,
            int softPenaltyAfter) {}

    /**
     * A read-only planning view of one lesson. It deliberately carries no JPA entity:
     * the pure cycle planner and its unit tests only need these values, and copying
     * them is what keeps the planner free of a persistence context.
     */
    public record PlanLesson(Long id, Long assignmentId, int lessonIndex, Long classId,
                             Long courseId, Long teacherId, Long periodId, Long roomId,
                             int studentCount, boolean locked) {}

    /**
     * Everything a plan search needs: the draft row (for version and semester), the
     * immutable lesson view and the shared rule context. {@code draft} stays an entity
     * because the search only reads its id, semester and version.
     */
    public record PlanningSnapshot(TimetableDraft draft, List<PlanLesson> lessons,
                                   ConstraintContext context) {}

    /** The load-once view used by the cycle search and by {@link #assess}. */
    @Transactional(readOnly = true)
    public PlanningSnapshot planningSnapshot(Long draftId) {
        Snapshot snapshot = snapshot(draftId);
        List<PlanLesson> lessons = snapshot.lessons.stream().map(L::toPlanLesson).toList();
        return new PlanningSnapshot(snapshot.draft, lessons, snapshot.context);
    }

    /**
     * Same whole-draft judgement as {@link #assessChanges}, but against an already
     * loaded planning snapshot so a search evaluates many candidates from one read.
     */
    public ChangeAssessment assess(PlanningSnapshot planning, List<LessonChange> changes) {
        Snapshot snapshot = new Snapshot(
                planning.draft(),
                planning.lessons().stream().map(L::of).toList(),
                planning.context());
        return assessSnapshot(snapshot, changes);
    }

    /**
     * Applies every change to an in-memory copy first, then compares the whole draft
     * before and after by stable violation key. A move into a slot that another lesson
     * is leaving in the same request is therefore not reported as a conflict. A
     * nonexistent, foreign or duplicate row is applied as-is (last value wins) and never
     * breaks the comparison; the write service rejects those inputs before calling this.
     */
    @Transactional(readOnly = true)
    public ChangeAssessment assessChanges(Long draftId, List<LessonChange> changes) {
        return assess(planningSnapshot(draftId), changes);
    }

    private ChangeAssessment assessSnapshot(Snapshot snapshot, List<LessonChange> changes) {
        List<LessonView> beforeViews = viewsOf(snapshot);
        List<ConstraintViolation> beforeViolations =
                ConstraintChecker.check(snapshot.context(), beforeViews, null);
        int softBefore = ConstraintChecker.softPenalty(snapshot.context(), beforeViews);

        Snapshot after = snapshot.withFreshLessons();
        applyChangesLenient(after, changes);
        List<LessonView> afterViews = viewsOf(after);
        List<ConstraintViolation> afterViolations =
                ConstraintChecker.check(after.context(), afterViews, null);
        int softAfter = ConstraintChecker.softPenalty(after.context(), afterViews);

        List<Violation> before = beforeViolations.stream().map(DraftValidator::toViolation).toList();
        List<Violation> remaining = afterViolations.stream().map(DraftValidator::toViolation).toList();
        List<Violation> added = excess(beforeViolations, afterViolations);
        return new ChangeAssessment(
                added.isEmpty(), before, remaining, added, remaining, softBefore, softAfter);
    }

    private static List<LessonView> viewsOf(Snapshot snapshot) {
        return snapshot.lessons.stream().map(L::toView).toList();
    }

    private static Violation toViolation(ConstraintViolation violation) {
        return new Violation(
                violation.key(), violation.code(), violation.message(), violation.lessonId(),
                violation.hard() ? 1 : 0, violation.weight());
    }

    /**
     * The after facts whose stable key is new or whose total weight is higher than
     * before. Message, teacher or class names and counts never take part in the
     * identity; a bucket that grows from two lessons to three raises its weight even
     * though it still produces one violation row.
     */
    private static List<Violation> excess(
            List<ConstraintViolation> before, List<ConstraintViolation> after) {
        Map<String, Integer> beforeWeights = new HashMap<>();
        before.forEach(violation -> beforeWeights.merge(violation.key(), violation.weight(), Integer::sum));
        Map<String, Integer> afterWeights = new LinkedHashMap<>();
        Map<String, ConstraintViolation> afterByKey = new LinkedHashMap<>();
        for (ConstraintViolation violation : after) {
            afterWeights.merge(violation.key(), violation.weight(), Integer::sum);
            afterByKey.putIfAbsent(violation.key(), violation);
        }
        List<Violation> added = new ArrayList<>();
        afterWeights.forEach((key, weight) -> {
            if (weight > beforeWeights.getOrDefault(key, 0)) {
                added.add(toViolation(afterByKey.get(key)));
            }
        });
        return added;
    }

    private static void applyChangesLenient(Snapshot snapshot, List<LessonChange> changes) {
        Map<Long, L> byId = new HashMap<>();
        snapshot.lessons.forEach(lesson -> byId.put(lesson.id, lesson));
        for (LessonChange change : changes == null ? List.<LessonChange>of() : changes) {
            L lesson = change.lessonId() == null ? null : byId.get(change.lessonId());
            if (lesson == null) {
                continue;
            }
            lesson.periodId = change.periodId();
            lesson.roomId = change.roomId();
            lesson.teacherId = change.teacherId();
        }
    }

    /**
     * A room that does not exist yet, used by a preview that must judge the change
     * before anything is written. Its id is a negative placeholder.
     */
    public record PlannedRoom(long placeholderId, String name, String type, int capacity) {}

    /**
     * Before and after the same whole-draft judgement. {@code added} holds only the
     * conflicts the change introduces: pre-existing ones stay visible but never block
     * a change on their own.
     */
    public record ChangeImpact(
            List<Conflict> before,
            List<Conflict> after,
            List<Conflict> added,
            List<DraftPublishReadiness.Blocker> remainingBlockers) {}

    /**
     * Impact of filling empty rooms only. Unlike {@link #validateChanges}, nothing but
     * the room is assigned, so a preview cannot accidentally clear a period or teacher.
     * Planned rooms are judged through their name, type and capacity before they exist.
     */
    @Transactional(readOnly = true)
    public ChangeImpact previewRoomAssignment(
            Long draftId, Map<Long, Long> roomIdByLesson, List<PlannedRoom> plannedRooms) {
        Snapshot snapshot = snapshot(draftId);
        List<Conflict> before = checkAffected(snapshot, null);
        Snapshot after = snapshot.withPlannedRooms(plannedRooms);
        for (L lesson : after.lessons) {
            Long roomId = roomIdByLesson.get(lesson.id);
            if (roomId != null) {
                lesson.roomId = roomId;
            }
        }
        List<Conflict> afterConflicts = checkAffected(after, null);
        return new ChangeImpact(
                before,
                afterConflicts,
                addedConflicts(before, afterConflicts),
                DraftPublishReadiness.blockers(factsOf(after, afterConflicts.size())));
    }

    /** Multiset difference by code, lesson and message: what the change newly breaks. */
    private List<Conflict> addedConflicts(List<Conflict> before, List<Conflict> after) {
        Map<String, Integer> remaining = new HashMap<>();
        before.forEach(conflict -> remaining.merge(keyOf(conflict), 1, Integer::sum));
        List<Conflict> added = new ArrayList<>();
        for (Conflict conflict : after) {
            String key = keyOf(conflict);
            Integer count = remaining.get(key);
            if (count == null || count == 0) {
                added.add(conflict);
            } else {
                remaining.put(key, count - 1);
            }
        }
        return added;
    }

    /**
     * Teacher-centred hard-conflict diagnostics for a finished draft: which teacher blocks
     * publishing, how many hard facts name them, the human-readable reasons, and the
     * grades/classes they teach in this draft. Only teacher-owned rule codes are counted
     * (class/room facts are not attributed to a teacher), and the list is capped at five
     * so the job card stays readable.
     */
    public record TeacherDiagnostic(Long teacherId, String teacherName, int hardCount,
                                    List<String> messages, List<Long> gradeIds,
                                    List<String> classNames) {}

    @Transactional(readOnly = true)
    public List<TeacherDiagnostic> diagnose(Long draftId) {
        Snapshot snapshot = snapshot(draftId);
        List<LessonView> views = viewsOf(snapshot);
        List<ConstraintViolation> violations =
                ConstraintChecker.checkHardFast(snapshot.context(), views);
        if (violations.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> gradeByClass = new HashMap<>();
        adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(snapshot.draft.getSemesterId())
                .forEach(adminClass -> gradeByClass.put(adminClass.getId(), adminClass.getGradeId()));

        Map<Long, Long> teacherOfLesson = new HashMap<>();
        Map<Long, Set<Long>> gradesByTeacher = new LinkedHashMap<>();
        Map<Long, Set<String>> classesByTeacher = new LinkedHashMap<>();
        for (L lesson : snapshot.lessons) {
            if (lesson.teacherId == null) {
                continue;
            }
            teacherOfLesson.put(lesson.id, lesson.teacherId);
            Long gradeId = gradeByClass.get(lesson.classId);
            if (gradeId != null) {
                gradesByTeacher.computeIfAbsent(lesson.teacherId, key -> new LinkedHashSet<>())
                        .add(gradeId);
            }
            ConstraintContext.ClassView adminClass = snapshot.context.classes().get(lesson.classId);
            if (adminClass != null) {
                classesByTeacher.computeIfAbsent(lesson.teacherId, key -> new LinkedHashSet<>())
                        .add(adminClass.name());
            }
        }

        Map<Long, List<ConstraintViolation>> byTeacher = new LinkedHashMap<>();
        for (ConstraintViolation violation : violations) {
            Long teacherId = teacherOfViolation(violation, teacherOfLesson);
            if (teacherId != null) {
                byTeacher.computeIfAbsent(teacherId, key -> new ArrayList<>()).add(violation);
            }
        }
        return byTeacher.entrySet().stream()
                .map(entry -> {
                    Long teacherId = entry.getKey();
                    List<ConstraintViolation> rows = entry.getValue();
                    ConstraintContext.TeacherView teacher =
                            snapshot.context.teachers().get(teacherId);
                    return new TeacherDiagnostic(
                            teacherId,
                            teacher == null ? null : teacher.name(),
                            rows.size(),
                            rows.stream().map(ConstraintViolation::message).distinct().toList(),
                            gradesByTeacher.getOrDefault(teacherId, Set.of()).stream()
                                    .sorted().toList(),
                            classesByTeacher.getOrDefault(teacherId, Set.of()).stream()
                                    .sorted().toList());
                })
                .sorted(Comparator.comparingInt(TeacherDiagnostic::hardCount).reversed()
                        .thenComparing(diagnostic -> diagnostic.teacherName() == null
                                ? "" : diagnostic.teacherName()))
                .limit(5)
                .toList();
    }

    /**
     * Teacher attribution without widening {@link ConstraintViolation}: lesson-scoped
     * facts go through the draft row, while the two aggregate teacher thresholds encode
     * the teacher id in their stable key ({@code TYPE|teacherId-day}).
     */
    private static Long teacherOfViolation(
            ConstraintViolation violation, Map<Long, Long> teacherOfLesson) {
        if (violation.lessonId() != null) {
            return teacherOfLesson.get(violation.lessonId());
        }
        if (!teacherRelated(violation.code())) {
            return null;
        }
        String key = violation.key();
        if (key == null) {
            return null;
        }
        String[] parts = key.split("\\|", 2);
        if (parts.length < 2) {
            return null;
        }
        String scope = parts[1];
        int dash = scope.indexOf('-');
        String raw = dash < 0 ? scope : scope.substring(0, dash);
        try {
            long teacherId = Long.parseLong(raw);
            return teacherOfLesson.containsValue(teacherId) ? teacherId : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean teacherRelated(String code) {
        return code != null && code.startsWith("TEACHER_");
    }

    private static String keyOf(Conflict conflict) {
        return conflict.type() + "|" + conflict.lessonId() + "|" + conflict.message();
    }

    /**
     * Whole-draft check: the 24 conflict rules plus every publishing precondition, from
     * the one judgement publishing itself uses ({@link DraftPublishReadiness}). Rooms are
     * not a publishing precondition: a lesson without a room is valid and publishable.
     *
     * <p>{@code valid} and {@code publishable} both come from this one list, differing only
     * in that the two terminal-state blockers ({@code DRAFT_PUBLISHED},
     * {@code SEMESTER_ALREADY_PUBLISHED}) do not make the content invalid — see
     * {@link ValidationResult}.
     */
    @Transactional(readOnly = true)
    public ValidationResult validateDraft(Long draftId) {
        Snapshot snapshot = snapshot(draftId);
        List<Conflict> conflicts = checkAffected(snapshot, null);
        List<DraftPublishReadiness.Blocker> blockers =
                DraftPublishReadiness.blockers(factsOf(snapshot, conflicts.size()));
        return new ValidationResult(
                contentComplete(blockers), conflicts.size(), conflicts, blockers.isEmpty(), blockers,
                snapshot.context().settings().fingerprint());
    }

    /**
     * True when every blocker is about the draft's content; a blocker that only says "this
     * is already published" is a state of the world, not something the content lacks.
     */
    private static boolean contentComplete(List<DraftPublishReadiness.Blocker> blockers) {
        return blockers.stream().allMatch(blocker -> isTerminalState(blocker.code()));
    }

    /** The codes that describe a publish state rather than a gap in the draft. */
    private static boolean isTerminalState(String code) {
        return DraftPublishReadiness.DRAFT_PUBLISHED.equals(code)
                || DraftPublishReadiness.SEMESTER_ALREADY_PUBLISHED.equals(code);
    }

    /** Counts the structural gaps the publishing preconditions care about. */
    private DraftPublishReadiness.Facts factsOf(Snapshot snapshot, int hardConflicts) {
        int unscheduled = 0;
        int invalidPeriods = 0;
        for (L lesson : snapshot.lessons) {
            if (lesson.periodId == null) {
                unscheduled++;
            } else if (!snapshot.context.periods().containsKey(lesson.periodId)) {
                invalidPeriods++;
            }
        }
        return new DraftPublishReadiness.Facts(
                "PUBLISHED".equals(snapshot.draft.getStatus()),
                snapshot.lessons.size(),
                unscheduled,
                invalidPeriods,
                hardConflicts,
                baselines.hasPublishedBaseline(snapshot.draft.getSemesterId()));
    }

    /**
     * Evaluates independent override scenarios against one snapshot. Each scenario
     * starts from a fresh copy of the lessons, so it never sees another scenario's
     * changes. The snapshot itself (periods, rooms, courses, teachers, classes and
     * constraint settings) is built once; this is what the drag preview and the swap
     * candidate list need, where one request asks "what if" for many targets.
     */
    @Transactional(readOnly = true)
    public List<ValidationResult> validateScenarios(Long draftId, List<List<LessonOverride>> scenarios) {
        Snapshot base = snapshot(draftId);
        List<ValidationResult> results = new ArrayList<>(scenarios.size());
        for (List<LessonOverride> scenario : scenarios) {
            Snapshot copy = base.withFreshLessons();
            results.add(resultOf(copy, checkAffected(copy, applyOverrides(copy, scenario))));
        }
        return results;
    }

    /**
     * Same as {@link #validateScenarios}, but each scenario is an exact change list
     * (an explicit null clears a field). Swap candidates need this because exchanging
     * rooms may hand a lesson a null room, which {@link LessonOverride} cannot express.
     */
    @Transactional(readOnly = true)
    public List<ValidationResult> validateChangeScenarios(Long draftId, List<List<LessonChange>> scenarios) {
        Snapshot base = snapshot(draftId);
        List<ValidationResult> results = new ArrayList<>(scenarios.size());
        for (List<LessonChange> scenario : scenarios) {
            Snapshot copy = base.withFreshLessons();
            results.add(resultOf(copy, checkAffected(copy, applyChanges(copy, scenario))));
        }
        return results;
    }

    /** Applies exact changes to the in-memory snapshot and returns the affected lesson ids. */
    private Set<Long> applyChanges(Snapshot snapshot, List<LessonChange> changes) {
        Map<Long, L> byId = new HashMap<>();
        snapshot.lessons.forEach(lesson -> byId.put(lesson.id, lesson));
        Set<Long> affected = new LinkedHashSet<>();
        for (LessonChange change : changes == null ? List.<LessonChange>of() : changes) {
            L lesson = byId.get(change.lessonId());
            if (lesson == null) {
                throw ApiException.notFound("课节不存在: " + change.lessonId());
            }
            lesson.periodId = change.periodId();
            lesson.roomId = change.roomId();
            lesson.teacherId = change.teacherId();
            affected.add(lesson.id);
        }
        return affected;
    }

    /** Applies every override to the in-memory snapshot and returns the affected lesson ids. */
    private Set<Long> applyOverrides(Snapshot snapshot, List<LessonOverride> overrides) {
        Map<Long, L> byId = new HashMap<>();
        snapshot.lessons.forEach(lesson -> byId.put(lesson.id, lesson));
        Set<Long> affected = new LinkedHashSet<>();
        for (LessonOverride override : overrides) {
            L lesson = byId.get(override.lessonId());
            if (lesson == null) {
                throw ApiException.notFound("课节不存在: " + override.lessonId());
            }
            if (override.periodId() != null) {
                lesson.periodId = override.periodId();
            }
            if (override.roomId() != null) {
                lesson.roomId = override.roomId();
            }
            if (override.teacherId() != null) {
                lesson.teacherId = override.teacherId();
            }
            affected.add(lesson.id);
        }
        return affected;
    }

    private List<Conflict> checkAffected(Snapshot s, Set<Long> affectedLessonIds) {
        List<LessonView> views = s.lessons.stream().map(L::toView).toList();
        List<ConstraintViolation> violations =
                ConstraintChecker.check(s.context(), views, affectedLessonIds);
        return violations.stream()
                .map(violation -> new Conflict(violation.code(), violation.message(), violation.lessonId()))
                .toList();
    }

    /** Local checks report conflicts only; they never gate on the rest of the draft. */
    private ValidationResult resultOf(Snapshot snapshot, List<Conflict> conflicts) {
        boolean valid = conflicts.isEmpty();
        return new ValidationResult(valid, conflicts.size(), conflicts, valid, List.of(),
                snapshot.context().settings().fingerprint());
    }

    private Snapshot snapshot(Long draftId) {
        // Every validate entry point (draft, move, changes, planning) reads through
        // here, so a recycled draft can never be validated or previewed.
        TimetableDraft draft = drafts.findByIdAndDeletedAtIsNull(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        List<DraftLesson> lessons = draftLessons.findByDraftIdOrderByIdAsc(draftId);
        Map<Long, ConstraintContext.PeriodView> periodMap = new HashMap<>();
        periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(draft.getSemesterId())
                .forEach(period -> periodMap.put(period.getId(),
                        new ConstraintContext.PeriodView(
                                period.getId(), period.getDayOfWeek(), period.getPeriodNo(),
                                period.getSection())));
        Map<Long, ConstraintContext.RoomView> roomMap = new HashMap<>();
        rooms.findAll().forEach(room -> roomMap.put(room.getId(),
                new ConstraintContext.RoomView(
                        room.getId(), room.getName(), room.getType(), room.getCapacity())));
        Map<Long, ConstraintContext.CourseView> courseMap = new HashMap<>();
        courses.findAll().forEach(course -> courseMap.put(course.getId(),
                new ConstraintContext.CourseView(
                        course.getId(), course.getName(), course.getRequiredRoomType())));
        Map<Long, ConstraintContext.TeacherView> teacherMap = new HashMap<>();
        teachers.findAll().forEach(teacher -> teacherMap.put(teacher.getId(),
                new ConstraintContext.TeacherView(
                        teacher.getId(), teacher.getName(), teacher.getSubject())));
        Map<Long, ConstraintContext.ClassView> classMap = new HashMap<>();
        adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(draft.getSemesterId())
                .forEach(adminClass -> classMap.put(adminClass.getId(),
                        new ConstraintContext.ClassView(
                                adminClass.getId(), adminClass.getName(),
                                adminClass.getStudentCount(),
                                adminClass.getHeadTeacherId())));

        // One snapshot builder for every entry point: the same type rows and entry
        // rows the solver and the substitution verifier read.
        ConstraintSettings settings = constraints.settings(draft.getSemesterId());
        ConstraintContext context =
                new ConstraintContext(periodMap, roomMap, courseMap, teacherMap, classMap, settings);
        List<L> copies = lessons.stream().map(L::of).toList();
        return new Snapshot(draft, copies, context);
    }

    private record Snapshot(TimetableDraft draft, List<L> lessons, ConstraintContext context) {

        /** Same reference data, independent lesson copies, for scenario evaluation. */
        Snapshot withFreshLessons() {
            return new Snapshot(draft, lessons.stream().map(L::copy).toList(), context);
        }

        /**
         * Independent lesson copies plus the rooms a preview is about to create, so the
         * rules can judge assignments to rooms that do not exist yet.
         */
        Snapshot withPlannedRooms(List<PlannedRoom> plannedRooms) {
            if (plannedRooms == null || plannedRooms.isEmpty()) {
                return withFreshLessons();
            }
            Map<Long, ConstraintContext.RoomView> roomMap = new HashMap<>(context.rooms());
            for (PlannedRoom room : plannedRooms) {
                roomMap.put(room.placeholderId(), new ConstraintContext.RoomView(
                        room.placeholderId(), room.name(), room.type(), room.capacity()));
            }
            ConstraintContext widened = new ConstraintContext(
                    context.periods(), Map.copyOf(roomMap), context.courses(),
                    context.teachers(), context.classes(), context.settings());
            return new Snapshot(draft, lessons.stream().map(L::copy).toList(), widened);
        }
    }

    private static final class L {
        private Long id;
        private Long assignmentId;
        private int lessonIndex;
        private Long courseId;
        private Long teacherId;
        private Long classId;
        private Long periodId;
        private Long roomId;
        private int studentCount;
        private boolean locked;

        static L of(DraftLesson lesson) {
            L copy = new L();
            copy.id = lesson.getId();
            copy.assignmentId = lesson.getAssignmentId();
            copy.lessonIndex = lesson.getLessonIndex();
            copy.courseId = lesson.getCourseId();
            copy.teacherId = lesson.getTeacherId();
            copy.classId = lesson.getAdminClassId();
            copy.periodId = lesson.getPeriodId();
            copy.roomId = lesson.getRoomId();
            copy.studentCount = lesson.getStudentCount();
            copy.locked = lesson.isLocked();
            return copy;
        }

        static L of(PlanLesson lesson) {
            L copy = new L();
            copy.id = lesson.id();
            copy.assignmentId = lesson.assignmentId();
            copy.lessonIndex = lesson.lessonIndex();
            copy.courseId = lesson.courseId();
            copy.teacherId = lesson.teacherId();
            copy.classId = lesson.classId();
            copy.periodId = lesson.periodId();
            copy.roomId = lesson.roomId();
            copy.studentCount = lesson.studentCount();
            copy.locked = lesson.locked();
            return copy;
        }

        PlanLesson toPlanLesson() {
            return new PlanLesson(id, assignmentId, lessonIndex, classId, courseId, teacherId,
                    periodId, roomId, studentCount, locked);
        }

        LessonView toView() {
            return new LessonView(id, classId, courseId, teacherId, roomId, periodId, studentCount);
        }

        L copy() {
            L copy = new L();
            copy.id = id;
            copy.assignmentId = assignmentId;
            copy.lessonIndex = lessonIndex;
            copy.courseId = courseId;
            copy.teacherId = teacherId;
            copy.classId = classId;
            copy.periodId = periodId;
            copy.roomId = roomId;
            copy.studentCount = studentCount;
            copy.locked = locked;
            return copy;
        }
    }
}
